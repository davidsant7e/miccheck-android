package com.dseu1.miccheck.audio

/**
 * Un bloque corto de audio capturado (por defecto 100 ms) con sus niveles ya calculados.
 *
 * @param clientSilenced en Android 10+ indica si Android silenció nuestra grabación
 *   porque otra app tiene prioridad sobre el micrófono; null si no se pudo saber.
 */
class AudioFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val clientSilenced: Boolean?,
) {
    val rmsDb: Double = LevelMath.rmsDb(samples)
    val peakDb: Double = LevelMath.peakDb(samples)
    val clippedFraction: Double = LevelMath.clippedFraction(samples)
    val digitalSilence: Boolean = LevelMath.isDigitalSilence(samples)

    fun toneDb(frequency: Double): Double =
        LevelMath.toneDb(samples, samples.size, sampleRate, frequency)
}
