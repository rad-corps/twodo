package app.twodo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A quiet palette: near-neutral surfaces with one muted sage accent.
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC9B4),
    onPrimary = Color(0xFF0F201A),
    primaryContainer = Color(0xFF22332D),
    onPrimaryContainer = Color(0xFFCDE8DD),
    background = Color(0xFF121413),
    onBackground = Color(0xFFE2E4E2),
    surface = Color(0xFF121413),
    onSurface = Color(0xFFE2E4E2),
    surfaceVariant = Color(0xFF1C1F1E),
    onSurfaceVariant = Color(0xFFA9AFAC),
    surfaceContainerHigh = Color(0xFF1E2120),
    surfaceContainerHighest = Color(0xFF252927),
    outline = Color(0xFF6F7673),
    outlineVariant = Color(0xFF2B302E),
    inverseSurface = Color(0xFFE2E4E2),
    inverseOnSurface = Color(0xFF1C1F1E),
    inversePrimary = Color(0xFF2E6B5E),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E6B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBE3),
    onPrimaryContainer = Color(0xFF0F201A),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1B1D1C),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1B1D1C),
    surfaceVariant = Color(0xFFEFF1EF),
    onSurfaceVariant = Color(0xFF575E5B),
    outline = Color(0xFF8A918E),
    outlineVariant = Color(0xFFE1E4E2),
)

@Composable
fun TwoDoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
