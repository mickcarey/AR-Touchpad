package com.pgratz.artouchpad

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceControl

/** A compositor layer owned by our shell service, with no window, focus or input channel.
 * Presentation windows hide desktop tasks on recent Android. Drawing just this small layer
 * keeps the existing app/video visible and needs no additional application permissions.
 * All calls run on the service's main looper; dismiss cancels animation and frees the layer.
 */
internal class ModeFeedbackOverlay(private val handler: Handler) {
    private var layer: SurfaceControl? = null
    private var transaction: SurfaceControl.Transaction? = null
    private var frame: Runnable? = null

    fun show(displayId: Int, width: Int, height: Int, badge: Bitmap) {
        dismiss()
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val info = global.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(global.getMethod("getInstance").invoke(null), displayId) ?: return
        val layerStack = info.javaClass.getField("layerStack").getInt(info)
        val surfaceControl = SurfaceControl.Builder().setName("AR Touchpad mode feedback")
            .setBufferSize(badge.width, badge.height).setFormat(PixelFormat.RGBA_8888).build()
        layer = surfaceControl
        try {
            val surface = Surface::class.java.getConstructor(SurfaceControl::class.java)
                .newInstance(surfaceControl)
            try {
                val canvas = surface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                    canvas.drawBitmap(badge, 0f, 0f, null)
                } finally { surface.unlockCanvasAndPost(canvas) }
            } finally { surface.release() }
            val tx = SurfaceControl.Transaction()
            transaction = tx
            tx.javaClass.getMethod("setLayerStack", SurfaceControl::class.java, Int::class.javaPrimitiveType)
                .invoke(tx, surfaceControl, layerStack)
            tx.javaClass.getMethod("show", SurfaceControl::class.java).invoke(tx, surfaceControl)
            tx.setLayer(surfaceControl, Int.MAX_VALUE)
                .setPosition(surfaceControl, (width - badge.width) / 2f, (height - badge.height) / 2f)
                .setAlpha(surfaceControl, 0f).apply()
            val started = SystemClock.uptimeMillis()
            frame = object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.uptimeMillis() - started
                    if (elapsed >= 1850) { dismiss(); return }
                    val alpha = when {
                        elapsed < 150 -> elapsed / 150f
                        elapsed < 1200 -> 1f
                        else -> (1850 - elapsed) / 650f
                    }
                    tx.setAlpha(surfaceControl, alpha).apply()
                    handler.postDelayed(this, 16)
                }
            }.also { handler.post(it) }
        } catch (e: Exception) {
            dismiss()
            throw e
        }
    }

    fun dismiss() {
        frame?.let { handler.removeCallbacks(it) }
        frame = null
        layer?.let { control ->
            runCatching {
                (transaction ?: SurfaceControl.Transaction().also { transaction = it }).apply {
                    javaClass.getMethod("remove", SurfaceControl::class.java).invoke(this, control)
                    apply()
                }
            }
            control.release()
        }
        transaction?.close()
        transaction = null
        layer = null
    }
}
