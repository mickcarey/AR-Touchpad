package au.com.michaelcarey.artouchpad

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InputSettingsStoreTest {
    private val prefs = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("input_settings_test", Context.MODE_PRIVATE)
    private val store = InputSettingsStore(prefs)
    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }

    @Test fun everyPreferenceSurvivesReopeningTheStore() {
        val settings = InputSettings(2.1f, 1.7f, false, Smoothing.STEADY, true, true, true,
            800, ThreeFingerAction.PRECISION, true, true)
        store.save(settings)
        assertEquals(settings, InputSettingsStore(prefs).load())
    }
    @Test fun profileSnapshotsAreIndependentOfCurrentSettingsAndEachOther() {
        val desktop = InputSettings(1.2f, autoCopy = true)
        val movie = InputSettings(0.3f, threeFingerAction = ThreeFingerAction.PLAY_PAUSE)
        store.save(desktop, UsageProfile.DESKTOP); store.save(movie, UsageProfile.MOVIE)
        store.save(InputSettings())
        assertEquals(desktop, store.load(UsageProfile.DESKTOP))
        assertEquals(movie, store.load(UsageProfile.MOVIE))
        assertEquals(InputSettings.preset(UsageProfile.MOONLIGHT), store.load(UsageProfile.MOONLIGHT))
        assertEquals(InputSettings(), store.load())
    }
    @Test fun migratesExistingKeysAndSafelyHandlesBadTypesAndEnums() {
        prefs.edit().putFloat("sensitivity", 1.4f).putBoolean("dex_keyboard", true)
            .putString("smoothing", "future_value").putString("hold_ms", "bad")
            .putString("scroll_speed", "bad").commit()
        val s = store.load()
        assertEquals(1.4f, s.sensitivity, 0f); assertTrue(s.phoneKeyboard)
        assertEquals(Smoothing.LIGHT, s.smoothing); assertEquals(600L, s.holdMs)
        assertEquals(0.8f, s.scrollSpeed, 0f); assertFalse(s.autoCopy)
    }
}
