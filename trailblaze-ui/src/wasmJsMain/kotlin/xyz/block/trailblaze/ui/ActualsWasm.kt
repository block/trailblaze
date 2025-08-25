package xyz.block.trailblaze.ui

import kotlinx.browser.window
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader

actual fun createLogsFileSystemImageLoader(): ImageLoader {
  // For WASM, we'll default to network loading since file system access is limited
  return NetworkImageLoader()
}

actual fun getCurrentUrl(): String? {
  return window.location.href
}

actual fun getPlatform(): Platform {
  return Platform.WASM
}