package au.com.michaelcarey.artouchpad

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LauncherIconTest {
    @Test fun debugIconIncludesVisibleRibbonAndThemedDevArtwork() {
        org.junit.Assume.assumeTrue(BuildConfig.BUILD_TYPE == "debug")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue(icon is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 192, 192); icon.draw(Canvas(bitmap))
        val base = AdaptiveIconDrawable(ColorDrawable(Color.BLACK), context.getDrawable(R.drawable.launcher_foreground))
        val plain = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        base.setBounds(0, 0, 192, 192); base.draw(Canvas(plain))
        var orange = 0
        for (y in 0 until 192) for (x in 0 until 192) {
            val pixel = bitmap.getPixel(x, y)
            if (pixel != plain.getPixel(x, y) && Color.red(pixel) > 220 && Color.green(pixel) in 70..160 && Color.blue(pixel) < 40) orange++
        }
        assertTrue("DEV ribbon is missing or clipped", orange > 800)
        assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        java.io.File(context.getExternalFilesDir(null), "debug-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        java.io.File(context.getExternalFilesDir(null), "prod-icon.png").outputStream().use {
            plain.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
