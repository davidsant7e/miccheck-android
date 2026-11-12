package com.dseu1.miccheck.audio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Formato del registro de El Juicio (una línea por evento) y estadísticas de lo que captó el micrófono.
 * Sin Android: se prueba con JUnit.
 *
 * Ejemplo de línea:
 *   2026-11-12 17:06:01.234 | MEDIDOR | fin | resultado=detenido | usado=Bluetooth · T-COM | nivel_prom=-62,1 dBFS
 * Pensado para que lo lea una persona o Claude: claves en español, sin JSON.
 */
object Bitacora {

    private val formatoHora = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US) }

    fun linea(momento: Long, accion: String, fase: String, datos: Map<String, Any?>): String {
        val sb = StringBuilder()
        sb.append(formatoHora.get()!!.format(Date(momento))).append(" | ").append(accion).append(" | ").append(fase)
        for ((k, v) in datos) {
            if (v == null) continue
            val texto = when (v) {
                is Double -> numero(v)
                is Float -> numero(v.toDouble())
                is Collection<*> -> v.joinToString(" ")
                else -> v.toString()
            }
            if (texto.isEmpty()) continue
            sb.append(" | ").append(k).append('=').append(limpiar(texto))
        }
        return sb.toString()
    }

    /** Una línea no puede tener saltos ni el separador « | ». */
    fun limpiar(s: String): String = s.replace("\r", " ").replace("\n", " ⏎ ").replace(" | ", " / ")

    fun numero(v: Double): String =
        if (v.isNaN() || v.isInfinite()) "?" else (Math.round(v * 10) / 10.0).toString().replace('.', ',')

    /** Acumula los bloques de audio de una acción y resume lo que llegó del micrófono. */
    class Estadistica {
        private var muestras = 0L
        private var frecuencia = 0
        private var bloques = 0
        private var silencioDigital = 0
        private var silenciadoPorAndroid = 0
        private var saturados = 0
        private var picoMax = LevelMath.MIN_DB
        private val rms = ArrayList<Double>()
        private val porSegundo = ArrayList<Double>()
        private val segundoActual = ArrayList<Double>()
        private var muestrasSegundo = 0

        fun agregar(rmsDb: Double, picoDb: Double, n: Int, frecuenciaHz: Int, digital: Boolean, silenciado: Boolean?, saturado: Boolean) {
            frecuencia = frecuenciaHz
            muestras += n
            bloques++
            if (digital) silencioDigital++
            if (silenciado == true) silenciadoPorAndroid++
            if (saturado) saturados++
            if (picoDb > picoMax) picoMax = picoDb
            rms += rmsDb
            segundoActual += rmsDb
            muestrasSegundo += n
            if (muestrasSegundo >= frecuenciaHz) {
                porSegundo += LevelMath.averageDb(segundoActual)
                segundoActual.clear()
                muestrasSegundo = 0
            }
        }

        val vacia: Boolean get() = bloques == 0

        fun resumen(): Map<String, Any?> {
            if (bloques == 0) return mapOf("audio" to "no llegó audio")
            val niveles = ArrayList(porSegundo).apply { if (segundoActual.isNotEmpty()) add(LevelMath.averageDb(segundoActual)) }
            val (paso, lista) = comprimir(niveles)
            return linkedMapOf(
                "audio_s" to muestras.toDouble() / frecuencia,
                "frecuencia_hz" to frecuencia,
                "nivel_prom_dbfs" to LevelMath.averageDb(rms),
                "nivel_min_dbfs" to rms.min(),
                "pico_max_dbfs" to picoMax,
                "silencio_digital" to "${pct(silencioDigital)} %",
                "silenciado_por_android" to "${pct(silenciadoPorAndroid)} %",
                "saturacion" to "${pct(saturados)} %",
                (if (paso == 1) "niveles_por_s" else "niveles_cada_${paso}s") to lista.map { it.roundToInt().toString() },
            )
        }

        private fun pct(n: Int) = (n * 100.0 / bloques).roundToInt()

        /** Más de 120 valores se agrupan (cada 2 s, 5 s, 10 s…) para que la línea no sea eterna. */
        private fun comprimir(v: List<Double>): Pair<Int, List<Double>> {
            if (v.size <= 120) return 1 to v
            val paso = listOf(2, 5, 10, 30, 60).firstOrNull { (v.size + it - 1) / it <= 120 } ?: 60
            return paso to v.chunked(paso) { LevelMath.averageDb(it) }
        }
    }
}
