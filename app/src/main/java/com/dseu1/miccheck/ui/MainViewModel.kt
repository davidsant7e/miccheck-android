package com.dseu1.miccheck.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dseu1.miccheck.audio.AudioEnvironment
import com.dseu1.miccheck.audio.AudioFrame
import com.dseu1.miccheck.audio.EnvironmentInfo
import com.dseu1.miccheck.audio.LevelMath
import com.dseu1.miccheck.audio.MicCapture
import com.dseu1.miccheck.audio.PcmPlayer
import com.dseu1.miccheck.diagnosis.CheckResult
import com.dseu1.miccheck.diagnosis.DiagnosisAnalyzer
import com.dseu1.miccheck.diagnosis.Measurements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Estado del medidor en vivo. */
data class MeterState(
    val running: Boolean = false,
    val rmsDb: Double = LevelMath.MIN_DB,
    val peakHoldDb: Double = LevelMath.MIN_DB,
    val clipping: Boolean = false,
    val digitalSilence: Boolean = false,
    val silencedByOtherApp: Boolean = false,
    val error: String? = null,
)

/** Pasos del diagnóstico, con la instrucción que ve el usuario. */
enum class DiagnosisStep(val title: String, val instruction: String) {
    QUIET("Ruido de fondo", "Quédate en silencio y no toques el celular."),
    VOICE("Tu voz", "Habla en voz normal, a unos 20 cm del celular: «uno, dos, tres, probando el micrófono»."),
    TONE("Parlante", "Vas a oír un pitido. Deja el celular quieto y sin tapar."),
}

sealed interface DiagnosisState {
    data object Idle : DiagnosisState

    data class Running(
        val step: DiagnosisStep,
        val stepNumber: Int,
        val totalSteps: Int,
        /** Segundos para empezar (3, 2, 1); 0 = midiendo. */
        val countdown: Int,
        val progress: Float,
        val levelDb: Double,
    ) : DiagnosisState

    data class Done(val results: List<CheckResult>) : DiagnosisState
    data class Failed(val message: String) : DiagnosisState
}

enum class RecordPhase { IDLE, RECORDING, READY, PLAYING }

data class RecordState(
    val phase: RecordPhase = RecordPhase.IDLE,
    val progress: Float = 0f,
    val levelDb: Double = LevelMath.MIN_DB,
    val error: String? = null,
)

/**
 * ViewModel de la app: guarda el estado de las pantallas y coordina el audio.
 * Sobrevive a la rotación de pantalla. Solo una operación de audio a la vez ([audioJob]).
 *
 * El permiso RECORD_AUDIO lo garantiza la interfaz antes de mostrar estas funciones.
 */
