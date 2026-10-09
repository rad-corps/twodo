package app.intack.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** A selectable colour theme. Themes are local to each phone; lists and diaries can each have their own. */
class AppTheme(val id: String, val name: String, val dark: Boolean, val colors: ColorScheme)

/**
 * Builds a quiet Material colour scheme from a few palette colours: [background] with [text], one
 * [accent], and the palette's own raised surface and muted text for secondary elements.
 */
private fun theme(
    id: String,
    name: String,
    dark: Boolean,
    background: Color,
    raised: Color,
    text: Color,
    muted: Color,
    accent: Color,
    error: Color,
): AppTheme {
    val onAccent = if (dark) background else Color.White
    val container = lerp(background, accent, if (dark) 0.22f else 0.16f)
    val onContainer = lerp(text, accent, 0.35f)
    val outline = lerp(muted, background, 0.15f)
    val outlineVariant = lerp(raised, text, 0.08f)
    val colors = if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = container, onPrimaryContainer = onContainer,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = container, onSecondaryContainer = onContainer,
            background = background, onBackground = text,
            surface = background, onSurface = text,
            surfaceVariant = raised, onSurfaceVariant = muted,
            surfaceContainer = raised, surfaceContainerHigh = raised,
            surfaceContainerHighest = lerp(raised, text, 0.06f), surfaceContainerLow = background,
            outline = outline, outlineVariant = outlineVariant,
            inverseSurface = text, inverseOnSurface = background, inversePrimary = lerp(accent, background, 0.4f),
            error = error, onError = background,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent,
            primaryContainer = container, onPrimaryContainer = onContainer,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = container, onSecondaryContainer = onContainer,
            background = background, onBackground = text,
            surface = background, onSurface = text,
            surfaceVariant = raised, onSurfaceVariant = muted,
            surfaceContainer = raised, surfaceContainerHigh = raised,
            surfaceContainerHighest = lerp(raised, text, 0.05f), surfaceContainerLow = background,
            outline = outline, outlineVariant = outlineVariant,
            inverseSurface = text, inverseOnSurface = background, inversePrimary = lerp(accent, background, 0.4f),
            error = error, onError = Color.White,
        )
    }
    return AppTheme(id, name, dark, colors)
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

/** All themes, in picker order. Colours are the palettes' published values. */
val THEMES: List<AppTheme> = listOf(
    theme("twodo-dark", "Default Dark", true, c(0x121413), c(0x1C1F1E), c(0xE2E4E2), c(0xA9AFAC), c(0x8FC9B4), c(0xF2B8B5)),
    theme("twodo-light", "Default Light", false, c(0xFAFAF8), c(0xEFF1EF), c(0x1B1D1C), c(0x575E5B), c(0x2E6B5E), c(0xBA1A1A)),
    // Solarized (Ethan Schoonover): base03/base02 dark, base3/base2 light; blue accent.
    theme("solarized-dark", "Solarized Dark", true, c(0x002B36), c(0x073642), c(0x93A1A1), c(0x839496), c(0x268BD2), c(0xDC322F)),
    theme("solarized-light", "Solarized Light", false, c(0xFDF6E3), c(0xEEE8D5), c(0x586E75), c(0x657B83), c(0x268BD2), c(0xDC322F)),
    // Nord (Arctic Ice Studio): polar night / snow storm with frost accents.
    theme("nord", "Nord", true, c(0x2E3440), c(0x3B4252), c(0xECEFF4), c(0xD8DEE9), c(0x88C0D0), c(0xBF616A)),
    theme("nord-light", "Nord Light", false, c(0xECEFF4), c(0xE5E9F0), c(0x2E3440), c(0x4C566A), c(0x5E81AC), c(0xBF616A)),
    // Dracula.
    theme("dracula", "Dracula", true, c(0x282A36), c(0x343746), c(0xF8F8F2), c(0x6272A4), c(0xBD93F9), c(0xFF5555)),
    // Gruvbox (morhetz).
    theme("gruvbox-dark", "Gruvbox Dark", true, c(0x282828), c(0x3C3836), c(0xEBDBB2), c(0xA89984), c(0xFABD2F), c(0xFB4934)),
    theme("gruvbox-light", "Gruvbox Light", false, c(0xFBF1C7), c(0xEBDBB2), c(0x3C3836), c(0x7C6F64), c(0xAF3A03), c(0x9D0006)),
    // Catppuccin: Mocha (dark) and Latte (light), mauve accent.
    theme("catppuccin-mocha", "Catppuccin Mocha", true, c(0x1E1E2E), c(0x313244), c(0xCDD6F4), c(0xA6ADC8), c(0xCBA6F7), c(0xF38BA8)),
    theme("catppuccin-latte", "Catppuccin Latte", false, c(0xEFF1F5), c(0xE6E9EF), c(0x4C4F69), c(0x6C6F85), c(0x8839EF), c(0xD20F39)),
    // Tokyo Night.
    theme("tokyo-night", "Tokyo Night", true, c(0x1A1B26), c(0x24283B), c(0xC0CAF5), c(0x9AA5CE), c(0x7AA2F7), c(0xF7768E)),
    // Rosé Pine: main (dark) and Dawn (light).
    theme("rose-pine", "Rosé Pine", true, c(0x191724), c(0x1F1D2E), c(0xE0DEF4), c(0x908CAA), c(0xEBBCBA), c(0xEB6F92)),
    theme("rose-pine-dawn", "Rosé Pine Dawn", false, c(0xFAF4ED), c(0xF2E9E1), c(0x575279), c(0x797593), c(0xD7827E), c(0xB4637A)),
    // One Dark (Atom).
    theme("one-dark", "One Dark", true, c(0x282C34), c(0x2C313A), c(0xABB2BF), c(0x828997), c(0x61AFEF), c(0xE06C75)),
)

const val DEFAULT_THEME = "twodo-dark"

fun themeById(id: String?): AppTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()

@Composable
fun IntackTheme(theme: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = theme.colors, content = content)
}
