package com.dseu1.miccheck.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Prepara el camino del audio para el micrófono elegido.
 *
 * - Celular, cable y USB: basta con pedírselo a AudioRecord (setPreferredDevice).
 * - Bluetooth: el micrófono de un audífono Bluetooth solo funciona en "modo llamada" (perfil HFP/SCO).
 *   Hay que poner el teléfono en modo comunicación y abrir ese canal; al terminar se cierra y se deja
 *   todo como estaba. En Android 12+ se usa setCommunicationDevice; antes, startBluetoothSco.
 *
 * Uso: [open] antes de grabar y [close] siempre al final (en un finally).
 */
class MicRoute(private val context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int? = null
    private var legacySco = false
    private var communicationSet = false

    /** Fuente de AudioRecord adecuada: la de llamadas para Bluetooth (si no, Android no usa el canal SCO). */
    fun source(option: MicOption): Int =
        if (option.kind == MicKind.BLUETOOTH) MediaRecorder.AudioSource.VOICE_COMMUNICATION
        else MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Devuelve el dispositivo de entrada que se debe preferir (null = automático). */
    suspend fun open(option: MicOption): AudioDeviceInfo? {
        if (option.kind == MicKind.AUTO) return null
        val input = MicInputs.device(context, option)
            ?: throw MicException("${option.label} no está conectado. Conéctalo o elige otro micrófono.")
        if (option.kind == MicKind.BLUETOOTH) startBluetooth(input)
        return input
    }

    fun close() {
        if (communicationSet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        }
        if (legacySco) {
            @Suppress("DEPRECATION")
            runCatching {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        }
        previousMode?.let { runCatching { am.mode = it } }
        previousMode = null
        legacySco = false
        communicationSet = false
    }

    private suspend fun startBluetooth(input: AudioDeviceInfo) {
        previousMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val candidates = am.availableCommunicationDevices
            val target = candidates.firstOrNull { it.type == input.type && it.address == input.address }
                ?: candidates.firstOrNull { it.type == input.type }
                ?: candidates.firstOrNull { MicInputs.kindOf(it.type) == MicKind.BLUETOOTH || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: throw MicException(NO_CALL_AUDIO)
            if (!am.setCommunicationDevice(target)) throw MicException(NO_CALL_AUDIO)
            communicationSet = true
            // El canal tarda un momento en abrir; se espera hasta 3 s.
            withTimeoutOrNull(3_000) { while (am.communicationDevice?.id != target.id) delay(100) }
                ?: throw MicException(NO_CHANNEL)
            delay(300)
        } else {
            legacySco = true
            val connected = withTimeoutOrNull(6_000) { waitForScoLegacy() } == true
            if (!connected) throw MicException(NO_CHANNEL)
        }
    }

    /** Android 8–11: abre el canal SCO y espera el aviso del sistema de que quedó conectado. */
    @Suppress("DEPRECATION")
    private suspend fun waitForScoLegacy(): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && cont.isActive) {
                    runCatching { context.unregisterReceiver(this) }
                    am.isBluetoothScoOn = true
                    cont.resume(true)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED,
        )
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        am.startBluetoothSco()
    }

    private companion object {
        const val NO_CALL_AUDIO =
            "Android no ofrece este Bluetooth para llamadas. En Ajustes → Bluetooth → tu audífono, activa «Llamadas» (o «Audio del teléfono»)."
        const val NO_CHANNEL =
            "El audífono Bluetooth no abrió el micrófono. Revisa que esté cerca y con carga, y que no esté en una llamada en otro equipo."
    }
}
