package xyz.block.trailblaze.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import xyz.block.trailblaze.ui.models.TrailblazeServerState.ThemeMode

// Custom colors for Trailblaze branding
private val TrailblazePrimary = Color(0xFF2196F3) // Blue
private val TrailblazePrimaryDark = Color(0xFF1976D2) // Darker blue
private val TrailblazeSecondary = Color(0xFF03DAC6) // Teal
private val TrailblazeSecondaryDark = Color(0xFF018786) // Darker teal

// Light theme colors
private val TrailblazeLightColorScheme = lightColorScheme(
  primary = TrailblazePrimary,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE3F2FD),
  onPrimaryContainer = Color(0xFF0D47A1),
  secondary = TrailblazeSecondary,
  onSecondary = Color.Black,
  secondaryContainer = Color(0xFFE0F7FA),
  onSecondaryContainer = Color(0xFF002020),
  surface = Color(0xFFFFFBFE),
  onSurface = Color(0xFF1C1B1F),
  surfaceVariant = Color(0xFFF5F5F5),
  onSurfaceVariant = Color(0xFF49454F),
  background = Color(0xFFFFFBFE),
  onBackground = Color(0xFF1C1B1F),
  error = Color(0xFFEF5350),
  onError = Color.White,
  errorContainer = Color(0xFFF8D7DA),
  onErrorContainer = Color(0xFF721C24),
)

// Dark theme colors
private val TrailblazeDarkColorScheme = darkColorScheme(
  primary = Color(0xFF90CAF9),
  onPrimary = Color(0xFF003258),
  primaryContainer = Color(0xFF004881),
  onPrimaryContainer = Color(0xFFD1E4FF),
  secondary = Color(0xFF4DD0E1),
  onSecondary = Color(0xFF00363A),
  secondaryContainer = Color(0xFF004F56),
  onSecondaryContainer = Color(0xFFB2EBF2),
  surface = Color(0xFF1C1B1F),
  onSurface = Color(0xFFE6E1E5),
  surfaceVariant = Color(0xFF2B2B2B),
  onSurfaceVariant = Color(0xFFCAC4D0),
  background = Color(0xFF1C1B1F),
  onBackground = Color(0xFFE6E1E5),
  error = Color(0xFFFF8A80),
  onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A),
  onErrorContainer = Color(0xFFFFDAD6),
)

// CompositionLocal to provide current theme mode
val LocalThemeMode = compositionLocalOf { ThemeMode.System }

@Composable
fun TrailblazeTheme(
  themeMode: ThemeMode = ThemeMode.System,
  content: @Composable () -> Unit,
) {
  val systemInDarkTheme = isSystemInDarkTheme()

  val darkTheme = when (themeMode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemInDarkTheme
  }

  val colorScheme = if (darkTheme) TrailblazeDarkColorScheme else TrailblazeLightColorScheme

  CompositionLocalProvider(LocalThemeMode provides themeMode) {
    MaterialTheme(
      colorScheme = colorScheme,
      content = content
    )
  }
}

@Composable
fun getCurrentThemeMode(): ThemeMode = LocalThemeMode.current

@Composable
fun isDarkTheme(): Boolean {
  val themeMode = getCurrentThemeMode()
  val systemInDarkTheme = isSystemInDarkTheme()

  return when (themeMode) {
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> systemInDarkTheme
  }
}