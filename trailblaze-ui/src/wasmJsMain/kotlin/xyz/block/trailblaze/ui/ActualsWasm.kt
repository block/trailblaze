package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader

actual fun createLogsFileSystemImageLoader(): ImageLoader {
    // For WASM, use NetworkImageLoader since screenshots are already resolved to data URLs
    return NetworkImageLoader()
}

actual fun getCurrentUrl(): String? {
    return window.location.href
}

actual fun getPlatform(): Platform {
    return Platform.WASM
}

@Composable
actual fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any? {
    // Lazy resolution: If screenshotFile is an image key (not a data URL), resolve it on-demand
    var resolvedImage by remember(screenshotFile) { mutableStateOf<String?>(screenshotFile) }

    LaunchedEffect(screenshotFile) {
        // Check if screenshot needs resolution (relative paths for embedded images or Buildkite artifacts)
        val isAlreadyResolved = screenshotFile == null ||
                screenshotFile.startsWith("data:") ||
                screenshotFile.startsWith("http")

        if (!isAlreadyResolved) {
            // Relative path needs resolution:
            // - For embedded images: decompress to data URL
            // - For Buildkite artifacts: construct full URL
            try {
                val dataUrl = resolveScreenshot(screenshotFile)
                resolvedImage = dataUrl
            } catch (e: Exception) {
                println("❌ Failed to resolve screenshot: $screenshotFile - ${e.message}")
                // If resolution fails, keep the original path as fallback
                resolvedImage = screenshotFile
            }
        } else {
            // Already resolved (data URL or full HTTP URL) - use as-is
            resolvedImage = screenshotFile
        }
    }

    // Return the resolved image model
    // If still resolving, imageLoader will handle the image key gracefully (may show placeholder)
    return imageLoader.getImageModel(sessionId, resolvedImage)
}
