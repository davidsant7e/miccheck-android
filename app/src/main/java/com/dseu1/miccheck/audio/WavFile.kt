package com.dseu1.miccheck.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Escribe un WAV (PCM 16 bits, mono) a medida que llega el audio, sin guardarlo todo en memoria.
 * Al cerrar se completan los tamaños de la cabecera. Sin Android: se prueba con JUnit.
 */
class WavFile(file: File, val sampleRate: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(64 * 1024)
    var dataBytes: Long = 0
        private set

    init {
        out.setLength(0)
        out.write(header(sampleRate, 0))
    }

    val durationMs: Long get() = dataBytes / 2 * 1000 / sampleRate

    fun write(samples: ShortArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, buffer.size / 2)
            for (k in 0 until n) {
                val s = samples[i + k].toInt()
                buffer[2 * k] = (s and 0xFF).toByte()
                buffer[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(buffer, 0, n * 2)
            dataBytes += n * 2L
            i += n
        }
    }

    override fun close() {
        out.seek(0)
        out.write(header(sampleRate, dataBytes))
        out.close()
    }

    companion object {
        /** Cabecera RIFF/WAVE de 44 bytes para PCM 16 bits mono. */
        fun header(sampleRate: Int, dataBytes: Long): ByteArray {
            val b = ByteArray(44)
            fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
            fun int32(at: Int, v: Long) { for (i in 0..3) b[at + i] = ((v shr (8 * i)) and 0xFF).toByte() }
            fun int16(at: Int, v: Int) { b[at] = (v and 0xFF).toByte(); b[at + 1] = ((v shr 8) and 0xFF).toByte() }
            ascii(0, "RIFF"); int32(4, 36 + dataBytes); ascii(8, "WAVE")
            ascii(12, "fmt "); int32(16, 16); int16(20, 1); int16(22, 1)
            int32(24, sampleRate.toLong()); int32(28, sampleRate * 2L); int16(32, 2); int16(34, 16)
            ascii(36, "data"); int32(40, dataBytes)
            return b
        }
    }
}
