package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.images.ImageLoader

enum class Platform {
    WASM,
    JVM,
}

// Platform-specific function to create FileSystemImageLoader for the logs directory
// Implementation is provided in platform-specific source sets (jvmMain, etc.)
expect fun createLogsFileSystemImageLoader(): ImageLoader

// Platform-specific function to get the current URL
// Returns null on JVM, current window location on WASM
expect fun getCurrentUrl(): String?

// Platform-specific function to get the current URL
// Returns null on JVM, current window location on WASM
expect fun getPlatform(): Platform