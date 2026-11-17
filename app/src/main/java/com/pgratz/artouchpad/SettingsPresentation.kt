package com.pgratz.artouchpad

import android.app.Presentation
import android.os.Bundle
import android.view.Display
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pgratz.artouchpad.ui.TouchpadSettings
import com.pgratz.artouchpad.ui.theme.ARTouchpadTheme

/** Shares the phone's model and mouse connection; opening settings never creates another service. */
class SettingsPresentation(
    private val owner: ComponentActivity,
    display: Display,
    private val viewModel: TouchpadViewModel,
) : Presentation(owner, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.decorView?.apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
        }
        val content = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { ARTouchpadTheme { TouchpadSettings(viewModel) } }
        }
        setContentView(content)
        setCanceledOnTouchOutside(false)
        window?.setTitle("AR Touchpad settings")
    }

    override fun onStart() {
        super.onStart()
        val metrics = context.resources.displayMetrics
        window?.setLayout(minOf((640 * metrics.density).toInt(), (metrics.widthPixels * 0.9f).toInt()),
            (metrics.heightPixels * 0.9f).toInt())
    }
}