@SuppressLint("MissingPermission")
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val capture = MicCapture()
    private var audioJob: Job? = null
    private var lastRecording: ShortArray? = null

    private val _meter = MutableStateFlow(MeterState())
    val meter: StateFlow<MeterState> = _meter.asStateFlow()

    private val _diagnosis = MutableStateFlow<DiagnosisState>(DiagnosisState.Idle)
    val diagnosis: StateFlow<DiagnosisState> = _diagnosis.asStateFlow()

    private val _record = MutableStateFlow(RecordState())
    val record: StateFlow<RecordState> = _record.asStateFlow()

    private val _environment = MutableStateFlow<EnvironmentInfo?>(null)
    val environment: StateFlow<EnvironmentInfo?> = _environment.asStateFlow()

    // ---------- Entorno ----------

    fun refreshEnvironment() {
        _environment.value = runCatching { AudioEnvironment.read(getApplication()) }.getOrNull()
    }

    // ---------- Medidor en vivo ----------

    fun toggleMeter() {
        if (_meter.value.running) stopAll() else startMeter()
    }

    private fun startMeter() {
        stopAll()
        _meter.value = MeterState(running = true)
        audioJob = viewModelScope.launch {
            var hold = LevelMath.MIN_DB
            var holdAge = 0
            try {
                // Bloques de 50 ms para que la barra se mueva con fluidez.
                capture.frames(frameSize = MicCapture.FRAME_SIZE / 2).collect { frame ->
                    // "Pico retenido": la marca se queda ~1 s en el valor más alto y luego baja.
                    if (frame.peakDb >= hold || holdAge > 20) {
                        hold = frame.peakDb
                        holdAge = 0
                    } else {
                        holdAge++
                    }
                    _meter.update {
                        it.copy(
                            rmsDb = frame.rmsDb,
                            peakHoldDb = hold,
                            clipping = frame.clippedFraction > 0.0,
                            digitalSilence = frame.digitalSilence,
                            silencedByOtherApp = frame.clientSilenced == true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Al detenerlo a propósito, stopAll() ya pone running = false.
                _meter.update { it.copy(running = false, error = e.message ?: "Error del micrófono.") }
            }
        }
    }

    // ---------- Grabar y escuchar ----------

    fun startRecording() {
        stopAll()
        _record.value = RecordState(phase = RecordPhase.RECORDING)
        audioJob = viewModelScope.launch {
            val total = RECORD_SECONDS * 10 // bloques de 100 ms
            val chunks = ArrayList<ShortArray>(total)
            try {
                capture.frames().take(total).collectIndexed { index, frame ->
                    chunks += frame.samples
                    _record.update {
                        it.copy(progress = (index + 1f) / total, levelDb = frame.rmsDb)
                    }
                }
                lastRecording = concat(chunks)
                _record.value = RecordState(phase = RecordPhase.READY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _record.value = RecordState(
                    phase = if (lastRecording != null) RecordPhase.READY else RecordPhase.IDLE,
                    error = e.message ?: "No se pudo grabar.",
                )
            }
        }
    }

    fun playRecording() {
        val samples = lastRecording ?: return
        stopAll()
        _record.value = RecordState(phase = RecordPhase.PLAYING)
        audioJob = viewModelScope.launch {
            try {
                PcmPlayer.play(samples, capture.sampleRate)
                _record.value = RecordState(phase = RecordPhase.READY)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _record.value = RecordState(
                    phase = RecordPhase.READY,
                    error = e.message ?: "No se pudo reproducir.",
                )
            }
        }
    }

    // ---------- Diagnóstico completo ----------

    fun startDiagnosis() {
        stopAll()
        audioJob = viewModelScope.launch {
            try {
                val env = AudioEnvironment.read(getApplication())
                _environment.value = env
                val canTestTone = env.externalOutput == null && !env.mediaVolumeZero
                val totalSteps = if (canTestTone) 3 else 2

                val quiet = measureStep(DiagnosisStep.QUIET, 1, totalSteps, frames = 25, skip = 5)
                val voice = measureStep(DiagnosisStep.VOICE, 2, totalSteps, frames = 40, skip = 2)
                val toneDb = if (canTestTone) measureTone(3, totalSteps) else null

                val measurements = Measurements(
                    noiseFloorDb = LevelMath.averageDb(quiet.map { it.rmsDb }),
                    quietDigitalSilence = quiet.all { it.digitalSilence },
                    voiceDigitalSilence = voice.all { it.digitalSilence },
                    clientSilenced = (quiet + voice).any { it.clientSilenced == true },
                    voiceLevelDb = voice.maxOf { it.rmsDb },
                    voicePeakDb = voice.maxOf { it.peakDb },
                    voiceClippedFraction = voice.maxOf { it.clippedFraction },
                    toneBaselineDb = LevelMath.averageDb(quiet.map { it.toneDb(TONE_HZ) }),
                    toneDb = toneDb,
                )
                _diagnosis.value = DiagnosisState.Done(DiagnosisAnalyzer.analyze(env, measurements))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _diagnosis.value = DiagnosisState.Failed(e.message ?: "Error inesperado durante el diagnóstico.")
            }
        }
    }

    /** Vuelve a la pantalla principal (cancela el diagnóstico si estaba corriendo). */
    fun closeDiagnosis() {
        stopAll()
        _diagnosis.value = DiagnosisState.Idle
    }

    /** Cuenta regresiva y luego graba [frames] bloques de 100 ms, descartando los primeros [skip]. */
    private suspend fun measureStep(
        step: DiagnosisStep,
        number: Int,
        total: Int,
        frames: Int,
        skip: Int,
    ): List<AudioFrame> {
        countdown(step, number, total)
        val kept = ArrayList<AudioFrame>(frames)
        val all = frames + skip
        capture.frames().take(all).collectIndexed { index, frame ->
            if (index >= skip) kept += frame
            _diagnosis.value = DiagnosisState.Running(step, number, total, 0, (index + 1f) / all, frame.rmsDb)
        }
        return kept
    }

    /** Suena un tono de 1 kHz por el parlante y mide cuánto lo capta el micrófono. */
    private suspend fun measureTone(number: Int, total: Int): Double = coroutineScope {
        val step = DiagnosisStep.TONE
        countdown(step, number, total)
        val tone = PcmPlayer.sineTone(TONE_HZ, durationMs = 3_000, amplitude = 0.5)
        val player = launch { PcmPlayer.play(tone, capture.sampleRate) }
        delay(300)

        val levels = ArrayList<Double>()
        val all = 18
        capture.frames().take(all).collectIndexed { index, frame ->
            if (index >= 3) levels += frame.toneDb(TONE_HZ) // los primeros bloques pueden traer el arranque
            _diagnosis.value = DiagnosisState.Running(step, number, total, 0, (index + 1f) / all, frame.rmsDb)
        }
        player.cancel()
        LevelMath.averageDb(levels)
    }

    private suspend fun countdown(step: DiagnosisStep, number: Int, total: Int) {
        for (seconds in 3 downTo 1) {
            _diagnosis.value = DiagnosisState.Running(step, number, total, seconds, 0f, LevelMath.MIN_DB)
            delay(1_000)
        }
    }

    // ---------- Común ----------

    /** Detiene cualquier grabación o reproducción en curso. Se llama también al salir de la app. */
    fun stopAll() {
        audioJob?.cancel()
        audioJob = null
        _meter.update { it.copy(running = false) }
        if (_diagnosis.value is DiagnosisState.Running) _diagnosis.value = DiagnosisState.Idle
        val rec = _record.value
        if (rec.phase == RecordPhase.RECORDING || rec.phase == RecordPhase.PLAYING) {
            _record.value = RecordState(phase = if (lastRecording != null) RecordPhase.READY else RecordPhase.IDLE)
        }
    }

    override fun onCleared() {
        stopAll()
    }

    private fun concat(chunks: List<ShortArray>): ShortArray {
        val result = ShortArray(chunks.sumOf { it.size })
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    companion object {
        const val TONE_HZ = 1_000.0
        const val RECORD_SECONDS = 5
    }
}
