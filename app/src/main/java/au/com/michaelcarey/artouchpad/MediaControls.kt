package au.com.michaelcarey.artouchpad

import android.content.Context
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
            val metadata = controller?.metadata
            val description = metadata?.description
            putString("title", description?.title?.toString())
            putString("subtitle", description?.subtitle?.toString()?.takeIf { it.isNotBlank() }
                ?: metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
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
