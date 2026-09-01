package com.machinepulse.edge.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun baselineIsNotReadyWithoutRecordedSessions() {
        assertFalse(HomeUiState().baselineReady)
    }

    @Test
    fun baselineRequiresThreeAcceptedSessions() {
        assertFalse(HomeUiState(baselineSessionCount = 2).baselineReady)
        assertTrue(HomeUiState(baselineSessionCount = 3).baselineReady)
    }

    @Test
    fun scanRequiresBothBaselineAndAudioCapture() {
        assertFalse(HomeUiState(baselineSessionCount = 3).scanReady)
        assertTrue(
            HomeUiState(
                baselineSessionCount = 3,
                audioCaptureReady = true,
            ).scanReady,
        )
    }
}
