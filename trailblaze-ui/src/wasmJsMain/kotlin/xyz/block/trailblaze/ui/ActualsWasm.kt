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
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.CaptureMetadataModel
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata
import xyz.block.trailblaze.ui.utils.JsonDefaults
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
        val json = fetchReportJsonOrNull("capture_metadata/$sessionId") ?: return null
        val metadata = JsonDefaults.FORWARD_COMPATIBLE
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

actual suspend fun loadDeviceLogs(sessionId: String): String? {
    val json = fetchReportJsonOrNull("device_logs/$sessionId") ?: return null
    // The report embeds device logs as a JSON-encoded string (to survive JSON.parse
    // in the JS decompression pipeline). Decode it back to raw text.
    return decodeWrappedStringOrPassThrough(json)
}

actual suspend fun loadNetworkLogs(sessionId: String): String? {
    // TODO(https://github.com/block/trailblaze/issues/125): WasmReport.kt does not yet
    // embed a `network_logs/<sessionId>` entry into the hosted-report bundle, so this
    // dispatch always falls through to the index.html "no key" branch and returns null.
    // The Network tab therefore never appears on hosted reports today. Once `WasmReport`
    // grows a `compressedNetworkLogs` parameter and `index.html` gains a `network_logs/`
    // lookup branch (mirroring the existing `device_logs/` path), this function returns
    // data without further changes here.
    val json = fetchReportJsonOrNull("network_logs/$sessionId") ?: return null
    return decodeWrappedStringOrPassThrough(json)
}

/**
 * Fetches a JSON entry from the embedded WASM report bundle by key, returning null when
 * the dispatcher reports no entry for that key (the browser-side handler in `index.html`
 * returns the literal `"{}"` placeholder when a key isn't found, distinct from a real
 * empty-object response).
 *
 * Centralizes the empty-response check so individual `loadX` functions don't each
 * re-derive the "no data" sentinel — three sites used to inline `json == "{}" || json.isBlank()`.
 */
private suspend fun fetchReportJsonOrNull(key: String): String? {
    val deferred = CompletableDeferred<String?>()
    getTrailblazeReportJsonFromBrowser(key) { json ->
        deferred.complete(if (isMissingReportEntry(json)) null else json)
    }
    return deferred.await()
}

/**
 * True when the report bundle's browser-side dispatcher returned the "no entry" sentinel for a
 * key. The dispatcher in `index.html` returns the literal string `"{}"` (or empty string,
 * depending on the path taken) when a key isn't present in the compressed bundle.
 */
private fun isMissingReportEntry(json: String): Boolean = json == "{}" || json.isBlank()

/**
 * Decodes a JSON-encoded string literal back to raw text, falling back to the input unchanged
 * when it isn't actually JSON-quoted. Device logs and network logs are both embedded as JSON
 * strings (so JS `JSON.parse` in the decompression pipeline doesn't mangle their content) and
 * need to be unwrapped after fetching.
 */
private fun decodeWrappedStringOrPassThrough(json: String): String =
    try {
        JsonDefaults.FORWARD_COMPATIBLE.decodeFromString<String>(json)
    } catch (_: Exception) {
        json
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
