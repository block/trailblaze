package xyz.block.trailblaze.ui.composables

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.icons.BrowserChrome
import xyz.block.trailblaze.yaml.TrailSourceType

/**
 * Provider for device classifier icons.
 * Internal builds can extend this to add proprietary icons (e.g., PNG resources).
 * Use rememberVectorPainter() for ImageVector icons, or painterResource() for PNG resources.
 */
abstract class DeviceClassifierIconProvider {
  /**
   * Returns a Painter for the given device classifier. This is called from a Composable context.
   */
  @Composable
  abstract fun getPainter(classifier: TrailblazeDeviceClassifier): Painter?
}

/**
 * Default implementation that provides icons for standard platforms.
 */
object DefaultDeviceClassifierIconProvider : DeviceClassifierIconProvider() {
  @Composable
  override fun getPainter(classifier: TrailblazeDeviceClassifier): Painter? {
    return when (classifier.classifier.lowercase()) {
      TrailblazeDevicePlatform.ANDROID.name.lowercase() -> rememberVectorPainter(Android)
      TrailblazeDevicePlatform.IOS.name.lowercase() -> rememberVectorPainter(Apple)
      TrailblazeDevicePlatform.WEB.name.lowercase() -> rememberVectorPainter(BrowserChrome)
      else -> null
    }
  }
}

/**
 * CompositionLocal providing device classifier icon provider throughout the composition tree.
 * Apps should provide their custom provider at the root using CompositionLocalProvider.
 */
val LocalDeviceClassifierIcons = compositionLocalOf<DeviceClassifierIconProvider> { DefaultDeviceClassifierIconProvider }

/**
 * Returns the appropriate icon for a given platform.
 */
fun TrailblazeDevicePlatform.getIcon(): ImageVector = when (this) {
  TrailblazeDevicePlatform.ANDROID -> Android
  TrailblazeDevicePlatform.IOS -> Apple
  TrailblazeDevicePlatform.WEB -> BrowserChrome
}

/**
 * Returns the appropriate icon for a trail source type, or null if none.
 * - HANDWRITTEN: Person icon (manually written test)
 * - GENERATED: SmartToy/robot icon (AI-generated test)
 * - TESTRAIL: No icon (default/external source)
 */
fun TrailSourceType.getIcon(): ImageVector? = when (this) {
  TrailSourceType.HANDWRITTEN -> Icons.Default.Person
  else -> Icons.Default.SmartToy
}
