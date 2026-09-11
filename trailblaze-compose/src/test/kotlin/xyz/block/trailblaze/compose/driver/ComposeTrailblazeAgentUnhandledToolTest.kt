package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.assertFailsWith
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * Pins that the agent's unhandled-tool error names the TOOL the trail called.
 *
 * Every tool the runtime can't resolve reaches dispatch wrapped as a single
 * [OtherTrailblazeTool], so an error built from the class alone reads identically for all of
 * them and a failed run can't say which tool needs registering.
 */
class ComposeTrailblazeAgentUnhandledToolTest {

  @Test
  fun `unhandled tool error names the wrapped tool, not the wrapper class`() {
    val unresolvable = OtherTrailblazeTool(
      toolName = "checkout_openTipScreen",
      raw = buildJsonObject { put("ref", "z639") },
    )

    val message = dispatchAndCaptureFailure(unresolvable)

    // Anchored to the naming position, not a bare `contains`. `OtherTrailblazeTool` is a data
    // class, so its `toString` — printed further down the message — already carries
    // `toolName=checkout_openTipScreen`. An unanchored match would pass against the very bug
    // this pins: a message whose subject is the wrapper class.
    assertThat(message).contains("Unhandled Trailblaze tool checkout_openTipScreen")
    assertThat(message).contains("arrived unresolved as OtherTrailblazeTool")
  }

  /**
   * The other side of the branch: a tool the agent can't dispatch that did NOT arrive wrapped.
   * It has a real class name to report, and the wrapper explanation would be a lie — so the
   * message must name the class and omit the clause.
   */
  @Test
  fun `unhandled tool error names the class and omits the wrapper clause when not wrapped`() {
    val message = dispatchAndCaptureFailure(UnsupportedShapeTool)

    assertThat(message).contains("Unhandled Trailblaze tool UnsupportedShapeTool")
    assertThat(message).doesNotContain("arrived unresolved as OtherTrailblazeTool")
  }

  /** Dispatches [tool] through the public entry point and returns the failure message. */
  private fun dispatchAndCaptureFailure(tool: TrailblazeTool): String {
    val agent = ComposeTrailblazeAgent(
      target = NoOpComposeTestTarget,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = { DEVICE_INFO },
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(
          sessionId = SessionId("unhandled-tool-test"),
          startTime = Clock.System.now(),
        )
      },
      // No repo, so a wrapper survives resolution and reaches the unsupported-tool branch —
      // exactly what a trail calling a tool nobody registered does at runtime.
      trailblazeToolRepo = null,
    )

    val exception = assertFailsWith<TrailblazeException> {
      agent.runTrailblazeTools(
        tools = listOf(tool),
        elementComparator = NoOpElementComparator,
      )
    }
    return exception.message.orEmpty()
  }

  /** A tool implementing none of the shapes the Compose agent dispatches. */
  @Serializable
  private object UnsupportedShapeTool : TrailblazeTool

  private companion object {
    val DEVICE_INFO = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "compose-unhandled-tool-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
      ),
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = 100,
      heightPixels = 100,
    )
  }

  /**
   * Stub target: dispatch throws before anything touches the Compose surface, so every member
   * erroring surfaces an accidental call into the target on this path.
   */
  private object NoOpComposeTestTarget : ComposeTestTarget {
    override fun rootSemanticsNode(): SemanticsNode = error("not invoked in failure-path tests")
    override fun allSemanticsNodes(): List<SemanticsNode> = error("not invoked in failure-path tests")
    override fun click(node: SemanticsNode) = error("not invoked in failure-path tests")
    override fun typeText(node: SemanticsNode, text: String) = error("not invoked in failure-path tests")
    override fun clearText(node: SemanticsNode) = error("not invoked in failure-path tests")
    override fun scrollToIndex(node: SemanticsNode, index: Int) = error("not invoked in failure-path tests")
    override fun captureScreenshot(): ImageBitmap? = null
    override fun waitForIdle() = Unit
  }
}
