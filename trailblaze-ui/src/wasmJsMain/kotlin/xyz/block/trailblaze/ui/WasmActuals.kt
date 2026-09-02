package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.VideoMetadata

/**
 * The wasmJs actuals for this module's `expect` surface, all inert.
 *
 * There is no wasm APPLICATION any more: the Compose/WebAssembly report this source set used to
 * implement was removed, and the Trailblaze report is now rendered by the TypeScript run-report
 * renderer in `:trailblaze-report`. What remains is the wasmJs TARGET, kept deliberately as a
 * KMP-cleanliness gate. Declaring the target
 * re-enables the commonMain metadata compilation that KGP disables for a JVM-only module, so the
 * COMPILER (not grep) is what keeps this module's ~100 commonMain files free of `java.*` and
 * other JVM-only stdlib surface.
 *
 * That gate only needs commonMain to compile for a non-JVM target, and an `expect` without an
 * `actual` doesn't compile — hence this file. Nothing calls any of it, so each body is the
 * cheapest total answer rather than a real implementation. If a genuine wasm consumer ever comes
 * back, these are the seams it fills in.
 */

private object NoOpImageLoader : ImageLoader {
  override fun getImageModel(sessionId: String, screenshotFile: String?): Any? = null
}

actual fun createLogsFileSystemImageLoader(): ImageLoader = NoOpImageLoader

actual fun getCurrentUrl(): String? = null

actual fun getPlatform(): Platform = Platform.WASM

@Composable
actual fun resolveImageModel(sessionId: String, screenshotFile: String?, imageLoader: ImageLoader): Any? = null

@Composable
actual fun preloadedScreenshotKeys(): Set<String> = emptySet()

actual fun openVideoInSystemPlayer(filePath: String) = Unit

actual suspend fun loadCaptureVideoMetadata(sessionId: String): VideoMetadata? = null

actual suspend fun loadDeviceLogs(sessionId: String): String? = null

actual suspend fun loadNetworkLogs(sessionId: String): String? = null

actual fun isExportAutoplayRequested(): Boolean = false

actual fun signalExportPlaybackEnded() = Unit
