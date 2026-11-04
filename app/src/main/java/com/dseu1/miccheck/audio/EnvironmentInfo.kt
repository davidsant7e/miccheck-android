package com.dseu1.miccheck.audio

/**
 * Lo que sabemos del entorno de audio del celular en un momento dado.
 * Es una clase simple (sin Android) para poder usarla en pruebas unitarias.
 */
data class EnvironmentInfo(
    /** El sistema declara que tiene micrófono. */
    val hasMicrophoneFeature: Boolean = true,
    /** Entradas de audio disponibles, con nombre legible ("Micrófono del celular", "Audífonos Bluetooth"…). */
    val inputs: List<String> = emptyList(),
    /** Si hay un micrófono externo conectado (audífonos, Bluetooth, USB), su descripción. */
    val externalInput: String? = null,
    /** Si el sonido sale por audífonos o Bluetooth en vez del parlante, su descripción. */
    val externalOutput: String? = null,
    /** Micrófonos físicos que reporta el sistema (Android 9+). Vacío si no los reporta. */
    val microphones: List<String> = emptyList(),
    val echoCancelerAvailable: Boolean = false,
    val noiseSuppressorAvailable: Boolean = false,
    val gainControlAvailable: Boolean = false,
    /** Grabaciones activas de otras apps vistas antes de empezar (puede no verlas todas). */
    val otherActiveRecordings: Int = 0,
    /** El volumen multimedia está en cero (la prueba del parlante no serviría). */
    val mediaVolumeZero: Boolean = false,
)
