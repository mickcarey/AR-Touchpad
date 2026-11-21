package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import java.time.*
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

internal fun city(zone: String) = zone.substringAfterLast('/').replace('_', ' ')

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchpadClock(zones: Set<String>) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { now = ZonedDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (DateFormat.is24HourFormat(context)) "Hm" else "hma")
    val time = DateTimeFormatter.ofPattern(pattern)
    val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd"))
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val rim = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.32f),
        colors.secondary.copy(alpha = 0.20f), Color.White.copy(alpha = 0.06f)))
    Column(Modifier.fillMaxWidth().clip(shape).drawBehind {
        drawRect(colors.surface.copy(alpha = 0.68f))
        drawRect(Brush.linearGradient(listOf(colors.primaryContainer.copy(alpha = 0.65f),
            colors.surfaceContainer.copy(alpha = 0.55f))))
        drawRect(Brush.radialGradient(listOf(colors.secondary.copy(alpha = 0.20f), Color.Transparent),
            center = Offset(size.width * 0.83f, 0f), radius = size.width * 0.75f))
    }.border(1.dp, rim, shape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showDial = maxWidth >= 260.dp * LocalDensity.current.fontScale
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Local time · ${city(now.zone.id)}", style = MaterialTheme.typography.labelLarge,
                        color = colors.secondary)
                    Text(time.format(now), fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.Light,
                        letterSpacing = (-1).sp, color = colors.onSurface)
                    Text(date.format(now), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                if (showDial) ClockDial(now, Modifier.size(94.dp))
            }
        }
        val others = zones.sorted().filter { it != now.zone.id }
        if (others.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp)) {
                others.forEachIndexed { index, id ->
                    val there = now.withZoneSameInstant(ZoneId.of(id))
                    val day = when (ChronoUnit.DAYS.between(now.toLocalDate(), there.toLocalDate())) {
                        -1L -> "Yesterday"
                        0L -> "Today"
                        1L -> "Tomorrow"
                        else -> there.format(DateTimeFormatter.ofPattern("EEE"))
                    }
                    if (index > 0) HorizontalDivider(color = colors.onSurface.copy(alpha = 0.10f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(city(id), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            Text("$day · ${there.format(DateTimeFormatter.ofPattern("z"))}",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text(time.format(there), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockDial(time: ZonedDateTime, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val radius = size.minDimension * 0.46f
        fun point(turn: Double, length: Float): Offset {
            val angle = turn * 2 * Math.PI
            return center + Offset(sin(angle).toFloat(), -cos(angle).toFloat()) * radius * length
        }
        drawCircle(Brush.radialGradient(listOf(colors.secondary.copy(alpha = 0.21f),
            colors.surface.copy(alpha = 0.76f)), center = center - Offset(radius * 0.5f, radius * 0.55f),
            radius = radius * 1.65f), radius)
        drawCircle(Brush.linearGradient(listOf(colors.onSurface.copy(alpha = 0.43f), colors.secondary.copy(alpha = 0.08f))),
            radius, style = Stroke(1.dp.toPx()))
        for (tick in 0 until 60) {
            val major = tick % 5 == 0
            drawLine(if (major) colors.secondary else colors.outline, point(tick / 60.0, if (major) 0.78f else 0.88f),
                point(tick / 60.0, 0.92f), if (major) 2.dp.toPx() else 1.dp.toPx())
        }
        drawLine(colors.onSurface, center, point((time.hour + time.minute / 60.0) / 12.0, 0.48f), 4.dp.toPx(), StrokeCap.Round)
        drawLine(colors.primary, center, point(time.minute / 60.0, 0.73f), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(colors.primary, 3.dp.toPx(), center)
    }
}
