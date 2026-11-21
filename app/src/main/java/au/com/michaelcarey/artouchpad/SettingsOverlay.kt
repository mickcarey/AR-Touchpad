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
    private var dismissPicker: (() -> Unit)? = null

    override fun cancel() {
        dismissPicker?.let { it(); return }
        super.cancel()
    }

    private fun setPickerFocus(dismiss: (() -> Unit)?) {
        dismissPicker = dismiss
        window?.apply {
            if (dismiss != null) clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            else addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.16f)
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
                    Launchpad(viewModel, onSwitchBuild, onRoomMeter, nonFocusable = true, onPickerFocus = ::setPickerFocus)
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
