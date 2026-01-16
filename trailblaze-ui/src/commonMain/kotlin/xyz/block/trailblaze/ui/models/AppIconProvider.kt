package xyz.block.trailblaze.ui.models

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

abstract class AppIconProvider {
  /**
   * Returns a Painter for the given app target. This is called from a Composable context.
   * Use rememberVectorPainter() for ImageVector icons, or painterResource() for PNG resources.
   */
  @Composable
  abstract fun getPainter(appTarget: TrailblazeHostAppTarget): Painter?

  @Composable
  fun getIcon(appTarget: TrailblazeHostAppTarget?) {
    if (appTarget != null) {
      getPainter(appTarget)?.let { painter ->
        Image(
          painter = painter,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }

  data object DefaultAppIconProvider : AppIconProvider() {
    @Composable
    override fun getPainter(appTarget: TrailblazeHostAppTarget): Painter? = null
  }
}
