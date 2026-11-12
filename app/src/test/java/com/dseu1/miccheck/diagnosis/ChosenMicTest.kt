package com.dseu1.miccheck.diagnosis

import com.dseu1.miccheck.audio.EnvironmentInfo
import com.dseu1.miccheck.audio.MicKind
import com.dseu1.miccheck.audio.MicOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChosenMicTest {

    private val healthy = Measurements(
        noiseFloorDb = -65.0, quietDigitalSilence = false, voiceDigitalSilence = false, clientSilenced = false,
        voiceLevelDb = -25.0, voicePeakDb = -10.0, voiceClippedFraction = 0.0, toneBaselineDb = -80.0, toneDb = null,
    )

    private val bluetooth = EnvironmentInfo(
        inputs = listOf("Micrófono del celular", "Micrófono Bluetooth"),
        externalInput = "Bluetooth: Buds", externalOutput = "Bluetooth: Buds",
        selectedMic = MicKind.BLUETOOTH, selectedMicLabel = "Bluetooth · Buds",
    )

    private fun List<CheckResult>.of(title: String) = first { it.title == title }

    @Test
    fun bluetoothElegidoYUsadoEsOk() {
        val r = DiagnosisAnalyzer.analyze(bluetooth.copy(usedMic = MicKind.BLUETOOTH, usedMicLabel = "Bluetooth · Buds"), healthy)
        assertEquals(Status.OK, r.of("Micrófono usado").status)
        assertTrue(r.of("Micrófono usado").detail.contains("Buds"))
        // El pitido no aplica a un micrófono externo, y no se le pide desconectar el Bluetooth.
        assertEquals(Status.INFO, r.of("Tono del parlante").status)
        assertTrue(r.of("Tono del parlante").suggestions.isEmpty())
        assertTrue(r.of("Información del equipo").detail.contains("calidad de llamada"))
    }

    @Test
    fun androidUsoOtroMicrofono() {
        val r = DiagnosisAnalyzer.analyze(bluetooth.copy(usedMic = MicKind.BUILTIN, usedMicLabel = "Micrófono del celular"), healthy)
        val usado = r.of("Micrófono usado")
        assertEquals(Status.WARNING, usado.status)
        assertTrue(usado.detail.contains("Elegiste Bluetooth · Buds, pero Android grabó con Micrófono del celular"))
        assertTrue(usado.suggestions.any { it.contains("«Llamadas»") })
    }

    @Test
    fun sugerenciasDeMicrofonoExterno() {
        val debil = healthy.copy(voiceLevelDb = -60.0)
        val r = DiagnosisAnalyzer.analyze(bluetooth.copy(usedMic = MicKind.BLUETOOTH), debil)
        val voz = r.of("Captación de voz")
        assertEquals(Status.FAIL, voz.status)
        assertFalse(voz.suggestions.any { it.contains("orificio") || it.contains("estuche") || it.contains("borde inferior") })
        assertTrue(voz.suggestions.any { it.contains("cerca de la boca") })
    }

    @Test
    fun automaticoSigueComoAntes() {
        val r = DiagnosisAnalyzer.analyze(EnvironmentInfo(externalInput = "Bluetooth: Buds"), healthy.copy(voiceLevelDb = -60.0))
        assertEquals(Status.WARNING, r.of("Micrófono usado").status)
        assertTrue(r.of("Captación de voz").suggestions.any { it.contains("orificio") })
    }

    @Test
    fun opcionSeRecuerdaPorClave() {
        val o = MicOption(MicKind.BLUETOOTH, "Buds Pro", deviceId = 42)
        assertEquals("BLUETOOTH|Buds Pro", o.key)
        assertEquals(MicOption(MicKind.BLUETOOTH, "Buds Pro"), MicOption.fromKey(o.key))
        assertEquals(MicOption.AUTO, MicOption.fromKey(null))
        assertEquals(MicOption.AUTO, MicOption.fromKey("RARO|x"))
        assertEquals("Micrófono del celular · inferior", MicOption(MicKind.BUILTIN, "inferior").label)
        assertTrue(MicKind.WIRED.external && !MicKind.BUILTIN.external && !MicKind.AUTO.external)
    }
}
