package xyz.block.trailblaze.ui.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import xyz.block.trailblaze.logs.client.TrailblazeLog

object ColorUtils {
  @Composable
  fun getLogTypeColor(log: TrailblazeLog): Color {
    val isSystemInDarkTheme = isSystemInDarkTheme()

    return when (log) {
      is TrailblazeLog.MaestroDriverLog -> if (isSystemInDarkTheme) Color(0xFF5A8BC7) else Color(0xFFa3c9f9) // blue
      is TrailblazeLog.MaestroCommandLog -> if (isSystemInDarkTheme) Color(0xFF9578A4) else Color(0xFFc3aed6) // purple  
      is TrailblazeLog.DelegatingTrailblazeToolLog -> if (isSystemInDarkTheme) Color(0xFFD4B382) else Color(0xFFffe5b4) // peach
      is TrailblazeLog.TrailblazeToolLog -> if (isSystemInDarkTheme) Color(0xFF82C696) else Color(0xFFb4f8c8) // mint
      is TrailblazeLog.TrailblazeLlmRequestLog -> if (isSystemInDarkTheme) Color(0xFF83B8A5) else Color(0xFFb5ead7) // aquamarine
      is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> if (isSystemInDarkTheme) Color(0xFFC5A4AE) else Color(
        0xFFf7d6e0
      ) // pink
      is TrailblazeLog.TrailblazeSessionStatusChangeLog -> if (isSystemInDarkTheme) Color(0xFFC7C59D) else Color(
        0xFFf9f7cf
      ) // yellow
      is TrailblazeLog.ObjectiveStartLog -> if (isSystemInDarkTheme) Color(0xFFC3883A) else Color(0xFFf5b042) // orange
      is TrailblazeLog.ObjectiveCompleteLog -> if (isSystemInDarkTheme) Color(0xFFC2689A) else Color(0xFFf49ac2) // magenta/pink
      else -> if (isSystemInDarkTheme) Color(0xFF666666) else Color(0xFF000000) // fallback color
    }
  }
}