package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Extracts a single video frame at the given timestamp. Returns null if extraction fails.
 *
 * On JVM: uses ffmpeg. On WASM: no-op stub.
 */
expect suspend fun extractVideoFrame(videoPath: String, timestampMs: Long): ImageBitmap?
