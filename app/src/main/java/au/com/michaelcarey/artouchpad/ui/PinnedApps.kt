package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import au.com.michaelcarey.artouchpad.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberAppCatalog(launcher: AppLauncher): List<AppShortcut>? {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { revision++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    var apps by remember(context) { mutableStateOf<List<AppShortcut>?>(null) }
    LaunchedEffect(context, revision) {
        apps = withContext(Dispatchers.IO) { launcher.catalog(context) }
    }
    return apps
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PinnedApps(apps: List<AppShortcut>?, pinned: Set<String>, onAdd: () -> Unit,
    onRequestRemoval: (ShortcutRemoval) -> Unit, onLaunch: (AppShortcut) -> Unit) {
    var menuApp by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val items = apps.orEmpty()
            val colors = MaterialTheme.colorScheme
            val iconSize = 52.dp
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTile("Add App", Modifier.clickable(role = Role.Button, onClick = onAdd)) {
                    Box(Modifier.size(iconSize).drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawCircle(colors.primary.copy(alpha = 0.8f), radius = (size.minDimension - stroke) / 2,
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        val arm = 7.dp.toPx()
                        drawLine(colors.primary, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
                        drawLine(colors.primary, center - Offset(0f, arm), center + Offset(0f, arm), stroke)
                    })
                }
                for (app in items.filter { it.id in pinned }) {
                    Box {
                        val actions = Modifier.pointerInput(app.id) {
                            awaitEachGesture {
                                val press = awaitPointerEvent(PointerEventPass.Initial)
                                if (press.type == PointerEventType.Press && press.buttons.isSecondaryPressed) {
                                    press.changes.forEach { it.consume() }
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    menuApp = app.id
                                }
                            }
                        }
                        AppTile(app.label, actions.combinedClickable(role = Role.Button,
                            onLongClickLabel = "Pin options", onLongClick = { menuApp = app.id },
                            onClick = {
                                try {
                                    onLaunch(app)
                                } catch (_: android.content.ActivityNotFoundException) {
                                    error = "${app.label} is no longer available."
                                } catch (_: SecurityException) {
                                    error = "Android couldn’t open ${app.label} on this display."
                                } catch (_: IllegalArgumentException) {
                                    error = "The selected display is unavailable."
                                }
                            })) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(iconSize))
                        }
                        DropdownMenu(expanded = menuApp == app.id, onDismissRequest = { menuApp = null },
                            properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
                            DropdownMenuItem(text = { Text("Remove shortcut") }, onClick = {
                                menuApp = null
                                onRequestRemoval(ShortcutRemoval(setOf(app.id), app.label))
                            })
                        }
                    }
                }
            }
            if (apps == null) SupportingText("Loading installed apps…")
            if (apps != null) {
                val missing = pinned - items.map { it.id }.toSet()
                if (missing.isNotEmpty()) TextButton(onClick = { onRequestRemoval(ShortcutRemoval(missing)) }) {
                    Text("Remove unavailable pins (${missing.size})")
                }
            }
            error?.let { Text(it, color = colors.error) }
        }
    }
}

@Composable
private fun AppTile(label: String, action: Modifier, icon: @Composable () -> Unit) {
    Column(Modifier.width(88.dp).then(action).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

internal data class ShortcutRemoval(val ids: Set<String>, val label: String? = null)

@Composable
internal fun ConfirmShortcutRemoval(removal: ShortcutRemoval, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val title = removal.label?.let { "Remove $it from quick launch?" } ?: "Remove unavailable shortcuts?"
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.42f))
            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = onCancel))
        Surface(Modifier.padding(16.dp).widthIn(max = 440.dp).fillMaxWidth().semantics { paneTitle = title },
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(if (removal.label != null) "The app stays installed on your device. You can add its shortcut again anytime."
                    else "This clears the unavailable shortcuts from quick launch. Apps on your device are unaffected.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = onConfirm) { Text("Remove") }
                }
            }
        }
    }
}
