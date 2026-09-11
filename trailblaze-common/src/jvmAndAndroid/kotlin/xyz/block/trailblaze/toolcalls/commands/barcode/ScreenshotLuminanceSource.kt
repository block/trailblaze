package xyz.block.trailblaze.toolcalls.commands.barcode

import com.google.zxing.LuminanceSource

/**
 * Loads encoded screenshot bytes into a ZXing [LuminanceSource].
 *
 * Must accept **every format [xyz.block.trailblaze.api.TrailblazeImageFormat] can produce**, not
 * just PNG: a driver encodes [xyz.block.trailblaze.api.ScreenState.screenshotBytes] using the
 * effective [xyz.block.trailblaze.api.ScreenshotScalingConfig], whose default is WebP.
 * `ScreenshotLuminanceSourceTest` pins that contract for the JVM actual.
 *
 * Split per platform because only the pixel loading differs — Android goes through
 * `BitmapFactory`, the JVM through `ImageIO`. Everything downstream ([BarcodeDecoder],
 * [cropAwaySystemChrome]) works on the resulting [LuminanceSource] and is shared.
 *
 * @throws IllegalStateException if the bytes cannot be decoded as an image.
 */
expect fun screenshotBytesToLuminanceSource(screenshotBytes: ByteArray): LuminanceSource
