package com.dseu1.miccheck.diagnosis

import com.dseu1.miccheck.audio.EnvironmentInfo

/** Resultado de una prueba: bien, aviso, falla o solo información. */
enum class Status { OK, WARNING, FAIL, INFO }

data class CheckResult(
    val title: String,
    val status: Status,
    val detail: String,
    val suggestions: List<String> = emptyList(),
)

/**
 * Lo que midió el diagnóstico con el micrófono. Todos los niveles en dBFS.
 *
 * @param noiseFloorDb nivel promedio con el usuario en silencio (ruido de fondo).
 * @param quietDigitalSilence todas las muestras en cero durante el silencio.
 * @param voiceDigitalSilence todas las muestras en cero mientras el usuario hablaba.
 * @param clientSilenced Android avisó que silenció nuestra grabación (otra app tiene el micrófono).
 * @param voiceLevelDb nivel RMS más alto mientras el usuario hablaba.
 * @param voicePeakDb pico más alto mientras el usuario hablaba.
 * @param voiceClippedFraction fracción de muestras saturadas al hablar (0..1).
 * @param toneBaselineDb nivel en la frecuencia del tono, sin tono (silencio).
 * @param toneDb nivel en la frecuencia del tono mientras sonaba; null si no se hizo la prueba.
 */
data class Measurements(
    val noiseFloorDb: Double,
    val quietDigitalSilence: Boolean,
    val voiceDigitalSilence: Boolean,
    val clientSilenced: Boolean,
    val voiceLevelDb: Double,
    val voicePeakDb: Double,
    val voiceClippedFraction: Double,
    val toneBaselineDb: Double,
    val toneDb: Double?,
)

/** Umbrales del diagnóstico (dBFS). Ajustables tras probar en varios celulares. */
object Thresholds {
    /** Ruido de fondo por debajo de esto (sin ser cero) es sospechosamente bajo. */
    const val FLOOR_TOO_LOW = -85.0

    /** Ruido de fondo por encima de esto es un ambiente ruidoso. */
    const val FLOOR_NOISY = -45.0

    /** La voz debe superar el ruido de fondo por lo menos en esto. */
    const val VOICE_MIN_MARGIN = 10.0

    /** Con este margen la voz se capta bien. */
    const val VOICE_GOOD_MARGIN = 20.0

    /** Nivel mínimo de voz para considerarla captada. */
    const val VOICE_MIN_LEVEL = -55.0

    /** Más de esta fracción de muestras saturadas es saturación. */
    const val CLIP_FRACTION = 0.005

    /** El tono del parlante debe subir esto sobre el silencio para contar como captado. */
    const val TONE_MIN_RISE = 8.0
    const val TONE_GOOD_RISE = 20.0
}

/** Convierte las mediciones en resultados con sugerencias. Sin Android: se prueba con JUnit. */
object DiagnosisAnalyzer {

    private const val SUGGEST_ACCESS =
        "Revisa el acceso al micrófono: baja la barra de ajustes rápidos y activa \"Acceso al micrófono\" (Android 12+)."
    private const val SUGGEST_OTHER_APPS =
        "Cierra las apps que puedan estar usando el micrófono (grabadoras, llamadas, asistentes de voz)."
    private const val SUGGEST_RESTART = "Reinicia el celular y repite el diagnóstico."
    private const val SUGGEST_CLEAN =
        "Limpia con cuidado el orificio del micrófono (borde inferior) con un cepillo suave y seco; no uses agujas."
    private const val SUGGEST_CASE = "Quita el estuche o protector: a veces tapa el orificio del micrófono."
    private const val SUGGEST_SERVICE =
        "Si el problema sigue después de limpiar y reiniciar, llévalo a servicio técnico."
    private const val SUGGEST_QUIET = "Repite la prueba en un lugar más silencioso."
    private const val SUGGEST_DISCONNECT =
        "Desconecta los audífonos o el Bluetooth para probar el micrófono del celular."

