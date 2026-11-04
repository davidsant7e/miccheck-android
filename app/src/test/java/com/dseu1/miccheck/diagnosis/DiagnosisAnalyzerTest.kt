package com.dseu1.miccheck.diagnosis

import com.dseu1.miccheck.audio.EnvironmentInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosisAnalyzerTest {

    private val env = EnvironmentInfo(inputs = listOf("Micrófono del celular"))

    /** Mediciones de un micrófono sano en un cuarto tranquilo. */
    private val healthy = Measurements(
        noiseFloorDb = -65.0,
        quietDigitalSilence = false,
        voiceDigitalSilence = false,
        clientSilenced = false,
        voiceLevelDb = -25.0,
        voicePeakDb = -10.0,
        voiceClippedFraction = 0.0,
        toneBaselineDb = -80.0,
        toneDb = -40.0,
    )

    private fun List<CheckResult>.status(title: String) = first { it.title == title }.status

    @Test
    fun healthyMicrophoneHasNoFailsOrWarnings() {
        val results = DiagnosisAnalyzer.analyze(env, healthy)
        assertTrue(results.none { it.status == Status.FAIL || it.status == Status.WARNING })
    }

    @Test
    fun totalSilenceIsAccessFailureAndStopsLevelChecks() {
        val m = healthy.copy(quietDigitalSilence = true, voiceDigitalSilence = true)
        val results = DiagnosisAnalyzer.analyze(env, m)
        assertEquals(Status.FAIL, results.status("Acceso al micrófono"))
        assertTrue(results.none { it.title == "Captación de voz" })
    }

    @Test
    fun clientSilencedIsAccessFailure() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(clientSilenced = true))
        assertEquals(Status.FAIL, results.status("Acceso al micrófono"))
    }

    @Test
    fun weakVoiceFails() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(voiceLevelDb = -60.0))
        assertEquals(Status.FAIL, results.status("Captación de voz"))
    }

    @Test
    fun voiceJustAboveNoiseIsWarning() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(noiseFloorDb = -50.0, voiceLevelDb = -35.0))
        assertEquals(Status.WARNING, results.status("Captación de voz"))
    }

    @Test
    fun noisyRoomIsWarning() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(noiseFloorDb = -40.0, voiceLevelDb = -10.0))
        assertEquals(Status.WARNING, results.status("Ruido de fondo"))
    }

    @Test
    fun clippingIsWarning() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(voiceClippedFraction = 0.05))
        assertEquals(Status.WARNING, results.status("Saturación"))
    }

    @Test
    fun toneNotHeardFails() {
        val results = DiagnosisAnalyzer.analyze(env, healthy.copy(toneDb = -78.0))
        assertEquals(Status.FAIL, results.status("Tono del parlante"))
    }

    @Test
    fun bluetoothMakesToneInfoAndMicWarning() {
        val bt = env.copy(externalInput = "Bluetooth: Auriculares", externalOutput = "Bluetooth: Auriculares")
        val results = DiagnosisAnalyzer.analyze(bt, healthy.copy(toneDb = null))
        assertEquals(Status.WARNING, results.status("Micrófono usado"))
        assertEquals(Status.INFO, results.status("Tono del parlante"))
    }
}
