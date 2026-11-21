package au.com.michaelcarey.artouchpad

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ComponentName
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** Providers expose stable shortcut IDs and launch intents. Only IDs are persisted. */
internal interface LauncherExtension {
    val namespace: String
    fun entries(context: Context): List<AppShortcut>
    fun pinnedEntries(context: Context, ids: Set<String>) = entries(context).filter { it.id in ids }
}

internal data class AppShortcut(val id: String, val label: String, val icon: Bitmap, val intent: Intent)

internal object InstalledApps : LauncherExtension {
    override val namespace = "android"
    override fun entries(context: Context): List<AppShortcut> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, PackageManager.ResolveInfoFlags.of(0)).mapNotNull { info ->
            val activity = info.activityInfo
            if (!activity.exported || activity.packageName == context.packageName) return@mapNotNull null
            shortcut(ComponentName(activity.packageName, activity.name), info.loadLabel(pm), info.loadIcon(pm))
        }
    }

    override fun pinnedEntries(context: Context, ids: Set<String>): List<AppShortcut> {
        val pm = context.packageManager
        return ids.mapNotNull { id ->
            val component = ComponentName.unflattenFromString(id) ?: return@mapNotNull null
            try {
                val info = pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
                if (info.exported && info.enabled && info.applicationInfo.enabled)
                    shortcut(component, info.loadLabel(pm), info.loadIcon(pm)) else null
            } catch (_: PackageManager.NameNotFoundException) { null }
        }
    }

    private fun shortcut(component: ComponentName, label: CharSequence, icon: android.graphics.drawable.Drawable) =
        AppShortcut(component.flattenToShortString(), label.toString(), icon.toBitmap(96, 96), Intent.makeMainActivity(component))
}

internal data class LauncherCatalog(val shortcuts: List<AppShortcut>? = null, val complete: Boolean = false)

internal class AppLauncher(private vararg val extensions: LauncherExtension) {
    init { require(extensions.distinctBy { it.namespace }.size == extensions.size) }
    fun catalog(context: Context, pinned: Set<String>? = null) = extensions.flatMap { extension ->
        val entries = if (pinned == null) extension.entries(context) else {
            val prefix = "${extension.namespace}/"
            val ids = pinned.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
            if (ids.isEmpty()) emptyList() else extension.pinnedEntries(context, ids)
        }
        entries.map { it.copy(id = "${extension.namespace}/${it.id}") }
    }.distinctBy { it.id }.sortedBy { it.label.lowercase() }

    fun openPlayer(context: Context, media: MediaState, displayId: Int): Boolean {
        val source = media.sourcePackage ?: return false
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val activity = media.sessionActivity
        if (activity != null && activity.isActivity && activity.creatorPackage == source) {
            options.pendingIntentBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            try {
                activity.send(context, 0, null, null, null, null, options.toBundle())
                return true
            } catch (_: android.app.PendingIntent.CanceledException) {
                // The browser tab or player activity expired. Reopen its owning app.
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(source) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        return true
    }

    fun launch(context: Context, shortcut: AppShortcut, displayId: Int) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(Intent(shortcut.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
    }
}

/** Stay subscribed while the touchpad is running, including while the launcher is closed. */
internal fun catalogChanges(context: Context) = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { trySend(Unit) }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
        addAction(Intent.ACTION_PACKAGE_CHANGED)
        addDataScheme("package")
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
    trySend(Unit)
    awaitClose { context.unregisterReceiver(receiver) }
}.conflate()
