package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainer) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainer)))
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 600.dp
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Local time · ${city(now.zone.id)}", style = MaterialTheme.typography.labelLarge, color = colors.secondary)
                        Text(time.format(now), fontFamily = FontFamily.Serif, fontSize = if (wide) 64.sp else 40.sp,
                            lineHeight = if (wide) 68.sp else 46.sp, color = colors.onSurface)
                        Text(date.format(now), style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                    }
                    if (this@BoxWithConstraints.maxWidth >= 380.dp) ClockDial(now, Modifier.size(if (wide) 126.dp else 92.dp))
                }
            }
            val others = zones.sorted().filter { it != now.zone.id }
            if (others.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { id ->
                        val there = now.withZoneSameInstant(ZoneId.of(id))
                        Column(Modifier.widthIn(min = 122.dp).weight(1f).background(colors.background.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(city(id), color = colors.secondary, style = MaterialTheme.typography.labelLarge)
                            Text(time.format(there), style = MaterialTheme.typography.titleLarge)
                            val day = when (ChronoUnit.DAYS.between(now.toLocalDate(), there.toLocalDate())) {
                                -1L -> "Yesterday"
                                0L -> "Today"
                                1L -> "Tomorrow"
                                else -> there.format(DateTimeFormatter.ofPattern("EEE"))
                            }
                            Text("$day · ${there.format(DateTimeFormatter.ofPattern("z"))}",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
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
        for (tick in 0 until 60) {
            val major = tick % 5 == 0
            drawLine(if (major) colors.secondary else colors.outline, point(tick / 60.0, if (major) 0.82f else 0.95f),
                point(tick / 60.0, 1f), if (major) 2.dp.toPx() else 1.dp.toPx())
        }
        drawLine(colors.onSurface, center, point((time.hour + time.minute / 60.0) / 12.0, 0.48f), 4.dp.toPx(), StrokeCap.Round)
        drawLine(colors.primary, center, point(time.minute / 60.0, 0.73f), 2.dp.toPx(), StrokeCap.Round)
        drawCircle(colors.primary, 3.dp.toPx(), center)
    }
}
