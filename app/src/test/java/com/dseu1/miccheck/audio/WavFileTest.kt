package com.dseu1.miccheck.audio

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WavFileTest {
    private fun le32(b: ByteArray, at: Int) =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    @Test
    fun escribeCabeceraYMuestras() {
        val f = File.createTempFile("prueba", ".wav")
        val w = WavFile(f, 16_000)
        w.write(shortArrayOf(1, -1, 0x1234))
        w.write(ShortArray(16_000)) // 1 s
        w.close()
        val b = f.readBytes()
        assertEquals(44 + (3 + 16_000) * 2, b.size)
        assertEquals("RIFF", String(b, 0, 4))
        assertEquals("WAVE", String(b, 8, 4))
        assertEquals(36L + (3 + 16_000) * 2, le32(b, 4))
        assertEquals(16_000L, le32(b, 24))
        assertEquals(32_000L, le32(b, 28))
        assertEquals((3L + 16_000) * 2, le32(b, 40))
        assertArrayEquals(byteArrayOf(1, 0, -1, -1, 0x34, 0x12), b.copyOfRange(44, 50))
        assertEquals(1000L, w.durationMs)
        f.delete()
    }

    @Test
    fun bloquesGrandesSePartenBien() {
        val f = File.createTempFile("prueba", ".wav")
        val datos = ShortArray(100_000) { (it % 300).toShort() }
        WavFile(f, 44_100).use { it.write(datos) }
        val b = f.readBytes()
        assertEquals(44 + 200_000, b.size)
        assertEquals(299, (b[44 + 2 * 299].toInt() and 0xFF) or (b[45 + 2 * 299].toInt() shl 8))
        f.delete()
    }
}
