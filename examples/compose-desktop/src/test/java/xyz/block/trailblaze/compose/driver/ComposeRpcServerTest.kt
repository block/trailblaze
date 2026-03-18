package xyz.block.trailblaze.compose.driver

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.prop
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer
import xyz.block.trailblaze.compose.driver.rpc.ExecuteToolsRequest
import xyz.block.trailblaze.compose.target.ComposeUiTestTarget
import xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSet
import xyz.block.trailblaze.compose.driver.tools.ComposeTypeTool
import xyz.block.trailblaze.compose.driver.tools.ComposeVerifyTextVisibleTool
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.getOrThrow
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.toolName
import java.net.ServerSocket

/**
 * Integration tests for [ComposeRpcServer] and [ComposeRpcClient].
 *
 * Each test spins up a real Ktor server wrapping a Compose UI ([SampleTodoApp]), then exercises it
 * via the HTTP client. This proves the RPC round-trip works against real Compose semantics.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeRpcServerTest {

  private fun findAvailablePort(): Int = ServerSocket(0).use { it.localPort }


  init {
    TrailblazeJsonInstance =
      TrailblazeJson.createTrailblazeJsonInstance(
        allToolClasses =
          TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName +
            ComposeToolSet.LlmToolSet.toolClasses.associateBy { it.toolName() },
      )
  }

  @Test
  fun `ping returns running status`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      val ready = runBlocking { client.waitForServer() }
      assertThat(ready).isTrue()
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `getScreenState returns view hierarchy and semantics text`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val state = runBlocking { client.getScreenState().getOrThrow() }

      assertThat(state.width).isGreaterThan(0)
      assertThat(state.height).isGreaterThan(0)
      assertThat(state.semanticsTreeText).isNotEmpty()
      assertThat(state.semanticsTreeText).contains("[testTag=${SampleTodoApp.TAG_ADD_BUTTON}]")
      assertThat(state.semanticsTreeText).contains("[testTag=${SampleTodoApp.TAG_TODO_INPUT}]")
      assertThat(state.viewHierarchy)
        .prop(ViewHierarchyTreeNode::children)
        .isNotEmpty()
      assertThat(
        state.viewHierarchy.aggregate().any {
          it.resourceId?.contains(SampleTodoApp.TAG_ADD_BUTTON) == true
        }
      ).isTrue()
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `getScreenState includes elementIdMapping`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val state = runBlocking { client.getScreenState().getOrThrow() }

      assertThat(state.elementIdMapping).isNotEmpty()
      val addButtonEntry =
        state.elementIdMapping.entries.find {
          it.value.testTag == SampleTodoApp.TAG_ADD_BUTTON
        }
      assertThat(addButtonEntry).isNotNull()
      assertThat(addButtonEntry!!.key).contains("e")
      assertThat(addButtonEntry.value.descriptor).contains("button")
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `getScreenState includes screenshot`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val state = runBlocking { client.getScreenState().getOrThrow() }

      assertThat(state.screenshotBase64).isNotNull()
      assertThat(state.screenshotBase64!!.length).isGreaterThan(0)
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `executeTools runs a single click tool`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val typeResponse =
        runBlocking {
          client
            .executeTools(
              ExecuteToolsRequest(
                tools =
                  listOf(
                    ComposeTypeTool(
                      text = "RPC item",
                      testTag = SampleTodoApp.TAG_TODO_INPUT,
                    )
                  )
              )
            )
            .getOrThrow()
        }
      assertThat(typeResponse.results).isNotEmpty()
      assertThat(typeResponse.results.first()).isInstanceOf(TrailblazeToolResult.Success::class)

      val clickResponse =
        runBlocking {
          client
            .executeTools(
              ExecuteToolsRequest(
                tools =
                  listOf(ComposeClickTool(testTag = SampleTodoApp.TAG_ADD_BUTTON))
              )
            )
            .getOrThrow()
        }
      assertThat(clickResponse.results).isNotEmpty()
      assertThat(clickResponse.results.first()).isInstanceOf(TrailblazeToolResult.Success::class)

      val state = runBlocking { client.getScreenState().getOrThrow() }
      assertThat(state.semanticsTreeText).contains("1 items")
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `executeTools runs multi-tool batch`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val response =
        runBlocking {
          client
            .executeTools(
              ExecuteToolsRequest(
                tools =
                  listOf(
                    ComposeTypeTool(
                      text = "Batch item",
                      testTag = SampleTodoApp.TAG_TODO_INPUT,
                    ),
                    ComposeClickTool(testTag = SampleTodoApp.TAG_ADD_BUTTON),
                  )
              )
            )
            .getOrThrow()
        }

      assertThat(response.results.size).isEqualTo(2)
      assertThat(response.results[0]).isInstanceOf(TrailblazeToolResult.Success::class)
      assertThat(response.results[1]).isInstanceOf(TrailblazeToolResult.Success::class)

      val state = runBlocking { client.getScreenState().getOrThrow() }
      assertThat(state.semanticsTreeText).contains("1 items")
    } finally {
      client.close()
      server.stop()
    }
  }

  @Test
  fun `executeTools stops on first error and returns partial results`() = runComposeUiTest {
    setContent { SampleTodoApp() }
    waitForIdle()

    val port = findAvailablePort()
    val server = ComposeRpcServer(ComposeUiTestTarget(this), port = port)
    val client = ComposeRpcClient("http://localhost:$port")
    server.start(wait = false)
    try {
      runBlocking { client.waitForServer() }

      val response =
        runBlocking {
          client
            .executeTools(
              ExecuteToolsRequest(
                tools =
                  listOf(
                    ComposeVerifyTextVisibleTool(text = "this text does not exist"),
                    ComposeClickTool(testTag = SampleTodoApp.TAG_ADD_BUTTON),
                  )
              )
            )
            .getOrThrow()
        }

      assertThat(response.results.size).isEqualTo(1)
      assertThat(response.results.first().isSuccess()).isEqualTo(false)
    } finally {
      client.close()
      server.stop()
    }
  }
}
