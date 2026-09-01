package com.machinepulse.edge.capture

import android.content.Context
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

enum class MotionCapturePhase {
    READY,
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
    val baselineSessionCount: Int = 0,
    val latestSummary: String? = null,
    val errorMessage: String? = null,
) {
    val sensorAvailable: Boolean
        get() = phase != MotionCapturePhase.UNAVAILABLE

    val isCapturing: Boolean
        get() = phase == MotionCapturePhase.CAPTURING
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
    private var sessionStartElapsedNanos = 0L
    private var sessionStartWallMillis = 0L
    private var lastSensorTimestampNanos = Long.MIN_VALUE
    private var activeSampleCount = 0

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

    fun startCapture(durationMillis: Long = DEFAULT_CAPTURE_DURATION_MILLIS) {
        if (uiState.isCapturing) return
        val sensor = accelerometer
        if (sensor == null) {
            uiState = uiState.copy(
                phase = MotionCapturePhase.UNAVAILABLE,
                errorMessage = "This phone does not expose an accelerometer.",
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
                latestSummary = null,
                errorMessage = null,
            )
            mainHandler.post(progressUpdate)
        } catch (error: Exception) {
            sensorManager.unregisterListener(this)
            closeWriter()
            uiState = uiState.copy(
                phase = MotionCapturePhase.ERROR,
                errorMessage = error.message ?: "Motion capture could not start.",
            )
        }
    }

    fun cancelCapture() {
        if (uiState.isCapturing) {
            finishCapture(completed = false, cancellationReason = "Cancelled by operator")
        }
    }

    fun stopForLifecycle() {
        if (uiState.isCapturing) {
            finishCapture(completed = false, cancellationReason = "Stopped when app left foreground")
        }
    }

    fun close() {
        stopForLifecycle()
        mainHandler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(this)
        closeWriter()
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
        val outcome = when {
            failure != null -> "failed"
            completed && activeSampleCount > 0 -> "completed"
            completed -> "failed"
            else -> "cancelled"
        }
        writeMetadata(
            outcome = outcome,
            durationMillis = durationMillis,
            note = failure?.message ?: cancellationReason,
        )

        uiState = when (outcome) {
            "completed" -> uiState.copy(
                phase = MotionCapturePhase.COMPLETE,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                baselineSessionCount = uiState.baselineSessionCount + 1,
                latestSummary = "Saved ${formatSeconds(durationMillis)} s | $activeSampleCount motion samples",
                errorMessage = null,
            )
            "cancelled" -> uiState.copy(
                phase = MotionCapturePhase.READY,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                latestSummary = "Capture cancelled | $activeSampleCount partial samples retained",
                errorMessage = null,
            )
            else -> uiState.copy(
                phase = MotionCapturePhase.ERROR,
                elapsedMillis = durationMillis,
                sampleCount = activeSampleCount,
                errorMessage = failure?.message ?: "No accelerometer samples were received.",
            )
        }

        sessionDirectory = null
    }

    private fun writeMetadata(outcome: String, durationMillis: Long, note: String?) {
        val directory = sessionDirectory ?: return
        val sensor = accelerometer ?: return
        val metadata = JSONObject().apply {
            put("schema_version", 1)
            put("session_type", "baseline")
            put("capture_channel", "accelerometer")
            put("outcome", outcome)
            put("started_at_unix_ms", sessionStartWallMillis)
            put("duration_ms", durationMillis)
            put("target_duration_ms", uiState.targetDurationMillis)
            put("sample_count", activeSampleCount)
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
            )
        }
    }

    private fun countCompletedBaselineSessions(): Int {
        return sessionsDirectory.listFiles()
            ?.filter { it.isDirectory }
            ?.count { directory ->
                runCatching {
                    JSONObject(File(directory, "metadata.json").readText())
                        .optString("outcome") == "completed"
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
