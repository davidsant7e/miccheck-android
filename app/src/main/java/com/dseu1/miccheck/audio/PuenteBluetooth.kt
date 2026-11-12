package com.dseu1.miccheck.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dseu1.miccheck.MainActivity
import com.dseu1.miccheck.R
import com.dseu1.miccheck.datos.Registro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * «Bluetooth para otras apps» (experimental).
 *
 * Android no deja que una app elija el micrófono de las demás. Lo que sí se puede es dejar abierto el
 * canal de llamada del audífono (modo comunicación + SCO): mientras está abierto, en muchos celulares
 * las otras apps que graban (grabadora, notas de voz…) reciben el audio del Bluetooth. Depende del
 * fabricante: en algunos funciona con todas las apps, en otros solo con las de llamadas.
 *
 * Mientras está activo, el sonido del audífono pasa a calidad de llamada. Se detiene desde la notificación.
 */
class PuenteBluetooth : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ruta: MicRoute? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }
        Registro.iniciar(this)
        crearCanal()
        val tipo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICACION, notificacion("Abriendo el micrófono Bluetooth…"), tipo)
        if (ruta == null) {
            val opcion = MicInputs.list(this).firstOrNull { it.kind == MicKind.BLUETOOTH }
            if (opcion == null) {
                _estado.value = Estado(false, "No hay un audífono Bluetooth conectado.")
                Registro.evento("PUENTE", "error", linkedMapOf<String, Any?>("error" to "sin audífono Bluetooth") + Registro.equipo(this))
                stopSelf()
                return START_NOT_STICKY
            }
            val r = MicRoute(this)
            ruta = r
            scope.launch {
                try {
                    Registro.evento("PUENTE", "inicio", linkedMapOf<String, Any?>("microfono" to opcion.label) + Registro.equipo(this@PuenteBluetooth))
                    r.open(opcion)
                    Registro.evento("PUENTE", "activo", Registro.equipo(this@PuenteBluetooth))
                    _estado.value = Estado(true, "Las apps que graben ahora deberían usar ${opcion.label}.")
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICACION, notificacion("Micrófono: ${opcion.label}. Graba en la otra app."))
                } catch (e: Exception) {
                    Registro.evento("PUENTE", "error", linkedMapOf<String, Any?>("error" to (e.message ?: e.javaClass.simpleName)) + Registro.equipo(this@PuenteBluetooth))
                    _estado.value = Estado(false, e.message ?: "No se pudo abrir el micrófono Bluetooth.")
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ruta != null) Registro.evento("PUENTE", "detenido", Registro.equipo(this))
        ruta?.close()
        ruta = null
        scope.cancel()
        if (_estado.value.activo) _estado.value = Estado(false, null)
        super.onDestroy()
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val detener = PendingIntent.getService(
            this, 1, Intent(this, PuenteBluetooth::class.java).setAction(ACCION_DETENER), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle("Bluetooth para otras apps")
            .setContentText(texto)
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(0, "Detener", detener)
            .build()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANAL, "Bluetooth para otras apps", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    data class Estado(val activo: Boolean, val mensaje: String?)

    companion object {
        private const val CANAL = "puente"
        private const val NOTIFICACION = 7
        private const val ACCION_DETENER = "detener"

        private val _estado = MutableStateFlow(Estado(false, null))
        val estado: StateFlow<Estado> = _estado

        fun iniciar(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, PuenteBluetooth::class.java))

        fun detener(context: Context) {
            context.stopService(Intent(context, PuenteBluetooth::class.java))
        }
    }
}
