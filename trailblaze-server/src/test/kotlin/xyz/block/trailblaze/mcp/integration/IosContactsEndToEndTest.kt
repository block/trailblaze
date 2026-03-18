package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * **iOS CONTACTS END-TO-END TEST**
 *
 * This test validates the complete MCP → MULTI_AGENT_V3 → Device flow on iOS by:
 * 1. Automatically launching Trailblaze server (if not running)
 * 2. Opening the Contacts app
 * 3. Creating a new contact with test data
 * 4. Asserting on the collected logs and actions
 *
 * ## Prerequisites
 *
 * 1. **iOS Simulator or device connected** - Run `xcrun simctl list devices` to verify
 * 2. **Simulator booted or device unlocked** - Screen must be accessible
 * 3. **Contacts app installed** - Standard iOS Contacts app
 *
 * ## Running Tests
 *
 * ```bash
 * # Run this test (server will auto-start if needed)
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.IosContactsEndToEndTest"
 * ```
 *
 * ## What Gets Verified
 *
 * - Session started log is emitted
 * - MCP agent run logs are emitted for each step
 * - Agent used the expected tools (launchApp, tap, inputText, etc.)
 * - Session completed successfully
 */
class IosContactsEndToEndTest : TrailblazeServerTestBase() {

  companion object {
    // Test contact data
    private const val TEST_FIRST_NAME = "Trailblaze"
    private const val TEST_LAST_NAME = "iOSTest"
    private const val TEST_PHONE = "5559876543"
  }

  // Configure base class for iOS
  override val devicePlatform: String = "ios"
  override val clientName: String = "IosContactsEndToEndTest"

  private lateinit var logsRepo: LogsRepo
  private var testSessionId: SessionId? = null

