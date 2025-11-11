package xyz.block.trailblaze.ui.models

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

abstract class AppIconProvider {
  abstract fun getImageVector(appTarget: TrailblazeHostAppTarget): ImageVector?

  @Composable
  fun getIcon(appTarget: TrailblazeHostAppTarget?) {
    if (appTarget != null) {
      getImageVector(appTarget)?.let { imageVector ->
        Icon(
          imageVector = imageVector,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
        )
      }
    }
  }

  data object DefaultAppIconProvider : AppIconProvider() {
    override fun getImageVector(appTarget: TrailblazeHostAppTarget): ImageVector? = null
  }
}
