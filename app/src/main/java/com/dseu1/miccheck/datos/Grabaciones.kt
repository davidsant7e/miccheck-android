package com.dseu1.miccheck.datos

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Una grabación guardada por El Juicio. */
data class Grabacion(val uri: Uri, val nombre: String, val duracionMs: Long, val fecha: Long, val bytes: Long)

/**
 * Guarda las grabaciones en la carpeta pública «Recordings/El Juicio» (Android 10+), donde las ven la
 * grabadora, el explorador de archivos y WhatsApp. En Android 8–9 quedan en la carpeta de la app.
 * No pide permisos de almacenamiento: la app solo ve y borra lo que ella misma guardó.
 */
object Grabaciones {
    private const val CARPETA = "El Juicio"

    fun nombreNuevo(etiqueta: String): String {
        val fecha = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val limpia = etiqueta.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        return "eljuicio_${fecha}_$limpia.wav"
    }

    /** Copia el WAV temporal a su lugar definitivo y lo borra. */
    fun guardar(context: Context, temporal: File, nombre: String, duracionMs: Long): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val valores = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, nombre)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, relativa())
                put(MediaStore.Audio.Media.DURATION, duracionMs)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), valores)
                ?: error("Android no dejó crear el archivo de audio.")
            try {
                cr.openOutputStream(uri)!!.use { out -> temporal.inputStream().use { it.copyTo(out) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            temporal.delete()
            return uri
        }
        val destino = File(carpetaVieja(context), nombre)
        temporal.copyTo(destino, overwrite = true)
        temporal.delete()
        return Uri.fromFile(destino)
    }

    fun listar(context: Context): List<Grabacion> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val lista = ArrayList<Grabacion>()
            val col = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val campos = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.SIZE,
            )
            context.contentResolver.query(
                col, campos, "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(relativa()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    lista += Grabacion(
                        ContentUris.withAppendedId(col, c.getLong(0)), c.getString(1) ?: "",
                        c.getLong(2), c.getLong(3) * 1000, c.getLong(4),
                    )
                }
            }
            return lista
        }
        return carpetaVieja(context).listFiles { f -> f.name.endsWith(".wav") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { Grabacion(Uri.fromFile(it), it.name, duracionWav(it), it.lastModified(), it.length()) }
    }

    fun borrar(context: Context, g: Grabacion): Boolean =
        if (g.uri.scheme == "file") File(g.uri.path!!).delete()
        else context.contentResolver.delete(g.uri, null, null) > 0

    /** «Recordings» existe desde Android 12; antes, las grabaciones van en «Music». */
    private fun relativa() =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC) + "/$CARPETA/"

    /** Duración de un WAV leyendo la frecuencia de su cabecera. */
    private fun duracionWav(f: File): Long = runCatching {
        val h = ByteArray(44)
        f.inputStream().use { it.read(h) }
        val rate = (h[24].toLong() and 0xFF) or ((h[25].toLong() and 0xFF) shl 8) or ((h[26].toLong() and 0xFF) shl 16)
        if (rate <= 0) 0L else (f.length() - 44) / 2 * 1000 / rate
    }.getOrDefault(0L)

    private fun carpetaVieja(context: Context) =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), CARPETA).apply { mkdirs() }
}
