package com.machinepulse.edge.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureAnalysisTest {
    private val stableBaseline = SignalFeatures(
        audioRms = 600.0,
        audioPeak = 4_000,
        audioClippingFraction = 0.0,
        audioZeroCrossingRate = 0.08,
        motionDynamicRms = 0.03,
        motionMaxAxisRange = 0.10,
    )

    @Test
    fun rejectsCaptureWhenPhoneMoves() {
        val moved = stableBaseline.copy(motionMaxAxisRange = 2.1)

        assertFalse(assessCaptureQuality(moved).accepted)
    }

    @Test
    fun acceptsStableAudibleCapture() {
        assertTrue(assessCaptureQuality(stableBaseline).accepted)
    }

    @Test
    fun matchingObservationStaysInsideBaseline() {
        val result = compareWithBaselines(
            observation = stableBaseline.copy(audioRms = 620.0, motionDynamicRms = 0.032),
            baselines = listOf(stableBaseline, stableBaseline.copy(audioRms = 610.0)),
        )

        assertFalse(result.outOfBaseline)
    }

    @Test
    fun largeAcousticChangeIsOutOfBaseline() {
        val result = compareWithBaselines(
            observation = stableBaseline.copy(audioRms = 1_200.0),
            baselines = listOf(stableBaseline, stableBaseline.copy(audioRms = 610.0)),
        )

        assertTrue(result.outOfBaseline)
        assertTrue(result.primaryExplanation.contains("Acoustic RMS"))
    }
}
