package au.com.michaelcarey.artouchpad.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal enum class LaunchpadPalette(val label: String, ink: Long, panel: Long, warm: Long, cool: Long) {
    Vanilla("Vanilla8", 0xFF081211, 0xFF142624, 0xFFE6AF52, 0xFF79BDB8),
    Pine("Rosé Pine", 0xFF191724, 0xFF26233A, 0xFFEBBCBA, 0xFF9CCFD8),
    Matcha("Matcha", 0xFF141C15, 0xFF253128, 0xFFB5D59E, 0xFFE4C388),
    Aizome("Aizome", 0xFF101A2B, 0xFF202F46, 0xFFAAC7FF, 0xFF8BDBCB),
    Sakura("Sakura", 0xFF24181F, 0xFF382832, 0xFFF7BBCA, 0xFFCFB9F0),
    Sumi("Sumi", 0xFF161719, 0xFF292B30, 0xFFE4E1DD, 0xFFACBBD0),
    Kohaku("Kohaku", 0xFF211B12, 0xFF352A1D, 0xFFFFC369, 0xFFB6D2B2),
    Tsuki("Tsuki", 0xFF191B2C, 0xFF292D46, 0xFFCAC2FF, 0xFFA9CDE8),
    Shio("Shio", 0xFF102022, 0xFF1B3336, 0xFF94E1D5, 0xFFBED5F4),
    Ember("Ember", 0xFF231813, 0xFF392820, 0xFFFFB195, 0xFFE1CB92);

    val background = Color(ink)
    val surface = Color(panel)
    val accent = Color(warm)
    val secondary = Color(cool)
    val colors = darkColorScheme(
        primary = accent, onPrimary = background, secondary = secondary, onSecondary = background,
        primaryContainer = if (label == "Vanilla8") Color(0xFF154462) else lerp(surface, secondary, 0.24f), onPrimaryContainer = Color.White,
        secondaryContainer = lerp(surface, secondary, 0.18f), onSecondaryContainer = secondary,
        tertiary = secondary, background = background, onBackground = Color.White,
        surface = background, surfaceContainerLowest = background, surfaceContainerLow = surface,
        surfaceContainer = surface, surfaceContainerHigh = lerp(surface, secondary, 0.08f),
        surfaceContainerHighest = lerp(surface, secondary, 0.14f),
        onSurface = lerp(Color.White, accent, 0.1f), onSurfaceVariant = lerp(Color.LightGray, secondary, 0.20f),
        outline = lerp(surface, Color.White, 0.48f), outlineVariant = lerp(surface, secondary, 0.22f),
        surfaceVariant = surface, surfaceTint = accent,
    )

    companion object {
        fun named(name: String) = entries.find { it.label == name } ?: Vanilla
    }
}
