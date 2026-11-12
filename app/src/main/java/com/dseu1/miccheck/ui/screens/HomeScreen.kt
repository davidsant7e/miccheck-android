package com.dseu1.miccheck.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dseu1.miccheck.audio.EnvironmentInfo
import com.dseu1.miccheck.audio.MicKind
import com.dseu1.miccheck.audio.MicOption
import com.dseu1.miccheck.ui.MainViewModel
import com.dseu1.miccheck.ui.MeterState
import com.dseu1.miccheck.ui.RecordPhase
import com.dseu1.miccheck.ui.RecordState
import com.dseu1.miccheck.ui.components.LevelMeter
import com.dseu1.miccheck.ui.components.formatDb
import com.dseu1.miccheck.ui.theme.StatusColors

/** Pantalla principal: diagnóstico, medidor en vivo, grabar y escuchar, e información del equipo. */
@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val meter by vm.meter.collectAsStateWithLifecycle()
    val record by vm.record.collectAsStateWithLifecycle()
    val environment by vm.environment.collectAsStateWithLifecycle()
    val mics by vm.mics.collectAsStateWithLifecycle()
    val selectedMic by vm.selectedMic.collectAsStateWithLifecycle()
    val usedMic by vm.usedMic.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("MicCheck", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Revisa si tu micrófono funciona bien y qué hacer si no.",
            style = MaterialTheme.typography.bodyMedium,
        )

        MicCard(mics, selectedMic, usedMic, onSelect = vm::selectMic, onRefresh = vm::refreshMics)
        DiagnosisCard(onStart = vm::startDiagnosis)
        MeterCard(meter = meter, onToggle = vm::toggleMeter)
        RecordCard(record = record, onRecord = vm::startRecording, onPlay = vm::playRecording, onStop = vm::stopAll)
        environment?.let { EnvironmentCard(it) }
    }
}

/** Elegir qué micrófono probar: automático, el del celular, con cable, USB o Bluetooth. */
@Composable
private fun MicCard(
    mics: List<MicOption>,
    selected: MicOption,
    used: MicOption?,
    onSelect: (MicOption) -> Unit,
    onRefresh: () -> Unit,
) {
    SectionCard(title = "Micrófono a probar") {
        val connected = selected.kind == MicKind.AUTO || mics.any { it.key == selected.key }
        // Si el elegido no está conectado, igual se muestra (marcado) para que se vea qué se había elegido.
        val options = if (connected) mics else mics + selected
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = option.key == selected.key, onClick = { onSelect(option) }, role = Role.RadioButton)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option.key == selected.key, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    val hint = when {
                        option.key == selected.key && !connected -> "No está conectado"
                        option.kind == MicKind.AUTO -> "El que use Android (con audífonos conectados suele ser el de ellos)"
                        option.kind == MicKind.BLUETOOTH -> "Modo llamada: calidad de teléfono"
                        else -> null
                    }
                    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (mics.none { it.kind == MicKind.BLUETOOTH }) {
            Text(
                "¿Buscas un Bluetooth? Conéctalo en Ajustes y pulsa «Buscar de nuevo».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (used != null && selected.kind != MicKind.AUTO && used.kind != selected.kind) {
            Warning("La última grabación la hizo Android con ${used.label}, no con el que elegiste.")
        } else if (used != null) {
            Text("Última grabación con: ${used.label}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onRefresh) { Text("Buscar de nuevo") }
    }
}

@Composable
private fun DiagnosisCard(onStart: () -> Unit) {
    SectionCard(title = "Diagnóstico completo") {
        Text(
            "Tres pasos de unos 6 segundos: silencio, tu voz y un pitido por el parlante. " +
                "Al final verás qué está bien y qué revisar.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Iniciar diagnóstico")
        }
    }
}

@Composable
private fun MeterCard(meter: MeterState, onToggle: () -> Unit) {
    SectionCard(title = "Medidor en vivo") {
        LevelMeter(levelDb = meter.rmsDb, peakHoldDb = meter.peakHoldDb)
        Spacer(Modifier.height(8.dp))
        if (meter.running) {
            Text("Nivel: ${formatDb(meter.rmsDb)}   Pico: ${formatDb(meter.peakHoldDb)}")
            when {
                meter.silencedByOtherApp -> Warning("Android silenció el micrófono: otra app lo está usando.")
                meter.digitalSilence -> Warning("Silencio absoluto: el micrófono no entrega audio. Revisa \"Acceso al micrófono\" en ajustes rápidos.")
                meter.clipping -> Warning("¡Saturando! El sonido es demasiado fuerte para el micrófono.")
            }
        } else {
            Text(
                "Habla, sopla o aplaude y mira cómo se mueve la barra.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        meter.error?.let { Warning(it) }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (meter.running) "Detener" else "Iniciar medidor")
        }
    }
}

@Composable
private fun RecordCard(
    record: RecordState,
    onRecord: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    SectionCard(title = "Grabar y escuchar") {
        when (record.phase) {
            RecordPhase.RECORDING -> {
                Text("Grabando… habla normalmente.")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { record.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LevelMeter(levelDb = record.levelDb, showScale = false)
            }
            RecordPhase.PLAYING -> Text("Reproduciendo tu grabación…")
            RecordPhase.READY -> Text("Escucha la grabación: ¿suena clara, sin cortes ni zumbidos?")
            RecordPhase.IDLE -> Text(
                "Graba ${MainViewModel.RECORD_SECONDS} segundos y escúchalos para juzgar la calidad con tu oído.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        record.error?.let { Warning(it) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            val busy = record.phase == RecordPhase.RECORDING || record.phase == RecordPhase.PLAYING
            if (busy) {
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
            } else {
                OutlinedButton(onClick = onRecord, modifier = Modifier.weight(1f)) { Text("Grabar") }
                if (record.phase == RecordPhase.READY) {
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onPlay, modifier = Modifier.weight(1f)) { Text("Escuchar") }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentCard(env: EnvironmentInfo) {
    SectionCard(title = "Tu equipo") {
        InfoLine("Entradas", env.inputs.joinToString(", ").ifEmpty { "ninguna reportada" })
        env.externalInput?.let { InfoLine("Micrófono externo", it) }
        env.externalOutput?.let { InfoLine("Sonido sale por", it) }
        if (env.microphones.isNotEmpty()) {
            InfoLine("Micrófonos físicos", env.microphones.size.toString())
        }
        InfoLine(
            "Efectos",
            listOfNotNull(
                "eco".takeIf { env.echoCancelerAvailable },
                "ruido".takeIf { env.noiseSuppressorAvailable },
                "ganancia".takeIf { env.gainControlAvailable },
            ).joinToString(", ").ifEmpty { "ninguno" },
        )
        if (env.mediaVolumeZero) {
            Warning("El volumen multimedia está en cero: no oirás las grabaciones ni el pitido.")
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Warning(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = StatusColors.warning, style = MaterialTheme.typography.bodyMedium)
}
