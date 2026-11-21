package au.com.michaelcarey.artouchpad.ui

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.Display
import androidx.compose.ui.platform.LocalContext
import au.com.michaelcarey.artouchpad.JellyfinPlaybackListener
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
        if (prefs.showTitle) {
            val context = LocalContext.current
            SupportingText("Allow Android notification access to show the series, episode number or movie year from Jellyfin’s playback notification.")
            TextButton(onClick = {
                val component = ComponentName(context, JellyfinPlaybackListener::class.java)
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                try {
                    context.startActivity(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
                } catch (_: android.content.ActivityNotFoundException) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options)
                }
            }) { Text("Jellyfin notification access") }
        }
        ToggleSetting("Room sound meter", "Use the phone mic to show live and recent peak levels in dBFS. Audio stays on device and is never saved.",
            prefs.roomMeter, onMeter)
        if (prefs.roomMeter) quietReference()
        SupportingText("For a bedside comparison, place the phone near the listener with its mic uncovered. Set a quiet reference before playback to see the change in dB. dBFS measures microphone signal level. Absolute room loudness in dB SPL requires acoustic calibration.")
    }
}
