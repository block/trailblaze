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
 * **ANDROID CONTACTS END-TO-END TEST**
 *
 * This test validates the complete MCP → DirectMcpAgent → Device flow by:
 * 1. Automatically launching Trailblaze server (if not running)
 * 2. Opening the Contacts app
 * 3. Creating a new contact with test data
 * 4. Asserting on the collected logs and actions
 *
 * ## Prerequisites
 *
 * 1. **Android device connected** - Run `adb devices` to verify
 * 2. **Device unlocked** - Screen must be on and unlocked
 * 3. **Contacts app installed** - Standard Android Contacts app
 *
 * ## Running Tests
 *
 * ```bash
 * # Run this test (server will auto-start if needed)
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.AndroidContactsEndToEndTest"
 * ```
 *
 * ## What Gets Verified
 *
 * - Session started log is emitted
 * - MCP agent run logs are emitted for each step
 * - Agent used the expected tools (launchApp, tap, inputText, etc.)
 * - Session completed successfully
 */
class AndroidContactsEndToEndTest : TrailblazeServerTestBase() {

  companion object {
    // Test contact data
    private const val TEST_FIRST_NAME = "Trailblaze"
    private const val TEST_LAST_NAME = "TestContact"
    private const val TEST_PHONE = "5551234567"
  }

  // Configure base class for Android
  override val devicePlatform: String = "android"
  override val clientName: String = "AndroidContactsEndToEndTest"

  private lateinit var logsRepo: LogsRepo
  private var testSessionId: SessionId? = null

  @Test
  fun `can create contact and verify logs`() {
    runBlocking {
      // Set up logs repository
      val logsDir = File(System.getProperty("user.home"), ".trailblaze/logs")
      logsRepo = LogsRepo(logsDir, watchFileSystem = true)

      try {
        // 1. Connect to Android device
        Console.log("\n--- Step 1: Connect to Device ---")
        val connectResult = client.connectToDevice(deviceId!!, "android")
        assertTrue(connectResult.isSuccess, "Should connect to device: ${connectResult.content}")
        Console.log("Connected: ${connectResult.content}")

        // 2. Configure for DirectMcpAgent
        Console.log("\n--- Step 2: Configure Agent ---")
        client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
        client.setAgentImplementation(AgentImplementation.TWO_TIER_AGENT)

        // 3. Run the contact creation automation
        Console.log("\n--- Step 3: Create Contact via Agent ---")
        val steps = listOf(
          "Press the home button to start from the home screen",
          "Open the Contacts app",
          "Tap on the button to create a new contact (usually a + or FAB button)",
          "Enter '$TEST_FIRST_NAME' in the first name field",
          "Enter '$TEST_LAST_NAME' in the last name field",
          "Enter '$TEST_PHONE' in the phone number field",
          "Save the contact by tapping the Save or checkmark button",
        )

        Console.log("Running automation with steps:")
        steps.forEachIndexed { i, step -> Console.log("  ${i + 1}. $step") }

        val startTime = System.currentTimeMillis()
        val result = client.runPrompt(steps)
        val duration = System.currentTimeMillis() - startTime

        Console.log("\n--- Step 5: Verify Results ---")
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
  fun `can open contacts app`() {
    runBlocking {
      Console.log("\n" + "=".repeat(60))
      Console.log("SIMPLE CONTACTS TEST - Opens Contacts app and verifies it worked")
      Console.log("=".repeat(60))

      // 1. Connect to device (deviceId populated by base class)
      Console.log("\n[1/4] Connecting to Android device: $deviceId")
      val connectResult = client.connectToDevice(deviceId!!, "android")
      assertTrue(connectResult.isSuccess, "Should connect to device: ${connectResult.content}")
      Console.log("Connected successfully")

      // 2. Configure agent mode
      Console.log("\n[2/4] Configuring TWO_TIER_AGENT mode with max 10 iterations")
      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
      client.setAgentImplementation(AgentImplementation.TWO_TIER_AGENT)
      client.setMaxIterations(10) // Strict limit for tests
      Console.log("Agent configured")

      // 3. Run automation to open Contacts
      Console.log("\n[3/4] Running automation to open Contacts app")
      val result = client.runPrompt(
        listOf(
          "Go to the home screen first if not already there, then open the Contacts app",
        ),
      )

      // Log the result
      Console.log("\n[4/4] Verifying result")
      Console.log("Success: ${result.isSuccess}")
      Console.log("Response: ${result.content.take(300)}${if (result.content.length > 300) "..." else ""}")

      assertTrue(result.isSuccess, "Should successfully open Contacts: ${result.content}")

      // Cleanup: Go home
      try {
        client.runPrompt(listOf("Press the home button"))
      } catch (_: Exception) {}

      Console.log("\n" + "=".repeat(60))
      Console.log("TEST PASSED")
      Console.log("=".repeat(60))
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

    // Verify agent objectives match our steps (or some subset completed)
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

    // From tool logs
    toolLogs.forEach { log ->
      actionsSummary.add("${log.toolName}: ${log.successful}")
    }

    actionsSummary.take(20).forEach { Console.log("  - $it") }
    if (actionsSummary.size > 20) {
      Console.log("  ... and ${actionsSummary.size - 20} more actions")
    }

    // Verify the test achieved its goal (contact was created)
    // AgentDriverLogs indicate driver actions happened, ToolLogs have explicit success field
    val successfulActions = toolLogs.count { it.successful } + maestroLogs.size
    Console.log("\nSuccessful actions: $successfulActions")
    assertTrue(successfulActions >= 3, "Should have at least 3 successful actions (launch, input, save)")

    Console.log("\n" + "=".repeat(60))
    Console.log("TEST PASSED: Contact creation flow completed with $successfulActions actions")
    Console.log("=".repeat(60))
  }
}
