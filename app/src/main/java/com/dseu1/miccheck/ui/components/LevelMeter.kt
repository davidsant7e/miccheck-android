package com.dseu1.miccheck.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.dseu1.miccheck.audio.LevelMath
import com.dseu1.miccheck.ui.theme.StatusColors

/** Rango visible de la barra: de -80 dBFS (izquierda) a 0 dBFS (derecha). */
private const val METER_FLOOR_DB = -80.0

fun dbToFraction(db: Double): Float =
    ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0.0, 1.0).toFloat()

/**
 * Barra horizontal de nivel. Verde = normal, ámbar = fuerte, rojo = cerca de saturar.
 * La línea vertical es el pico retenido (lo más alto del último segundo).
 */
@Composable
fun LevelMeter(
    levelDb: Double,
    modifier: Modifier = Modifier,
    peakHoldDb: Double = LevelMath.MIN_DB,
    showScale: Boolean = true,
) {
    val fraction by animateFloatAsState(
        targetValue = dbToFraction(levelDb),
        animationSpec = tween(durationMillis = 60),
        label = "nivel",
    )
    val barColor = when {
        levelDb > -6.0 -> StatusColors.fail
        levelDb > -20.0 -> StatusColors.warning
        else -> StatusColors.ok
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val holdColor = MaterialTheme.colorScheme.onSurface
    val holdFraction = dbToFraction(peakHoldDb)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(trackColor)
        ) {
            drawRect(color = barColor, size = Size(size.width * fraction, size.height))
            if (holdFraction > 0f) {
                val x = size.width * holdFraction
                drawLine(
                    color = holdColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        if (showScale) {
            Row(Modifier.fillMaxWidth()) {
                Text("-80", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("-40", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("0 dBFS", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Formatea un nivel para mostrarlo: "-42,5 dBFS" o "silencio". */
fun formatDb(db: Double): String =
    if (db <= LevelMath.MIN_DB) "silencio" else "%.1f dBFS".format(db).replace('.', ',')
