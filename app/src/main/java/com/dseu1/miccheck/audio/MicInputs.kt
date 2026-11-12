package com.dseu1.miccheck.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Lista los micrófonos conectados y los traduce a [MicOption]. */
object MicInputs {

    fun list(context: Context): List<MicOption> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val found = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { option(it) }
            .distinctBy { it.key }
            .sortedBy { it.kind.ordinal }
        return listOf(MicOption.AUTO) + found
    }

    /** El dispositivo de entrada para esa opción: por id y, si cambió (se reconectó), por tipo y nombre. */
    fun device(context: Context, option: MicOption): AudioDeviceInfo? {
        if (option.kind == MicKind.AUTO) return null
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return inputs.firstOrNull { it.id == option.deviceId && option(it)?.key == option.key }
            ?: inputs.firstOrNull { option(it)?.key == option.key }
            ?: inputs.firstOrNull { kindOf(it.type) == option.kind } // mismo tipo, aunque cambie el nombre
    }

    fun option(device: AudioDeviceInfo): MicOption? {
        val kind = kindOf(device.type) ?: return null
        return MicOption(kind, nameOf(device, kind), device.id)
    }

    fun kindOf(type: Int): MicKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> MicKind.BUILTIN
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> MicKind.BLUETOOTH
        else -> null // canal de llamadas, sintonizadores, etc.: no son micrófonos que se puedan elegir
    }

    private fun nameOf(device: AudioDeviceInfo, kind: MicKind): String {
        if (kind == MicKind.BUILTIN) {
            // Algunos celulares reportan cada micrófono con su posición en la dirección ("bottom", "back"…).
            return when (device.address.lowercase()) {
                "bottom" -> "inferior"
                "back" -> "trasero"
                "top" -> "superior"
                else -> ""
            }
        }
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name == Build.MODEL) "" else name
    }

    // Constante de Android 12 (API 31), copiada como número para compilar con minSdk 26.
    const val TYPE_BLE_HEADSET = 26
}
