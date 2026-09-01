package com.machinepulse.edge.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionSampleTest {
    @Test
    fun csvRowKeepsTimestampAxesAndAccuracyInSchemaOrder() {
        val sample = MotionSample(
            timestampNanos = 123_456L,
            elapsedMillis = 12.5,
            xMetersPerSecondSquared = 1.25f,
            yMetersPerSecondSquared = -2.5f,
            zMetersPerSecondSquared = 9.81f,
            accuracy = 3,
        )

        assertEquals("123456,12.5,1.25,-2.5,9.81,3", sample.toCsvRow())
    }
}
