package au.com.michaelcarey.artouchpad

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import au.com.michaelcarey.artouchpad.ui.Launchpad
import au.com.michaelcarey.artouchpad.ui.theme.ARTouchpadTheme

/** A touchable overlay that leaves the external task resumed, visible and keyboard-focused. */
internal class SettingsOverlay(
    private val owner: ComponentActivity,
    val display: Display,
    private val viewModel: TouchpadViewModel,
    private val onSwitchBuild: (TouchpadBuild) -> Unit,
    private val onRoomMeter: (Boolean) -> Unit,
) : Dialog(ContextThemeWrapper(owner.createDisplayContext(display)
    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null), R.style.Theme_ARTouchpad)) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            setDimAmount(0.16f)
            attributes = attributes.apply {
                blurBehindRadius = (8f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            }
        }
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent {
                ARTouchpadTheme(viewModel.state.collectAsState().value.launchpad) {
                    Launchpad(viewModel, onSwitchBuild, onRoomMeter, nonFocusable = true)
                }
            }
        }
        setContentView(content)
        setCanceledOnTouchOutside(false)
        window?.setTitle("AR Touchpad Launchpad")
    }

    override fun onStart() {
        super.onStart()
        val metrics = context.resources.displayMetrics
        window?.setGravity(Gravity.CENTER)
        window?.setLayout(minOf((1120 * metrics.density).toInt(), (metrics.widthPixels * 0.92f).toInt()),
            minOf((780 * metrics.density).toInt(), (metrics.heightPixels * 0.9f).toInt()))
    }
}
