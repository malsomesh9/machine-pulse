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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

enum class CaptureMode(val filePrefix: String, val metadataValue: String) {
    BASELINE("baseline", "baseline"),
    OBSERVATION("observation", "observation"),
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
    val captureMode: CaptureMode = CaptureMode.BASELINE,
    val latestComparison: BaselineComparison? = null,
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
    private var pendingCaptureMode = CaptureMode.BASELINE
    private var activeCaptureMode = CaptureMode.BASELINE
    private var motionSumX = 0.0
    private var motionSumY = 0.0
    private var motionSumZ = 0.0
    private var motionSquareSumX = 0.0
    private var motionSquareSumY = 0.0
    private var motionSquareSumZ = 0.0
    private var motionMinX = Double.POSITIVE_INFINITY
    private var motionMinY = Double.POSITIVE_INFINITY
    private var motionMinZ = Double.POSITIVE_INFINITY
    private var motionMaxX = Double.NEGATIVE_INFINITY
    private var motionMaxY = Double.NEGATIVE_INFINITY
    private var motionMaxZ = Double.NEGATIVE_INFINITY

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
                startCapture(pendingCaptureDurationMillis, pendingCaptureMode)
                return
            }
            val remainingSeconds = ((remainingNanos + 999_999_999L) / 1_000_000_000L).toInt()
            uiState = uiState.copy(countdownSeconds = remainingSeconds)
            mainHandler.postDelayed(this, COUNTDOWN_UPDATE_INTERVAL_MILLIS)
        }
    }

    fun prepareBaselineCapture(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS) {
        prepareCapture(CaptureMode.BASELINE, durationMillis)
    }

    fun prepareObservationCapture(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS) {
        prepareCapture(CaptureMode.OBSERVATION, durationMillis)
    }

    private fun prepareCapture(mode: CaptureMode, durationMillis: Long) {
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
        pendingCaptureMode = mode
        countdownEndsAtElapsedNanos = SystemClock.elapsedRealtimeNanos() +
            SETTLE_COUNTDOWN_MILLIS * 1_000_000L
        uiState = uiState.copy(
            phase = MotionCapturePhase.PREPARING,
            countdownSeconds = (SETTLE_COUNTDOWN_MILLIS / 1_000L).toInt(),
            targetDurationMillis = durationMillis,
            elapsedMillis = 0,
            sampleCount = 0,
            audioSampleCount = 0,
            captureMode = mode,
            latestComparison = if (mode == CaptureMode.BASELINE) uiState.latestComparison else null,
            latestSummary = null,
            errorMessage = null,
        )
        mainHandler.post(countdownUpdate)
    }

    private fun startCapture(durationMillis: Long, mode: CaptureMode) {
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
            val sessionName = "${mode.filePrefix}_${timestampForFileName()}"
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
            activeCaptureMode = mode
            resetMotionStatistics()

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
                captureMode = mode,
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
            updateMotionStatistics(sample)
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
        val features = if (
            finalFailure == null && activeSampleCount > 0 && (audioCaptureResult?.sampleCount ?: 0) > 0
        ) {
            buildSignalFeatures(audioCaptureResult!!)
        } else {
            null
        }
        val quality = features?.let(::assessCaptureQuality)
        val baselineFeatures = if (
            activeCaptureMode == CaptureMode.OBSERVATION && quality?.accepted == true
        ) {
            loadAcceptedBaselineFeatures()
        } else {
            emptyList()
        }
        val comparison = if (
            activeCaptureMode == CaptureMode.OBSERVATION &&
            features != null &&
            quality?.accepted == true &&
            baselineFeatures.isNotEmpty()
        ) {
            compareWithBaselines(features, baselineFeatures)
        } else {
            null
        }
        val outcome = when {
            finalFailure != null -> "failed"
            !completed -> "cancelled"
            features == null -> "failed"
            quality?.accepted == false -> "rejected"
            activeCaptureMode == CaptureMode.OBSERVATION && comparison == null -> "failed"
            else -> "completed"
        }
        val note = finalFailure?.message
            ?: cancellationReason
            ?: quality?.takeIf { !it.accepted }?.reason
            ?: if (activeCaptureMode == CaptureMode.OBSERVATION && comparison == null) {
                "No accepted baseline was available for comparison."
            } else {
                null
            }
        writeMetadata(
            outcome = outcome,
            durationMillis = durationMillis,
            features = features,
            quality = quality,
            comparison = comparison,
            note = note,
        )

        uiState = when (outcome) {
            "completed" -> if (activeCaptureMode == CaptureMode.BASELINE) {
                uiState.copy(
                    phase = MotionCapturePhase.COMPLETE,
                    elapsedMillis = durationMillis,
                    sampleCount = activeSampleCount,
                    audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                    baselineSessionCount = uiState.baselineSessionCount + 1,
                    latestSummary = "Baseline accepted | ${formatSeconds(durationMillis)} s",
                    errorMessage = null,
                )
            } else {
                uiState.copy(
                    phase = MotionCapturePhase.COMPLETE,
                    elapsedMillis = durationMillis,
                    sampleCount = activeSampleCount,
                    audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                    latestSummary = "Scan complete | ${formatSeconds(durationMillis)} s",
                    latestComparison = comparison,
                    errorMessage = null,
                )
            }
            "rejected" -> uiState.copy(
                phase = MotionCapturePhase.READY,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                audioSampleCount = audioCaptureResult?.sampleCount ?: 0,
                latestSummary = "Rejected | ${quality?.reason}",
                latestComparison = null,
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

    private fun writeMetadata(
        outcome: String,
        durationMillis: Long,
        features: SignalFeatures?,
        quality: CaptureQuality?,
        comparison: BaselineComparison?,
        note: String?,
    ) {
        val directory = sessionDirectory ?: return
        val sensor = accelerometer ?: return
        val metadata = JSONObject().apply {
            put("schema_version", 3)
            put("session_type", activeCaptureMode.metadataValue)
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
            put("audio_rms", audioCaptureResult?.rmsAmplitude ?: 0.0)
            put("audio_peak", audioCaptureResult?.peakAmplitude ?: 0)
            put("audio_clipping_fraction", audioCaptureResult?.clippingFraction ?: 0.0)
            put("audio_zero_crossing_rate", audioCaptureResult?.zeroCrossingRate ?: 0.0)
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
            if (features != null) {
                put("features", JSONObject().apply {
                    put("audio_rms", features.audioRms)
                    put("audio_peak", features.audioPeak)
                    put("audio_clipping_fraction", features.audioClippingFraction)
                    put("audio_zero_crossing_rate", features.audioZeroCrossingRate)
                    put("motion_dynamic_rms", features.motionDynamicRms)
                    put("motion_max_axis_range", features.motionMaxAxisRange)
                })
            }
            if (quality != null) {
                put("quality", JSONObject().apply {
                    put("accepted", quality.accepted)
                    put("reason", quality.reason)
                })
            }
            if (comparison != null) {
                put("comparison", JSONObject().apply {
                    put("out_of_baseline", comparison.outOfBaseline)
                    put("score", comparison.score)
                    put("primary_explanation", comparison.primaryExplanation)
                    put("audio_rms_delta_percent", comparison.audioRmsDeltaPercent)
                    put("vibration_delta_percent", comparison.vibrationDeltaPercent)
                    put("spectral_proxy_delta_percent", comparison.spectralProxyDeltaPercent)
                })
            }
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
                    val metadata = JSONObject(File(directory, "metadata.json").readText())
                    metadata.optInt("schema_version") >= 3 &&
                        metadata.optString("session_type") == CaptureMode.BASELINE.metadataValue &&
                        metadata.optString("outcome") == "completed" &&
                        metadata.optJSONObject("quality")?.optBoolean("accepted") == true &&
                        File(directory, "accelerometer.csv").isFile &&
                        File(directory, "audio.wav").isFile
                }.getOrDefault(false)
            }
            ?: 0
    }

    private fun loadAcceptedBaselineFeatures(): List<SignalFeatures> {
        return sessionsDirectory.listFiles()
            ?.mapNotNull { directory ->
                runCatching {
                    val metadata = JSONObject(File(directory, "metadata.json").readText())
                    if (
                        metadata.optInt("schema_version") < 3 ||
                        metadata.optString("session_type") != CaptureMode.BASELINE.metadataValue ||
                        metadata.optString("outcome") != "completed" ||
                        metadata.optJSONObject("quality")?.optBoolean("accepted") != true
                    ) {
                        return@runCatching null
                    }
                    metadata.optJSONObject("features")?.toSignalFeatures()
                }.getOrNull()
            }
            ?: emptyList()
    }

    private fun JSONObject.toSignalFeatures(): SignalFeatures {
        return SignalFeatures(
            audioRms = getDouble("audio_rms"),
            audioPeak = getInt("audio_peak"),
            audioClippingFraction = getDouble("audio_clipping_fraction"),
            audioZeroCrossingRate = getDouble("audio_zero_crossing_rate"),
            motionDynamicRms = getDouble("motion_dynamic_rms"),
            motionMaxAxisRange = getDouble("motion_max_axis_range"),
        )
    }

    private fun resetMotionStatistics() {
        motionSumX = 0.0
        motionSumY = 0.0
        motionSumZ = 0.0
        motionSquareSumX = 0.0
        motionSquareSumY = 0.0
        motionSquareSumZ = 0.0
        motionMinX = Double.POSITIVE_INFINITY
        motionMinY = Double.POSITIVE_INFINITY
        motionMinZ = Double.POSITIVE_INFINITY
        motionMaxX = Double.NEGATIVE_INFINITY
        motionMaxY = Double.NEGATIVE_INFINITY
        motionMaxZ = Double.NEGATIVE_INFINITY
    }

    private fun updateMotionStatistics(sample: MotionSample) {
        val x = sample.xMetersPerSecondSquared.toDouble()
        val y = sample.yMetersPerSecondSquared.toDouble()
        val z = sample.zMetersPerSecondSquared.toDouble()
        motionSumX += x
        motionSumY += y
        motionSumZ += z
        motionSquareSumX += x * x
        motionSquareSumY += y * y
        motionSquareSumZ += z * z
        motionMinX = min(motionMinX, x)
        motionMinY = min(motionMinY, y)
        motionMinZ = min(motionMinZ, z)
        motionMaxX = max(motionMaxX, x)
        motionMaxY = max(motionMaxY, y)
        motionMaxZ = max(motionMaxZ, z)
    }

    private fun buildSignalFeatures(audio: AudioCaptureResult): SignalFeatures {
        val count = activeSampleCount.coerceAtLeast(1).toDouble()
        val varianceX = max(0.0, motionSquareSumX / count - (motionSumX / count) * (motionSumX / count))
        val varianceY = max(0.0, motionSquareSumY / count - (motionSumY / count) * (motionSumY / count))
        val varianceZ = max(0.0, motionSquareSumZ / count - (motionSumZ / count) * (motionSumZ / count))
        val maxAxisRange = max(
            motionMaxX - motionMinX,
            max(motionMaxY - motionMinY, motionMaxZ - motionMinZ),
        )
        return SignalFeatures(
            audioRms = audio.rmsAmplitude,
            audioPeak = audio.peakAmplitude,
            audioClippingFraction = audio.clippingFraction,
            audioZeroCrossingRate = audio.zeroCrossingRate,
            motionDynamicRms = sqrt(varianceX + varianceY + varianceZ),
            motionMaxAxisRange = maxAxisRange,
        )
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
