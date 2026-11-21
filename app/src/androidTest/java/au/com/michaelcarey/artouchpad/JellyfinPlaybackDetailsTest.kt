package au.com.michaelcarey.artouchpad

import android.app.Notification
import android.media.session.MediaSession
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JellyfinPlaybackDetailsTest {
    @Test fun usesOnlyTheMatchingJellyfinPlaybackNotificationAndCurrentItem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = MediaSession(context, "Episode fixture")
        val listener = JellyfinPlaybackListener()
        val token = session.sessionToken
        val name = "Dragon Ball Z - S1:E2 - The Episode (1989)"
        fun notice(pkg: String) = StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), Process.myPid(), 0,
            Notification.Builder(context, "media").setContentTitle(name).setStyle(Notification.MediaStyle()
                .setMediaSession(token)).build(), Process.myUserHandle(), System.currentTimeMillis())
        try {
            listener.onNotificationPosted(notice("com.example.other"))
            assertNull(JellyfinPlaybackListener.titleFor(token))
            val media = notice("org.jellyfin.mobile")
            listener.onNotificationPosted(media)
            assertEquals("The Episode" to "Dragon Ball Z - S1:E2 · 1989",
                jellyfinDetails("The Episode", null, JellyfinPlaybackListener.titleFor(token)))
            assertEquals("Next episode" to null, jellyfinDetails("Next episode", null, name))
            assertEquals("Movie (2026)" to null, jellyfinDetails("Movie", null, "Movie (2026)"))
            listener.onNotificationRemoved(media)
            assertNull(JellyfinPlaybackListener.titleFor(token))
        } finally { listener.onListenerDisconnected(); session.release() }
    }
}
