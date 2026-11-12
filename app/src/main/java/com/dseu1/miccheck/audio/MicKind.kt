package com.dseu1.miccheck.audio

/** Tipo de micrófono que el usuario puede elegir. Sin Android: se usa en el diagnóstico y en las pruebas. */
enum class MicKind(val label: String) {
    AUTO("Automático"),
    BUILTIN("Micrófono del celular"),
    WIRED("Audífonos con cable"),
    USB("Micrófono USB"),
    BLUETOOTH("Bluetooth"),
    ;

    /** Micrófono fuera del celular: las sugerencias de limpiar el orificio no aplican. */
    val external: Boolean get() = this == WIRED || this == USB || this == BLUETOOTH
}

/**
 * Un micrófono disponible para elegir.
 * @param deviceId id de AudioDeviceInfo (cambia si se desconecta y se vuelve a conectar); 0 en AUTO.
 * @param name nombre del equipo ("Buds Pro") o posición del micrófono del celular ("inferior").
 */
data class MicOption(val kind: MicKind, val name: String = "", val deviceId: Int = 0) {
    val label: String get() = if (name.isBlank()) kind.label else "${kind.label} · $name"

    /** Clave para recordar la elección aunque cambie el id del dispositivo. */
    val key: String get() = "${kind.name}|$name"

    companion object {
        val AUTO = MicOption(MicKind.AUTO)

        fun fromKey(key: String?): MicOption {
            val parts = key?.split('|', limit = 2) ?: return AUTO
            val kind = MicKind.entries.firstOrNull { it.name == parts[0] } ?: return AUTO
            return MicOption(kind, parts.getOrElse(1) { "" })
        }
    }
}
