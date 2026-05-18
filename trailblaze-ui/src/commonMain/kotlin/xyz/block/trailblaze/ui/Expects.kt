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

/**
 * Returns the set of screenshot refs that have already been decompressed into the
 * background data-URL cache. Reading this inside a `@Composable` subscribes to cache
 * mutations via the Compose snapshot system: when the WASM preloader adds a new entry,
 * the calling composable recomposes automatically — no polling required.
 *
 * - On WASM: the live set of keys in the embedded-image decompression cache, growing
 *   as `preloadScreenshots` works through the timeline-sorted ref list.
 * - On JVM: always empty. The screenshot preload strip is a deliberate no-op on
 *   desktop — file-system reads are already fast, and full-resolution Coil decodes for
 *   every screenshot in a long session would balloon JVM heap and churn Coil's LRU
 *   enough to evict the very entries the strip just populated.
 */
@androidx.compose.runtime.Composable
expect fun preloadedScreenshotKeys(): Set<String>

// Platform-specific function to load video capture metadata for a session.
// On JVM: reads capture_metadata.json from the session logs directory.
// On WASM: returns null (video playback not supported in browser).
/** Opens the given video file in the system's default video player (JVM only; no-op on WASM). */
expect fun openVideoInSystemPlayer(filePath: String)

expect suspend fun loadCaptureVideoMetadata(sessionId: String): VideoMetadata?

/** Loads raw device log content (logcat / iOS log stream) for a session, or null if unavailable. */
expect suspend fun loadDeviceLogs(sessionId: String): String?

/**
 * Loads the raw NDJSON content of `<session-dir>/network.ndjson` for a session, or null when no
 * capture file exists (e.g. capture never ran or the session has no traffic). One line per
 * [xyz.block.trailblaze.network.NetworkEvent]. Source-agnostic — web and on-device mobile captures
 * write to the same path with the same schema.
 */
expect suspend fun loadNetworkLogs(sessionId: String): String?

/**
 * True when this WASM instance was loaded with `?autoplay=...` in the URL — the signal
 * `trailblaze report --video` uses to ask the report app to start timeline playback on
 * mount instead of waiting for a user click. JVM always returns false. Presence of the
 * key is the trigger; the value isn't inspected.
 */
expect fun isExportAutoplayRequested(): Boolean

/**
 * Notifies the external recorder driving `trailblaze report --video` that timeline
 * playback has reached the end and the screen-capture can be torn down. WASM sets
 * `globalThis.__tbPlaybackEnded = true`; the Playwright exporter polls for that flag via
 * `page.waitForFunction("() => globalThis.__tbPlaybackEnded === true", ...)`. JVM is a
 * no-op.
 */
expect fun signalExportPlaybackEnded()
