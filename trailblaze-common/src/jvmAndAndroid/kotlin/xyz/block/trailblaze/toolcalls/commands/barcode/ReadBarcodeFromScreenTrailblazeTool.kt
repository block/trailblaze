package xyz.block.trailblaze.toolcalls.commands.barcode

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.memory.renderCaptured
import xyz.block.trailblaze.util.Console

/**
 * Reads whatever barcode is on screen and stores the decoded text in agent memory, so a later
 * step can assert on it or type it somewhere.
 *
 * Screenshot-based rather than camera-based: it decodes the pixels the driver already captures
 * for every step, so it works on any driver that produces a screenshot and needs nothing
 * installed on the device.
 *
 * [ReadOnlyTrailblazeTool] because it only reads those pixels — nothing on the device changes, so
 * the dispatcher can keep its cached snapshot.
 */
@Serializable
@TrailblazeToolClass("readBarcodeFromScreen")
@LLMDescription(
  "Decode a QR, Data Matrix, Aztec or PDF417 code on screen and store its text in memory under " +
    "`variable`. Fails if no readable code is shown, so it also works as an assertion.",
)
data class ReadBarcodeFromScreenTrailblazeTool(
  @param:LLMDescription("Memory variable to store the decoded text under, e.g. \"barcodeValue\".")
  val variable: String,
  /**
   * Off by default: linear readers look for runs of light and dark bars, which ordinary UI and
   * image compression produce by accident, so on a full screen they can return a confidently wrong
   * number.
   */
  @param:LLMDescription(
    "Also read striped barcodes (UPC, EAN, Code 128/39/93, ITF, Codabar). Off by default because " +
      "ordinary UI can cause false reads; enable only when one is on screen.",
  )
  val includeLinearFormats: Boolean = false,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (variable.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "readBarcodeFromScreen: variable must be non-blank so the decoded " +
          "barcode can be recalled later.",
        command = this,
      )
    }

    val screenState = toolExecutionContext.screenState
      ?: return TrailblazeToolResult.Error.InvalidToolCall(
        errorMessage = "readBarcodeFromScreen: no screen state available to scan for a barcode.",
        command = this,
      )

    // Empty, not just null: the on-device Android driver declares `screenshotBytes` non-null and
    // yields `ByteArray(0)` when it has no screenshot — its mirror-only fast path skips the
    // capture, and a failed capture falls back to the same empty array. A null-only check sends
    // those zero bytes into the platform decoder, which fails with a confusing "could not decode
    // 0 bytes" instead of saying no screenshot was available.
    val screenshotBytes = screenState.screenshotBytes
    if (screenshotBytes == null || screenshotBytes.isEmpty()) {
      return TrailblazeToolResult.Error.InvalidToolCall(
        errorMessage = "readBarcodeFromScreen: screen state contains no screenshot bytes to scan. " +
          "The driver may have captured the screen without a screenshot.",
        command = this,
      )
    }

    logScannedScreen(toolExecutionContext, screenState)

    val formats = BarcodeDecoder.TWO_DIMENSIONAL_FORMATS +
      if (includeLinearFormats) BarcodeDecoder.LINEAR_FORMATS else emptyList()

    var scanned: LuminanceSource? = null
    var attempt: BarcodeDecoder.Attempt? = null
    return try {
      val fullScreenshot = screenshotBytesToLuminanceSource(screenshotBytes)
      // iOS screenshots carry a status bar, and often browser bars, whose icons ZXing mistakes
      // for finder patterns. Android's chrome does not cause the same false positives, so it
      // scans the whole screenshot.
      val scanSource =
        if (screenState.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS) {
          try {
            fullScreenshot.cropAwaySystemChrome(
              viewHierarchy = screenState.viewHierarchy,
              deviceHeight = screenState.deviceHeight,
            )
          } catch (cancellation: CancellationException) {
            throw cancellation
          } catch (chromeUnavailable: Exception) {
            // The crop is an optimization, not a requirement, and the two captures are
            // independent: `AxeScreenState.viewHierarchy` throws outright when `axe describe-ui`
            // produced no usable tree, while `screenshotBytes` runs its own `axe screenshot`. So
            // a transient metadata failure must not fail a decode the screenshot alone can serve
            // — the cost of skipping the crop is a decode attempt that may hit chrome favicons,
            // which the tile escalation already handles.
            Console.log(
              "readBarcodeFromScreen: no view hierarchy for the iOS chrome crop " +
                "(${chromeUnavailable.message}); scanning the full screenshot instead.",
            )
            fullScreenshot
          }
        } else {
          fullScreenshot
        }
      scanned = scanSource

      val decoded = BarcodeDecoder.decode(scanSource, formats) { attempt = it }
      if (decoded.isEmpty()) {
        // A barcode that encodes nothing is not something a trail can act on, and reporting
        // success would store an empty value that silently breaks later interpolation.
        return TrailblazeToolResult.Error.InvalidToolCall(
          errorMessage = "readBarcodeFromScreen: decoded a barcode on screen but it was empty.",
          command = this,
        )
      }

      toolExecutionContext.memory.remember(variable, decoded)
      val rendered = renderCaptured(toolExecutionContext, variable, decoded)
      Console.log("readBarcodeFromScreen: remembered $variable = $rendered")
      TrailblazeToolResult.Success(
        message = "readBarcodeFromScreen: stored $rendered as \"$variable\".",
      )
    } catch (_: NotFoundException) {
      TrailblazeToolResult.Error.InvalidToolCall(
        errorMessage = "readBarcodeFromScreen: no barcode found. " +
          describeScan(scanned, attempt, formats.size),
        command = this,
      )
    } catch (cancellation: CancellationException) {
      // Cancellation is an Exception, so it has to be re-thrown ahead of the catch below or a
      // cancelled run would be reported as a tool failure.
      throw cancellation
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "readBarcodeFromScreen: failed to decode barcode: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }
  }

  /**
   * Puts the exact screenshot this read scanned into the session log, so the report shows the code
   * that was read. The step's other screenshots can predate it: a code drawn a moment after the
   * screen's text appears is missing from the assertion that waited for that text.
   *
   * Logged before decoding, on every outcome: a failed read is when the scanned image matters
   * most, so the name states what was attempted, not that it worked.
   *
   * The display name carries the variable, never the value. The image itself is not redacted — no
   * screenshot is — so a code read into a secret variable is visible here, as it already is in
   * every other screenshot of that screen.
   *
   * A snapshot log carries the view hierarchy, which `AxeScreenState` throws on when
   * `axe describe-ui` failed. The screenshot is captured separately and is the evidence here, so
   * it is logged with an empty hierarchy instead of not at all.
   */
  private fun logScannedScreen(context: TrailblazeToolExecutionContext, screenState: ScreenState) {
    val loggable = try {
      screenState.viewHierarchy
      screenState
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      WithoutViewHierarchy(screenState)
    }
    try {
      context.trailblazeLogger.logSnapshot(
        session = context.sessionProvider.invoke(),
        screenState = loggable,
        displayName = "Screen scanned for $variable",
        traceId = context.traceId,
      )
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (e: Exception) {
      // Evidence, not behavior: a log write that fails must not fail the read.
      Console.log("readBarcodeFromScreen: could not log the scanned screenshot (${e.message})")
    }
  }

  /** [delegate] with an empty view hierarchy in place of one that could not be read. */
  private class WithoutViewHierarchy(delegate: ScreenState) : ScreenState by delegate {
    override val viewHierarchy = ViewHierarchyTreeNode()
  }

  /**
   * Says what was actually scanned. Without this, "no barcode found" cannot be told apart from a
   * screenshot that arrived at a quarter of the expected size, or a crop that removed the code.
   */
  private fun describeScan(
    scanned: LuminanceSource?,
    attempt: BarcodeDecoder.Attempt?,
    formatCount: Int,
  ): String = buildString {
    if (scanned != null) append("Scanned ${scanned.width}x${scanned.height}px")
    if (attempt != null) {
      append(" across ${attempt.regionsScanned} region(s)")
      if (attempt.tiled) append(" (whole image, then tiles)")
    }
    append(" for $formatCount symbolog${if (formatCount == 1) "y" else "ies"}.")
    if (!includeLinearFormats) {
      append(" Striped retail barcodes were not included; set includeLinearFormats to scan those.")
    }
  }
}
