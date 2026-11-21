package au.com.michaelcarey.artouchpad.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.flow.filterNot
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-header").padding(horizontal = LocalLaunchpadGutter.current, vertical = 12.dp)) {
        val inlineMedia = maxWidth >= 860.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { LaunchpadDate() }
                if (inlineMedia) MediaButtons(mediaEnabled, onMediaKey, mediaState)
                // Equal side widths keep media centered in the whole header, independent of title/actions.
                Row(if (inlineMedia) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    if (availableBuild != null) {
                        IconButton(onClick = { onSwitchBuild(availableBuild) }, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_switch_build),
                                contentDescription = "Switch to ${availableBuild.name}")
                        }
                    }
                    IconButton(onClick = onInformation, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(R.drawable.ic_settings_info), contentDescription = "Information")
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(if (settingsOpen) R.drawable.ic_home else R.drawable.ic_settings_cog),
                            contentDescription = if (settingsOpen) "Home" else "Settings",
                            tint = if (settingsOpen) Color.White else LocalContentColor.current)
                    }
                }
            }
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
internal fun NowPlayingStrip(enabled: Boolean, state: MediaState, onOpenPlayer: (MediaState) -> Unit,
    loadArtwork: suspend (String) -> Bitmap?) {
    val title = state.title.orEmpty().trim()
    val subtitle = state.subtitle.orEmpty().trim()
    AnimatedVisibility(visible = enabled && title.isNotEmpty(),
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top)) {
        Column(Modifier.fillMaxWidth().testTag("now-playing")) {
            val source = state.sourcePackage
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = LocalLaunchpadGutter.current, vertical = 8.dp),
                contentAlignment = Alignment.Center) {
                val size = if (maxWidth >= 480.dp) 56.dp else 44.dp
                if (source != null) key(source) { PlaybackAppIcon(source, Modifier.align(Alignment.CenterStart).size(size)) }
                state.artworkKey?.let { artworkKey ->
                    key(artworkKey) {
                        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(artworkKey) { bitmap = withContext(Dispatchers.IO) { loadArtwork(artworkKey) } }
                        bitmap?.let {
                            Image(it.asImageBitmap(), if (source?.startsWith("com.spotify.") == true) "Album cover" else "Jellyfin cover",
                                Modifier.align(Alignment.CenterEnd).size(size)
                                    .clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Fit, alignment = Alignment.CenterEnd)
                        }
                    }
                }
                val playerAction = if (source == null) Modifier else {
                    val playerInteractions = remember { MutableInteractionSource() }
                    val feedback = remember(playerInteractions) {
                        object : InteractionSource {
                            override val interactions = playerInteractions.interactions.filterNot {
                                it is HoverInteraction.Enter || it is HoverInteraction.Exit
                            }
                        }
                    }
                    Modifier.indication(feedback, LocalIndication.current).clickable(interactionSource = playerInteractions,
                        indication = null, role = Role.Button, onClickLabel = "Open playback app") { onOpenPlayer(state) }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = if (source == null) 0.dp else size + 8.dp)
                    .then(playerAction).heightIn(min = if (source == null) 0.dp else size, max = 156.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle.isNotEmpty() && subtitle != title) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun PlaybackAppIcon(packageName: String, modifier: Modifier) {
    val pm = LocalContext.current.packageManager
    var app by remember(pm, packageName) { mutableStateOf<Pair<ImageBitmap, String>?>(null) }
    LaunchedEffect(pm, packageName) {
        app = withContext(Dispatchers.IO) {
            try {
                val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() to info.loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) { null }
        }
    }
    app?.let { (icon, label) ->
        Image(icon, contentDescription = label, modifier = modifier)
    }
}
