package au.com.michaelcarey.artouchpad

import android.content.SharedPreferences
import java.time.ZoneId

data class LaunchpadPreferences(
    val palette: String = "Vanilla8",
    val textPercent: Int = 125,
    val showTitle: Boolean = false,
    val roomMeter: Boolean = false,
    val worldZones: Set<String> = setOf("Asia/Tokyo", "Europe/London", "America/New_York"),
    val pinned: Set<String> = emptySet(),
) {
    fun save(prefs: SharedPreferences) {
        prefs.edit().putString("palette", palette).putInt("text_percent", textPercent)
            .putBoolean("show_title", showTitle).putBoolean("room_meter", roomMeter)
            .putStringSet("world_zones", worldZones).putStringSet("pinned_apps", pinned).apply()
    }

    companion object {
        val sizes = listOf(100, 125, 150, 175)
        fun load(prefs: SharedPreferences): LaunchpadPreferences {
            val defaults = LaunchpadPreferences()
            return LaunchpadPreferences(
                palette = prefs.getString("palette", defaults.palette) ?: defaults.palette,
                textPercent = prefs.getInt("text_percent", defaults.textPercent).takeIf { it in sizes } ?: defaults.textPercent,
                showTitle = prefs.getBoolean("show_title", false),
                roomMeter = prefs.getBoolean("room_meter", false),
                worldZones = prefs.getStringSet("world_zones", defaults.worldZones).orEmpty()
                    .intersect(ZoneId.getAvailableZoneIds()),
                pinned = prefs.getStringSet("pinned_apps", emptySet()).orEmpty().toSet(),
            )
        }
    }
}
