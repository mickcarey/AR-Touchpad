package au.com.michaelcarey.artouchpad

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap

/** Providers expose stable shortcut IDs and launch intents. Only IDs are persisted. */
internal interface LauncherExtension {
    val namespace: String
    fun entries(context: Context): List<AppShortcut>
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
            val component = ComponentName(activity.packageName, activity.name)
            AppShortcut(component.flattenToShortString(), info.loadLabel(pm).toString(),
                info.loadIcon(pm).toBitmap(96, 96), Intent.makeMainActivity(component))
        }
    }
}

internal class AppLauncher(private vararg val extensions: LauncherExtension) {
    init { require(extensions.distinctBy { it.namespace }.size == extensions.size) }
    fun catalog(context: Context) = extensions.flatMap { extension ->
        extension.entries(context).map { it.copy(id = "${extension.namespace}/${it.id}") }
    }.distinctBy { it.id }.sortedBy { it.label.lowercase() }

    fun launch(context: Context, shortcut: AppShortcut, displayId: Int) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(Intent(shortcut.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
    }
}
