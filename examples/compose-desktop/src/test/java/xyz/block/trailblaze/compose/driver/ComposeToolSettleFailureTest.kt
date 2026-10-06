package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeScrollTool
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * A Compose tool whose action landed reports success even when the settle wait after it times out.
 *
 * Failed tool calls are never recorded, so a click reported as failed after it changed the app
 * would be missing from the recording, and replay would run every later step from the wrong
 * screen. An app exception the settle rethrows is different: the app broke, so the call fails.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeToolSettleFailureTest {

  @Test
  fun `click that landed succeeds when the settle after it times out`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    onNodeWithTag("todo_input").performTextInput("Buy milk")
    val target = SettleFailingTarget(this)

    val result = runBlocking {
      ComposeClickTool(testTag = "add_button").executeWithCompose(target, stubContext())
    }

    assertThat(target.settleFailures).isGreaterThan(0)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(onAllNodes(hasText("1 items")).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun `typing that landed succeeds when the settle after it times out`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    val target = SettleFailingTarget(this)

    val result = runBlocking {
      ComposeTypeTool(text = "Buy milk", testTag = "todo_input")
        .executeWithCompose(target, stubContext())
    }

    assertThat(target.settleFailures).isGreaterThan(0)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat(onAllNodes(hasText("Buy milk")).fetchSemanticsNodes()).isNotEmpty()
  }

  @Test
  fun `scroll that landed succeeds when the settle after it times out`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    onNodeWithTag("todo_input").performTextInput("Buy milk")
    onNodeWithTag("add_button").performClick()
    val target = SettleFailingTarget(this)

    val result = runBlocking {
      ComposeScrollTool(testTag = "todo_list", index = 0).executeWithCompose(target, stubContext())
    }

    assertThat(target.settleFailures).isGreaterThan(0)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Success::class)
  }

  @Test
  fun `click still fails when the settle rethrows an app exception`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    onNodeWithTag("todo_input").performTextInput("Buy milk")
    val target = SettleFailingTarget(this, settleFailure = IllegalStateException("app crashed"))

    val result = runBlocking {
      ComposeClickTool(testTag = "add_button").executeWithCompose(target, stubContext())
    }

    assertThat(target.settleFailures).isGreaterThan(0)
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat((result as TrailblazeToolResult.Error).errorMessage)
      .isEqualTo("Click failed on 'add_button': app crashed")
  }

  @Test
  fun `click that throws still fails`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    onNodeWithTag("todo_input").performTextInput("Buy milk")
    val target = SettleFailingTarget(this, clickFailure = IllegalStateException("click rejected"))

    val result = runBlocking {
      ComposeClickTool(testTag = "add_button").executeWithCompose(target, stubContext())
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat((result as TrailblazeToolResult.Error).errorMessage)
      .isEqualTo("Click failed on 'add_button': click rejected")
    assertThat(onAllNodes(hasText("0 items")).fetchSemanticsNodes()).isNotEmpty()
  }

  /**
   * The real [ComposeUiTestTarget], except that every settle wait throws [settleFailure] — by
   * default a timeout, as if the app never went idle — and [click] can be made to fail.
   */
  private class SettleFailingTarget(
    composeUiTest: ComposeUiTest,
    private val settleFailure: Throwable = ComposeTimeoutException("UI never went idle"),
    private val clickFailure: Exception? = null,
  ) : ComposeTestTarget {
    private val real = ComposeUiTestTarget(composeUiTest)
    var settleFailures = 0
      private set

    override fun waitForIdle() {
      settleFailures++
      throw settleFailure
    }

    override fun click(node: SemanticsNode) {
      clickFailure?.let { throw it }
      real.click(node)
    }

    override fun rootSemanticsNode(): SemanticsNode = real.rootSemanticsNode()
    override fun allRootSemanticsNodes(): List<SemanticsNode> = real.allRootSemanticsNodes()
    override fun allSemanticsNodes(): List<SemanticsNode> = real.allSemanticsNodes()
    override fun typeText(node: SemanticsNode, text: String) = real.typeText(node, text)
    override fun clearText(node: SemanticsNode) = real.clearText(node)
    override fun scrollToIndex(node: SemanticsNode, index: Int) = real.scrollToIndex(node, index)
    override fun captureScreenshot(): ImageBitmap? = real.captureScreenshot()
  }

  private fun stubContext() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "compose-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
      ),
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = 1280,
      heightPixels = 800,
    ),
    sessionProvider = {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
