package au.com.michaelcarey.artouchpad.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import au.com.michaelcarey.artouchpad.DisplayInfo
import au.com.michaelcarey.artouchpad.R

@Composable
internal fun PanelCloseButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

private val pointerGestures = listOf(
    "One-finger drag" to "Move cursor",
    "One-finger tap / double-tap" to "Click / double-click",
    "One-finger long-press" to "Right-click",
    "Long-press, then drag" to "Drag / select text",
    "Two-finger tap" to "Right-click",
    "Two-finger drag" to "Scroll",
    "Two-finger pinch" to "Zoom page",
)
private val shortcuts = listOf(
    "Three-finger tap" to "Your chosen shortcut",
    "Four-finger tap" to "Open / close Launchpad",
    "Four-finger hold · 0.8 seconds" to "Lock / unlock Pocket mode",
)

@Composable
internal fun SettingsInfoDialog(allDisplays: List<DisplayInfo>, targetDisplay: DisplayInfo?, onDismiss: () -> Unit) {
    if (LocalOnBackPressedDispatcherOwner.current != null) BackHandler(onBack = onDismiss)
    // Keep all layers inside the existing external window and its rounded clip.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.46f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
        Surface(Modifier.padding(12.dp).widthIn(max = 980.dp).fillMaxWidth().wrapContentHeight()
            .semantics { paneTitle = "Information" }, shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Information", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    PanelCloseButton("Close information", onDismiss)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    val wide = maxWidth >= 700.dp
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (wide) 20.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val secondary: @Composable () -> Unit = {
                            GestureCard("Shortcuts", shortcuts) {
                                SupportingText("Four-finger gestures also work in Pocket mode. With haptics enabled, one pulse means locked and two mean unlocked.")
                            }
                            DisplayCard(allDisplays, targetDisplay)
                        }
                        if (wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Box(Modifier.weight(1f)) { GestureCard("Pointer gestures", pointerGestures) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { secondary() }
                            }
                        } else {
                            GestureCard("Pointer gestures", pointerGestures)
                            secondary()
                        }
                        SupportingText("A fork of the original AR Touchpad project.")
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureCard(title: String, entries: List<Pair<String, String>>, note: @Composable () -> Unit = {}) {
    SettingsSection(title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEachIndexed { index, (gesture, action) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(gesture, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(action, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        note()
    }
}

@Composable
private fun DisplayCard(displays: List<DisplayInfo>, target: DisplayInfo?) {
    SettingsSection("Displays", accent = MaterialTheme.colorScheme.secondary) {
        if (displays.isEmpty()) SupportingText("No displays detected.")
        displays.forEachIndexed { index, display ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(display.name, style = MaterialTheme.typography.titleSmall)
                    SupportingText("${display.width} × ${display.height}")
                }
                if (display.id == target?.id) Text("Active", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        if (target == null) SupportingText("Connect a display in desktop or extended mode.")
    }
}
