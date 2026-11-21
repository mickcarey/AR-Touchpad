package au.com.michaelcarey.artouchpad

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.view.Display
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
    // A non-focusable launcher stays behind the IME and leaves the current app focused.
    // The picker clears both flags so its search field can become the IME target.
    private val passiveFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
    private var dismissPicker: (() -> Unit)? = null

    private var dismissOutside: (() -> Unit)? = null
    private var pointerAtSystemBar = false
    private val unhandledClick = Runnable { if (isShowing) dismissOutside?.invoke() }

    // The OS handles clicks on its own bars before application overlays. Match clicks from
    // our touchpad to the event received here; clicks handled by the IME or a popup stay there.
    fun expectTouchpadClick() {
        val decor = window?.decorView ?: return
        decor.removeCallbacks(unhandledClick)
        if (decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true && !pointerAtSystemBar) return
        val hasPopup = WindowInspector.getGlobalWindowViews().any { view ->
            val type = (view.layoutParams as? WindowManager.LayoutParams)?.type
            view.display?.displayId == display.displayId && view.isShown && type != null &&
                type in WindowManager.LayoutParams.FIRST_SUB_WINDOW..WindowManager.LayoutParams.LAST_SUB_WINDOW
        }
        if (!hasPopup) decor.postDelayed(unhandledClick, android.view.ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            val decor = window?.decorView
            val bars = decor?.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars())
            if (bars != null) {
                val screen = window!!.windowManager.currentWindowMetrics.bounds
                pointerAtSystemBar = event.rawY < screen.top + bars.top || event.rawY >= screen.bottom - bars.bottom
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            window?.decorView?.removeCallbacks(unhandledClick)
        }
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (window?.decorView?.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true || pointerAtSystemBar) dismissOutside?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onStop() {
        window?.decorView?.removeCallbacks(unhandledClick)
        super.onStop()
    }

    override fun cancel() {
        dismissPicker?.let { it(); return }
        super.cancel()
    }

    private fun setPickerFocus(dismiss: (() -> Unit)?) {
        dismissPicker = dismiss
        window?.apply {
            if (dismiss != null) clearFlags(passiveFlags) else addFlags(passiveFlags)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(this, false)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(passiveFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
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
                    Launchpad(viewModel, onSwitchBuild, onRoomMeter, nonFocusable = true, onPickerFocus = ::setPickerFocus,
                        onOutsideHandler = { dismissOutside = it })
                }
            }
        }
        setContentView(content)
        setCanceledOnTouchOutside(false)
        window?.setTitle("AR Touchpad Launchpad")
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}
