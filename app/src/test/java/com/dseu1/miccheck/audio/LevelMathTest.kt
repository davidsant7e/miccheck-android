package com.dseu1.miccheck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelMathTest {

    private val sampleRate = 44_100

    private fun sine(frequency: Double, amplitude: Double, size: Int = 4_410) =
        ShortArray(size) { i -> (sin(2 * PI * frequency * i / sampleRate) * amplitude * 32_767).toInt().toShort() }

    @Test
    fun silenceIsMinDbAndDigitalSilence() {
        val zeros = ShortArray(1_000)
        assertEquals(LevelMath.MIN_DB, LevelMath.rmsDb(zeros), 0.0)
        assertEquals(LevelMath.MIN_DB, LevelMath.peakDb(zeros), 0.0)
        assertTrue(LevelMath.isDigitalSilence(zeros))
    }

    @Test
    fun oneNonZeroSampleIsNotDigitalSilence() {
        val samples = ShortArray(1_000).also { it[500] = 1 }
        assertFalse(LevelMath.isDigitalSilence(samples))
    }

    @Test
    fun fullScaleSineIsAboutMinus3DbRms() {
        // El RMS de una senoidal es su amplitud / √2 → -3,01 dB.
        val samples = sine(1_000.0, 1.0)
        assertEquals(-3.01, LevelMath.rmsDb(samples), 0.1)
        assertEquals(0.0, LevelMath.peakDb(samples), 0.1)
    }

    @Test
    fun halfAmplitudeIsAboutMinus6Db() {
        val samples = sine(1_000.0, 0.5)
        assertEquals(-6.02, LevelMath.peakDb(samples), 0.1)
    }

    @Test
    fun clippedFractionCountsSaturatedSamples() {
        val samples = ShortArray(100).also {
            it[0] = Short.MAX_VALUE
            it[1] = Short.MIN_VALUE
        }
        assertEquals(0.02, LevelMath.clippedFraction(samples), 1e-9)
    }

    @Test
    fun toneDbFindsTheToneFrequency() {
        val samples = sine(1_000.0, 0.5)
        // Amplitud 0,5 → -6 dB en 1 kHz.
        assertEquals(-6.02, LevelMath.toneDb(samples, samples.size, sampleRate, 1_000.0), 0.3)
        // En otra frecuencia casi no hay energía.
        assertTrue(LevelMath.toneDb(samples, samples.size, sampleRate, 3_000.0) < -50.0)
    }

    @Test
    fun averageDbAveragesPowerNotDecibels() {
        assertEquals(-20.0, LevelMath.averageDb(listOf(-20.0, -20.0)), 1e-9)
        // -20 y -60: domina el más fuerte (≈ -23 dB), no el promedio aritmético (-40).
        assertEquals(-23.0, LevelMath.averageDb(listOf(-20.0, -60.0)), 0.1)
        assertEquals(LevelMath.MIN_DB, LevelMath.averageDb(emptyList()), 0.0)
    }
}
