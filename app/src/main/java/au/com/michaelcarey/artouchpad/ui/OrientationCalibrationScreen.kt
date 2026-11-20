package au.com.michaelcarey.artouchpad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.michaelcarey.artouchpad.CalibratedRotation
import au.com.michaelcarey.artouchpad.OrientationCalibration
import au.com.michaelcarey.artouchpad.CalibrationStrokeTracker
import java.util.Locale
import kotlin.math.abs

internal fun calibratedRotationLabel(degrees: Float): String {
    val amount = String.format(Locale.getDefault(), "%.1f", abs(degrees))
    return "Calibrated · $amount°" + when {
        abs(degrees) < 0.05f -> ""
        degrees > 0 -> " clockwise"
        else -> " anticlockwise"
    }
}

@Composable
internal fun OrientationCalibrationScreen(onCancel: () -> Unit, onSave: (Float) -> Unit) {
    val minimumTravel = with(LocalDensity.current) { 80.dp.toPx() }
    val reversalTravel = with(LocalDensity.current) { 16.dp.toPx() }
    var calibration by remember { mutableStateOf(OrientationCalibration(minimumTravel)) }
    var count by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Float?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf(Offset.Zero) }
    var trail by remember { mutableStateOf(emptyList<OrientationCalibration.Point>()) }
    BackHandler(onBack = onCancel)
    Surface(Modifier.fillMaxSize(), color = Color(0xFF081211), contentColor = Color(0xFFF4F0E6)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Calibrate orientation", style = MaterialTheme.typography.headlineSmall)
            Text("Hold the phone how you use it. Swipe up and down without lifting, starting upwards.",
                textAlign = TextAlign.Center)
            Text(if (result != null) calibratedRotationLabel(result!!) else
                "Swipe ${if (count % 2 == 0) "up" else "down"} · ${count + 1} of ${OrientationCalibration.STROKES}",
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1B3333), RoundedCornerShape(24.dp))
                .pointerInput(calibration) {
                    fun updateProgress(rejection: OrientationCalibration.Rejection?) {
                        hint = when (rejection) {
                            OrientationCalibration.Rejection.TOO_SHORT -> "Try a longer swipe."
                            OrientationCalibration.Rejection.NOT_STRAIGHT -> "Try a straighter swipe."
                            OrientationCalibration.Rejection.INCONSISTENT -> "Keep the phone still and follow the swipe direction."
                            null -> null
                        }
                        count = calibration.count
                        result = calibration.degrees
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (calibration.complete) {
                            val rotation = CalibratedRotation(calibration.degrees!!)
                            var last = down.position
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull { it.id == down.id }?.let {
                                    val delta = it.position - last
                                    last = it.position
                                    val (dx, dy) = rotation.rotate(delta.x, delta.y)
                                    preview = Offset((preview.x + dx).coerceIn(-size.width * 0.45f, size.width * 0.45f),
                                        (preview.y + dy).coerceIn(-size.height * 0.45f, size.height * 0.45f))
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                            return@awaitEachGesture
                        }
                        val points = mutableListOf(OrientationCalibration.Point(down.position.x, down.position.y))
                        val tracker = CalibrationStrokeTracker(calibration, reversalTravel)
                        tracker.add(points.first())
                        var singleFinger = true
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.id != down.id }) singleFinger = false
                            event.changes.firstOrNull { it.id == down.id }?.let {
                                val point = OrientationCalibration.Point(it.position.x, it.position.y)
                                points += point
                                if (singleFinger) {
                                    val rejection = tracker.add(point)
                                    if (calibration.count != count || rejection != null) updateProgress(rejection)
                                }
                            }
                            event.changes.forEach { it.consume() }
                            trail = points.takeLast(256)
                        } while (event.changes.any { it.pressed })
                        if (!singleFinger) hint = "Use one finger."
                        else updateProgress(tracker.finish())
                    }
                }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    if (result != null) {
                        drawCircle(Color(0xFFE6AF52), 9.dp.toPx(), center + preview)
                        drawCircle(Color.White, 3.dp.toPx(), center + preview)
                    } else for (i in 1 until trail.size) {
                        val a = trail[i - 1]; val b = trail[i]
                        drawLine(Color(0xFFE6AF52), Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = 3.dp.toPx())
                    }
                }
                Text(if (result != null) "Swipe to test direction" else "Swipe here", color = Color(0xFFC2CCBF))
            }
            Text(hint ?: if (result == null) "$count / ${OrientationCalibration.STROKES} swipes" else "Test the dot before saving. Up should move it up; right should move it right.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                TextButton(onClick = {
                    calibration = OrientationCalibration(minimumTravel)
                    count = 0; result = null; hint = null; trail = emptyList(); preview = Offset.Zero
                }) { Text("Start again") }
                Button(enabled = result != null, onClick = { result?.let(onSave) }) { Text("Save") }
            }
        }
    }
}
