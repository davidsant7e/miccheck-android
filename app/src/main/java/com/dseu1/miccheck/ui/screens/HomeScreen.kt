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
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dseu1.miccheck.audio.EnvironmentInfo
import com.dseu1.miccheck.audio.MicKind
import com.dseu1.miccheck.audio.MicOption
import com.dseu1.miccheck.audio.PuenteBluetooth
import com.dseu1.miccheck.ui.GrabadoraState
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
    val grabadora by vm.grabadora.collectAsStateWithLifecycle()
    val puente by PuenteBluetooth.estado.collectAsStateWithLifecycle()

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
        GrabadoraCard(grabadora, selectedMic, vm)
        PuenteCard(puente, hayBluetooth = mics.any { it.kind == MicKind.BLUETOOTH })
        environment?.let { EnvironmentCard(it) }
    }
}

/** Grabar todo lo que quieras con el micrófono elegido (también Bluetooth) y guardarlo. */
@Composable
private fun GrabadoraCard(g: GrabadoraState, mic: MicOption, vm: MainViewModel) {
    val context = LocalContext.current
    SectionCard(title = "Grabadora") {
        Text(
            "Graba con: ${mic.label}. Se guarda en Recordings/El Juicio (la ven la grabadora, Archivos y WhatsApp).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        when {
            g.grabando -> {
                Text("● Grabando ${tiempo(g.segundos * 1000L)}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                LevelMeter(levelDb = g.nivelDb, showScale = false)
                Text("Si sales de la app, la grabación se detiene y se guarda.", style = MaterialTheme.typography.bodySmall)
            }
            g.guardando -> Text("Guardando…")
            g.guardada != null -> Text("✓ Guardada: ${g.guardada}", style = MaterialTheme.typography.bodySmall)
        }
        g.error?.let { Warning(it) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = vm::grabadoraIniciarODetener, enabled = !g.guardando, modifier = Modifier.fillMaxWidth()) {
            Text(if (g.grabando) "■ Detener y guardar" else "● Grabar")
        }
        if (g.grabaciones.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Grabaciones", style = MaterialTheme.typography.titleSmall)
            g.grabaciones.take(10).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.nombre.removePrefix("eljuicio_").removeSuffix(".wav"), style = MaterialTheme.typography.bodySmall)
                        Text("${tiempo(item.duracionMs)} · ${item.bytes / 1024} KB", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val sonando = g.reproduciendo == item.uri
                    TextButton(onClick = { if (sonando) vm.detenerReproduccion() else vm.reproducir(item) }) { Text(if (sonando) "■" else "▶") }
                    if (item.uri.scheme == "content") TextButton(onClick = { compartir(context, item.uri) }) { Text("Enviar") }
                    TextButton(onClick = { vm.borrar(item) }) { Text("✕") }
                }
            }
        }
    }
}

private fun tiempo(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun compartir(context: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(i, "Enviar grabación"))
}

/** Experimental: dejar abierto el Bluetooth para que otras apps graben con él. */
@Composable
private fun PuenteCard(estado: PuenteBluetooth.Estado, hayBluetooth: Boolean) {
    val context = LocalContext.current
    val permisos = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val bt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || r[Manifest.permission.BLUETOOTH_CONNECT] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (bt) PuenteBluetooth.iniciar(context)
    }
    SectionCard(title = "Bluetooth para otras apps (experimental)") {
        Text(
            "Android no deja que una app elija el micrófono de las demás. Esto deja abierto el canal de llamada del audífono: " +
                "mientras está activo, en muchos celulares la grabadora, las notas de voz y otras apps graban con el Bluetooth. " +
                "Depende del fabricante; pruébalo con la app que quieras usar. El sonido del audífono pasa a calidad de llamada.",
            style = MaterialTheme.typography.bodyMedium,
        )
        estado.mensaje?.let {
            Spacer(Modifier.height(6.dp))
            if (estado.activo) Text("✓ $it", color = MaterialTheme.colorScheme.primary) else Warning(it)
        }
        Spacer(Modifier.height(10.dp))
        if (estado.activo) {
            OutlinedButton(onClick = { PuenteBluetooth.detener(context) }, modifier = Modifier.fillMaxWidth()) { Text("Detener") }
        } else {
            Button(
                onClick = {
                    val faltan = permisos.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                    if (faltan.isEmpty()) PuenteBluetooth.iniciar(context) else pedir.launch(faltan.toTypedArray())
                },
                enabled = hayBluetooth,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (hayBluetooth) "Activar" else "Conecta un audífono Bluetooth") }
        }
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