    fun analyze(env: EnvironmentInfo, m: Measurements): List<CheckResult> {
        val results = mutableListOf<CheckResult>()

        if (!env.hasMicrophoneFeature) {
            results += CheckResult(
                "Micrófono en el sistema",
                Status.FAIL,
                "Android no reporta ningún micrófono en este equipo.",
                listOf(SUGGEST_RESTART, SUGGEST_SERVICE),
            )
        }

        // 1. ¿Qué micrófono se está usando?
        results += if (env.externalInput != null) {
            CheckResult(
                "Micrófono usado",
                Status.WARNING,
                "Se detectó ${env.externalInput}. Android puede estar usando ese micrófono en vez del del celular.",
                listOf(SUGGEST_DISCONNECT),
            )
        } else {
            CheckResult("Micrófono usado", Status.OK, "Micrófono del celular (no hay audífonos ni Bluetooth conectados).")
        }

        // 2. ¿Android nos entrega silencio?
        val totalSilence = m.quietDigitalSilence && m.voiceDigitalSilence
        when {
            m.clientSilenced -> results += CheckResult(
                "Acceso al micrófono",
                Status.FAIL,
                "Android silenció la grabación: otra app tiene prioridad sobre el micrófono.",
                listOf(SUGGEST_OTHER_APPS, SUGGEST_RESTART),
            )
            totalSilence -> results += CheckResult(
                "Acceso al micrófono",
                Status.FAIL,
                "El micrófono entrega silencio absoluto (ceros perfectos). Un micrófono real siempre capta algo de ruido.",
                listOf(SUGGEST_ACCESS, SUGGEST_OTHER_APPS, SUGGEST_RESTART, SUGGEST_SERVICE),
            )
            env.otherActiveRecordings > 0 -> results += CheckResult(
                "Acceso al micrófono",
                Status.WARNING,
                "Había ${env.otherActiveRecordings} grabación(es) activa(s) de otras apps al empezar, pero el audio sí llegó.",
                listOf(SUGGEST_OTHER_APPS),
            )
            else -> results += CheckResult("Acceso al micrófono", Status.OK, "La app recibe audio del micrófono.")
        }

        // Si no llega audio, las demás pruebas de nivel no tienen sentido.
        if (totalSilence || m.clientSilenced) {
            results += environmentInfo(env)
            return results
        }

        // 3. Ruido de fondo.
        results += when {
            m.noiseFloorDb < Thresholds.FLOOR_TOO_LOW -> CheckResult(
                "Ruido de fondo",
                Status.WARNING,
                "Casi no se capta nada en silencio (${db(m.noiseFloorDb)}). Puede ser un lugar muy silencioso o un micrófono tapado.",
                listOf(SUGGEST_CASE, SUGGEST_CLEAN),
            )
            m.noiseFloorDb > Thresholds.FLOOR_NOISY -> CheckResult(
                "Ruido de fondo",
                Status.WARNING,
                "Hay mucho ruido de fondo (${db(m.noiseFloorDb)}). Si el lugar estaba en silencio, puede ser un zumbido del micrófono.",
                listOf(SUGGEST_QUIET, SUGGEST_SERVICE),
            )
            else -> CheckResult("Ruido de fondo", Status.OK, "Normal (${db(m.noiseFloorDb)}).")
        }

        // 4. Voz.
        val margin = m.voiceLevelDb - m.noiseFloorDb
        results += when {
            m.voiceLevelDb < Thresholds.VOICE_MIN_LEVEL || margin < Thresholds.VOICE_MIN_MARGIN -> CheckResult(
                "Captación de voz",
                Status.FAIL,
                "La voz casi no se distingue del ruido (voz ${db(m.voiceLevelDb)}, ${margin.oneDecimal()} dB sobre el fondo).",
                listOf(
                    "Repite la prueba hablando a unos 20 cm del borde inferior del celular.",
                    SUGGEST_CASE,
                    SUGGEST_CLEAN,
                    SUGGEST_SERVICE,
                ),
            )
            margin < Thresholds.VOICE_GOOD_MARGIN -> CheckResult(
                "Captación de voz",
                Status.WARNING,
                "La voz se capta débil (${margin.oneDecimal()} dB sobre el fondo; lo normal es más de ${Thresholds.VOICE_GOOD_MARGIN.toInt()} dB).",
                listOf(SUGGEST_CASE, SUGGEST_CLEAN, SUGGEST_QUIET),
            )
            else -> CheckResult(
                "Captación de voz",
                Status.OK,
                "Buena: ${margin.oneDecimal()} dB sobre el ruido de fondo.",
            )
        }

        // 5. Saturación.
        results += if (m.voiceClippedFraction > Thresholds.CLIP_FRACTION) {
            CheckResult(
                "Saturación",
                Status.WARNING,
                "El audio se saturó (${(m.voiceClippedFraction * 100).oneDecimal()} % de las muestras al máximo).",
                listOf(
                    "Repite la prueba hablando un poco más lejos o más bajo.",
                    "Si se satura con voz normal, el micrófono puede estar dañado.",
                    SUGGEST_SERVICE,
                ),
            )
        } else {
            CheckResult("Saturación", Status.OK, "Sin saturación (pico ${db(m.voicePeakDb)}).")
        }

        // 6. Parlante → micrófono.
        results += toneResult(env, m)

        results += environmentInfo(env)
        return results
    }

