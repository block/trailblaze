package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.vector.ImageVector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.ui.icons.Android
import xyz.block.trailblaze.ui.icons.Apple
import xyz.block.trailblaze.ui.icons.BrowserChrome

/**
 * Returns the appropriate icon for a given platform
 */
fun TrailblazeDevicePlatform.getIcon(): ImageVector = when (this) {
  TrailblazeDevicePlatform.ANDROID -> Android
  TrailblazeDevicePlatform.IOS -> Apple
  TrailblazeDevicePlatform.WEB -> BrowserChrome
}
