package com.dseu1.miccheck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitacoraTest {

    @Test
    fun lineaLegible() {
        val l = Bitacora.linea(
            0L, "MEDIDOR", "fin",
            linkedMapOf("resultado" to "detenido", "nivel" to -62.14, "nada" to null, "vacio" to "", "lista" to listOf("-60", "-61"), "texto" to "a | b\nc"),
        )
        val partes = l.split(" | ")
        assertEquals("MEDIDOR", partes[1])
        assertEquals("fin", partes[2])
        assertTrue(l, l.endsWith(" | resultado=detenido | nivel=-62,1 | lista=-60 -61 | texto=a / b ⏎ c"))
        assertFalse(l.contains("nada="))
        assertFalse(l.contains("vacio="))
    }

    @Test
    fun estadisticaDeSilencioTotal() {
        val e = Bitacora.Estadistica()
        repeat(30) { e.agregar(LevelMath.MIN_DB, LevelMath.MIN_DB, 1_600, 16_000, digital = true, silenciado = false, saturado = false) }
        val r = e.resumen()
        assertEquals("100 %", r["silencio_digital"])
        assertEquals("0 %", r["silenciado_por_android"])
        assertEquals(3.0, r["audio_s"] as Double, 1e-9)
        assertEquals(listOf("-100", "-100", "-100"), r["niveles_por_s"])
    }

    @Test
    fun estadisticaConVozYGrabacionLarga() {
        val e = Bitacora.Estadistica()
        // 10 min a 10 bloques/s: 600 segundos → se agrupa para no pasar de 120 valores.
        for (i in 0 until 6_000) e.agregar(if (i % 10 < 5) -30.0 else -60.0, -12.0, 4_410, 44_100, false, null, i == 0)
        val r = e.resumen()
        assertEquals("0 %", r["silencio_digital"])
        assertEquals("0 %", r["saturacion"]) // 1 de 6000 redondea a 0
        assertEquals(-12.0, r["pico_max_dbfs"] as Double, 1e-9)
        val clave = r.keys.first { it.startsWith("niveles_cada_") }
        assertEquals("niveles_cada_5s", clave)
        assertEquals(120, (r[clave] as List<*>).size)
    }

    @Test
    fun sinAudio() {
        assertEquals(mapOf("audio" to "no llegó audio"), Bitacora.Estadistica().resumen())
    }
}
