package com.pgratz.artouchpad.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pgratz.artouchpad.*

@Composable
internal fun ExtraSettings(viewModel: TouchpadViewModel, state: TouchpadState) {
    var saved by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    SettingsSection("Profiles", "Load a preset or save your current preferences for later.") {
        SettingsChoice("Profile", state.selectedProfile.label,
            "Profiles include pointer, gesture, keyboard and black-touchpad preferences. Pocket lock and calibration stay as they are.",
            UsageProfile.entries.map { it to it.label }) {
            viewModel.applyProfile(it); saved = false
        }
        OutlinedButton(onClick = { viewModel.saveProfile(state.selectedProfile); saved = true }) {
            Text("Save current preferences to ${state.selectedProfile.label}")
        }
        if (saved) SupportingText("Profile saved.")
    }
    SettingsSection("Precision & gestures") {
        SettingsChoice("Smoothing", state.smoothing.label, "Light reduces jitter. Off follows your finger directly.",
            Smoothing.entries.map { it to it.label }) { value -> viewModel.updateInputSettings { it.copy(smoothing = value) } }
        ToggleSetting("Precision mode", "Use 30% of your cursor speed for small targets.", state.precision) { value ->
            viewModel.updateInputSettings { it.copy(precision = value) }
        }
        ToggleSetting("Copy after dragging", "Send Copy after a completed drag. Leave off for windows, sliders and objects.", state.autoCopy) { value ->
            viewModel.updateInputSettings { it.copy(autoCopy = value) }
        }
        ToggleSetting("Drag lock", "After a hold and drag, lift to reposition. Tap to release. A second finger cancels without copying.", state.dragLock) { value ->
            viewModel.updateInputSettings { it.copy(dragLock = value) }
        }
        SettingsChoice("Hold before dragging", "${state.holdMs} ms", "Also sets the long-press right-click delay. Pocket unlock remains 0.8 seconds.",
            listOf(300L, 450L, 600L, 800L, 1000L, 1200L).map { it to "$it ms" }) { value ->
            viewModel.updateInputSettings { it.copy(holdMs = value) }
        }
        SettingsChoice("Three-finger tap", state.threeFingerAction.label, "Choose a shortcut or disable it.",
            ThreeFingerAction.entries.map { it to it.label }) { value ->
            viewModel.updateInputSettings { it.copy(threeFingerAction = value) }
        }
    }
    SettingsSection("Display & recovery") {
        val external = state.allDisplays.filter { it.id != 0 }
        val preferred = external.firstOrNull { it.uniqueId == state.preferredDisplay }
        SettingsChoice("Target display", preferred?.name ?: if (state.preferredDisplay == null) "Automatic" else "Saved display unavailable",
            "Remembers your display across reconnects. Uses another connected display while the saved one is absent.",
            listOf(null to "Automatic") + external.map { it.uniqueId to "${it.name} · ${it.width} × ${it.height}" },
            viewModel::selectTargetDisplay)
        SupportingText(state.connectionMessage)
        OutlinedButton(onClick = viewModel::retryConnection) { Text("Retry connection") }
        HorizontalDivider()
        if (confirmReset) {
            SupportingText("Reset preferences, saved profiles, display choice and calibration? This also unlocks Pocket mode.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                Button(onClick = { viewModel.resetDefaults(); confirmReset = false; saved = false }) { Text("Reset all settings") }
            }
        } else TextButton(onClick = { confirmReset = true }) { Text("Reset defaults…") }
    }
}
