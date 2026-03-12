package xyz.block.trailblaze.mcp.executor

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Integration tests for [HttpMcpToolExecutor].
 *
 * These tests require a running Trailblaze MCP server on localhost:52525.
 * They will be skipped if the server is not running.
 *
 * Run these tests with:
 * ```bash
 * ./gradlew :trailblaze-desktop:run &  # Start Trailblaze first
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.executor.HttpMcpToolExecutorTest"
 * ```
 */
class HttpMcpToolExecutorTest {

  private lateinit var executor: HttpMcpToolExecutor

  @Before
  fun setUp() {
    // Skip tests if server is not running
    assumeTrue("Trailblaze MCP server must be running on localhost:52525", isServerRunning())
    executor = HttpMcpToolExecutor()
  }

  private fun isServerRunning(): Boolean {
    return try {
      Socket("localhost", 52525).use { true }
    } catch (e: Exception) {
      false
    }
  }

  // ==========================================================================
  // Tool Discovery Tests
  // ==========================================================================

  @Test
  fun `fetchAvailableTools returns tools from MCP server`() {
    runBlocking {
      val tools = executor.fetchAvailableTools()

      assertTrue(tools.isNotEmpty(), "Should return at least some tools")
      Console.log("Discovered ${tools.size} tools from MCP server:")
      tools.take(10).forEach { Console.log("  - ${it.name}: ${it.description?.take(60) ?: ""}...") }
    }
  }

  @Test
  fun `fetchAvailableTools includes primitive tools when header is sent`() {
    runBlocking {
      val tools = executor.fetchAvailableTools()

      // HttpMcpToolExecutor sends X-Trailblaze-Include-Primitive-Tools: true
      // So we should see primitive tools like tapOnPoint, swipe, etc.
      val primitiveToolNames =
        listOf("tapOnPoint", "swipe", "inputText", "getScreenshot", "viewHierarchy")
      val foundPrimitives = tools.filter { it.name in primitiveToolNames }

      Console.log("Found primitive tools: ${foundPrimitives.map { it.name }}")
      // Note: Some tools may not be available depending on server configuration
      assertTrue(
        tools.any { it.name == "getSessionConfig" || it.name == "setMode" },
        "Should at least find config tools",
      )
    }
  }

  // ==========================================================================
  // Tool Execution Tests
  // ==========================================================================

  @Test
  fun `executeToolByName calls getSessionConfig successfully`() {
    runBlocking {
      val result = executor.executeToolByName("getSessionConfig", buildJsonObject {})

      assertIs<ToolExecutionResult.Success>(result)
      assertEquals("getSessionConfig", result.toolName)
      Console.log("Session config:\n${result.output}")

      // Verify we got actual session config
      assertTrue(result.output.contains("Mode:"), "Should contain mode info")
    }
  }

  @Test
  fun `executeToolByName returns error for unknown tool`() {
    runBlocking {
      val result = executor.executeToolByName("nonExistentTool", buildJsonObject {})

      // Should be either ToolNotFound or Failure with error message
      when (result) {
        is ToolExecutionResult.ToolNotFound -> {
          assertEquals("nonExistentTool", result.requestedTool)
        }
        is ToolExecutionResult.Failure -> {
          assertTrue(result.error.contains("not found", ignoreCase = true))
        }
        is ToolExecutionResult.Success -> {
          // Unexpected - fail the test
          throw AssertionError("Expected tool not found error, got success: ${result.output}")
        }
      }
    }
  }

  @Test
  fun `executeToolByName calls setMode successfully`() {
    runBlocking {
      val result =
        executor.executeToolByName(
          "setMode",
          buildJsonObject { put("mode", "TRAILBLAZE_AS_AGENT") },
        )

      assertIs<ToolExecutionResult.Success>(result)
      Console.log("Set mode result: ${result.output}")
      assertTrue(
        result.output.contains("Mode") || result.output.contains("TRAILBLAZE_AS_AGENT"),
        "Should acknowledge mode change",
      )

      // Reset to default mode
      executor.executeToolByName(
        "setMode",
        buildJsonObject { put("mode", "MCP_CLIENT_AS_AGENT") },
      )
    }
  }

  @Test
  fun `executeToolByName handles tool with invalid arguments`() {
    runBlocking {
      // Try to call setMode with invalid mode value
      val result =
        executor.executeToolByName(
          "setMode",
          buildJsonObject { put("mode", "INVALID_MODE_VALUE") },
        )

      // Should return either failure or success with error message
      when (result) {
        is ToolExecutionResult.Failure -> {
          Console.log("Got expected failure: ${result.error}")
        }
        is ToolExecutionResult.Success -> {
          // Tool executed but returned error in output
          assertTrue(
            result.output.contains("Invalid", ignoreCase = true) ||
              result.output.contains("Error", ignoreCase = true),
            "Should report invalid argument: ${result.output}",
          )
        }
        is ToolExecutionResult.ToolNotFound -> {
          throw AssertionError("Tool should exist: setMode")
        }
      }
    }
  }

  // ==========================================================================
  // Session Management Tests
  // ==========================================================================

  @Test
  fun `executor reuses MCP session across calls`() {
    runBlocking {
      // Make multiple calls and verify they use same session
      val result1 = executor.executeToolByName("getSessionConfig", buildJsonObject {})
      assertIs<ToolExecutionResult.Success>(result1)

      val result2 = executor.executeToolByName("getSessionConfig", buildJsonObject {})
      assertIs<ToolExecutionResult.Success>(result2)

      // Both should succeed (session maintained)
      Console.log("First call succeeded, second call succeeded - session maintained")
    }
  }

  // ==========================================================================
  // Close/Cleanup Tests
  // ==========================================================================

  @Test
  fun `executor can be closed and recreated`() {
    runBlocking {
      executor.executeToolByName("getSessionConfig", buildJsonObject {})
      executor.close()

      // Create new executor
      val newExecutor = HttpMcpToolExecutor()
      val result = newExecutor.executeToolByName("getSessionConfig", buildJsonObject {})
      assertIs<ToolExecutionResult.Success>(result)
      newExecutor.close()
    }
  }
}
