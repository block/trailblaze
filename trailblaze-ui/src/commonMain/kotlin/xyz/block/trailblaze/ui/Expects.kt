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

// Platform-specific function to get the current platform
expect fun getPlatform(): Platform

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
