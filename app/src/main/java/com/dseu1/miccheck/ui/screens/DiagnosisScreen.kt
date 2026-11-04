package com.dseu1.miccheck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dseu1.miccheck.diagnosis.CheckResult
import com.dseu1.miccheck.diagnosis.Status
import com.dseu1.miccheck.ui.DiagnosisState
import com.dseu1.miccheck.ui.components.LevelMeter
import com.dseu1.miccheck.ui.theme.StatusColors

/** Pantalla del diagnóstico: pasos en curso, resultados o error. */
@Composable
fun DiagnosisScreen(
    state: DiagnosisState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El botón "atrás" del celular cancela y vuelve al inicio.
    BackHandler(onBack = onClose)

    when (state) {
        is DiagnosisState.Running -> RunningContent(state, onClose, modifier)
        is DiagnosisState.Done -> ResultsContent(state.results, onRetry, onClose, modifier)
        is DiagnosisState.Failed -> FailedContent(state.message, onRetry, onClose, modifier)
        DiagnosisState.Idle -> Unit
    }
}

@Composable
private fun RunningContent(state: DiagnosisState.Running, onCancel: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Paso ${state.stepNumber} de ${state.totalSteps}: ${state.step.title}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            state.step.instruction,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        if (state.countdown > 0) {
            Text("Empieza en ${state.countdown}…", style = MaterialTheme.typography.titleLarge)
        } else {
            Text("Midiendo…", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            LevelMeter(levelDb = state.levelDb, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(40.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancelar") }
    }
}

@Composable
private fun ResultsContent(
    results: List<CheckResult>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val fails = results.count { it.status == Status.FAIL }
    val warnings = results.count { it.status == Status.WARNING }
    val (summary, color) = when {
        fails > 0 -> "Se encontraron $fails problema(s). Revisa las sugerencias." to StatusColors.fail
        warnings > 0 -> "El micrófono funciona, con $warnings aviso(s)." to StatusColors.warning
        else -> "¡Tu micrófono funciona bien!" to StatusColors.ok
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Resultados", style = MaterialTheme.typography.headlineMedium)
        Text(summary, color = color, style = MaterialTheme.typography.titleMedium)
        results.forEach { ResultCard(it) }
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Volver") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Repetir") }
        }
        Text(
            "Los niveles están en dBFS (respecto al máximo digital del celular), no son decibeles calibrados.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ResultCard(result: CheckResult) {
    val (icon, color) = when (result.status) {
        Status.OK -> "✅" to StatusColors.ok
        Status.WARNING -> "⚠️" to StatusColors.warning
        Status.FAIL -> "❌" to StatusColors.fail
        Status.INFO -> "ℹ️" to StatusColors.info
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$icon  ${result.title}",
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(result.detail, style = MaterialTheme.typography.bodyMedium)
            if (result.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Qué hacer:", style = MaterialTheme.typography.labelLarge)
                result.suggestions.forEach { suggestion ->
                    Text("• $suggestion", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No se pudo completar el diagnóstico", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(message, color = StatusColors.fail, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row {
            OutlinedButton(onClick = onClose) { Text("Volver") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onRetry) { Text("Reintentar") }
        }
    }
}
