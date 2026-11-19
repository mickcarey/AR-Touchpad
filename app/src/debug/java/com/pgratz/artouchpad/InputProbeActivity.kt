package com.pgratz.artouchpad

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.CopyOnWriteArrayList

/** A harmless two-pane receiver for end-to-end tests; never present in release APKs. */
class InputProbeActivity : Activity() {
    data class Sample(val action: Int, val x: Float, val y: Float, val meta: Int, val wheel: Float,
        val receivedAtNanos: Long = android.os.SystemClock.elapsedRealtimeNanos())
    companion object {
        val samples = CopyOnWriteArrayList<Sample>()
        val keys = CopyOnWriteArrayList<Pair<Int, Int>>()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(object : View(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                canvas.drawColor(Color.rgb(18, 42, 44))
                paint.color = Color.rgb(50, 65, 85)
                canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
                paint.color = Color.WHITE; paint.textSize = 28f
                canvas.drawText("AR Touchpad input test · left pane", 30f, 60f, paint)
                canvas.drawText("Right pane", width / 2f + 30f, 60f, paint)
            }
        })
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        samples += Sample(event.actionMasked, event.rawX, event.rawY, event.metaState,
            event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        return true
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        samples += Sample(event.actionMasked, event.rawX, event.rawY, event.metaState, 0f)
        return true
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        keys += event.keyCode to event.action
        return true
    }
}
