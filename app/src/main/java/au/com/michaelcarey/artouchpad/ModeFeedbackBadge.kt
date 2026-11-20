package au.com.michaelcarey.artouchpad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/** Render in the normal app process, which owns Android's font/theme initialization. */
internal fun modeFeedbackBadge(locked: Boolean, displayDensity: Float): Bitmap {
    // Bound the Binder payload even on a high-density monitor (at most 400 KB).
    val density = displayDensity.coerceAtMost(2f)
    val bitmap = Bitmap.createBitmap((284 * density).toInt(), (88 * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(density, density)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xE6171C23.toInt()
    canvas.drawRoundRect(1f, 1f, 283f, 87f, 22f, 22f, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1f
    paint.color = 0x405F6B78
    canvas.drawRoundRect(1f, 1f, 283f, 87f, 22f, 22f, paint)
    paint.color = if (locked) 0xFFFFC56D.toInt() else 0xFF7DE0BB.toInt()
    paint.strokeWidth = 3f
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawArc(34f, 22f, 54f, 46f, 180f, if (locked) 180f else 150f, false, paint)
    canvas.drawLine(34f, 34f, 34f, 42f, paint)
    if (locked) canvas.drawLine(54f, 34f, 54f, 42f, paint)
    canvas.drawRoundRect(29f, 42f, 59f, 65f, 5f, 5f, paint)
    canvas.drawLine(44f, 51f, 44f, 56f, paint)
    paint.style = Paint.Style.FILL
    paint.textSize = 20f
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    canvas.drawText(if (locked) "Pocket locked" else "Touchpad unlocked", 78f, 51f, paint)
    return bitmap
}