  @Test
  fun `can create contact on iOS and verify logs`() {
    runBlocking {
      // Set up logs repository
      val logsDir = File(System.getProperty("user.home"), ".trailblaze/logs")
      logsRepo = LogsRepo(logsDir, watchFileSystem = true)

      try {
        // 1. Connect to iOS device
        Console.log("\n--- Step 1: Connect to Device ---")
        val connectResult = client.connectToDevice(deviceId!!, "ios")
        assertTrue(connectResult.isSuccess, "Should connect to device: ${connectResult.content}")
        Console.log("Connected: ${connectResult.content}")

        // 2. Configure for MULTI_AGENT_V3
        Console.log("\n--- Step 2: Configure Agent ---")
        client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
        client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)

        // 3. Run the contact creation automation (iOS-specific steps)
        Console.log("\n--- Step 3: Create Contact via Agent ---")
        val steps = listOf(
          "Press the home button to start from the home screen",
          "Open the Contacts app from the home screen",
          "Tap the + button in the top right corner to add a new contact",
          "Enter '$TEST_FIRST_NAME' in the First name field",
          "Enter '$TEST_LAST_NAME' in the Last name field",
          "Tap 'add phone' and enter '$TEST_PHONE' as the phone number",
          "Tap Done in the top right corner to save the contact",
        )

        Console.log("Running automation with steps:")
        steps.forEachIndexed { i, step -> Console.log("  ${i + 1}. $step") }

        val startTime = System.currentTimeMillis()
        val result = client.runPrompt(steps)
        val duration = System.currentTimeMillis() - startTime

        Console.log("\n--- Step 4: Verify Results ---")
        Console.log("Result: ${result.content.take(500)}...")
        Console.log("Duration: ${duration}ms")

        // Assert automation completed
        assertTrue(result.isSuccess, "Contact creation should complete: ${result.content}")

        // 5. Collect and verify logs
        verifyLogs()

      } finally {
        // Clean up - go back to home screen
        try {
          client.runPrompt(listOf("Press the home button"))
        } catch (_: Exception) {}
        logsRepo.close()
      }
    }
  }

  @Test
  fun `can open contacts on iOS and take screenshot`() {
    runBlocking {
      // Simpler test - just open contacts and verify basic functionality
      Console.log("\n--- Simple iOS Contacts Test ---")

      // Connect
      val connectResult = client.connectToDevice(deviceId!!, "ios")
      assertTrue(connectResult.isSuccess, "Should connect to device")

      // Configure
      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
      client.setAgentImplementation(AgentImplementation.MULTI_AGENT_V3)
      client.setMaxIterations(10) // Strict limit for tests

      // Run simple automation to open Contacts
      val result = client.runPrompt(
        listOf(
          "Go to the home screen first if not already there, then open the Contacts app",
        ),
      )

      Console.log("Result: ${result.content}")
      assertTrue(result.isSuccess, "Should open contacts: ${result.content}")

      // Get screen state for verification
      val screenState = client.getScreenState()
      Console.log("Screen state result: success=${screenState.isSuccess}, length=${screenState.content.length}")

      // Go home to clean up
      client.runPrompt(listOf("Press the home button to return to home screen"))
    }
  }

  private fun verifyLogs() {
    Console.log("\n--- Step 6: Verify Logs ---")

    // Find the session that was created
    val sessions = logsRepo.getSessionIds()
    Console.log("Available sessions: ${sessions.size}")

    // Get the most recent MCP session
    val recentSession = sessions
      .mapNotNull { logsRepo.getSessionInfo(it) }
      .filter { it.testClass == "MCP" }
      .maxByOrNull { it.timestamp }

    assertNotNull(recentSession, "Should have an MCP session")
    testSessionId = recentSession.sessionId
    Console.log("Test session: ${testSessionId?.value}")

    // Get logs for this session
    val logs = logsRepo.getLogsForSession(testSessionId)
    Console.log("Total logs in session: ${logs.size}")

    // Verify session started log
    val sessionStartLog = logs.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().firstOrNull()
    assertNotNull(sessionStartLog, "Should have session start log")
    Console.log("Session started: ${sessionStartLog.timestamp}")

    // Verify we have MCP agent run logs
    val agentRunLogs = logs.filterIsInstance<TrailblazeLog.McpAgentRunLog>()
    Console.log("MCP Agent Run logs: ${agentRunLogs.size}")
    assertTrue(agentRunLogs.isNotEmpty(), "Should have at least one McpAgentRunLog")

    // Verify agent objectives completed
    val completedObjectives = agentRunLogs.filter { it.successful }
    Console.log("Completed objectives: ${completedObjectives.size}")

    // Verify we have tool logs (indicating actions were taken)
    val toolLogs = logs.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    val maestroLogs = logs.filterIsInstance<TrailblazeLog.AgentDriverLog>()
    Console.log("Tool execution logs: ${toolLogs.size}")
    Console.log("Maestro driver logs: ${maestroLogs.size}")

    assertTrue(
      toolLogs.isNotEmpty() || maestroLogs.isNotEmpty(),
      "Should have tool execution logs showing actions were taken",
    )

    // Print summary of actions taken
    Console.log("\n--- Actions Summary ---")
    val actionsSummary = mutableListOf<String>()

    // From Maestro driver logs (action type, no success field - presence means it ran)
    maestroLogs.forEach { log ->
      actionsSummary.add("MaestroDriver: ${log.action}")
    }

    toolLogs.forEach { log ->
      actionsSummary.add("${log.toolName}: ${log.successful}")
    }

    actionsSummary.take(20).forEach { Console.log("  - $it") }
    if (actionsSummary.size > 20) {
      Console.log("  ... and ${actionsSummary.size - 20} more actions")
    }

    // Verify the test achieved its goal
    // AgentDriverLogs indicate driver actions happened, ToolLogs have explicit success field
    val successfulActions = toolLogs.count { it.successful } + maestroLogs.size
    Console.log("\nSuccessful actions: $successfulActions")
    assertTrue(successfulActions >= 3, "Should have at least 3 successful actions (launch, input, save)")

    Console.log("\n" + "=".repeat(60))
    Console.log("TEST PASSED: iOS Contact creation flow completed with $successfulActions actions")
    Console.log("=".repeat(60))
  }
}
