package com.machinepulse.edge.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val DEFAULT_CAPTURE_DURATION_MILLIS = 10_000L
private const val SENSOR_SAMPLING_PERIOD_MICROS = 10_000
private const val UI_UPDATE_INTERVAL_MILLIS = 200L
private const val SETTLE_COUNTDOWN_MILLIS = 3_000L
private const val COUNTDOWN_UPDATE_INTERVAL_MILLIS = 100L

enum class MotionCapturePhase {
    READY,
    PREPARING,
    CAPTURING,
    COMPLETE,
    ERROR,
    UNAVAILABLE,
}

data class MotionCaptureUiState(
    val phase: MotionCapturePhase,
    val sensorName: String? = null,
    val elapsedMillis: Long = 0,
    val targetDurationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS,
    val sampleCount: Int = 0,
    val audioSampleCount: Long = 0,
    val baselineSessionCount: Int = 0,
    val microphonePermissionGranted: Boolean = false,
    val countdownSeconds: Int = 0,
    val latestSummary: String? = null,
    val errorMessage: String? = null,
) {
    val sensorAvailable: Boolean
        get() = phase != MotionCapturePhase.UNAVAILABLE

    val isCapturing: Boolean
        get() = phase == MotionCapturePhase.CAPTURING

    val isActive: Boolean
        get() = phase == MotionCapturePhase.PREPARING || isCapturing
}

internal data class MotionSample(
    val timestampNanos: Long,
    val elapsedMillis: Double,
    val xMetersPerSecondSquared: Float,
    val yMetersPerSecondSquared: Float,
    val zMetersPerSecondSquared: Float,
    val accuracy: Int,
) {
    fun toCsvRow(): String = listOf(
        timestampNanos,
        elapsedMillis,
        xMetersPerSecondSquared,
        yMetersPerSecondSquared,
        zMetersPerSecondSquared,
        accuracy,
    ).joinToString(",")
}

class MotionCaptureController(context: Context) : SensorEventListener {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionsDirectory = File(appContext.filesDir, "sessions")

    private var sessionDirectory: File? = null
    private var csvWriter: BufferedWriter? = null
    private var audioCaptureEngine: AudioCaptureEngine? = null
    private var audioCaptureResult: AudioCaptureResult? = null
    private var sessionStartElapsedNanos = 0L
    private var sessionStartWallMillis = 0L
    private var lastSensorTimestampNanos = Long.MIN_VALUE
    private var activeSampleCount = 0
    private var countdownEndsAtElapsedNanos = 0L
    private var pendingCaptureDurationMillis = DEFAULT_CAPTURE_DURATION_MILLIS

    var uiState by mutableStateOf(initialState())
        private set

    private val progressUpdate = object : Runnable {
        override fun run() {
            if (!uiState.isCapturing) return
            val elapsed = elapsedSinceSessionStartMillis()
            uiState = uiState.copy(
                elapsedMillis = elapsed.coerceAtMost(uiState.targetDurationMillis),
                sampleCount = activeSampleCount,
            )
            if (elapsed >= uiState.targetDurationMillis) {
                finishCapture(completed = true)
            } else {
                mainHandler.postDelayed(this, UI_UPDATE_INTERVAL_MILLIS)
            }
        }
    }

    private val countdownUpdate = object : Runnable {
        override fun run() {
            if (uiState.phase != MotionCapturePhase.PREPARING) return
            val remainingNanos = countdownEndsAtElapsedNanos - SystemClock.elapsedRealtimeNanos()
            if (remainingNanos <= 0) {
                startCapture(pendingCaptureDurationMillis)
                return
            }
            val remainingSeconds = ((remainingNanos + 999_999_999L) / 1_000_000_000L).toInt()
            uiState = uiState.copy(countdownSeconds = remainingSeconds)
            mainHandler.postDelayed(this, COUNTDOWN_UPDATE_INTERVAL_MILLIS)
        }
    }

    fun prepareCapture(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS) {
        if (uiState.isActive) return
        if (accelerometer == null) {
            uiState = uiState.copy(
                phase = MotionCapturePhase.UNAVAILABLE,
                errorMessage = "This phone does not expose an accelerometer.",
            )
            return
        }
        if (!hasMicrophonePermission()) {
            uiState = uiState.copy(
                phase = MotionCapturePhase.READY,
                microphonePermissionGranted = false,
                errorMessage = "Microphone permission is required for combined capture.",
            )
            return
        }

        pendingCaptureDurationMillis = durationMillis
        countdownEndsAtElapsedNanos = SystemClock.elapsedRealtimeNanos() +
            SETTLE_COUNTDOWN_MILLIS * 1_000_000L
        uiState = uiState.copy(
            phase = MotionCapturePhase.PREPARING,
            countdownSeconds = (SETTLE_COUNTDOWN_MILLIS / 1_000L).toInt(),
            targetDurationMillis = durationMillis,
            elapsedMillis = 0,
            sampleCount = 0,
            audioSampleCount = 0,
            latestSummary = null,
            errorMessage = null,
        )
        mainHandler.post(countdownUpdate)
    }

