package com.pgratz.artouchpad.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.artouchpad.AxisRotation
import com.pgratz.artouchpad.DIM_DELAY_CHOICES_SEC
import com.pgratz.artouchpad.DisplayInfo

// Neutral Material surfaces and a restrained blue accent, scoped to settings.
private val SettingsColors = darkColorScheme(
    primary = Color(0xFFA8C7FA), onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF244574), onPrimaryContainer = Color(0xFFD3E3FD),
    secondaryContainer = Color(0xFF35445C), onSecondaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF1B1B1F), surface = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF25262A), surfaceContainerHigh = Color(0xFF303136),
    surfaceVariant = Color(0xFF44474E), onSurface = Color(0xFFE3E3E8),
    onSurfaceVariant = Color(0xFFC4C6D0), outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
)

/** Same controls on both displays; wide windows use two columns, phones use one. */
@Composable
internal fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    axisRotation: AxisRotation,
    dimDelaySec: Int,
    dexKeyboard: Boolean,
    dexKeyboardActive: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
    onSensitivity: (Float) -> Unit,
    onScrollSpeed: (Float) -> Unit,
    onNaturalScroll: (Boolean) -> Unit,
    onAxisRotation: (AxisRotation) -> Unit,
    onDimDelay: (Int) -> Unit,
    onDexKeyboard: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    connectionControls: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = SettingsColors) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(28.dp)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Medium)
                        SupportingText("AR Touchpad")
                    }
                    Button(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)) {
                        Text("Done")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 800.dp
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        connectionControls()
                        val pointer: @Composable () -> Unit = {
                            SettingsSection("Pointer", "Movement and scrolling") {
                                SpeedSetting("Cursor speed", sensitivity, 0.4f..2f, onSensitivity)
                                SpeedSetting("Scroll speed", scrollSpeed, 0.3f..1.3f, onScrollSpeed)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                SettingsChoice("Phone orientation", axisRotationLabel(axisRotation),
                                    "Rotate cursor movement and two-finger scrolling.",
                                    AxisRotation.entries.map { it to axisRotationLabel(it) }, onAxisRotation)
                                SupportingText(when (axisRotation) {
                                    AxisRotation.NONE -> "Swipe right → cursor moves right"
                                    AxisRotation.CLOCKWISE_45 -> "Swipe right → cursor moves down-right"
                                    AxisRotation.CLOCKWISE_90 -> "Swipe right → cursor moves down"
                                    AxisRotation.COUNTERCLOCKWISE_45 -> "Swipe right → cursor moves up-right"
                                    AxisRotation.COUNTERCLOCKWISE_90 -> "Swipe right → cursor moves up"
                                })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ToggleSetting("Natural scrolling", "Content follows your fingers.", naturalScroll, onNaturalScroll)
                            }
                        }
                        val display: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                SettingsSection("Phone & keyboard", "Keep the touchpad comfortable to use") {
                                    SettingsChoice("Dim phone after", dimDelayLabel(dimDelaySec),
                                        "Only dims the phone. External display stays bright.",
                                        DIM_DELAY_CHOICES_SEC.map { it to dimDelayLabel(it) }, onDimDelay)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text("Popup keyboard", style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = !dexKeyboard, onClick = { onDexKeyboard(false) },
                                            label = { Text("External display") })
                                        FilterChip(selected = dexKeyboard, onClick = { onDexKeyboard(true) },
                                            label = { Text("Phone") })
                                    }
                                    SupportingText(when {
                                        !dexKeyboard -> "Use the cursor to click keys on your external display."
                                        dexKeyboardActive -> "Type on the phone for text fields on your external display."
                                        targetDisplay != null -> "Phone keyboard isn't supported here. The keyboard opens on the external display."
                                        else -> "Connect an external display to use this option."
                                    })
                                }
                                SettingsSection("Displays") {
                                    if (allDisplays.isEmpty()) SupportingText("No displays detected.")
                                    allDisplays.forEachIndexed { i, d ->
                                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(d.name, style = MaterialTheme.typography.titleSmall)
                                                SupportingText("${d.width} × ${d.height}")
                                            }
                                            if (d.id == targetDisplay?.id) Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(8.dp)) {
                                                Text("Active", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    style = MaterialTheme.typography.labelMedium)
                                            }
                                        }
                                    }
                                    if (targetDisplay == null) SupportingText("Connect your glasses in Desktop / Extended mode.")
                                }
                            }
                        }
                        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Box(Modifier.weight(1f)) { pointer() }
                            Box(Modifier.weight(1f)) { display() }
                        } else {
                            pointer()
                            display()
                        }
                        GestureReference()
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(horizontal = 28.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Saved automatically", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    SupportingText("To reopen: with the phone 90° anticlockwise, swipe down along its right edge.")
                }
            }
        }
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) SupportingText(subtitle)
            }
            content()
        }
    }
}

@Composable
private fun SpeedSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("%.1f×".format(value), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToggleSetting(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            SupportingText(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun <T> SettingsChoice(title: String, value: String, description: String,
    options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        SupportingText(description)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
                Text(value, Modifier.weight(1f))
                Text("▾", Modifier.padding(start = 12.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (option, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

private fun axisRotationLabel(rotation: AxisRotation): String = when {
    rotation == AxisRotation.NONE -> "Upright · 0°"
    rotation.degrees > 0 -> "${rotation.degrees}° clockwise"
    else -> "${-rotation.degrees}° anticlockwise"
}

private fun dimDelayLabel(sec: Int): String = when {
    sec <= 0 -> "Never"
    sec < 60 -> "$sec seconds"
    sec == 60 -> "1 minute"
    else -> "${sec / 60} minutes"
}

@Composable
private fun GestureReference() {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)) {
                Text("Gesture guide", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "Hide ▴" else "Show ▾")
            }
            if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(
                    "One-finger drag" to "Move cursor",
                    "One-finger tap / double-tap" to "Click / double-click",
                    "One-finger long-press" to "Right-click",
                    "Long-press, then drag" to "Select text",
                    "Two-finger tap" to "Right-click",
                    "Two-finger drag" to "Scroll",
                    "Two-finger pinch" to "Zoom page",
                    "Three-finger tap" to "Toggle Moonlight capture",
                ).forEach { (gesture, action) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(gesture, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
