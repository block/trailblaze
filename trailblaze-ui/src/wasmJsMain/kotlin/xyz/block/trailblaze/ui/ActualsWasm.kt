package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import getTrailblazeReportJsonFromBrowser
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.CaptureMetadataModel
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata
import xyz.block.trailblaze.util.Console

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

actual fun openVideoInSystemPlayer(filePath: String) {
    // Video playback not supported in browser
}

actual suspend fun loadCaptureVideoMetadata(sessionId: String): VideoMetadata? {
    return try {
        val json = loadCaptureMetadataJson(sessionId) ?: return null
        val metadata = Json { ignoreUnknownKeys = true }
            .decodeFromString<CaptureMetadataModel>(json)
        // The WASM timeline has no way to decode a raw mp4 — it can only render
        // frames when the report embeds a sprite sheet (VIDEO_FRAMES). If sprite
        // generation was skipped (e.g., ffmpeg not installed on the CI runner),
        // fall back to null so the timeline uses screenshot slideshow mode
        // instead of sticking on "Loading frame...".
        val spritesArtifact = metadata.artifacts.firstOrNull { it.type == "VIDEO_FRAMES" }
            ?: return null
        VideoMetadata(
            url = "",
            filePath = sessionId, // Used as key prefix by WasmEmbeddedVideoFrameCache
            startTimestampMs = spritesArtifact.startTimestampMs,
            endTimestampMs = spritesArtifact.endTimestampMs,
        )
    } catch (e: Exception) {
        Console.log("Failed to load capture video metadata for $sessionId: ${e.message}")
        null
    }
}

private suspend fun loadCaptureMetadataJson(sessionId: String): String? {
    val deferred = CompletableDeferred<String?>()
    getTrailblazeReportJsonFromBrowser("capture_metadata/$sessionId") { json ->
        deferred.complete(if (json == "{}" || json.isBlank()) null else json)
    }
    return deferred.await()
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
                // Keep the original ref if decompression returns null (e.g., on-demand report
                // with useRelativeImageUrls=true where images aren't embedded — the ref will
                // be resolved to a /static/ URL by NetworkImageLoader.getImageModel instead)
                resolvedImage = dataUrl ?: resolvedImage
            } catch (e: Exception) {
                Console.log("❌ Failed to resolve screenshot: $screenshotFile - ${e.message}")
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