    private fun startCapture(durationMillis: Long) {
        if (uiState.isCapturing) return
        val sensor = accelerometer
        if (sensor == null) {
            uiState = uiState.copy(
                phase = MotionCapturePhase.UNAVAILABLE,
                errorMessage = "This phone does not expose an accelerometer.",
            )
            return
        }
        if (!hasMicrophonePermission()) {
            uiState = uiState.copy(
                phase = MotionCapturePhase.READY,
                microphonePermissionGranted = false,
                errorMessage = "Microphone permission is required for combined capture.",
            )
            return
        }

        try {
            sessionsDirectory.mkdirs()
            val sessionName = "baseline_${timestampForFileName()}"
            sessionDirectory = File(sessionsDirectory, sessionName).also { it.mkdirs() }
            csvWriter = BufferedWriter(FileWriter(File(sessionDirectory, "accelerometer.csv"))).also {
                it.write("timestamp_ns,elapsed_ms,x_m_s2,y_m_s2,z_m_s2,accuracy")
                it.newLine()
            }

            sessionStartElapsedNanos = SystemClock.elapsedRealtimeNanos()
            sessionStartWallMillis = System.currentTimeMillis()
            lastSensorTimestampNanos = Long.MIN_VALUE
            activeSampleCount = 0
            audioCaptureResult = null

            audioCaptureEngine = AudioCaptureEngine(sessionDirectory!!) { error ->
                mainHandler.post {
                    if (uiState.isCapturing) {
                        finishCapture(completed = false, failure = error)
                    }
                }
            }.also { it.start() }

            val registered = sensorManager.registerListener(
                this,
                sensor,
                SENSOR_SAMPLING_PERIOD_MICROS,
                0,
            )
            if (!registered) {
                throw IllegalStateException("Android could not start the accelerometer listener.")
            }

            uiState = uiState.copy(
                phase = MotionCapturePhase.CAPTURING,
                elapsedMillis = 0,
                targetDurationMillis = durationMillis,
                sampleCount = 0,
                audioSampleCount = 0,
                countdownSeconds = 0,
                microphonePermissionGranted = true,
                latestSummary = null,
                errorMessage = null,
            )
            mainHandler.post(progressUpdate)
        } catch (error: Exception) {
            sensorManager.unregisterListener(this)
            closeWriter()
            audioCaptureEngine?.release()
            audioCaptureEngine = null
            sessionDirectory?.deleteRecursively()
            sessionDirectory = null
            uiState = uiState.copy(
                phase = MotionCapturePhase.ERROR,
                errorMessage = error.message ?: "Motion capture could not start.",
            )
        }
    }

