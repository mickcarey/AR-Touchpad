package au.com.michaelcarey.artouchpad

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.content.ContentResolver
import android.net.Uri
import android.util.Size
import kotlin.math.roundToInt
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent

/** Runs under the Shizuku shell identity. No notification-listener access is needed. */
internal class MediaControls(context: Context) {
    // Audio/media services validate attribution against the caller's UID.
    private val shellContext = context.createPackageContext("com.android.shell", 0)
    private val audio = shellContext.getSystemService(AudioManager::class.java)
    private val sessions by lazy {
        // Shizuku starts with app_process, not ActivityThread's normal app bootstrap.
        // Android's modular media wrappers otherwise dereference a null service manager.
        val managerClass = Class.forName("android.media.MediaServiceManager")
        for (name in listOf("android.media.MediaFrameworkPlatformInitializer", "android.media.MediaFrameworkInitializer")) {
            val initializer = Class.forName(name)
            synchronized(initializer) {
                if (initializer.getMethod("getMediaServiceManager").invoke(null) == null) {
                    initializer.getMethod("setMediaServiceManager", managerClass)
                        .invoke(null, managerClass.getConstructor().newInstance())
                }
            }
        }
        shellContext.getSystemService(MediaSessionManager::class.java)
    }

    private fun activeController(): MediaController? {
        val controllers = sessions.getActiveSessions(null)
        return controllers.firstOrNull { isPlaying(it.playbackState?.state) } ?: controllers.firstOrNull()
    }

    private fun isPlaying(state: Int?) = state in listOf(
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    fun snapshot(): Bundle = Bundle().apply {
        // Failure to inspect a session must not invent a paused state or hide volume state.
        runCatching {
            val controller = activeController()
            putString("sessionPackage", controller?.packageName)
            if (isJellyfin(controller?.packageName)) putParcelable("jellyfinSession", controller?.sessionToken)
            putParcelable("sessionActivity", runCatching { controller?.sessionActivity }.getOrNull())
            val metadata = controller?.metadata
            putString("artworkKey", artworkKey(controller?.packageName, metadata))
            val description = metadata?.description
            putString("title", description?.title?.toString())
            val subtitle = description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
                ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            val details = if (isJellyfin(controller?.packageName)) {
                listOfNotNull(subtitle, metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
                    description?.description?.toString()).map(String::trim)
                    .filter { it.isNotEmpty() && it != description?.title?.toString() }.distinct().joinToString(" · ")
            } else subtitle
            putString("subtitle", details)
            val playback = controller?.playbackState
            putInt("playing", when {
                playback != null -> if (isPlaying(playback.state)) 1 else 0
                controller == null -> if (audio.isMusicActive) 1 else 0
                else -> -1
            })
        }.onFailure { putInt("playing", -1) }
        putBoolean("muted", audio.isStreamMute(AudioManager.STREAM_MUSIC))
        putInt("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        putInt("maxVolume", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    /** Resolve only the active player's own published cover, independently of volume/status polling. */
    fun artwork(expectedKey: String): Bitmap? {
        val controller = activeController() ?: return null
        val metadata = controller.metadata ?: return null
        if (artworkKey(controller.packageName, metadata) != expectedKey) return null
        return loadPlaybackCover(shellContext, controller.packageName, metadata)
    }

    fun send(key: Int): Boolean {
        val direction = when (key) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> null
        }
        if (direction != null) {
            // No FLAG_SHOW_UI, sound or vibration: the settings header supplies feedback on
            // its own display. Injecting a volume key wakes the phone's SystemUI panel.
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            return true
        }
        if (key !in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_NEXT)) return false
        val controller = activeController()
        if (controller != null) {
            when (key) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> controller.transportControls.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> controller.transportControls.pause()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controller.transportControls.skipToPrevious()
                KeyEvent.KEYCODE_MEDIA_NEXT -> controller.transportControls.skipToNext()
                else -> if (isPlaying(controller.playbackState?.state)) controller.transportControls.pause()
                    else controller.transportControls.play()
            }
        } else {
            // Let Android resume the remembered media receiver when no session is active.
            val time = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, key, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, key, 0))
        }
        return true
    }
}

private const val COVER_PIXELS = 192

internal fun artworkKey(source: String?, metadata: MediaMetadata?): String? {
    if (metadata == null || !(isJellyfin(source) || source == "com.spotify.music" || source == "com.spotify.lite")) return null
    val description = metadata.description
    val bitmap = description.iconBitmap != null
    val uri = metadata.coverUri()
    if (!bitmap && uri?.scheme != ContentResolver.SCHEME_CONTENT) return null
    return listOf(source, description.mediaId, description.title, description.subtitle,
        metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), uri, bitmap)
        .joinToString("|") { Uri.encode(it?.toString().orEmpty()) }
}

private fun MediaMetadata.coverUri(): Uri? {
    val preferred = getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: getString(MediaMetadata.METADATA_KEY_ART_URI)
    return preferred?.let(Uri::parse)?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT } ?: description.iconUri
}

internal fun loadPlaybackCover(context: Context, source: String, metadata: MediaMetadata): Bitmap? {
    val description = metadata.description
    val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: description.iconBitmap
    if (bitmap != null) {
        val scale = minOf(1f, COVER_PIXELS.toFloat() / maxOf(bitmap.width, bitmap.height))
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }
    val uri = metadata.coverUri() ?: return null
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val provider = uri.authority?.let { context.packageManager.resolveContentProvider(it, 0) }
    if (provider?.packageName != source) return null
    return context.contentResolver.loadThumbnail(uri, Size(COVER_PIXELS, COVER_PIXELS), null)
}
