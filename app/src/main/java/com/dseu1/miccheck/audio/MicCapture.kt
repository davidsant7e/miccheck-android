package com.dseu1.miccheck.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.max

/** Error del micrófono con un mensaje listo para mostrar al usuario. */
class MicException(message: String) : Exception(message)

/**
 * Captura del micrófono con AudioRecord.
 *
 * [frames] entrega un Flow: mientras alguien lo recolecta (collect), el micrófono graba;
 * al cancelar la recolección, se detiene y libera el micrófono automáticamente (finally).
 */
class MicCapture(
    val sampleRate: Int = SAMPLE_RATE,
) {
    companion object {
        const val SAMPLE_RATE = 44_100

        /** 100 ms por bloque a 44.1 kHz. */
        const val FRAME_SIZE = 4_410
    }

    /**
     * VOICE_RECOGNITION suele entregar el audio sin control automático de ganancia,
     * así los niveles reflejan mejor el estado real del micrófono que la fuente MIC.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(frameSize: Int = FRAME_SIZE): Flow<AudioFrame> = flow {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        if (minBuffer <= 0) {
            throw MicException("Este celular no acepta el formato de grabación (44.1 kHz, mono).")
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channel,
                encoding,
                max(minBuffer, frameSize * 2 * 2), // 2 bytes por muestra, 2 bloques de margen
            )
        } catch (e: IllegalArgumentException) {
            throw MicException("No se pudo preparar el micrófono: ${e.message}")
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw MicException("No se pudo abrir el micrófono. Revisa el permiso y que ninguna llamada esté en curso.")
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw MicException("No se pudo empezar a grabar: otra app puede estar usando el micrófono.")
            }

            val buffer = ShortArray(frameSize)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < frameSize) {
                    val read = record.read(buffer, filled, frameSize - filled)
                    if (read < 0) throw MicException("Error al leer el micrófono (código $read).")
                    filled += read
                }
                val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    record.activeRecordingConfiguration?.isClientSilenced
                } else {
                    null
                }
                emit(AudioFrame(buffer.copyOf(), sampleRate, silenced))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
