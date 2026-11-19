package com.pgratz.artouchpad

import android.content.SharedPreferences

internal class InputSettingsStore(private val prefs: SharedPreferences) {
    fun load(profile: UsageProfile? = null): InputSettings {
        val prefix = profile?.let { "profile_${it.name}_" } ?: ""
        val defaults = profile?.let(InputSettings::preset) ?: InputSettings()
        fun float(key: String, default: Float) = runCatching { prefs.getFloat(prefix + key, default) }.getOrDefault(default)
        fun bool(key: String, default: Boolean) = runCatching { prefs.getBoolean(prefix + key, default) }.getOrDefault(default)
        fun string(key: String, default: String) = runCatching { prefs.getString(prefix + key, default) ?: default }.getOrDefault(default)
        return InputSettings(
            sensitivity = float("sensitivity", defaults.sensitivity),
            scrollSpeed = float("scroll_speed", defaults.scrollSpeed),
            naturalScroll = bool("natural_scroll", defaults.naturalScroll),
            smoothing = runCatching { Smoothing.valueOf(string("smoothing", defaults.smoothing.name)) }.getOrDefault(defaults.smoothing),
            precision = bool("precision", defaults.precision),
            autoCopy = bool("auto_copy", defaults.autoCopy),
            dragLock = bool("drag_lock", defaults.dragLock),
            holdMs = runCatching { prefs.getLong(prefix + "hold_ms", defaults.holdMs) }.getOrDefault(defaults.holdMs),
            threeFingerAction = runCatching { ThreeFingerAction.valueOf(string("three_finger", defaults.threeFingerAction.name)) }.getOrDefault(defaults.threeFingerAction),
            blackTouchpad = bool("black_touchpad", defaults.blackTouchpad),
            phoneKeyboard = bool("dex_keyboard", defaults.phoneKeyboard),
        ).validated()
    }

    fun save(settings: InputSettings, profile: UsageProfile? = null) {
        val s = settings.validated()
        val prefix = profile?.let { "profile_${it.name}_" } ?: ""
        prefs.edit().putFloat(prefix + "sensitivity", s.sensitivity)
            .putFloat(prefix + "scroll_speed", s.scrollSpeed)
            .putBoolean(prefix + "natural_scroll", s.naturalScroll)
            .putString(prefix + "smoothing", s.smoothing.name)
            .putBoolean(prefix + "precision", s.precision)
            .putBoolean(prefix + "auto_copy", s.autoCopy)
            .putBoolean(prefix + "drag_lock", s.dragLock)
            .putLong(prefix + "hold_ms", s.holdMs)
            .putString(prefix + "three_finger", s.threeFingerAction.name)
            .putBoolean(prefix + "black_touchpad", s.blackTouchpad)
            .putBoolean(prefix + "dex_keyboard", s.phoneKeyboard).apply()
    }
}
