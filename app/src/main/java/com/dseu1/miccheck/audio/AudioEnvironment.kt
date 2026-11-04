package com.dseu1.miccheck.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build

/** Lee del sistema la información del entorno de audio. */
object AudioEnvironment {

    fun read(context: Context): EnvironmentInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val inputNames = inputs.mapNotNull { inputName(it.type) }.distinct()
        val externalInput = inputs.firstOrNull { isExternal(it.type) }?.let { describe(it) }
        val externalOutput = outputs.firstOrNull { isExternal(it.type) }?.let { describe(it) }

        val microphones = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                audioManager.microphones.map { mic ->
                    val where = locationName(mic.location)
                    if (mic.description.isNullOrBlank()) where else "${mic.description} ($where)"
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        return EnvironmentInfo(
            hasMicrophoneFeature = context.packageManager
                .hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            inputs = inputNames,
            externalInput = externalInput,
            externalOutput = externalOutput,
            microphones = microphones,
            echoCancelerAvailable = AcousticEchoCanceler.isAvailable(),
            noiseSuppressorAvailable = NoiseSuppressor.isAvailable(),
            gainControlAvailable = AutomaticGainControl.isAvailable(),
            otherActiveRecordings = audioManager.activeRecordingConfigurations.size,
            mediaVolumeZero = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0,
        )
    }

    private fun isExternal(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
    )

    private fun describe(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Audífonos con cable"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            TYPE_BLE_HEADSET,
            TYPE_BLE_SPEAKER -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            else -> "Dispositivo externo"
        }
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty() || name == Build.MODEL) kind else "$kind: $name"
    }

    private fun inputName(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micrófono del celular"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Micrófono de audífonos con cable"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> "Micrófono Bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Micrófono USB"
        AudioDeviceInfo.TYPE_TELEPHONY -> null // canal interno de llamadas, no es un micrófono
        AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER -> null
        else -> null
    }

    private fun locationName(location: Int): String = when (location) {
        MicrophoneInfo.LOCATION_MAINBODY -> "en el cuerpo del celular"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "en una parte móvil"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "externo"
        else -> "ubicación desconocida"
    }

    // Constantes de Android 12 (API 31). Se copian como número para compilar con minSdk 26;
    // en versiones viejas simplemente nunca aparecen.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
}
