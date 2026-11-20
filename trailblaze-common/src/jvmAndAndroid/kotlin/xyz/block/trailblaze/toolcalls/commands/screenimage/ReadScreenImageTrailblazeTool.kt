package xyz.block.trailblaze.toolcalls.commands.screenimage

import kotlin.io.encoding.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/** What [ReadScreenImageTrailblazeTool] hands back. The TS SDK wraps it as `ctx.screen.image()`. */
@Serializable
data class ReadScreenImageResult(
  val width: Int,
  val height: Int,
  /** RGBA, 8 bits per channel, row-major, base64-encoded. */
  val data: String,
  /**
   * True when the screenshot's encoding keeps exact colours (PNG, lossless WebP). False for the
   * drivers' default lossy WebP, where colours drift by a few levels: compare with a tolerance.
   * Downscaling never blends colours, so it does not affect this.
   */
  val lossless: Boolean,
  /** The rectangle read, in the screenshot's own pixels, after scaling and clamping. */
  val pixelRect: TrailblazeNode.Bounds,
  /** The screenshot the pixels came from. */
  val source: ScreenImageSource,
)

@Serializable
data class ScreenImageSource(
  /** `png`, `webp` or `jpeg`. */
  val encoding: String,
  val width: Int,
  val height: Int,
  /** Screenshot pixels per driver unit, horizontally. */
  val scaleX: Double,
  /** Screenshot pixels per driver unit, vertically. */
  val scaleY: Double,
)

/**
 * Hands a scripted tool the pixels of one rectangle of the screen, or the whole screen, as raw
 * RGBA, so the tool can make its own visual check ("the theme is dark", "the sticker is on the
 * card", "the frame changed") with no image decoder and no LLM.
 *
 * Captures a fresh screenshot through the driver and crops it, wherever the agent runs: the host
 * JVM, or the device itself for on-device runs with no host. Scripted tools call it through the
 * SDK's `ctx.screen.image({ bounds })`, which decodes the result into a `ScreenImage`.
 *
 * Hidden from the LLM and never recorded: it is a building block for tools, not a step.
 */
@Serializable
@TrailblazeToolClass(
  name = "readScreenImage",
  surfaceToLlm = false,
  isRecordable = false,
  resultType = ReadScreenImageResult::class,
)
data class ReadScreenImageTrailblazeTool(
  /**
   * Rectangle to read, in the driver's coordinates: the same space as `MatchDescriptor.bounds`, so
   * an element found by `findSelectorMatches` can be passed straight through. Null reads the whole
   * screen. Edges outside the screen are clamped.
   */
  val bounds: TrailblazeNode.Bounds? = null,
  /**
   * Downscale so the longer side is at most this many pixels. Defaults to
   * [DEFAULT_MAX_DIMENSION], which leaves element-sized reads untouched and keeps a whole-screen
   * read small enough to decode quickly in the on-device JavaScript runtime.
   */
  val maxDimension: Int? = null,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val limit = maxDimension ?: DEFAULT_MAX_DIMENSION
    if (limit <= 0) return invalid("maxDimension must be positive, got $limit.")
    if (bounds != null && (bounds.right <= bounds.left || bounds.bottom <= bounds.top)) {
      return invalid("bounds $bounds has no area.")
    }

    val screenState = (toolExecutionContext.screenshotStateProvider ?: toolExecutionContext.screenStateProvider)?.invoke()
      ?: toolExecutionContext.screenState
      ?: return invalid("no screen state is available to read.")
    // Empty, not just null: the on-device Android driver yields an empty array when it skipped or
    // failed the capture.
    val bytes = screenState.screenshotBytes
    if (bytes == null || bytes.isEmpty()) return invalid("the driver captured no screenshot.")
    if (screenState.deviceWidth <= 0 || screenState.deviceHeight <= 0) {
      return invalid("the driver reported a ${screenState.deviceWidth}x${screenState.deviceHeight} screen.")
    }

    return try {
      decodeScreenshot(bytes).use { image -> read(image, ScreenshotEncoding.of(bytes), screenState.deviceWidth, screenState.deviceHeight, limit) }
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "readScreenImage: could not read the screenshot: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  private fun read(
    image: DecodedScreenshot,
    encoding: ScreenshotEncoding,
    deviceWidth: Int,
    deviceHeight: Int,
    limit: Int,
  ): TrailblazeToolResult {
    val rect = toPixelRect(bounds, deviceWidth, deviceHeight, image.width, image.height)
      ?: return invalid(
        "bounds $bounds is outside the ${deviceWidth}x$deviceHeight screen.",
      )
    val (outWidth, outHeight) = outputSize(rect.width, rect.height, limit)
    val rgba = sampleRgba(image, rect, outWidth, outHeight)

    // A black element is a real answer; a black screen is not. Only when the read came back black
    // is the whole screenshot scanned, to tell the two apart.
    if (isBlank(rgba) && isBlankScreenshot(image)) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "readScreenImage: the whole screenshot is black, so there are no pixels to " +
          "check. The screen may show a secure window (FLAG_SECURE), be off, or the capture failed.",
        command = this,
      )
    }

    val result = ReadScreenImageResult(
      width = outWidth,
      height = outHeight,
      data = Base64.encode(rgba),
      lossless = encoding.lossless,
      pixelRect = rect,
      source = ScreenImageSource(
        encoding = encoding.wireName,
        width = image.width,
        height = image.height,
        scaleX = image.width.toDouble() / deviceWidth,
        scaleY = image.height.toDouble() / deviceHeight,
      ),
    )
    return TrailblazeToolResult.Success(
      // Set so the CLI and logs print this line rather than the base64 pixels.
      message = "readScreenImage: ${outWidth}x$outHeight pixels of " +
        (bounds?.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" } ?: "the screen") +
        " (${encoding.wireName}${if (encoding.lossless) ", lossless" else ""}).",
      structuredContent = TrailblazeJsonInstance.encodeToJsonElement(ReadScreenImageResult.serializer(), result),
    )
  }

  private fun invalid(reason: String) = TrailblazeToolResult.Error.InvalidToolCall(
    errorMessage = "readScreenImage: $reason",
    command = this,
  )

  companion object {
    /** Default for [maxDimension]. */
    const val DEFAULT_MAX_DIMENSION = 512
  }
}
