package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.Display
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
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
internal fun PinnedApps(pinned: Set<String>, displayId: Int?, onPins: (Set<String>) -> Unit, onLaunched: () -> Unit) {
    val context = LocalContext.current
    val launcher = remember { AppLauncher(InstalledApps) }
    var revision by remember { mutableIntStateOf(0) }
    var managing by remember { mutableStateOf(false) }
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
    SettingsSection("Pinned apps") {
        val items = apps
        if (items == null) SupportingText("Loading installed apps…")
        else {
            val selected = items.filter { it.id in pinned }
            if (!managing && selected.isEmpty()) SupportingText("Pin an app from your phone to launch it here.")
            if (managing || selected.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (app in if (managing) items else selected) {
                    val icon: @Composable () -> Unit = {
                        Image(app.icon.asImageBitmap(), null, Modifier.size(28.dp))
                    }
                    if (managing) FilterChip(selected = app.id in pinned, leadingIcon = icon,
                        onClick = { onPins(if (app.id in pinned) pinned - app.id else pinned + app.id) },
                        label = { Text(app.label) })
                    else Surface(onClick = {
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
                    }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.widthIn(min = 112.dp, max = 164.dp).padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Image(app.icon.asImageBitmap(), null, Modifier.size(40.dp))
                            Text(app.label, style = MaterialTheme.typography.labelLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
            val missing = pinned - items.map { it.id }.toSet()
            if (managing && missing.isNotEmpty()) TextButton(onClick = { onPins(pinned - missing) }) {
                Text("Remove unavailable pins (${missing.size})")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { managing = !managing; error = null }) {
                Text(if (managing) "Finish pinning" else if (selected.isEmpty()) "Choose apps" else "Edit pins")
            }
        }
    }
}
