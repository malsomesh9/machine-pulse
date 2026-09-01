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
}
