package xyz.block.trailblaze.toolcalls.commands.barcode

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.toolName

/**
 * Covers the tool itself — what a trail author actually depends on: the decoded text landing in
 * memory under their variable name, and each failure arriving as a result rather than an
 * exception. [BarcodeDecoderTest] covers the decoding underneath.
 */
class ReadBarcodeFromScreenTrailblazeToolTest {

  private val resolver = ToolNameResolver.fromBuiltInAndCustomTools()

  @Test
  fun `readBarcodeFromScreen resolves to its class via its tool yaml`() {
    // The .tool.yaml lives in trailblaze-common while the toolset yaml lives in
    // trailblaze-models; nothing but this assertion connects the two.
    assertTrue(resolver.isKnown("readBarcodeFromScreen"))
    assertThat(resolver.resolveOrNull("readBarcodeFromScreen"))
      .isEqualTo(ReadBarcodeFromScreenTrailblazeTool::class)
  }

  @Test
  fun `readBarcodeFromScreen is a trail-callable primitive that is never offered to the LLM`() {
    // Deliberate, not an oversight: the tool is registered by name (asserted above) but belongs
    // to no toolset, so a trail author can call it while the agent is never told it exists.
    // Decided because what to do with a decoded value is entirely use-case dependent, and there
    // is no ergonomic answer yet — better to ship the primitive than to guess at the tool.
    // Adding it to a toolset is a real product decision, so it should fail this test first.
    val everyToolsetTool = TrailblazeToolSetCatalog.defaultEntries().flatMap { it.toolClasses }

    assertTrue(ReadBarcodeFromScreenTrailblazeTool::class !in everyToolsetTool)
    assertTrue(
      everyToolsetTool.any { it.toolName().toolName == "takeSnapshot" },
      "sanity: takeSnapshot IS offered to the LLM, so an empty catalog cannot pass this",
    )
  }

  @Test
  fun `reading a barcode is read-only so the dispatcher keeps its snapshot`() {
    assertThat(ReadBarcodeFromScreenTrailblazeTool(variable = "v"))
      .isInstanceOf(ReadOnlyTrailblazeTool::class)
  }

