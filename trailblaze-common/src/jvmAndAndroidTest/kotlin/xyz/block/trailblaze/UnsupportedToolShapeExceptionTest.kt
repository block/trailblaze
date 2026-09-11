package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.InstanceNamedTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Pins [BaseTrailblazeAgent.unsupportedToolShapeException] — the one message every driver agent
 * throws for a tool it can't dispatch.
 *
 * The bug this replaced: each driver built the message from `tool::class.simpleName`, and every
 * unresolvable tool reaches dispatch wrapped in a single [OtherTrailblazeTool]. So one message
 * covered all of them and named none, leaving a failed run without the one fact needed to fix it.
 */
class UnsupportedToolShapeExceptionTest {

  @Test
  fun `names the wrapped tool and reports arriving unresolved as an observation`() {
    val message = ProbeAgent.messageFor(
      OtherTrailblazeTool(
        toolName = "checkout_openTipScreen",
        raw = buildJsonObject { put("ref", "z639") },
      ),
    )

    // Anchored to the naming position. `OtherTrailblazeTool` is a data class, so an unanchored
    // `contains("checkout_openTipScreen")` is satisfied by the `Tool:` dump further down — it
    // would pass against the very bug this pins.
    assertThat(message).contains("Unhandled Trailblaze tool checkout_openTipScreen")
    assertThat(message).contains("arrived unresolved as OtherTrailblazeTool")
  }

  /**
   * Arriving wrapped means only that nothing resolved the name. The message must not pin that on
   * the name being unregistered: a registered tool whose args don't fit its schema lands here
   * too, as does any agent holding no repo at all — the Compose agent holds none in production,
   * so it never performs the lookup a single-cause message would be reporting.
   */
  @Test
  fun `does not blame the wrapper on an unregistered name alone`() {
    val message = ProbeAgent.messageFor(OtherTrailblazeTool(toolName = "checkout_openTipScreen"))

    assertThat(message).contains("it may be unregistered")
    assertThat(message).contains("may not fit the tool that is registered under it")
    assertThat(message).contains("may hold no tool repo to resolve against")
  }

  /**
   * `raw` is unredacted wire data and this message reaches CI logs and LLM-facing error content,
   * so a trail that inlines a credential must not have it copied through. Argument NAMES are
   * what diagnose a schema mismatch; the values never are.
   */
  @Test
  fun `reports argument names but never their values`() {
    val message = ProbeAgent.messageFor(
      OtherTrailblazeTool(
        toolName = "checkout_openTipScreen",
        raw = buildJsonObject {
          put("password", "hunter2-do-not-log")
          put("ref", "z639")
        },
      ),
    )

    assertThat(message).contains("Arguments provided (values omitted): password, ref")
    assertThat(message).doesNotContain("hunter2-do-not-log")
    assertThat(message).doesNotContain("z639")
  }

  @Test
  fun `names the class and omits the wrapper note when the tool did not arrive wrapped`() {
    val message = ProbeAgent.messageFor(UnsupportedShapeTool)

    assertThat(message).contains("Unhandled Trailblaze tool UnsupportedShapeTool")
    assertThat(message).doesNotContain("arrived unresolved as OtherTrailblazeTool")
  }

  /**
   * Every `tools:`-authored tool shares one class annotation, so resolving the name by annotation
   * collapses all of them into a single indistinguishable token — the same anonymity as printing
   * the wrapper class. The instance's own name has to win.
   */
  @Test
  fun `prefers an instance's own name over its class`() {
    val message = ProbeAgent.messageFor(SelfNamingTool("kiosk_openDrawer"))

    assertThat(message).contains("Unhandled Trailblaze tool kiosk_openDrawer")
    assertThat(message).doesNotContain("Unhandled Trailblaze tool SelfNamingTool")
  }

  @Test
  fun `lists the agent's supported shapes and closes with what to register`() {
    val message = ProbeAgent.messageFor(UnsupportedShapeTool)

    assertThat(message).contains("ProbeAgent supports:")
    assertThat(message).contains("- ProbeExecutableTool")
    assertThat(message).contains("Register a ProbeExecutableTool.")
  }

  /**
   * The Compose-RPC and Revyl agents REPORT this failure as a `TrailblazeToolResult.Error` instead
   * of throwing, so a batch keeps the results of the tools that already ran. They read the message
   * off the string overload. If the two ever diverge, those two drivers silently go back to a
   * different — and historically worse — message than every other driver.
   */
  @Test
  fun `the reported message and the thrown message are the same text`() {
    val tool = OtherTrailblazeTool(
      toolName = "checkout_openTipScreen",
      raw = buildJsonObject { put("ref", "z639") },
    )

    assertThat(ProbeAgent.messageFor(tool)).isEqualTo(ProbeAgent.reportedMessageFor(tool))
  }

  @Serializable
  private object UnsupportedShapeTool : TrailblazeTool

  @Serializable
  private data class SelfNamingTool(
    override val instanceToolName: String,
  ) : TrailblazeTool, InstanceNamedTrailblazeTool

  /**
   * Minimal agent that exists only to reach the protected helper. Its `executeTool` throws the
   * exception under test for anything it is handed, which is every tool.
   */
  private object ProbeAgent : BaseTrailblazeAgent() {
    fun messageFor(tool: TrailblazeTool): String =
      unsupportedToolShapeException(
        tool = tool,
        agentName = "ProbeAgent",
        supportedShapes = listOf("ProbeExecutableTool"),
        remediation = "Register a ProbeExecutableTool.",
      ).message.orEmpty()

    /** What the result-returning agents read instead of throwing. */
    fun reportedMessageFor(tool: TrailblazeTool): String =
      unsupportedToolShapeMessage(
        tool = tool,
        agentName = "ProbeAgent",
        supportedShapes = listOf("ProbeExecutableTool"),
        remediation = "Register a ProbeExecutableTool.",
      )

    override val trailblazeLogger = TrailblazeLogger.createNoOp()
    override val trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "probe",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
    override val sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("probe-session"), startTime = Clock.System.now())
    }

    override fun buildExecutionContext(
      traceId: TraceId,
      screenState: ScreenState?,
      screenStateProvider: (() -> ScreenState)?,
    ) = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = traceId,
      trailblazeDeviceInfo = trailblazeDeviceInfoProvider(),
      sessionProvider = sessionProvider,
      trailblazeLogger = trailblazeLogger,
      memory = memory,
    )

    override fun executeTool(
      tool: TrailblazeTool,
      context: TrailblazeToolExecutionContext,
      toolsExecuted: MutableList<TrailblazeTool>,
    ): TrailblazeToolResult = throw unsupportedToolShapeException(
      tool = tool,
      agentName = "ProbeAgent",
      supportedShapes = listOf("ProbeExecutableTool"),
      remediation = "Register a ProbeExecutableTool.",
    )
  }
}
