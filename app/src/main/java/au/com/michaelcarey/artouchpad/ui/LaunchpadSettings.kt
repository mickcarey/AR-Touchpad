package au.com.michaelcarey.artouchpad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.michaelcarey.artouchpad.LaunchpadPreferences
import au.com.michaelcarey.artouchpad.ui.theme.LaunchpadPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LaunchpadSettings(prefs: LaunchpadPreferences, update: ((LaunchpadPreferences) -> LaunchpadPreferences) -> Unit,
    onMeter: (Boolean) -> Unit, quietReference: @Composable () -> Unit) {
    SettingsSection("Appearance") {
        Text("Colour scheme", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LaunchpadPalette.entries.forEach { palette ->
                FilterChip(selected = palette.label == prefs.palette, onClick = { update { it.copy(palette = palette.label) } },
                    leadingIcon = { Spacer(Modifier.size(14.dp).background(palette.accent, CircleShape)) },
                    label = { Text(palette.label) })
            }
        }
        SettingsChoice("Text size", "${prefs.textPercent}%", "Applies on the phone and external display.",
            LaunchpadPreferences.sizes.map { it to "$it%" }) { size -> update { it.copy(textPercent = size) } }
    }
    SettingsSection("Media & sound") {
        ToggleSetting("Now playing", "Show the title and subtitle supplied by the active player.", prefs.showTitle) { enabled ->
            update { it.copy(showTitle = enabled) }
        }
        ToggleSetting("Room sound meter", "Use the phone mic to show live and recent peak levels in dBFS. Audio stays on device and is never saved.",
            prefs.roomMeter, onMeter)
        if (prefs.roomMeter) quietReference()
        SupportingText("For a bedside comparison, place the phone near the listener with its mic uncovered. Set a quiet reference before playback to see the change in dB. dBFS measures microphone signal level. Absolute room loudness in dB SPL requires acoustic calibration.")
    }
}