  @Test
  fun `stores the decoded barcode in memory under the given variable`() = runBlocking<Unit> {
    val context = contextWith(BarcodeTestImages.driverEncodedScreenshotBytes())

    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "paymentQrUrl").execute(context)

    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(context.memory.variables["paymentQrUrl"]).isEqualTo(BarcodeTestImages.QR_CODE_CONTENT)
  }

  @Test
  fun `a barcode read into a secret variable is redacted in the result message`() = runBlocking<Unit> {
    // A pairing token or payment URL is exactly the kind of thing this tool reads, and the result
    // message rides into the logs and the LLM-facing surface. Pre-marking is how `--secret` and
    // `rememberSensitive` reach a variable the tool has not written yet.
    val context = contextWith(BarcodeTestImages.driverEncodedScreenshotBytes())
    context.memory.markSensitive("pairingToken")

    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "pairingToken").execute(context)

    val message = (result as TrailblazeToolResult.Success).message!!
    assertThat(message).doesNotContain(BarcodeTestImages.QR_CODE_CONTENT)
    assertThat(message).contains("[REDACTED]")
    // Redaction is a reporting concern only — the trail still gets to assert on the value.
    assertThat(context.memory.variables["pairingToken"]).isEqualTo(BarcodeTestImages.QR_CODE_CONTENT)
  }

  @Test
  fun `a screen with no barcode fails and says what was scanned`() = runBlocking<Unit> {
    val context = contextWith(BarcodeTestImages.encodeBlankPng(width = 600, height = 900))

    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "v").execute(context)

    val error = result as TrailblazeToolResult.Error
    // A bare "not found" cannot be told apart from a screenshot that arrived at the wrong size,
    // which is the failure this tool actually shipped with.
    assertThat(error.errorMessage).contains("600x900")
    assertThat(error.errorMessage).contains("region")
    assertThat(context.memory.variables["v"]).isNull()
  }

  @Test
  fun `a blank variable is refused before anything is decoded`() = runBlocking<Unit> {
    val context = contextWith(BarcodeTestImages.driverEncodedScreenshotBytes())

    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "  ").execute(context)

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(context.memory.variables.keys.contains("  ")).isEqualTo(false)
  }

  @Test
  fun `a screen state with no screenshot fails as an invalid call`() = runBlocking<Unit> {
    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "v")
      .execute(contextWith(screenshotBytes = null))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.InvalidToolCall::class)
  }

  @Test
  fun `empty screenshot bytes fail as an invalid call, like the on-device driver produces`() =
    runBlocking<Unit> {
      // `AndroidOnDeviceUiAutomatorScreenState` declares screenshotBytes non-null and returns
      // ByteArray(0) when it has no screenshot — its mirror-only fast path skips the capture. A
      // null-only guard let those zero bytes reach the platform decoder, so on-device this
      // surfaced as "could not decode 0 bytes" rather than as a missing screenshot. Null is the
      // host shape; this is the on-device one, and both have to land on the same result.
      val result = ReadBarcodeFromScreenTrailblazeTool(variable = "v")
        .execute(contextWith(ByteArray(0)))

      assertThat(result).isInstanceOf(TrailblazeToolResult.Error.InvalidToolCall::class)
      assertThat((result as TrailblazeToolResult.Error).errorMessage)
        .contains("no screenshot bytes")
    }

  @Test
  fun `undecodable screenshot bytes fail the step instead of throwing`() = runBlocking<Unit> {
    val result = ReadBarcodeFromScreenTrailblazeTool(variable = "v")
      .execute(contextWith(byteArrayOf(9, 9, 9, 9)))

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
  }

  @Test
  fun `linear symbologies are off unless the caller asks for them`() = runBlocking<Unit> {
    // The lossy JPEG capture is the image where the 1D readers return a wrong number (see
    // BarcodeDecoderTest). Reached through the tool, the default must still be the QR code.
    val default = contextWith(BarcodeTestImages.lossyJpegScreenshotBytes())
    ReadBarcodeFromScreenTrailblazeTool(variable = "v").execute(default)
    assertThat(default.memory.variables["v"]).isEqualTo(BarcodeTestImages.QR_CODE_CONTENT)

    val optedIn = contextWith(BarcodeTestImages.lossyJpegScreenshotBytes())
    ReadBarcodeFromScreenTrailblazeTool(variable = "v", includeLinearFormats = true)
      .execute(optedIn)
    assertThat(optedIn.memory.variables["v"]).isNotEqualTo(BarcodeTestImages.QR_CODE_CONTENT)
  }

  @Test
  fun `an iOS screen with no view hierarchy still decodes from the full screenshot`() =
    runBlocking<Unit> {
      // `AxeScreenState.viewHierarchy` throws when `axe describe-ui` produced no usable tree, and
      // that capture is independent of the screenshot capture. The iOS chrome crop is an
      // optimization, so a transient metadata failure must not fail a decode the screenshot alone
      // can serve.
      val context = contextWith(
        BarcodeTestImages.driverEncodedScreenshotBytes(),
        platform = TrailblazeDevicePlatform.IOS,
        viewHierarchyUnavailable = true,
      )

      val result = ReadBarcodeFromScreenTrailblazeTool(variable = "v").execute(context)

      assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(context.memory.variables["v"]).isEqualTo(BarcodeTestImages.QR_CODE_CONTENT)
    }

  private fun contextWith(
    screenshotBytes: ByteArray?,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    viewHierarchyUnavailable: Boolean = false,
  ): TrailblazeToolExecutionContext {
    val bytes = screenshotBytes
    val screen = object : ScreenState {
      override val screenshotBytes: ByteArray? = bytes
      override val deviceWidth: Int = 768
      override val deviceHeight: Int = 1105
      override val viewHierarchy: ViewHierarchyTreeNode
        get() = if (viewHierarchyUnavailable) {
          error("axe describe-ui did not produce a usable view hierarchy")
        } else {
          ViewHierarchyTreeNode()
        }
      override val trailblazeDevicePlatform = platform
      override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    }
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "barcode-test",
          trailblazeDevicePlatform = platform,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 768,
        heightPixels = 1105,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("barcode-test"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }
}
