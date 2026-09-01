package com.machinepulse.edge.capture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCaptureEngineTest {
    @Test
    fun wavHeaderDescribesMonoPcm16Data() {
        val dataSize = 88_200
        val header = createWavHeader(dataSize)
        val littleEndian = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(44, header.size)
        assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(36 + dataSize, littleEndian.getInt(4))
        assertEquals("WAVE", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(1, littleEndian.getShort(22).toInt())
        assertEquals(44_100, littleEndian.getInt(24))
        assertEquals(88_200, littleEndian.getInt(28))
        assertEquals(2, littleEndian.getShort(32).toInt())
        assertEquals(16, littleEndian.getShort(34).toInt())
        assertEquals("data", header.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(dataSize, littleEndian.getInt(40))
    }
}
