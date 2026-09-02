package xyz.block.trailblaze.ui

import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata

enum class Platform {
    WASM,
    JVM,
}

// JVM is the only platform with a real implementation of the expects below. The wasmJs source set
// exists solely to keep the target compiling as the commonMain-purity gate (see WasmActuals.kt),
// so every wasmJs actual is inert.

// Platform-specific function to create FileSystemImageLoader for the logs directory
expect fun createLogsFileSystemImageLoader(): ImageLoader

// Platform-specific function to get the current URL. Null on JVM.
expect fun getCurrentUrl(): String?

// Platform-specific function to get the current platform
expect fun getPlatform(): Platform

// Platform-specific image resolution. On JVM: returns imageLoader.getImageModel directly.
@androidx.compose.runtime.Composable
expect fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any?

/**
 * Returns the set of screenshot refs that have already been decompressed into the
 * background data-URL cache. Reading this inside a `@Composable` subscribes to cache
 * mutations via the Compose snapshot system, so a platform that populates the cache
 * recomposes its callers automatically — no polling required.
 *
 * On JVM: always empty. The screenshot preload strip is a deliberate no-op on desktop —
 * file-system reads are already fast, and full-resolution Coil decodes for every
 * screenshot in a long session would balloon JVM heap and churn Coil's LRU enough to
 * evict the very entries the strip just populated.
 */
@androidx.compose.runtime.Composable
expect fun preloadedScreenshotKeys(): Set<String>

/** Opens the given video file in the system's default video player (JVM only). */
expect fun openVideoInSystemPlayer(filePath: String)

// Loads video capture metadata for a session. On JVM: reads capture_metadata.json from the
// session logs directory.
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
 * True when the host page was loaded with `?autoplay=...` in the URL — the signal
 * `trailblaze report --video` uses to ask a browser-hosted timeline to start playback on
 * mount instead of waiting for a user click. JVM always returns false. Presence of the
 * key is the trigger; the value isn't inspected.
 */
expect fun isExportAutoplayRequested(): Boolean

/**
 * Notifies the external recorder driving `trailblaze report --video` that timeline
 * playback has reached the end and the screen-capture can be torn down. A browser host
 * sets `globalThis.__tbPlaybackEnded = true`; the Playwright exporter polls for that flag
 * via `page.waitForFunction("() => globalThis.__tbPlaybackEnded === true", ...)`. JVM is a
 * no-op.
 */
expect fun signalExportPlaybackEnded()
