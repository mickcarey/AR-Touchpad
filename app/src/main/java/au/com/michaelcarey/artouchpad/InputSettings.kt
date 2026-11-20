package au.com.michaelcarey.artouchpad

enum class Smoothing(val label: String, val timeConstantMs: Float) {
    OFF("Off", 0f), LIGHT("Light", 12f), STEADY("Steady", 24f)
}

enum class ThreeFingerAction(val label: String) {
    MOONLIGHT("Moonlight capture"), PLAY_PAUSE("Play / pause"), PRECISION("Toggle precision"),
    SETTINGS("Open settings"), NONE("Disabled")
}

enum class UsageProfile(val label: String) { DESKTOP("Desktop"), MOONLIGHT("Moonlight"), MOVIE("Movie") }

/** Durable preferences only. Connection, pressed buttons and preview state never enter a profile. */
data class InputSettings(
    val sensitivity: Float = 0.5f,
    val scrollSpeed: Float = 0.8f,
    val naturalScroll: Boolean = true,
    val smoothing: Smoothing = Smoothing.LIGHT,
    val precision: Boolean = false,
    val autoCopy: Boolean = false,
    val dragLock: Boolean = false,
    val holdMs: Long = 600,
    val threeFingerAction: ThreeFingerAction = ThreeFingerAction.MOONLIGHT,
    val blackTouchpad: Boolean = false,
    val phoneKeyboard: Boolean = false,
) {
    fun validated() = copy(
        sensitivity = sensitivity.takeIf { it.isFinite() }?.coerceIn(0.2f, 3f) ?: 0.5f,
        scrollSpeed = scrollSpeed.takeIf { it.isFinite() }?.coerceIn(0.1f, 2f) ?: 0.8f,
        holdMs = holdMs.coerceIn(300, 1200),
    )

    companion object {
        fun preset(profile: UsageProfile) = when (profile) {
            UsageProfile.DESKTOP -> InputSettings(phoneKeyboard = true)
            UsageProfile.MOONLIGHT -> InputSettings(smoothing = Smoothing.OFF, blackTouchpad = true)
            UsageProfile.MOVIE -> InputSettings(blackTouchpad = true, threeFingerAction = ThreeFingerAction.PLAY_PAUSE)
        }
    }
}

internal fun selectDisplay(displays: List<DisplayInfo>, preferred: String?): DisplayInfo? =
    displays.firstOrNull { it.id != 0 && it.uniqueId == preferred }
        ?: displays.firstOrNull { it.id != 0 }

internal fun TouchpadState.inputSettings() = InputSettings(sensitivity, scrollSpeed, naturalScroll,
    smoothing, precision, autoCopy, dragLock, holdMs, threeFingerAction, blackTouchpad, dexKeyboardEnabled)

internal fun TouchpadState.withInputSettings(s: InputSettings) = copy(sensitivity = s.sensitivity,
    scrollSpeed = s.scrollSpeed, naturalScroll = s.naturalScroll, smoothing = s.smoothing,
    precision = s.precision, autoCopy = s.autoCopy, dragLock = s.dragLock, holdMs = s.holdMs,
    threeFingerAction = s.threeFingerAction, blackTouchpad = s.blackTouchpad, dexKeyboardEnabled = s.phoneKeyboard)
