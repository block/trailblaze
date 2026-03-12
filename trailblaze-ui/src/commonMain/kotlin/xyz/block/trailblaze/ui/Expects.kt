package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata

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

// Platform-specific function to get the current platform
expect fun getPlatform(): Platform

// Platform-specific image resolution (for lazy loading on WASM)
// On WASM: Returns a composable function that lazily resolves image keys
// On JVM: Returns imageLoader.getImageModel directly
@androidx.compose.runtime.Composable
expect fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any?

// Platform-specific function to load video capture metadata for a session.
// On JVM: reads capture_metadata.json from the session logs directory.
// On WASM: returns null (video playback not supported in browser).
/** Opens the given video file in the system's default video player (JVM only; no-op on WASM). */
expect fun openVideoInSystemPlayer(filePath: String)

expect suspend fun loadCaptureVideoMetadata(sessionId: String): VideoMetadata?