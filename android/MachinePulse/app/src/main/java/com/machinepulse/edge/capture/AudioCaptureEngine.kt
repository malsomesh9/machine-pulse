package com.machinepulse.edge.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal const val AUDIO_SAMPLE_RATE_HZ = 44_100
private const val AUDIO_CHANNEL_COUNT: Short = 1
private const val AUDIO_BITS_PER_SAMPLE: Short = 16
private const val WAV_HEADER_SIZE = 44

internal data class AudioCaptureResult(
    val sampleRateHz: Int,
    val sampleCount: Long,
    val dataBytes: Long,
)

internal class AudioCaptureEngine(
    private val sessionDirectory: File,
    private val onFailure: (Exception) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val rawFile = File(sessionDirectory, "audio.pcm.part")
    private val wavFile = File(sessionDirectory, "audio.wav")

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var bytesWritten = 0L

    @Volatile
    private var recordingFailure: Exception? = null

    @SuppressLint("MissingPermission")
    fun start() {
        check(!running.get()) { "Audio capture is already running." }

        val minimumBufferSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferSize <= 0) {
            throw IllegalStateException("Android reported an invalid microphone buffer size.")
        }
        val bufferSize = max(minimumBufferSize, 8_192)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("The phone microphone could not be initialized.")
        }

        bytesWritten = 0
        recordingFailure = null
        audioRecord = recorder
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            audioRecord = null
            throw IllegalStateException("Android did not start microphone recording.")
        }

        running.set(true)
        recordingThread = Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val buffer = ByteArray(bufferSize)
                try {
                    rawFile.outputStream().buffered().use { output ->
                        while (running.get()) {
                            val read = recorder.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING,
                            )
                            when {
                                read > 0 -> {
                                    output.write(buffer, 0, read)
                                    bytesWritten += read
                                }
                                read < 0 && running.get() -> {
                                    throw IllegalStateException("Microphone read failed with code $read.")
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (running.getAndSet(false)) {
                        recordingFailure = error
                        onFailure(error)
                    }
                }
            },
            "MachinePulseAudioCapture",
        ).also { it.start() }
    }

    fun stopAndWriteWav(): AudioCaptureResult {
        running.set(false)
        val recorder = audioRecord
        runCatching {
            if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        }
        recordingThread?.join(2_000)
        recorder?.release()
        audioRecord = null
        recordingThread = null

        recordingFailure?.let { throw it }
        if (bytesWritten <= 0 || !rawFile.exists()) {
            throw IllegalStateException("No microphone samples were recorded.")
        }

        val dataSize = bytesWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        FileOutputStream(wavFile).use { output ->
            output.write(createWavHeader(dataSize))
            rawFile.inputStream().use { input -> input.copyTo(output) }
        }
        rawFile.delete()

        return AudioCaptureResult(
            sampleRateHz = AUDIO_SAMPLE_RATE_HZ,
            sampleCount = bytesWritten / (AUDIO_CHANNEL_COUNT * AUDIO_BITS_PER_SAMPLE / 8),
            dataBytes = bytesWritten,
        )
    }

    fun release() {
        running.set(false)
        runCatching { audioRecord?.stop() }
        recordingThread?.join(2_000)
        audioRecord?.release()
        audioRecord = null
        recordingThread = null
    }
}

internal fun createWavHeader(
    dataSize: Int,
    sampleRateHz: Int = AUDIO_SAMPLE_RATE_HZ,
    channelCount: Short = AUDIO_CHANNEL_COUNT,
    bitsPerSample: Short = AUDIO_BITS_PER_SAMPLE,
): ByteArray {
    require(dataSize >= 0)
    val bytesPerSample = bitsPerSample / 8
    val byteRate = sampleRateHz * channelCount * bytesPerSample
    val blockAlign = (channelCount * bytesPerSample).toShort()

    return ByteBuffer.allocate(WAV_HEADER_SIZE)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(36 + dataSize)
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(channelCount)
        .putInt(sampleRateHz)
        .putInt(byteRate)
        .putShort(blockAlign)
        .putShort(bitsPerSample)
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(dataSize)
        .array()
}
