package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Display
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinnedApps(pinned: Set<String>, displayId: Int?, library: Boolean,
    onAdd: () -> Unit, onPins: (Set<String>) -> Unit, onLaunched: () -> Unit) {
    val context = LocalContext.current
    val launcher = remember { AppLauncher(InstalledApps) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
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
    SettingsSection(if (library) "Installed apps" else "Pinned apps",
        if (library) "Select an app to pin it or remove it." else null) {
        val items = apps.orEmpty()
        val colors = MaterialTheme.colorScheme
        val iconSize = 52.dp
        val tileShape = RoundedCornerShape(14.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!library) AppTile("Add App", Modifier.clickable(role = Role.Button, onClick = onAdd)) {
                Box(Modifier.size(iconSize).drawBehind {
                    val stroke = 1.5.dp.toPx()
                    drawRoundRect(colors.primary.copy(alpha = 0.8f), Offset(stroke / 2, stroke / 2),
                        Size(size.width - stroke, size.height - stroke), CornerRadius(14.dp.toPx()),
                        style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                    val arm = 7.dp.toPx()
                    drawLine(colors.primary, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
                    drawLine(colors.primary, center - Offset(0f, arm), center + Offset(0f, arm), stroke)
                })
            }
            for (app in if (library) items else items.filter { it.id in pinned }) {
                val selected = app.id in pinned
                val action = if (library) Modifier.toggleable(selected, role = Role.Checkbox) {
                    onPins(if (selected) pinned - app.id else pinned + app.id)
                } else Modifier.clickable(role = Role.Button) {
                    try {
                        launcher.launch(context, app, displayId ?: Display.DEFAULT_DISPLAY)
                        onLaunched()
                    } catch (_: android.content.ActivityNotFoundException) {
                        error = "${app.label} is no longer available."
                        revision++
                    } catch (_: SecurityException) {
                        error = "Android couldn’t open ${app.label} on this display."
                    } catch (_: IllegalArgumentException) {
                        error = "The selected display is unavailable."
                    }
                }
                AppTile(app.label, action) {
                    Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                        Image(app.icon.asImageBitmap(), null, Modifier.fillMaxSize())
                        if (library && selected) Text("✓", Modifier.align(Alignment.BottomEnd)
                            .background(colors.primary, tileShape).padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                    }
                }
            }
        }
        if (apps == null) SupportingText("Loading installed apps…")
        if (library && apps != null) {
            val missing = pinned - items.map { it.id }.toSet()
            if (missing.isNotEmpty()) TextButton(onClick = { onPins(pinned - missing) }) {
                Text("Remove unavailable pins (${missing.size})")
            }
        }
        error?.let { Text(it, color = colors.error) }
    }
}

@Composable
private fun AppTile(label: String, action: Modifier, icon: @Composable () -> Unit) {
    Column(Modifier.width(88.dp).then(action).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        icon()
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
