package xyz.block.trailblaze.ui.recording

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decode an encoded screenshot frame (PNG or JPEG) into an [ImageBitmap].
 *
 * - JVM: implemented via `org.jetbrains.skia.Image.makeFromEncoded(...).toComposeImageBitmap()`.
 * - wasmJs: implemented via Compose Resources' `decodeToImageBitmap(...)` extension.
 *
 * Returns null on decode failure so callers can keep the previous frame on transient errors
 * (matches the prior Skia-based behavior in the desktop recording tab).
 */
internal expect fun ByteArray.decodeFrameBytes(): ImageBitmap?
