package au.com.michaelcarey.artouchpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import au.com.michaelcarey.artouchpad.AxisRotation
import au.com.michaelcarey.artouchpad.DIM_DELAY_CHOICES_SEC
import au.com.michaelcarey.artouchpad.DisplayInfo
import au.com.michaelcarey.artouchpad.FourFingerGesture
import au.com.michaelcarey.artouchpad.MultiFingerTapTracker
import au.com.michaelcarey.artouchpad.MediaState
import au.com.michaelcarey.artouchpad.TouchpadBuild
import androidx.compose.ui.window.PopupProperties

private val LocalNonFocusableSettings = staticCompositionLocalOf { false }

/** Same controls on both displays; wide windows use two columns, phones use one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsPanel(
    sensitivity: Float,
    scrollSpeed: Float,
    naturalScroll: Boolean,
    axisRotation: AxisRotation,
    calibratedRotationDegrees: Float?,
    calibratingOrientation: Boolean,
    onCalibrate: () -> Unit,
    dimDelaySec: Int,
    blackTouchpad: Boolean,
    pocketMode: Boolean,
    dexKeyboard: Boolean,
    dexKeyboardError: Boolean,
    allDisplays: List<DisplayInfo>,
    targetDisplay: DisplayInfo?,
    onSensitivity: (Float) -> Unit,
    onScrollSpeed: (Float) -> Unit,
    onNaturalScroll: (Boolean) -> Unit,
    onAxisRotation: (AxisRotation) -> Unit,
    onDimDelay: (Int) -> Unit,
    onBlackTouchpad: (Boolean) -> Unit,
    onPocketMode: (Boolean) -> Unit,
    onDexKeyboard: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    appControls: @Composable () -> Unit,
    onSpeedFinished: () -> Unit,
    extraControls: @Composable () -> Unit,
    mediaEnabled: Boolean = false,
    onMediaKey: (Int) -> Unit = {},
    mediaState: MediaState = MediaState(),
    nonFocusable: Boolean = false,
    availableBuild: TouchpadBuild? = null,
    onSwitchBuild: (TouchpadBuild) -> Unit = {},
    showTitle: Boolean = false,
    indicators: @Composable () -> Unit = {},
    apps: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    clock: @Composable () -> Unit = {},
    appearance: @Composable () -> Unit = {},
    recovery: @Composable () -> Unit = {},
) {
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("Personalise") }
    var library by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var settings by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismiss)
    val density = LocalDensity.current.density
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(enabled = !showInfo) {
        when {
            settings -> settings = false
            library -> library = false
            else -> onDismiss()
        }
    }
    CompositionLocalProvider(LocalNonFocusableSettings provides nonFocusable) {

        // Phone-side fallback replaces the touchpad, so it must also recognise the closing chord.
        // Observe before child controls and only consume once four fingers claim the gesture.
        Box(Modifier.fillMaxSize().pointerInput(density) {
            val gesture = FourFingerGesture(16f * density)
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val decision = gesture.update(event.changes.first().uptimeMillis, event.changes.map {
                        MultiFingerTapTracker.Contact(it.id.value, it.position.x, it.position.y, it.pressed)
                    })
                    if (decision != FourFingerGesture.Decision.PASS) event.changes.forEach { it.consume() }
                    if (decision == FourFingerGesture.Decision.TOGGLE_SETTINGS) dismiss()
                }
            }
        }) {
            Surface(Modifier.fillMaxSize().then(if (showInfo) Modifier.clearAndSetSemantics {} else Modifier),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (nonFocusable) 0.92f else 1f),
                contentColor = MaterialTheme.colorScheme.onSurface) {
                Column {
                    SettingsHeader(mediaEnabled, onMediaKey, { showInfo = true }, mediaState,
                        availableBuild, onSwitchBuild, showTitle, indicators, settings, { settings = !settings }, onDismiss)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (settings) {
                        PanelTabs(listOf("Personalise", "Controls", "Connection"), section) { section = it }
                    } else {
                        PanelTabs(listOf("Overview", "App library"), if (library) "App library" else "Overview") {
                            library = it == "App library"
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val wide = maxWidth >= 800.dp
                        if (!settings) {
                            val appsColumn: @Composable () -> Unit = {
                                apps(library) { library = true }
                                appControls()
                            }
                            if (nonFocusable || maxWidth >= 640.dp) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Column(Modifier.weight(1f).testTag("apps-pane").verticalScroll(key(library) { rememberScrollState() }),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) { appsColumn() }
                                    Column(Modifier.weight(1f).testTag("clock-pane").verticalScroll(rememberScrollState())) { clock() }
                                }
                            } else {
                                Column(Modifier.fillMaxSize().verticalScroll(key(library) { rememberScrollState() }).padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) { appsColumn(); clock() }
                            }
                            return@BoxWithConstraints
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(key(section) { rememberScrollState() }).padding(if (wide) 24.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            when (section) {
                                "Personalise" -> { appearance(); return@Column }
                                "Connection" -> { appControls(); recovery(); return@Column }
                            }
                            val pointer: @Composable () -> Unit = {
                                SettingsSection("Pointer", "Movement and scrolling", accent = MaterialTheme.colorScheme.secondary) {
                                    SpeedSetting("Cursor speed", sensitivity, 0.2f..3f, onSensitivity, onSpeedFinished)
                                    SpeedSetting("Scroll speed", scrollSpeed, 0.1f..2f, onScrollSpeed, onSpeedFinished)
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    SettingsChoice("Phone orientation", calibratedRotationDegrees?.let(::calibratedRotationLabel)
                                        ?: axisRotationLabel(axisRotation),
                                        if (calibratedRotationDegrees != null) "Choose a preset to replace calibration."
                                        else "Rotate cursor movement and two-finger scrolling.",
                                        AxisRotation.entries.map { it to axisRotationLabel(it) }, onAxisRotation)
                                    OutlinedButton(onClick = onCalibrate, enabled = !calibratingOrientation) {
                                        Text(if (calibratingOrientation) "Follow the swipes on your phone"
                                            else if (calibratedRotationDegrees != null) "Recalibrate" else "Calibrate")
                                    }
                                    if (calibratedRotationDegrees == null) SupportingText(when (axisRotation) {
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
                                        ToggleSetting("Pocket mode", "Four-finger hold locks/unlocks. Four-finger tap opens/closes Launchpad.", pocketMode, onPocketMode)
                                        ToggleSetting("Black touchpad", "Keep the phone completely black while using the pad.", blackTouchpad, onBlackTouchpad)
                                        if (!blackTouchpad) SettingsChoice("Dim phone after", dimDelayLabel(dimDelaySec),
                                            "Only dims the phone. External display stays bright.",
                                            DIM_DELAY_CHOICES_SEC.map { it to dimDelayLabel(it) }, onDimDelay)
                                        else SupportingText(if (pocketMode)
                                            "The pad stays black. Launchpad and unlock gestures stay active."
                                        else "Touch stays active. Four-finger tap opens/closes Launchpad on your external display.")
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        Text("Popup keyboard", style = MaterialTheme.typography.titleSmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            FilterChip(selected = !dexKeyboard, onClick = { onDexKeyboard(false) },
                                                label = { Text("External display") })
                                            FilterChip(selected = dexKeyboard, onClick = { onDexKeyboard(true) },
                                                label = { Text("Phone") })
                                        }
                                        SupportingText(when {
                                            pocketMode -> "Keyboard stays off the phone while locked."
                                            !dexKeyboard -> "Keyboard opens on the external display."
                                            targetDisplay == null -> "Connect an external display first."
                                            dexKeyboardError -> "Phone keyboard unavailable on this display."
                                            else -> "Keyboard opens on the phone."
                                        })
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
                            extraControls()
                        }
                    }
                }
            }
            if (showInfo) SettingsInfoDialog(allDisplays, targetDisplay) { showInfo = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.Center) {
        tabs.forEach { title ->
            val active = title == selected
            Box(Modifier.selectable(selected = active, role = Role.Tab, onClick = { onSelect(title) })
                .heightIn(min = 48.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsSection(title: String, subtitle: String? = null, accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.width(4.dp).height(22.dp).background(accent, RoundedCornerShape(2.dp)))
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                if (subtitle != null) SupportingText(subtitle)
            }
            content()
        }
    }
}

@Composable
private fun SpeedSetting(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onFinished: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text("%.1f×".format(value), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, onValueChangeFinished = onFinished, valueRange = range)
    }
}

@Composable
internal fun ToggleSetting(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
internal fun <T> SettingsChoice(title: String, value: String, description: String,
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
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = !LocalNonFocusableSettings.current)) {
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
private fun SettingsInfoDialog(allDisplays: List<DisplayInfo>, targetDisplay: DisplayInfo?, onDismiss: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onDismiss)
    // Compose Dialog creates TYPE_APPLICATION windows, incompatible with a Presentation's
    // window context. Keep this modal inside the existing external settings window.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.92f).heightIn(max = maxHeight * 0.94f)
            .semantics { paneTitle = "Information" }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Information", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    SettingsSection("Gesture guide", accent = MaterialTheme.colorScheme.secondary) {
                        listOf(
                            "One-finger drag" to "Move cursor",
                            "One-finger tap / double-tap" to "Click / double-click",
                            "One-finger long-press" to "Right-click",
                            "Long-press, then drag" to "Drag / select text",
                            "Two-finger tap" to "Right-click",
                            "Two-finger drag" to "Scroll",
                            "Two-finger pinch" to "Zoom page",
                            "Three-finger tap" to "Your chosen shortcut",
                            "Four-finger tap" to "Open / close Launchpad",
                            "Four-finger hold · 0.8 seconds" to "Lock / unlock Pocket mode",
                        ).forEach { (gesture, action) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(gesture, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SupportingText("Four-finger gestures work while locked. Launchpad opens on the external display when connected. With haptics enabled: one pulse locks, two unlock.")
                    }
                    SettingsSection("Displays", accent = MaterialTheme.colorScheme.secondary) {
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
                        if (targetDisplay == null) SupportingText("Connect a display in desktop or extended mode.")
                    }
                    SupportingText("A fork of the original AR Touchpad project.")
                }
            }
        }
    }
}
