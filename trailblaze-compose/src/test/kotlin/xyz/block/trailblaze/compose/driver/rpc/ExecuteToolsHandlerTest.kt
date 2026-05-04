package xyz.block.trailblaze.compose.driver.rpc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.compose.target.ComposeTestTarget
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * Unit-level coverage for [ExecuteToolsHandler]'s tool-resolution error path. The full Compose
 * RPC server integration test (`ComposeRpcServerTest` in `examples/compose-desktop`) is
 * environment-gated (LLM provider config) — this test pins the partial-results contract
 * directly: when [ExecuteToolsHandler.resolveTool] throws because the toolName isn't
 * registered, the loop must convert the failure into a [TrailblazeToolResult.Error.ExceptionThrown]
 * with the original wrapper as `command` rather than letting the exception bubble out and
 * turn the whole batch into an opaque RPC failure.
 */
class ExecuteToolsHandlerTest {

  @Test
  fun `unknown tool name produces ExceptionThrown result with original tool as command`() {
    val handler = ExecuteToolsHandler(
      target = NoOpComposeTestTarget,
      mutex = Mutex(),
      viewportWidth = 100,
      viewportHeight = 100,
      // Empty repo — every lookup fails. We want the failure path, not real dispatch.
      toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "empty-for-handler-failure-test",
          toolClasses = emptySet(),
          yamlToolNames = emptySet(),
        ),
      ),
    )

    val unknownTool = OtherTrailblazeTool(
      toolName = "definitely_not_registered",
      raw = buildJsonObject { put("ref", "z639") },
    )
    val request = ExecuteToolsRequest(tools = listOf(unknownTool))

    val rpcResult = runBlocking { handler.handle(request) }

    assertThat(rpcResult).isInstanceOf(RpcResult.Success::class)
    val response = (rpcResult as RpcResult.Success).data
    assertThat(response.results).hasSize(1)
    val result = response.results.single()
    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    val error = result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(error.errorMessage).contains("definitely_not_registered")
    // The original wrapper should be attached as `command` so log readers can see what
    // the agent tried to dispatch.
    assertThat(error.command).isEqualTo(unknownTool)
  }

  /**
   * Stub that satisfies [ComposeTestTarget]'s interface without doing anything useful — the
   * resolution-failure test never reaches `executeWithCompose`, so none of these methods get
   * invoked. Throwing on access surfaces an obvious bug if a future change accidentally
   * calls into the target during the failure path.
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

  /** Silences `unused` for the imports above when running on platforms without compose-ui. */
  @Suppress("unused")
  private val ignoredJsonObjectImport: JsonObject = JsonObject(emptyMap())
}
