package xyz.block.trailblaze.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.mcp.models.McpSessionId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TrailblazeMcpSessionContext] progress notification functionality.
 *
 * These tests verify:
 * - Determinate progress messages (with current/total)
 * - Indeterminate progress messages (without total)
 * - Custom SSE notification sender
 * - Progress token handling
 */
class TrailblazeMcpSessionContextProgressTest {

  /**
   * Test that determinate progress messages are sent correctly.
   * Verifies the message format, progress values, and token handling.
   */
  @Test
  fun testDeterminateProgressMessage() = runBlocking {
    // Track sent notifications
    val sentNotifications = mutableListOf<String>()

    // Create session context with custom SSE sender
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = RequestId.StringId("test-progress-token"),
    )

    // Set up custom SSE notification sender to capture messages
    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send determinate progress message
    sessionContext.sendProgressMessage(
      message = "Installing app...",
      current = 3,
      total = 10,
    )

    // Give coroutines time to process
    delay(100)

    // Verify notification was sent
    assertEquals(1, sentNotifications.size, "Expected one notification to be sent")

    val notification = sentNotifications[0]

    // Verify JSON-RPC format
    assertTrue(notification.contains("\"jsonrpc\":\"2.0\""), "Should have JSON-RPC version")
    assertTrue(notification.contains("\"method\":\"notifications/progress\""), "Should have progress method")

    // Verify progress token
    assertTrue(notification.contains("\"progressToken\":\"test-progress-token\""), "Should include progress token")

    // Verify determinate values
    assertTrue(notification.contains("\"progress\":3"), "Should have current progress value")
    assertTrue(notification.contains("\"total\":10"), "Should have total value")

    // Verify message
    assertTrue(notification.contains("\"message\":\"Installing app...\""), "Should include message")
  }

  /**
   * Test that indeterminate progress messages are sent correctly.
   * Verifies that total is omitted for indeterminate progress.
   */
  @Test
  fun testIndeterminateProgressMessage() = runBlocking {
    val sentNotifications = mutableListOf<String>()

    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = RequestId.StringId("test-token"),
    )

    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send indeterminate progress message
    sessionContext.sendIndeterminateProgressMessage("Processing...")

    delay(100)

    assertEquals(1, sentNotifications.size)
    val notification = sentNotifications[0]

    // Verify indeterminate format (no total field)
    assertTrue(notification.contains("\"progress\":0"), "Should have auto-incremented progress")
    assertTrue(!notification.contains("\"total\":"), "Should NOT have total for indeterminate")
    assertTrue(notification.contains("\"message\":\"Processing...\""), "Should include message")
  }

  /**
   * Test multiple determinate progress messages showing incremental progress.
   */
  @Test
  fun testMultipleDeterminateProgressMessages() = runBlocking {
    val sentNotifications = mutableListOf<String>()

    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = RequestId.StringId("multi-progress-token"),
    )

    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send a sequence of progress updates
    val steps = listOf(
      Triple("Preparing device", 1, 5),
      Triple("Installing app", 2, 5),
      Triple("Launching app", 3, 5),
      Triple("Initializing test", 4, 5),
      Triple("Running test", 5, 5),
    )

    steps.forEach { (message, current, total) ->
      sessionContext.sendProgressMessage(message, current, total)
      delay(50)
    }

    // Verify we got all 5 notifications
    assertEquals(5, sentNotifications.size, "Should have 5 progress notifications")

    // Verify each notification has correct progress values
    steps.forEachIndexed { index, (message, current, total) ->
      val notification = sentNotifications[index]
      assertTrue(notification.contains("\"progress\":$current"), "Step $index should have progress=$current")
      assertTrue(notification.contains("\"total\":$total"), "Step $index should have total=$total")
      assertTrue(notification.contains("\"message\":\"$message\""), "Step $index should have message")
    }
  }

  /**
   * Test that progress messages handle special characters in messages correctly.
   */
  @Test
  fun testProgressMessageWithSpecialCharacters() = runBlocking {
    val sentNotifications = mutableListOf<String>()

    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = RequestId.StringId("special-char-token"),
    )

    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send message with special characters that need escaping
    sessionContext.sendProgressMessage(
      message = "Installing \"Test App\" with newline\nand backslash\\",
      current = 1,
      total = 1,
    )

    delay(100)

    assertEquals(1, sentNotifications.size)
    val notification = sentNotifications[0]

    // Verify JSON escaping is correct
    assertTrue(notification.contains("\\\"Test App\\\""), "Should escape quotes")
    assertTrue(notification.contains("\\n"), "Should escape newlines")
    assertTrue(notification.contains("\\\\"), "Should escape backslashes")
  }

  /**
   * Test progress messages without a progress token (fallback to logging notification).
   */
  @Test
  fun testProgressMessageWithoutToken() = runBlocking {
    val sentNotifications = mutableListOf<String>()

    // Create session without progress token
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = null, // No token
    )

    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send progress message without token
    sessionContext.sendProgressMessage("Testing without token", 1, 3)

    delay(100)

    assertEquals(1, sentNotifications.size)
    val notification = sentNotifications[0]

    // Should fall back to logging notification
    assertTrue(notification.contains("\"method\":\"notifications/message\""), "Should use message notification")
    assertTrue(notification.contains("[progress]"), "Should include [progress] prefix")
    assertTrue(notification.contains("Testing without token"), "Should include message")
  }

  /**
   * Test that progress counter auto-increments for indeterminate progress.
   */
  @Test
  fun testIndeterminateProgressCounterIncrement() = runBlocking {
    val sentNotifications = mutableListOf<String>()

    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
      mcpProgressToken = RequestId.StringId("counter-test"),
    )

    sessionContext.customSseNotificationSender = { jsonRpcMessage ->
      sentNotifications.add(jsonRpcMessage)
    }

    // Send multiple indeterminate messages
    repeat(3) { index ->
      sessionContext.sendIndeterminateProgressMessage("Step $index")
      delay(50)
    }

    assertEquals(3, sentNotifications.size)

    // Verify counter increments (0, 1, 2)
    (0..2).forEach { expectedProgress ->
      val notification = sentNotifications[expectedProgress]
      assertTrue(
        notification.contains("\"progress\":$expectedProgress"),
        "Notification $expectedProgress should have auto-incremented progress value",
      )
    }
  }

  /**
   * Test session configuration description includes correct mode.
   */
  @Test
  fun testSessionConfigurationDescription() {
    val sessionContext = TrailblazeMcpSessionContext(
      mcpServerSession = null,
      mcpSessionId = McpSessionId("test-session"),
      mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
    )

    val description = sessionContext.describeConfiguration()

    assertTrue(description.contains("Mode: MCP_CLIENT_AS_AGENT"), "Should show current mode")
    assertNotNull(description, "Description should not be null")
  }
}
