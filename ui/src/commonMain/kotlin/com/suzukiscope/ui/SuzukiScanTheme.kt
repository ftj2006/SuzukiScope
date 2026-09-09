package com.suzukiscope.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Suzuki brand blue (deep blue used across Suzuki motorsport/marine livery) with a lighter
// accent blue for highlights, on a dark background to match modern OBD/dash tooling conventions.
private val SuzukiBlue = Color(0xFF003DA5)
private val SuzukiBlueLight = Color(0xFF4FC3F7)
private val SuzukiBlueContainer = Color(0xFF0A2A5E)

private val SuzukiDarkColorScheme = darkColorScheme(
    primary = SuzukiBlueLight,
    onPrimary = Color(0xFF00223B),
    primaryContainer = SuzukiBlue,
    onPrimaryContainer = Color.White,
    secondary = SuzukiBlueLight,
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF12151D),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = SuzukiBlueContainer,
    onSurfaceVariant = Color(0xFFC7D6EC),
)

/** App-wide dark theme with Suzuki blue accents, used by androidApp. */
@Composable
fun SuzukiScopeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SuzukiDarkColorScheme, content = content)
}
