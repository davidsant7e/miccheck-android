package com.dseu1.miccheck.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF0F5F5A)
private val TealLight = Color(0xFF7CF2C8)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Color(0xFF4A635F),
    tertiary = Color(0xFF8A5100),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = Color(0xFFB1CCC6),
    tertiary = Color(0xFFFFB870),
)

/** Colores verde/rojo/ámbar para los estados, que no dependen del fondo de pantalla. */
object StatusColors {
    val ok = Color(0xFF2E7D32)
    val warning = Color(0xFFB26A00)
    val fail = Color(0xFFC62828)
    val info = Color(0xFF546E7A)
}

/**
 * Tema de la app. En Android 12+ usa los colores del fondo de pantalla (Material You);
 * en versiones anteriores, los colores propios de arriba.
 */
@Composable
fun MicCheckTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
