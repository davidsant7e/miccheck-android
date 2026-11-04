package com.dseu1.miccheck.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dseu1.miccheck.ui.screens.DiagnosisScreen
import com.dseu1.miccheck.ui.screens.HomeScreen
import com.dseu1.miccheck.ui.screens.PermissionScreen

/**
 * Raíz de la interfaz. Decide qué pantalla mostrar:
 * sin permiso → PermissionScreen; diagnóstico en curso o terminado → DiagnosisScreen; si no → HomeScreen.
 */
@Composable
fun MicCheckApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasMicPermission(context)) }

    // Al volver a la app (por ejemplo, desde Ajustes) se revisa otra vez el permiso y el entorno.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = hasMicPermission(context)
        vm.refreshEnvironment()
    }
    // Al salir de la app se suelta el micrófono (y desaparece el punto verde de Android).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.stopAll()
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)

        if (!hasPermission) {
            PermissionScreen(
                onGranted = { hasPermission = true },
                modifier = contentModifier,
            )
        } else {
            val diagnosis by vm.diagnosis.collectAsStateWithLifecycle()
            if (diagnosis is DiagnosisState.Idle) {
                HomeScreen(vm = vm, modifier = contentModifier)
            } else {
                DiagnosisScreen(
                    state = diagnosis,
                    onRetry = vm::startDiagnosis,
                    onClose = vm::closeDiagnosis,
                    modifier = contentModifier,
                )
            }
        }
    }
}

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
