package au.com.michaelcarey.artouchpad.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import au.com.michaelcarey.artouchpad.R
import au.com.michaelcarey.artouchpad.MediaState
import au.com.michaelcarey.artouchpad.TouchpadBuild
import kotlin.math.roundToInt

/** Outside the settings scroll area. Compact windows keep the controls on a second header row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsHeader(
    mediaEnabled: Boolean,
    onMediaKey: (Int) -> Unit,
    onInformation: () -> Unit,
    mediaState: MediaState = MediaState(),
    availableBuild: TouchpadBuild? = null,
    onSwitchBuild: (TouchpadBuild) -> Unit = {},
    indicators: @Composable () -> Unit = {},
    settingsOpen: Boolean = false,
    onSettings: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-header").padding(horizontal = 16.dp, vertical = 12.dp)) {
        val inlineMedia = maxWidth >= 860.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Launchpad", Modifier.align(Alignment.CenterVertically), fontSize = 28.sp,
                            fontWeight = FontWeight.Medium)
                        IconButton(onClick = onInformation, modifier = Modifier.align(Alignment.CenterVertically)) {
                            Icon(painterResource(R.drawable.ic_settings_info), contentDescription = "Information")
                        }
                        if (TouchpadBuild.current == TouchpadBuild.Debug) {
                            Surface(Modifier.align(Alignment.CenterVertically), shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                                Text("Debug", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    if (inlineMedia) LaunchpadDate()
                }
                if (inlineMedia) MediaButtons(mediaEnabled, onMediaKey, mediaState)
                // Equal side widths keep media centered in the whole header, independent of title/actions.
                Row(if (inlineMedia) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    if (availableBuild != null) {
                        IconButton(onClick = { onSwitchBuild(availableBuild) }) {
                            Icon(painterResource(R.drawable.ic_switch_build),
                                contentDescription = "Switch to ${availableBuild.name}")
                        }
                    }
                    IconToggleButton(checked = settingsOpen, onCheckedChange = { onSettings() }) {
                        Icon(painterResource(R.drawable.ic_settings_cog), contentDescription = "Settings",
                            tint = if (settingsOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                    }
                }
            }
            if (!inlineMedia) LaunchpadDate()
            if (!inlineMedia) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MediaButtons(mediaEnabled, onMediaKey, mediaState)
            }
            indicators()
        }
    }
}

private enum class MediaControl(val icon: Int, val description: String, val keyCode: Int) {
    PREVIOUS(R.drawable.ic_media_previous, "Previous track", KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    PLAY_PAUSE(R.drawable.ic_media_play_pause, "Play / pause", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT(R.drawable.ic_media_next, "Next track", KeyEvent.KEYCODE_MEDIA_NEXT),
    VOLUME_DOWN(R.drawable.ic_media_volume_down, "Volume down", KeyEvent.KEYCODE_VOLUME_DOWN),
    MUTE(R.drawable.ic_media_mute, "Mute / unmute", KeyEvent.KEYCODE_VOLUME_MUTE),
    VOLUME_UP(R.drawable.ic_media_volume_up, "Volume up", KeyEvent.KEYCODE_VOLUME_UP),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaButtons(enabled: Boolean, onMediaKey: (Int) -> Unit, state: MediaState) {
    Column(Modifier.widthIn(max = 288.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(horizontalArrangement = Arrangement.Center) {
            MediaControl.entries.forEach { control ->
                val (icon, description, key) = when (control) {
                    MediaControl.PLAY_PAUSE -> when (state.playing) {
                        true -> Triple(R.drawable.ic_media_pause, "Pause", KeyEvent.KEYCODE_MEDIA_PAUSE)
                        false -> Triple(R.drawable.ic_media_play, "Play", KeyEvent.KEYCODE_MEDIA_PLAY)
                        null -> Triple(control.icon, control.description, control.keyCode)
                    }
                    MediaControl.MUTE -> when (state.muted) {
                        true -> Triple(R.drawable.ic_media_unmute, "Unmute", control.keyCode)
                        false -> Triple(control.icon, "Mute", control.keyCode)
                        null -> Triple(control.icon, control.description, control.keyCode)
                    }
                    else -> Triple(control.icon, control.description, control.keyCode)
                }
                IconButton(onClick = { onMediaKey(key) }, enabled = enabled,
                    modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(icon), contentDescription = description,
                        modifier = Modifier.size(24.dp), tint = if (control == MediaControl.MUTE && state.muted == true)
                            MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
        }
        if (enabled && state.maxVolume > 0) {
            Row(Modifier.width(260.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinearProgressIndicator(progress = { state.volumeFraction }, modifier = Modifier.weight(1f)
                    .testTag("media-volume-level"))
                Text(if (state.muted == true) "Muted" else "${(state.volumeFraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
internal fun NowPlayingStrip(enabled: Boolean, state: MediaState) {
    val title = state.title.orEmpty().trim()
    val subtitle = state.subtitle.orEmpty().trim()
    AnimatedVisibility(visible = enabled && title.isNotEmpty(),
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
        Surface(Modifier.fillMaxWidth().testTag("now-playing"), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.heightIn(max = 156.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Now playing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle.isNotEmpty() && subtitle != title) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