    private fun toneResult(env: EnvironmentInfo, m: Measurements): CheckResult {
        val title = "Tono del parlante"
        if (env.externalOutput != null) {
            return CheckResult(
                title,
                Status.INFO,
                "El sonido sale por ${env.externalOutput}, así que el micrófono del celular no puede oír el tono.",
                listOf(SUGGEST_DISCONNECT),
            )
        }
        if (env.mediaVolumeZero) {
            return CheckResult(
                title,
                Status.INFO,
                "El volumen multimedia está en cero; la prueba del tono no se pudo hacer.",
                listOf("Sube el volumen multimedia y repite el diagnóstico."),
            )
        }
        val tone = m.toneDb ?: return CheckResult(title, Status.INFO, "No se hizo la prueba del tono.")
        val rise = tone - m.toneBaselineDb
        return when {
            rise >= Thresholds.TONE_GOOD_RISE -> CheckResult(
                title,
                Status.OK,
                "El micrófono captó el tono del parlante (+${rise.oneDecimal()} dB).",
            )
            rise >= Thresholds.TONE_MIN_RISE -> CheckResult(
                title,
                Status.WARNING,
                "El tono se captó débil (+${rise.oneDecimal()} dB). Puede ser volumen bajo, el estuche o suciedad.",
                listOf("Sube el volumen multimedia.", SUGGEST_CASE, SUGGEST_CLEAN),
            )
            else -> CheckResult(
                title,
                Status.FAIL,
                "El micrófono no captó el tono (+${rise.oneDecimal()} dB). Puede fallar el micrófono o el parlante.",
                listOf(
                    "Comprueba si escuchaste el pitido: si no sonó, el problema es el parlante o el volumen.",
                    SUGGEST_CLEAN,
                    SUGGEST_SERVICE,
                ),
            )
        }
    }

    private fun environmentInfo(env: EnvironmentInfo): CheckResult {
        val lines = buildList {
            if (env.microphones.isNotEmpty()) {
                add("Micrófonos físicos: ${env.microphones.size} (${env.microphones.joinToString("; ")}).")
            }
            if (env.inputs.isNotEmpty()) add("Entradas: ${env.inputs.joinToString(", ")}.")
            add(
                "Efectos: cancelación de eco ${yesNo(env.echoCancelerAvailable)}, " +
                    "reducción de ruido ${yesNo(env.noiseSuppressorAvailable)}, " +
                    "ganancia automática ${yesNo(env.gainControlAvailable)}."
            )
        }
        return CheckResult("Información del equipo", Status.INFO, lines.joinToString("\n"))
    }

    private fun yesNo(value: Boolean) = if (value) "sí" else "no"

    private fun db(value: Double) = "${value.oneDecimal()} dBFS"

    private fun Double.oneDecimal(): String {
        val rounded = Math.round(this * 10) / 10.0
        return rounded.toString().replace('.', ',')
    }
}
