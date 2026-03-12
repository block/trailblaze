package xyz.block.trailblaze.ui

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import xyz.block.trailblaze.util.Console

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
 * External bridge to browser-level image preloading (triggers download via new Image())
 */
external fun preloadImageInBrowser(url: String)

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
            Console.log("🔗 URL transformed: $screenshotRef -> $transformedRef")
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
                Console.log("⚠️ Failed to decompress: $transformedRef")
            }
            continuation.resume(dataUrl)
        }
    }
}

/**
 * Proactively preloads all screenshots in the background.
 * - For embedded/compressed images: decompresses and caches as data URLs
 * - For remote HTTP images: also triggers browser-level download caching
 *
 * Prioritizes foreground images by:
 * 1. Waiting briefly so visible composables can start resolving first
 * 2. Processing in small batches with yields between them, letting
 *    foreground LaunchedEffect resolutions take priority on the main thread
 *
 * The imageCache prevents duplicate work — if a visible image already
 * resolved via its composable, the preloader skips it instantly.
 */
suspend fun preloadScreenshots(screenshotRefs: List<String>) {
    val uniqueRefs = screenshotRefs.distinct()
    if (uniqueRefs.isEmpty()) return

    // Let visible composables start their own LaunchedEffect image
    // resolutions before we begin background preloading.
    delay(500)

    Console.log("🔄 Preloading ${uniqueRefs.size} screenshots in background...")

    // Process in small batches, yielding between them so foreground
    // image resolutions (from visible composables) get priority.
    val batchSize = 5
    uniqueRefs.chunked(batchSize).forEach { batch ->
        coroutineScope {
            batch.forEach { ref ->
                launch {
                    try {
                        val resolved = resolveScreenshot(ref)
                        // For HTTP URLs, also trigger browser-level preloading so the
                        // browser cache is warm when Coil3 AsyncImage requests the image.
                        if (resolved != null &&
                            (resolved.startsWith("http://") || resolved.startsWith("https://"))
                        ) {
                            try {
                                preloadImageInBrowser(resolved)
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Console.log("⚠️ Preload failed for: $ref - ${e.message}")
                    }
                }
            }
        }
        // Yield between batches so foreground work (UI rendering,
        // visible image resolutions) isn't starved.
        yield()
    }

    Console.log("✅ Preloaded ${uniqueRefs.size} screenshots")
}
