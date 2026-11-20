package au.com.michaelcarey.artouchpad

import android.content.Context

internal enum class TouchpadBuild(val packageId: String) {
    Debug("au.com.michaelcarey.artouchpad.debug"),
    Prod("au.com.michaelcarey.artouchpad");

    fun launchIntent(context: Context) = context.packageManager.getLaunchIntentForPackage(packageId)

    companion object {
        val current = entries.find { it.packageId == BuildConfig.APPLICATION_ID }
        val alternative = when (current) {
            Debug -> Prod
            Prod -> Debug
            null -> null
        }
    }
}
