package xyz.block.trailblaze.ui

actual fun createLogsFileSystemImageLoader(): ImageLoader {
  // For WASM, we'll default to network loading since file system access is limited
  return NetworkImageLoader()
}