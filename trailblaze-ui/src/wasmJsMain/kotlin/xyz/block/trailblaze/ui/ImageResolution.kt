package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
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
 * Cache for decompressed images — backed by a [SnapshotStateMap] so Compose code can
 * observe new entries without polling. Mutations from non-Composable contexts (the
 * `resolveScreenshot` callback below) propagate to any composable that reads from this
 * map under the snapshot system; `ScreenshotPreloadStrip` relies on that to mount
 * hidden `AsyncImage`s as soon as each ref is decompressed, no polling required.
 */
private val imageCache: SnapshotStateMap<String, String> = mutableStateMapOf()

/**
 * Synchronous lookup into the decompressed-image cache. Returns the resolved
 * `data:` URL for [screenshotRef] if it has already been decompressed (e.g. by
 * [preloadScreenshots]), or `null` if it hasn't been seen yet. Callers can use
 * this to skip the first-render unresolved-ref state and paint the resolved
 * image immediately when it's already in cache.
 */
fun getCachedDataUrl(screenshotRef: String): String? = imageCache[screenshotRef]

/**
 * Composable observer of the decompressed-screenshot keys. Reading the result inside
 * a `@Composable` subscribes to mutations of [imageCache] via the Compose snapshot
 * system — any subsequent `preloadScreenshots` insertion triggers a recomposition of
 * the calling composable.
 *
 * Internal to the WASM module so the public surface in [Expects.preloadedScreenshotKeys]
 * stays platform-neutral.
 */
@Composable
internal fun observePreloadedScreenshotKeys(): Set<String> = imageCache.keys.toSet()

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
 * Max screenshot decompressions in flight at once. Sized to stay comfortably ahead of
 * slideshow playback (one image per ~hundreds-of-ms) without flooding the JS main thread
 * with parallel gzip+base64 work that would starve foreground rendering.
 */
private const val PRELOAD_CONCURRENCY = 3

/**
 * Proactively preloads all screenshots in the background.
 * - For embedded/compressed images: decompresses and caches as data URLs
 * - For remote HTTP images: also triggers browser-level download caching
 *
 * Refs are *dispatched* in caller-provided order (typically timeline-sorted), keeping a
 * rolling window of [PRELOAD_CONCURRENCY] decompressions in flight via a [Semaphore]. That
 * means an earlier ref always starts before any later ref, while still parallelizing a few
 * at a time so the preloader stays ahead of slideshow playback. Completion order is not strictly
 * ordered — a small ref can finish before an earlier large one — but the start order plus
 * a tight window keeps the head of the slideshow ready well before the tail.
 *
 * The imageCache prevents duplicate work — if a visible image already resolved via its
 * composable, the preloader skips it instantly.
 */
suspend fun preloadScreenshots(screenshotRefs: List<String>) {
    val uniqueRefs = screenshotRefs.distinct()
    if (uniqueRefs.isEmpty()) return

    // Let visible composables start their own LaunchedEffect image
    // resolutions before we begin background preloading.
    delay(500)

    Console.log(
        "🔄 Preloading ${uniqueRefs.size} screenshots in background " +
            "(timeline order, up to $PRELOAD_CONCURRENCY at a time)...",
    )

    val semaphore = Semaphore(PRELOAD_CONCURRENCY)
    coroutineScope {
        for (ref in uniqueRefs) {
            // Acquire a permit before launching: this both bounds concurrency and forces
            // dispatch to happen in input order (we can't start ref N+1 until one of the
            // first N has freed a permit). `acquire` itself suspends and reschedules, so
            // no extra `yield()` is needed between iterations.
            semaphore.acquire()
            launch {
                try {
                    val resolved = resolveScreenshot(ref)
                    if (resolved != null &&
                        (resolved.startsWith("http://") || resolved.startsWith("https://"))
                    ) {
                        try {
                            preloadImageInBrowser(resolved)
                        } catch (_: Exception) {}
                    }
                } catch (e: CancellationException) {
                    // Don't swallow cancellation — let structured concurrency tear down cleanly.
                    throw e
                } catch (e: Exception) {
                    Console.log("⚠️ Preload failed for: $ref - ${e.message}")
                } finally {
                    semaphore.release()
                }
            }
        }
    }

    Console.log("✅ Preloaded ${uniqueRefs.size} screenshots")
}
