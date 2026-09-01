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
    fun baselineIsReadyAfterAtLeastOneRecordedSession() {
        assertTrue(HomeUiState(baselineSessionCount = 1).baselineReady)
    }

    @Test
    fun scanRequiresBothBaselineAndAudioCapture() {
        assertFalse(HomeUiState(baselineSessionCount = 1).scanReady)
        assertTrue(
            HomeUiState(
                baselineSessionCount = 1,
                audioCaptureReady = true,
            ).scanReady,
        )
    }
}
