package com.pgratz.artouchpad.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.artouchpad.R

/** Outside the settings scroll area. Compact windows keep the controls on a second header row. */
@Composable
internal fun SettingsHeader(
    mediaEnabled: Boolean,
    onMediaKey: (Int) -> Unit,
    onInformation: () -> Unit,
    onDismiss: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("settings-header").padding(horizontal = 16.dp, vertical = 12.dp)) {
        val inlineMedia = maxWidth >= 768.dp
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings", Modifier.weight(1f), fontSize = 28.sp, fontWeight = FontWeight.Medium)
                if (inlineMedia) MediaButtons(mediaEnabled, onMediaKey)
                // Equal side widths keep media centered in the whole header, independent of title/actions.
                Row(if (inlineMedia) Modifier.weight(1f) else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    IconButton(onClick = onInformation) {
                        Icon(painterResource(R.drawable.ic_settings_info), contentDescription = "Information")
                    }
                    Button(onClick = onDismiss) { Text("Done") }
                }
            }
            if (!inlineMedia) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MediaButtons(mediaEnabled, onMediaKey)
            }
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
private fun MediaButtons(enabled: Boolean, onMediaKey: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.Center) {
        MediaControl.entries.forEach { control ->
            IconButton(onClick = { onMediaKey(control.keyCode) }, enabled = enabled,
                modifier = Modifier.size(48.dp)) {
                Icon(painterResource(control.icon), contentDescription = control.description,
                    modifier = Modifier.size(24.dp))
            }
        }
    }
}
