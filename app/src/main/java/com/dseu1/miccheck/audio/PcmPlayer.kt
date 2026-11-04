package com.dseu1.miccheck.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Reproduce audio PCM de 16 bits por el parlante (o por los audífonos, si están conectados). */
object PcmPlayer {

    /** Reproduce las muestras y espera a que terminen. Si se cancela la corrutina, se detiene. */
    suspend fun play(samples: ShortArray, sampleRate: Int) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()

        try {
            track.write(samples, 0, samples.size)
            track.play()
            val durationMs = samples.size * 1000L / sampleRate
            delay(durationMs + 150)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Genera un tono senoidal con entrada y salida suaves (evita "clics"). */
    fun sineTone(
        frequency: Double,
        durationMs: Int,
        amplitude: Double,
        sampleRate: Int = MicCapture.SAMPLE_RATE,
    ): ShortArray {
        val count = sampleRate * durationMs / 1000
        val fade = min(count / 2, sampleRate / 50) // 20 ms
        return ShortArray(count) { i ->
            val envelope = when {
                i < fade -> i.toDouble() / fade
                i > count - fade -> (count - i).toDouble() / fade
                else -> 1.0
            }
            val value = sin(2.0 * PI * frequency * i / sampleRate) * amplitude * envelope
            (value * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
