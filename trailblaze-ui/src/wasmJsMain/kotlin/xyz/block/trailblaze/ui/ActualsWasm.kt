package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import getTrailblazeReportJsonFromBrowser
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
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

actual fun isExportAutoplayRequested(): Boolean {
    // window.location.search starts with "?" when params are present. We split on "&"
    // and look for any `autoplay=...` (or bare `autoplay`) — being lenient because both
    // `?autoplay` and `?autoplay=1` should trigger.
    val search = window.location.search.removePrefix("?")
    if (search.isEmpty()) return false
    return search.split("&").any { it == "autoplay" || it.startsWith("autoplay=") }
}

actual fun signalExportPlaybackEnded() {
    // The exporter polls for `globalThis.__tbPlaybackEnded === true` via
    // `page.waitForFunction(...)`. Writing `globalThis` (vs `window`) keeps the flag
    // reachable from any same-realm context — equivalent in a normal browser tab but
    // strictly the right global to assign to.
    js("globalThis.__tbPlaybackEnded = true;")
}

actual suspend fun loadNetworkLogs(sessionId: String): String? {
    // WasmReport embeds a `network_logs/<sessionId>` entry (gzip+base64 NDJSON, wrapped as a
    // JSON string) into the hosted-report bundle, and index.html dispatches the `network_logs/`
    // key prefix to it (mirroring the `device_logs/` path). When a session captured no network
    // traffic the key is absent and the dispatcher returns the "no entry" sentinel, so the
    // Network tab simply doesn't appear for that session.
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

/**
 * Bounded cache of the final Coil3 model (the base64-decoded `ByteArray` for embedded
 * data URLs) keyed by `(sessionId, screenshotFile)`. Without this, every recomposition
 * base64-decodes a fresh `ByteArray` instance and Coil3's memory cache — which uses
 * reference identity for byte-array models — misses, forcing a re-decode even when the
 * image is already in cache. Stable instances let the hidden preloader's decode result
 * be reused by the later visible render.
 *
 * Keying by `(sessionId, screenshotFile)` keeps two sessions whose embedded payloads
 * happen to share a relative key (e.g. `screenshot_0.png`) from colliding. Capacity is
 * bounded via FIFO eviction so navigating between many sessions on a long-lived report
 * page doesn't grow the cache unboundedly.
 */
private const val IMAGE_MODEL_CACHE_CAP = 500
private val imageModelCache: LinkedHashMap<Pair<String, String>, Any> = LinkedHashMap()

private fun putModel(sessionId: String, screenshotFile: String, model: Any) {
    val key = sessionId to screenshotFile
    if (imageModelCache.size >= IMAGE_MODEL_CACHE_CAP && !imageModelCache.containsKey(key)) {
        // Evict the oldest insertion (FIFO). Strict LRU would re-order on read but the
        // slideshow walks screenshots roughly in playback order, so FIFO ≈ LRU here.
        val firstKey = imageModelCache.keys.firstOrNull()
        if (firstKey != null) imageModelCache.remove(firstKey)
    }
    imageModelCache[key] = model
}

@Composable
actual fun preloadedScreenshotKeys(): Set<String> = observePreloadedScreenshotKeys()

@Composable
actual fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any? {
    // Lazy resolution: If screenshotFile is an image key (not a data URL), resolve it on-demand.
    // Seed from the synchronous decompressed-image cache when available so a slideshow advance
    // doesn't render a blank frame while the LaunchedEffect resolves the data URL.
    var resolvedImage by remember(screenshotFile) {
        val seed = if (screenshotFile != null &&
            !screenshotFile.startsWith("data:") &&
            !screenshotFile.startsWith("http")
        ) {
            getCachedDataUrl(screenshotFile) ?: screenshotFile
        } else {
            screenshotFile
        }
        mutableStateOf<String?>(seed)
    }

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
            } catch (e: CancellationException) {
                // Don't swallow cancellation — let the LaunchedEffect tear down cleanly.
                throw e
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

    // Fast path: same (sessionId, screenshotFile) reuses the cached model instance so
    // Coil3's reference-identity memory-cache lookup hits across composables. Checked
    // here — after `resolvedImage` is seeded but before we invoke `getImageModel` —
    // so we never short-circuit before the caller-provided `imageLoader` has had a
    // chance to run for a brand-new ref. Once a model is memoized, subsequent calls
    // for the same `(sessionId, screenshotFile)` are assumed to come from a
    // functionally-equivalent `imageLoader`; passing a different loader for the same
    // key would silently reuse the first model.
    if (screenshotFile != null) {
        imageModelCache[sessionId to screenshotFile]?.let { return it }
    }

    // Return the resolved image model
    // If still resolving, imageLoader will handle the image key gracefully (may show placeholder)
    val currentResolved = resolvedImage
    val model = imageLoader.getImageModel(sessionId, currentResolved)
    // Memoize once we have a fully resolved model (data URL or HTTP URL) so subsequent
    // recompositions return the same instance and Coil3's memory cache hits.
    if (model != null && screenshotFile != null && currentResolved != null &&
        (currentResolved.startsWith("data:") || currentResolved.startsWith("http"))
    ) {
        putModel(sessionId, screenshotFile, model)
    }
    return model
}
