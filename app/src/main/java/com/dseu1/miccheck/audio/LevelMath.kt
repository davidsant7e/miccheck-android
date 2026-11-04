package com.dseu1.miccheck.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cálculos de nivel de audio sobre muestras PCM de 16 bits.
 *
 * Todos los valores están en dBFS (decibeles respecto al máximo digital):
 * 0 dBFS es el máximo posible y los valores normales son negativos.
 * No son decibeles SPL calibrados (lo que mediría un sonómetro).
 *
 * No depende de Android, así que se puede probar con pruebas unitarias simples.
 */
object LevelMath {

    /** Valor que usamos para "silencio absoluto" (log de cero sería -infinito). */
    const val MIN_DB = -100.0

    /** Muestra a partir de la cual consideramos que la señal está saturada. */
    const val CLIP_THRESHOLD = 32_000

    private const val FULL_SCALE = 32_768.0

    /** Convierte una amplitud lineal (0..1) a dBFS. */
    fun toDb(linear: Double): Double =
        if (linear <= 0.0) MIN_DB else max(MIN_DB, 20.0 * log10(linear))

    /**
     * Promedio de varios niveles en dB, promediando la energía (no los números en dB).
     * Ej.: promediar -20 y -60 da unos -23 dB, porque -20 dB tiene mucha más energía.
     */
    fun averageDb(values: List<Double>): Double {
        if (values.isEmpty()) return MIN_DB
        val meanPower = values.sumOf { 10.0.pow(it / 10.0) } / values.size
        return if (meanPower <= 0.0) MIN_DB else max(MIN_DB, 10.0 * log10(meanPower))
    }

    /** Nivel RMS (energía promedio) del bloque, en dBFS. */
    fun rmsDb(samples: ShortArray, size: Int = samples.size): Double {
        if (size <= 0) return MIN_DB
        var sum = 0.0
        for (i in 0 until size) {
            val s = samples[i] / FULL_SCALE
            sum += s * s
        }
        return toDb(sqrt(sum / size))
    }

    /** Pico (muestra más alta) del bloque, en dBFS. */
    fun peakDb(samples: ShortArray, size: Int = samples.size): Double {
        var peak = 0
        for (i in 0 until size) {
            val a = abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        return toDb(peak / FULL_SCALE)
    }

    /** Fracción (0..1) de muestras saturadas o casi saturadas. */
    fun clippedFraction(samples: ShortArray, size: Int = samples.size): Double {
        if (size <= 0) return 0.0
        var clipped = 0
        for (i in 0 until size) {
            if (abs(samples[i].toInt()) >= CLIP_THRESHOLD) clipped++
        }
        return clipped.toDouble() / size
    }

    /**
     * true si todas las muestras son exactamente cero.
     * Un micrófono real siempre capta algo de ruido; ceros perfectos indican que
     * Android está entregando silencio (acceso bloqueado u otra app con el micrófono).
     */
    fun isDigitalSilence(samples: ShortArray, size: Int = samples.size): Boolean {
        for (i in 0 until size) {
            if (samples[i].toInt() != 0) return false
        }
        return true
    }

    /**
     * Nivel de una sola frecuencia (algoritmo de Goertzel), en dBFS.
     * Una onda senoidal de amplitud máxima en esa frecuencia da cerca de 0 dBFS.
     * Lo usamos para saber si el micrófono "oye" el tono que suena por el parlante.
     */
    fun toneDb(samples: ShortArray, size: Int, sampleRate: Int, frequency: Double): Double {
        if (size <= 0) return MIN_DB
        val k = (size * frequency / sampleRate).roundToInt()
        val omega = 2.0 * PI * k / size
        val coeff = 2.0 * cos(omega)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until size) {
            val s0 = samples[i] / FULL_SCALE + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        val amplitude = 2.0 * sqrt(max(0.0, power)) / size
        return toDb(amplitude)
    }
}
