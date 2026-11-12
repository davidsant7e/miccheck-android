package com.dseu1.miccheck.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dseu1.miccheck.audio.AudioEnvironment
import com.dseu1.miccheck.audio.AudioFrame
import com.dseu1.miccheck.audio.EnvironmentInfo
import com.dseu1.miccheck.audio.LevelMath
import com.dseu1.miccheck.audio.MicCapture
import com.dseu1.miccheck.audio.MicInputs
import com.dseu1.miccheck.audio.MicKind
import com.dseu1.miccheck.audio.MicOption
import com.dseu1.miccheck.audio.MicRoute
import com.dseu1.miccheck.audio.PcmPlayer
import com.dseu1.miccheck.audio.WavFile
import com.dseu1.miccheck.datos.Grabacion
import com.dseu1.miccheck.datos.Grabaciones
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

/** Grabadora: graba con el micrófono elegido (también Bluetooth) y guarda un WAV. */
data class GrabadoraState(
    val grabando: Boolean = false,
    val guardando: Boolean = false,
    val segundos: Int = 0,
    val nivelDb: Double = LevelMath.MIN_DB,
    val error: String? = null,
    val guardada: String? = null,
    val grabaciones: List<Grabacion> = emptyList(),
    val reproduciendo: Uri? = null,
)

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

    // ---------- Elegir micrófono ----------

    private val prefs = application.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    private val _mics = MutableStateFlow(listOf(MicOption.AUTO))
    /** Micrófonos conectados (el primero siempre es «Automático»). */
    val mics: StateFlow<List<MicOption>> = _mics.asStateFlow()

    private val _selectedMic = MutableStateFlow(MicOption.fromKey(prefs.getString("mic", null)))
    val selectedMic: StateFlow<MicOption> = _selectedMic.asStateFlow()

    private val _usedMic = MutableStateFlow<MicOption?>(null)
    /** El micrófono que Android usó de verdad en la última grabación. */
    val usedMic: StateFlow<MicOption?> = _usedMic.asStateFlow()

    /** Dispositivo y fuente de la operación de audio en curso (las prepara [withMic]). */
    private var routeDevice: AudioDeviceInfo? = null
    private var routeSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    fun selectMic(option: MicOption) {
        stopAll()
        _selectedMic.value = option
        _usedMic.value = null
        prefs.edit().putString("mic", option.key).apply()
    }

    fun refreshMics() {
        _mics.value = runCatching { MicInputs.list(getApplication()) }.getOrDefault(listOf(MicOption.AUTO))
    }

    /** Abre el camino al micrófono elegido (Bluetooth: modo llamada), corre [block] y lo cierra siempre. */
    private suspend fun <T> withMic(block: suspend () -> T): T {
        val route = MicRoute(getApplication())
        val option = _selectedMic.value
        try {
            routeDevice = route.open(option)
            routeSource = route.source(option)
            return block()
        } finally {
            route.close()
            routeDevice = null
            routeSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private fun micFrames(frameSize: Int = MicCapture.FRAME_SIZE) =
        capture.frames(frameSize, routeDevice, routeSource) { device ->
            _usedMic.value = device?.let { MicInputs.option(it) }
        }

    // ---------- Entorno ----------

    fun refreshEnvironment() {
        _environment.value = runCatching { AudioEnvironment.read(getApplication()) }.getOrNull()
        refreshMics()
        refrescarGrabaciones()
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
                withMic { micFrames(frameSize = MicCapture.FRAME_SIZE / 2).collect { frame ->
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
                } }
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
                withMic {
                    micFrames().take(total).collectIndexed { index, frame ->
                        chunks += frame.samples
                        _record.update {
                            it.copy(progress = (index + 1f) / total, levelDb = frame.rmsDb)
                        }
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

    // ---------- Grabadora (guarda el audio) ----------

    private val _grabadora = MutableStateFlow(GrabadoraState())
    val grabadora: StateFlow<GrabadoraState> = _grabadora.asStateFlow()
    private var reproductor: MediaPlayer? = null

    fun refrescarGrabaciones() {
        viewModelScope.launch {
            val lista = withContext(Dispatchers.IO) { runCatching { Grabaciones.listar(getApplication()) }.getOrDefault(emptyList()) }
            _grabadora.update { it.copy(grabaciones = lista) }
        }
    }

    fun grabadoraIniciarODetener() {
        if (_grabadora.value.grabando) stopAll() else iniciarGrabadora()
    }

    private fun iniciarGrabadora() {
        stopAll()
        val opcion = _selectedMic.value
        // El Bluetooth de llamada llega a 16 kHz como mucho: grabar a 16 kHz ocupa un tercio.
        val frecuencia = if (opcion.kind == MicKind.BLUETOOTH) 16_000 else MicCapture.SAMPLE_RATE
        _grabadora.update { it.copy(grabando = true, segundos = 0, error = null, guardada = null) }
        audioJob = viewModelScope.launch {
            val temporal = File(getApplication<Application>().cacheDir, "grabando.wav")
            var wav: WavFile? = null
            var error: String? = null
            try {
                val w = withContext(Dispatchers.IO) { WavFile(temporal, frecuencia) }
                wav = w
                val captura = MicCapture(frecuencia)
                withMic {
                    var bloques = 0
                    captura.frames(frecuencia / 10, routeDevice, routeSource) { d -> _usedMic.value = d?.let { MicInputs.option(it) } }
                        .collect { f ->
                            w.write(f.samples)
                            bloques++
                            _grabadora.update { it.copy(segundos = bloques / 10, nivelDb = f.rmsDb) }
                            if (bloques >= MAX_GRABACION_SEGUNDOS * 10) throw MaximoAlcanzado()
                        }
                }
            } catch (e: MaximoAlcanzado) {
                error = "Se llegó al máximo de ${MAX_GRABACION_SEGUNDOS / 60} minutos; quedó guardado."
            } catch (e: CancellationException) {
                // Se detuvo con el botón (o al salir de la app): se guarda lo grabado.
            } catch (e: Exception) {
                error = e.message ?: "No se pudo grabar."
            } finally {
                withContext(NonCancellable) {
                    val w = wav
                    var nombre: String? = null
                    if (w != null) {
                        _grabadora.update { it.copy(grabando = false, guardando = true) }
                        nombre = withContext(Dispatchers.IO) {
                            runCatching {
                                w.close()
                                if (w.dataBytes < frecuencia / 2) { temporal.delete(); null } // menos de 0,25 s: nada que guardar
                                else {
                                    val etiqueta = _usedMic.value?.label ?: opcion.label
                                    val n = Grabaciones.nombreNuevo(etiqueta)
                                    Grabaciones.guardar(getApplication(), temporal, n, w.durationMs)
                                    n
                                }
                            }.onFailure { error = error ?: "No se pudo guardar: ${it.message}" }.getOrNull()
                        }
                    }
                    val lista = withContext(Dispatchers.IO) { runCatching { Grabaciones.listar(getApplication()) }.getOrDefault(emptyList()) }
                    _grabadora.update { it.copy(grabando = false, guardando = false, error = error, guardada = nombre, grabaciones = lista) }
                }
            }
        }
    }

    fun reproducir(g: Grabacion) {
        detenerReproduccion()
        stopAll()
        runCatching {
            reproductor = MediaPlayer().apply {
                setDataSource(getApplication(), g.uri)
                setOnCompletionListener { detenerReproduccion() }
                prepare()
                start()
            }
            _grabadora.update { it.copy(reproduciendo = g.uri) }
        }.onFailure { e -> _grabadora.update { it.copy(error = "No se pudo reproducir: ${e.message}") } }
    }

    fun detenerReproduccion() {
        reproductor?.runCatching { stop(); release() }
        reproductor = null
        _grabadora.update { it.copy(reproduciendo = null) }
    }

    fun borrar(g: Grabacion) {
        if (_grabadora.value.reproduciendo == g.uri) detenerReproduccion()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { Grabaciones.borrar(getApplication(), g) } }
            refrescarGrabaciones()
        }
    }

    private class MaximoAlcanzado : Exception()

    // ---------- Diagnóstico completo ----------

    fun startDiagnosis() {
        stopAll()
        audioJob = viewModelScope.launch {
            try {
                val chosen = _selectedMic.value
                val env0 = AudioEnvironment.read(getApplication())
                _environment.value = env0
                // El pitido solo prueba el micrófono del celular (con uno externo no tiene sentido).
                val canTestTone = env0.externalOutput == null && !env0.mediaVolumeZero && !chosen.kind.external
                val totalSteps = if (canTestTone) 3 else 2

                _usedMic.value = null
                // Mientras se abre el micrófono (con Bluetooth tarda 1–3 s) ya se ve la pantalla del diagnóstico.
                _diagnosis.value = DiagnosisState.Running(DiagnosisStep.QUIET, 1, totalSteps, 3, 0f, LevelMath.MIN_DB)
                val (quiet, voice, toneDb) = withMic {
                    val q = measureStep(DiagnosisStep.QUIET, 1, totalSteps, frames = 25, skip = 5)
                    val v = measureStep(DiagnosisStep.VOICE, 2, totalSteps, frames = 40, skip = 2)
                    Triple(q, v, if (canTestTone) measureTone(3, totalSteps) else null)
                }
                val used = _usedMic.value
                val env = env0.copy(
                    selectedMic = chosen.kind,
                    selectedMicLabel = chosen.label.takeIf { chosen.kind != MicKind.AUTO },
                    usedMic = used?.kind,
                    usedMicLabel = used?.label,
                )

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
        micFrames().take(all).collectIndexed { index, frame ->
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
        micFrames().take(all).collectIndexed { index, frame ->
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
        detenerReproduccion()
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
        const val MAX_GRABACION_SEGUNDOS = 60 * 60
    }
}
