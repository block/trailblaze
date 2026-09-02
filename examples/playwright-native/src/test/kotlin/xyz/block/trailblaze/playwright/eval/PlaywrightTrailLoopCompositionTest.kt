package xyz.block.trailblaze.playwright.eval

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeWaitTool
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Pins the trail-loop threading contract: while a host-local tool is EXECUTING (which blocks
 * the trail-loop thread — tool dispatch is non-suspend), a nested composition arriving on a
 * different thread must still be able to run a Playwright tool.
 *
 * This is the shape of every `runtime: subprocess` scripted tool that composes a Playwright
 * tool: the subprocess POSTs `/scripting/callback`, whose handler invokes
 * `context.nestedToolExecutor` from a server thread, and the nested Playwright dispatch
 * bridges onto the Playwright thread via `PlaywrightPageManager.onPlaywrightThread`.
 *
 * The regression this guards (the dark `trailblaze-web-pr` lane): `runTrailblazeYamlSuspend`
 * used to run the whole trail loop ON the Playwright thread, so the blocked host-local
 * dispatch parked the very thread the nested bridge needed — every such composition hung
 * for the subprocess's full 122s callback timeout. If the loop is ever moved back onto
 * `browserManager.playwrightDispatcher`, the probe below times out and this test fails.
 */
class PlaywrightTrailLoopCompositionTest {

  /**
   * The rule builds its agent runner eagerly enough that a client must exist, but this trail is
   * fully recorded — no step ever reaches the LLM. A no-credential client keeps the test
   * hermetic: any call would fail loudly rather than reach a provider.
   */
  private val offlineLlmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = OpenAILLMClient(apiKey = "not-a-real-key")
  }

  private val playwrightTest =
    BasePlaywrightNativeTest(
      dynamicLlmClient = offlineLlmClient,
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "playwright-trail-loop-composition",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        ),
      customToolClasses = setOf(NestedPlaywrightCompositionProbeTool::class),
    )

  @JvmField
  @RegisterExtension
  val loggingExtension = TestRuleExtension(playwrightTest.loggingRule)

  @AfterEach
  fun tearDown() {
    playwrightTest.close()
  }

  @Test
  fun nestedPlaywrightToolCompletesWhileHostLocalToolIsExecuting() {
    val yaml = """
      trail:
        - step: Compose a Playwright tool from inside a running host-local tool
          recording:
            web:
              - nestedPlaywrightCompositionProbe: {}
    """.trimIndent()

    // The probe returns Error (→ TrailblazeException out of the trail run) when the nested
    // Playwright tool fails to complete within its budget, so a plain successful run IS the
    // assertion.
    runBlocking {
      playwrightTest.runTrailblazeYamlSuspend(
        yaml = yaml,
        trailblazeDeviceId = playwrightTest.trailblazeDeviceInfo.trailblazeDeviceId,
        trailFilePath = null,
        sendSessionStartLog = true,
      )
    }
  }
}

/**
 * Host-local tool that, mid-execution, invokes a Playwright tool through the context's
 * `nestedToolExecutor` from a separate thread — the minimal stand-in for a subprocess
 * scripted tool composing a `web_*` tool through `/scripting/callback`.
 */
@Serializable
@TrailblazeToolClass("nestedPlaywrightCompositionProbe")
class NestedPlaywrightCompositionProbeTool : HostLocalExecutableTrailblazeTool {
  override val advertisedToolName: String = "nestedPlaywrightCompositionProbe"

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val nested = toolExecutionContext.nestedToolExecutor
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "nestedToolExecutor is not wired on this execution context",
      )
    val nestedResult = AtomicReference<TrailblazeToolResult?>(null)
    // A plain thread, matching production: the `/scripting/callback` handler runs on a server
    // worker thread, never on the trail-loop thread this tool is currently blocking.
    val callbackThread = Thread {
      nestedResult.set(runBlocking { nested(PlaywrightNativeWaitTool(seconds = 1)) })
    }
    callbackThread.isDaemon = true
    callbackThread.start()
    callbackThread.join(NESTED_COMPLETION_TIMEOUT_MS)
    return when {
      callbackThread.isAlive -> {
        // Don't leave the probe thread blocked past this tool's return — it would keep the
        // deadlocked nested dispatch alive into teardown. Interrupt cancels its runBlocking.
        callbackThread.interrupt()
        TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "Nested Playwright tool did not complete within ${NESTED_COMPLETION_TIMEOUT_MS}ms " +
            "while a host-local tool was executing — the Playwright thread is parked under the " +
            "trail loop (the subprocess-composition deadlock).",
        )
      }
      else -> nestedResult.get() ?: TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Nested Playwright dispatch finished without producing a result",
      )
    }
  }

  companion object {
    /**
     * Generous against a healthy run (the nested wait tool needs ~1.5–2s including the
     * post-action settle), tiny against the failure mode it detects (a 122s callback-timeout
     * hang per composition).
     */
    const val NESTED_COMPLETION_TIMEOUT_MS: Long = 20_000L
  }
}
