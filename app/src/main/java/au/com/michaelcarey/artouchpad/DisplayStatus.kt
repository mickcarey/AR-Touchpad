package au.com.michaelcarey.artouchpad

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import au.com.michaelcarey.artouchpad.ui.theme.LaunchpadPalette
import kotlin.math.roundToInt

/** A small, click-through status window above the external task while Launchpad is closed. */
internal class DisplayStatus(
    private val context: Context,
    private val position: Int = Gravity.TOP or Gravity.END,
    private val windowTitle: String = "AR Touchpad display status",
) {
    private var window: WindowManager? = null
    private var label: TextView? = null
    private var shownOn: Int? = null
    private var style: Pair<String, Int>? = null

    fun show(display: Display, message: String, prefs: LaunchpadPreferences): Boolean {
        if (display.displayId != shownOn) hide()
        if (label == null) {
            if (!Settings.canDrawOverlays(context)) return false
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val external = context.createDisplayContext(display).createWindowContext(type, null)
            val manager = external.getSystemService(WindowManager::class.java)
            val view = TextView(external).apply {
                val dp = resources.displayMetrics.density
                setPadding((12 * dp).toInt(), (5 * dp).toInt(), (12 * dp).toInt(), (5 * dp).toInt())
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val params = WindowManager.LayoutParams(-2, -2, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply {
                gravity = position
                val inset = (8 * external.resources.displayMetrics.density).roundToInt()
                x = inset
                y = inset
                alpha = 0.75f
                title = windowTitle
            }
            try { manager.addView(view, params) } catch (_: WindowManager.InvalidDisplayException) { return false }
            catch (_: WindowManager.BadTokenException) { return false }
            catch (_: SecurityException) { return false }
            label = view
            window = manager
            shownOn = display.displayId
        }
        val view = label ?: return false
        val appearance = prefs.palette to prefs.textPercent
        if (style != appearance) {
            val colors = LaunchpadPalette.named(prefs.palette)
            view.background = GradientDrawable().apply {
                setColor(colors.background.toArgb())
                cornerRadius = 16 * view.resources.displayMetrics.density
            }
            view.setTextColor(colors.accent.toArgb())
            view.textSize = 16f * prefs.textPercent / 100
            style = appearance
        }
        view.text = message
        view.contentDescription = message.replace("🔒", "Pocket locked")
        return true
    }

    fun hide() {
        label?.let { if (it.isAttachedToWindow) window?.removeViewImmediate(it) }
        label = null
        window = null
        shownOn = null
        style = null
    }
}
