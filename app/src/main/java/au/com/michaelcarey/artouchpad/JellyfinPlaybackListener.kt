package au.com.michaelcarey.artouchpad

import android.app.Notification
import android.media.session.MediaSession
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

internal fun isJellyfin(packageName: String?) =
    packageName == "org.jellyfin.mobile" || packageName == "org.jellyfin.androidtv"

/** The native player publishes its formatted series/episode or movie/year as the media notification title. */
class JellyfinPlaybackListener : NotificationListenerService() {
    private data class Notice(val token: MediaSession.Token, val title: String, val posted: Long)

    companion object {
        private val notices = ConcurrentHashMap<String, Notice>()
        internal fun titleFor(token: MediaSession.Token?): String? =
            token?.let { notices.values.filter { it.token == token }.maxByOrNull { it.posted }?.title }
    }

    override fun onListenerConnected() {
        notices.clear()
        activeNotifications.orEmpty().filter { isJellyfin(it.packageName) }.forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isJellyfin(sbn.packageName)) return
        val extras = sbn.notification.extras
        val token = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (token == null || title.isNullOrEmpty()) notices.remove(sbn.key)
        else notices[sbn.key] = Notice(token, title, sbn.postTime)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { notices.remove(sbn.key) }
    override fun onListenerDisconnected() { notices.clear() }
    override fun onDestroy() {
        notices.clear()
        super.onDestroy()
    }
}

private val releaseYear = Regex(""" \((\d{4})\)$""")

internal fun jellyfinDetails(title: String?, subtitle: String?, notificationTitle: String?): Pair<String?, String?> {
    if (title.isNullOrBlank() || notificationTitle.isNullOrBlank()) return title to subtitle
    val year = releaseYear.find(notificationTitle)
    val name = notificationTitle.removeSuffix(year?.value.orEmpty())
    if (name == title) return notificationTitle to subtitle
    // A media token can span several episodes. Only enrich the currently named item.
    val ending = " - $title"
    if (!name.endsWith(ending)) return title to subtitle
    val show = name.removeSuffix(ending)
    return title to listOfNotNull(show, year?.groupValues?.get(1), subtitle)
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
}
