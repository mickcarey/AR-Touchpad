package com.pgratz.artouchpad

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.PointerIcon
import java.util.function.Consumer

/** Changes the icon, not the device. Observes windows only while Pocket mode is locked.
 * Android requires the input-channel token of the window currently under this mouse.
 * WindowInfosListenerForTest provides a framework-created listener (the base listener is
 * hidden/abstract); its raw snapshot exposes input tokens rather than app window tokens.
 * Nothing here sends touches, changes focus, or changes another device's configuration.
 */
internal class CursorVisibility(
    context: Context,
    private val handler: Handler,
    private val setIcon: (PointerIcon, Int, Int, IBinder?) -> Boolean,
) {
    private val factoryClass = Class.forName("android.window.WindowInfosListenerForTest")
    private val listenerClass = Class.forName("android.window.WindowInfosListener")
    private val add = factoryClass.getMethod("addWindowInfosListener", Consumer::class.java)
    private val remove = factoryClass.getMethod("removeWindowInfosListener", Consumer::class.java)
    private val listeners = factoryClass.getDeclaredField("mListeners").apply { isAccessible = true }
    private val snapshot = listenerClass.getMethod("register")
    private val transparent = PointerIcon.create(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 0f, 0f)
    private val arrow = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_ARROW)
    private var factory: Any? = null
    private var rawListener: Any? = null
    private var displayId = -1
    private var deviceId = -1
    private var lastToken: IBinder? = null
    private var retries = 0
    @Volatile private var hidden = false
    private val consumer = Consumer<List<*>> {
        // Native callbacks arrive off-main. Coalesce them; no polling while unlocked.
        if (hidden) {
            handler.removeCallbacks(reapply)
            handler.post(reapply)
        }
    }
    private val reapply = object : Runnable {
        override fun run() {
            if (!hidden) return
            val applied = runCatching { applyToHoveredWindow(transparent) }
                .onFailure { Log.w("CursorVisibility", "Could not update cursor icon", it) }
                .getOrDefault(false)
            // SurfaceFlinger's initial snapshot and hover transition may arrive a frame later.
            if (!applied && retries++ < 8) handler.postDelayed(this, 16)
        }
    }

    fun setHidden(hide: Boolean, display: Int, device: Int) {
        if (hide == hidden && displayId == display && deviceId == device) return
        restore()
        displayId = display
        deviceId = device
        if (!hide || display < 0 || device < 0) return
        hidden = true
        observe()
    }

    private fun observe() {
        retries = 0
        try {
            val owner = factoryClass.getConstructor().newInstance()
            factory = owner
            add.invoke(owner, consumer)
            rawListener = (listeners.get(owner) as Map<*, *>).values.first()
            handler.post(reapply)
        } catch (e: Exception) {
            restore()
            throw e
        }
    }

    private fun applyToHoveredWindow(icon: PointerIcon): Boolean {
        lastToken?.let { if (setIcon(icon, displayId, deviceId, it)) return true }
        val listener = rawListener ?: return false
        val pair = snapshot.invoke(listener)
        val handles = pair.javaClass.getField("first").get(pair) as Array<*>
        for (handle in handles.filterNotNull()) {
            if (handle.javaClass.getField("displayId").getInt(handle) != displayId) continue
            val token = handle.javaClass.getField("token").get(handle) as? IBinder ?: continue
            if (setIcon(icon, displayId, deviceId, token)) {
                lastToken = token
                return true
            }
        }
        return false
    }

    fun restore() {
        hidden = false
        handler.removeCallbacks(reapply)
        // Restore before removing the observer; the next hover event selects the app's own icon.
        if (deviceId >= 0) runCatching {
            if (!setIcon(arrow, displayId, deviceId, lastToken)) applyToHoveredWindow(arrow)
        }
        factory?.let { runCatching { remove.invoke(it, consumer) } }
        factory = null
        rawListener = null
        lastToken = null
        displayId = -1
        deviceId = -1
    }
}