    fun hasMicrophonePermission(): Boolean {
        return appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun refreshMicrophonePermission() {
        uiState = uiState.copy(microphonePermissionGranted = hasMicrophonePermission())
    }

    fun reportMicrophonePermissionDenied() {
        uiState = uiState.copy(
            phase = MotionCapturePhase.READY,
            microphonePermissionGranted = false,
            errorMessage = "Microphone access was denied. Tap Learn to try again.",
        )
    }

    fun createLatestSessionShareIntent(): Intent {
        return SessionExporter(appContext).createLatestSessionShareIntent()
    }

    fun cancelCapture() {
        if (uiState.phase == MotionCapturePhase.PREPARING) {
            mainHandler.removeCallbacks(countdownUpdate)
            uiState = uiState.copy(
                phase = MotionCapturePhase.READY,
                countdownSeconds = 0,
                latestSummary = "Capture cancelled before recording started",
                errorMessage = null,
            )
        } else if (uiState.isCapturing) {
            finishCapture(completed = false, cancellationReason = "Cancelled by operator")
        }
    }

    fun stopForLifecycle() {
        if (uiState.phase == MotionCapturePhase.PREPARING) {
            cancelCapture()
        } else if (uiState.isCapturing) {
            finishCapture(completed = false, cancellationReason = "Stopped when app left foreground")
        }
    }

    fun close() {
        stopForLifecycle()
        mainHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        closeWriter()
        audioCaptureEngine?.release()
        audioCaptureEngine = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!uiState.isCapturing || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (event.timestamp <= lastSensorTimestampNanos) return

        val elapsedMillis = (event.timestamp - sessionStartElapsedNanos) / 1_000_000.0
        if (elapsedMillis < 0.0) return

        val sample = MotionSample(
            timestampNanos = event.timestamp,
            elapsedMillis = elapsedMillis,
            xMetersPerSecondSquared = event.values[0],
            yMetersPerSecondSquared = event.values[1],
            zMetersPerSecondSquared = event.values[2],
            accuracy = event.accuracy,
        )

        try {
            csvWriter?.apply {
                write(sample.toCsvRow())
                newLine()
            }
            lastSensorTimestampNanos = event.timestamp
            activeSampleCount += 1
        } catch (error: Exception) {
            finishCapture(completed = false, failure = error)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finishCapture(
        completed: Boolean,
        cancellationReason: String? = null,
        failure: Exception? = null,
    ) {
        if (!uiState.isCapturing) return

        mainHandler.removeCallbacks(progressUpdate)
        sensorManager.unregisterListener(this)
        closeWriter()

        val durationMillis = elapsedSinceSessionStartMillis()
        val audioFailure = runCatching {
            audioCaptureEngine?.stopAndWriteWav()
                ?: throw IllegalStateException("Microphone capture was not active.")
        }.onSuccess {
            audioCaptureResult = it
        }.exceptionOrNull()
        audioCaptureEngine = null
        val finalFailure = failure ?: audioFailure
        val outcome = when {
            finalFailure != null -> "failed"
            completed && activeSampleCount > 0 && (audioCaptureResult?.sampleCount ?: 0) > 0 -> "completed"
            completed -> "failed"
            else -> "cancelled"
        }
        writeMetadata(
            outcome = outcome,
            durationMillis = durationMillis,
            note = finalFailure?.message ?: cancellationReason,
        )

        uiState = when (outcome) {
            "completed" -> uiState.copy(
                phase = MotionCapturePhase.COMPLETE,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                baselineSessionCount = uiState.baselineSessionCount + 1,
                latestSummary = "Saved ${formatSeconds(durationMillis)} s | " +
                    "$activeSampleCount motion + ${audioCaptureResult?.sampleCount ?: 0} audio samples",
                errorMessage = null,
            )
            "cancelled" -> uiState.copy(
                phase = MotionCapturePhase.READY,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                latestSummary = "Capture cancelled | partial WAV and CSV retained",
                errorMessage = null,
            )
            else -> uiState.copy(
                phase = MotionCapturePhase.ERROR,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                errorMessage = finalFailure?.message ?: "The combined capture contained no samples.",
            )
        }

        sessionDirectory = null
    }

    private fun writeMetadata(outcome: String, durationMillis: Long, note: String?) {
        val directory = sessionDirectory ?: return
        val sensor = accelerometer ?: return
        val metadata = JSONObject().apply {
            put("schema_version", 2)
            put("session_type", "baseline")
            put("capture_channels", JSONArray(listOf("accelerometer", "microphone")))
            put("outcome", outcome)
            put("started_at_unix_ms", sessionStartWallMillis)
            put("duration_ms", durationMillis)
            put("target_duration_ms", uiState.targetDurationMillis)
            put("sample_count", activeSampleCount)
            put("audio_sample_count", audioCaptureResult?.sampleCount ?: 0)
            put("audio_sample_rate_hz", audioCaptureResult?.sampleRateHz ?: AUDIO_SAMPLE_RATE_HZ)
            put("audio_channel_count", 1)
            put("audio_encoding", "PCM_16BIT_LE")
            put("audio_data_bytes", audioCaptureResult?.dataBytes ?: 0)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("android_release", Build.VERSION.RELEASE)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("sensor_name", sensor.name)
            put("sensor_vendor", sensor.vendor)
            put("sensor_version", sensor.version)
            put("sensor_resolution_m_s2", sensor.resolution.toDouble())
            put("sensor_max_range_m_s2", sensor.maximumRange.toDouble())
            put("sensor_min_delay_us", sensor.minDelay)
            put("requested_sampling_period_us", SENSOR_SAMPLING_PERIOD_MICROS)
            if (note != null) put("note", note)
        }
        runCatching {
            File(directory, "metadata.json").writeText(metadata.toString(2))
        }
    }

    private fun initialState(): MotionCaptureUiState {
        val sensor = accelerometer
        return if (sensor == null) {
            MotionCaptureUiState(
                phase = MotionCapturePhase.UNAVAILABLE,
                errorMessage = "This phone does not expose an accelerometer.",
            )
        } else {
            MotionCaptureUiState(
                phase = MotionCapturePhase.READY,
                sensorName = sensor.name,
                baselineSessionCount = countCompletedBaselineSessions(),
                microphonePermissionGranted = hasMicrophonePermission(),
            )
        }
    }

    private fun countCompletedBaselineSessions(): Int {
        return sessionsDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.count { directory ->
                runCatching {
                    JSONObject(File(directory, "metadata.json").readText())
                        .optString("outcome") == "completed" &&
                        File(directory, "accelerometer.csv").isFile &&
                        File(directory, "audio.wav").isFile
                }.getOrDefault(false)
            }
            ?: 0
    }

    private fun elapsedSinceSessionStartMillis(): Long {
        return ((SystemClock.elapsedRealtimeNanos() - sessionStartElapsedNanos) / 1_000_000L)
            .coerceAtLeast(0L)
    }

    private fun closeWriter() {
        runCatching { csvWriter?.flush() }
        runCatching { csvWriter?.close() }
        csvWriter = null
    }

    private fun timestampForFileName(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    }

    private fun formatSeconds(durationMillis: Long): String {
        return String.format(Locale.US, "%.1f", durationMillis / 1_000.0)
    }
}
