package xyz.block.trailblaze.ui

import kotlinx.coroutines.suspendCancellableCoroutine
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import kotlin.coroutines.resume

/**
 * External function to transform image URLs (defined in External.kt)
 */
@JsName("transformImageUrl")
external fun transformImageUrl(screenshotRef: String): String

/**
 * External bridge to window.decompressImageCallback (low-level decompression only)
 */
external fun decompressImageCallback(imageKey: String, callback: (String?) -> Unit)

/**
 * Cache for decompressed images - managed in Kotlin/WASM
 */
private val imageCache = mutableMapOf<String, String>()

/**
 * Resolves a single screenshot reference to a usable format:
 * - Calls external transformImageUrl() as preprocessing hook (e.g., for Buildkite URL construction)
 * - For remote URLs: returns as-is
 * - For embedded images: decompresses to data URL (with caching)
 */
suspend fun resolveScreenshot(screenshotRef: String): String? {
    // Apply external URL transformation first (optional JS hook for environment-specific logic)
    val transformedRef = try {
        transformImageUrl(screenshotRef)
    } catch (e: Exception) {
        // If transform function not available, use original
        screenshotRef
    }

    // If remote URL (after transformation), return as-is
    if (transformedRef.startsWith("http://") || transformedRef.startsWith("https://")) {
        if (transformedRef != screenshotRef) {
            println("🔗 URL transformed: $screenshotRef -> $transformedRef")
        }
        return transformedRef
    }

    // If already a data URL, return as-is
    if (transformedRef.startsWith("data:")) {
        return transformedRef
    }

    // Check cache first (for embedded/compressed images)
    imageCache[transformedRef]?.let { cachedDataUrl ->
        return cachedDataUrl
    }

    // Try to decompress from embedded data
    return suspendCancellableCoroutine { continuation ->
        decompressImageCallback(transformedRef) { dataUrl ->
            if (dataUrl != null) {
                // Cache the result
                imageCache[transformedRef] = dataUrl
            } else {
                println("���️ Failed to decompress: $transformedRef")
            }
            continuation.resume(dataUrl)
        }
    }
}
