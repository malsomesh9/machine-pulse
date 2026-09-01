package com.machinepulse.edge.ui

data class HomeUiState(
    val machineName: String = "Table Fan 01",
    val machineType: String = "Table fan",
    val baselineSessionCount: Int = 0,
    val sensorCaptureReady: Boolean = false,
    val audioCaptureReady: Boolean = false,
) {
    val baselineReady: Boolean
        get() = baselineSessionCount >= 3

    val scanReady: Boolean
        get() = baselineReady && audioCaptureReady
}
