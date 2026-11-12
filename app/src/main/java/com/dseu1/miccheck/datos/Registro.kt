package com.dseu1.miccheck.datos

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.dseu1.miccheck.audio.Bitacora
import com.dseu1.miccheck.audio.MicInputs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Registro de todo lo que se hace en El Juicio: una línea por evento, un archivo por día, 14 días.
 *
 * Dónde queda: /sdcard/Android/data/com.dseu1.miccheck/files/registros/registro-AAAA-MM-DD.txt
 * Desde el PC (cable, depuración USB):
 *   adb -s ZY22KKQ9LD pull /sdcard/Android/data/com.dseu1.miccheck/files/registros %USERPROFILE%\Downloads\eljuicio-registros
 * Desde el celular: botón «Enviar registro» (lo comparte como texto, por ejemplo con la app de Claude).
 *
 * No guarda audio ni lo que dices: solo niveles, estados y nombres de dispositivos.
 */
object Registro {
    const val DIAS = 14
    private val hilo = Executors.newSingleThreadExecutor()
    @Volatile private var carpeta: File? = null
    @Volatile private var appContext: Context? = null

    fun iniciar(context: Context) {
        if (carpeta != null) return
        val c = context.applicationContext
        appContext = c
        carpeta = (c.getExternalFilesDir("registros") ?: File(c.filesDir, "registros")).apply { mkdirs() }
        hilo.execute { limpiarViejos() }
        val version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull()
        evento(
            "APP", "inicio",
            linkedMapOf(
                "celular" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                "version_app" to version,
            ),
        )
    }

    /** Escribe una línea (en segundo plano, nunca bloquea ni falla hacia afuera). */
    fun evento(accion: String, fase: String, datos: Map<String, Any?> = emptyMap()) {
        val ahora = System.currentTimeMillis()
        val linea = Bitacora.linea(ahora, accion, fase, datos)
        hilo.execute {
            runCatching { archivoDe(ahora)?.appendText(linea + "\n") }
        }
    }

    /** Foto del estado del audio en este momento: modo, a dónde va la llamada, entradas y salidas. */
    fun equipo(context: Context): Map<String, Any?> = runCatching {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val entradas = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { describir(it) }
        val salidas = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { describir(it) }
        linkedMapOf(
            "modo_audio" to modo(am.mode),
            "dispositivo_llamada" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.communicationDevice?.let { describir(it) } else null,
            "sco_activo" to @Suppress("DEPRECATION") am.isBluetoothScoOn,
            "microfono_silenciado" to am.isMicrophoneMute,
            "grabaciones_de_otras_apps" to am.activeRecordingConfigurations.size,
            "volumen_llamada" to "${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}",
            "volumen_multimedia" to "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",
            "entradas" to entradas.joinToString(", "),
            "salidas" to salidas.joinToString(", "),
        )
    }.getOrElse { mapOf("equipo_error" to it.message) }

    fun describir(d: AudioDeviceInfo): String {
        val nombre = d.productName?.toString()?.trim().orEmpty()
        val tipo = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic-celular"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "parlante"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "auricular-celular"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-llamada(SCO)"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-musica(A2DP)"
            MicInputs.TYPE_BLE_HEADSET -> "bt-le-audio"
            27 -> "bt-le-parlante"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "cable-con-mic"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "cable"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telefonia"
            else -> "tipo${d.type}"
        }
        return if (nombre.isEmpty() || nombre == Build.MODEL) tipo else "$tipo:$nombre"
    }

    private fun modo(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "timbre"
        AudioManager.MODE_IN_CALL -> "llamada-telefonica"
        AudioManager.MODE_IN_COMMUNICATION -> "llamada-app"
        else -> "modo$m"
    }

    /** Últimas [n] líneas (de hoy y, si hace falta, de días anteriores). */
    fun ultimas(n: Int): List<String> {
        val archivos = archivos()
        val todas = ArrayList<String>()
        for (f in archivos) { // del más reciente al más viejo
            val l = runCatching { f.readLines() }.getOrDefault(emptyList())
            todas.addAll(0, l)
            if (todas.size >= n) break
        }
        return todas.takeLast(n)
    }

    /** Texto para enviar (lo más reciente primero se corta si es muy largo). */
    fun textoParaEnviar(maxCaracteres: Int = 60_000): String {
        val l = ultimas(2_000)
        val sb = StringBuilder()
        for (i in l.indices.reversed()) {
            if (sb.length + l[i].length + 1 > maxCaracteres) break
            sb.insert(0, l[i] + "\n")
        }
        return "Registro de XX · El Juicio (MicCheck)\n\n$sb"
    }

    fun archivos(): List<File> =
        carpeta?.listFiles { f -> f.name.startsWith("registro-") && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun ruta(): String = carpeta?.absolutePath ?: "(sin carpeta)"

    private fun archivoDe(momento: Long): File? {
        val dir = carpeta ?: return null
        return File(dir, "registro-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(momento)) + ".txt")
    }

    private fun limpiarViejos() {
        val limite = System.currentTimeMillis() - DIAS * 86_400_000L
        archivos().filter { it.lastModified() < limite }.forEach { it.delete() }
    }
}
