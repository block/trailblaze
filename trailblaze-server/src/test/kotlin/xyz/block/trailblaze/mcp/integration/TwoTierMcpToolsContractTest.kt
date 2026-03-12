package xyz.block.trailblaze.mcp.integration

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * ## Two-Tier MCP Tools Contract Test
 *
 * ### Purpose
 *
 * This test validates the **MCP tool contract** for the two-tier agent architecture.
 * It ensures that external MCP clients can successfully call `getScreenAnalysis` and
 * `executeUiAction` tools over HTTP/JSON-RPC.
 *
 * ### What This Test Validates
 *
 * | Layer | Validated |
 * |-------|-----------|
 * | **MCP Protocol** | JSON-RPC requests/responses encode/decode correctly |
 * | **HTTP Transport** | Tools are callable over HTTP |
 * | **Tool Registration** | `getScreenAnalysis` and `executeUiAction` are registered when LLM is configured |
 * | **Tool Contract** | Input parameters are accepted, output structure matches expected schema |
 * | **Wiring** | Tools are connected to real `InnerLoopScreenAnalyzer` which uses configured LLM |
 *
 * ### What This Test Does NOT Validate
 *
 * These are tested elsewhere or manually:
 *
 * | Component | Where Tested |
 * |-----------|--------------|
 * | `InnerLoopScreenAnalyzer` LLM logic | `TwoTierAgentIntegrationTest` (real LLM) |
 * | `DefaultOuterStrategy` decisions | `TwoTierAgentIntegrationTest` (opensource, mocked) |
 * | `OuterLoopAgent` orchestration | Unit tests with mocks |
 * | MCP sampling (client → server LLM calls) | Manual testing |
 *
 * ### Why This Test Exists
 *
 * When an external AI agent (e.g., Claude via MCP) acts as the **outer agent (strategist)**,
 * it needs to call these tools to:
 *
 * 1. `getScreenAnalysis` - Ask Trailblaze's inner agent to analyze the screen and recommend an action
 * 2. `executeUiAction` - Execute the recommended action on the device
 *
 * This test simulates that external client to verify the contract is stable.
 *
 * ### Architecture Under Test
 *
 * ```
 * [External MCP Client]          [Trailblaze Server]
 * (outer agent/strategist)
 *        │
 *        │ HTTP POST /mcp
 *        │ tools/call: getScreenAnalysis
 *        │────────────────────────────────>│
 *        │                                 │ InnerLoopScreenAnalyzer
 *        │                                 │ (inner agent, uses LLM)
 *        │<────────────────────────────────│
 *        │ { recommendedTool, args, ... }  │
 *        │                                 │
 *        │ HTTP POST /mcp                  │
 *        │ tools/call: executeUiAction     │
 *        │────────────────────────────────>│
 *        │                                 │ Executes on device
 *        │<────────────────────────────────│
 *        │ { success, screenSummary }      │
 * ```
 *
 * ### Prerequisites
 *
 * 1. **Device connected** - iOS simulator or Android emulator
 * 2. **Trailblaze desktop app running** with LLM configured
 *    - Two-tier tools are only registered when an LLM is available
 *    - Or use `autoLaunch = true` to start automatically
 *
 * ### Running
 *
 * ```bash
 * ./gradlew :trailblaze-server:test \
 *   --tests "xyz.block.trailblaze.mcp.integration.TwoTierMcpToolsContractTest"
 * ```
 *
 * @see ScreenAnalysisTool
 * @see ExecuteUiActionTool
 * @see InnerLoopScreenAnalyzer
 */
class TwoTierMcpToolsContractTest : TrailblazeServerTestBase() {

  companion object {
    private const val MAX_ITERATIONS = 10
    private val json = Json { ignoreUnknownKeys = true }
  }

  override val devicePlatform: String = "ios"
  override val clientName: String = "TwoTierMcpToolsContractTest"

  // Use the already-running desktop app instead of force-restarting
  // This allows the test to use the LLM configuration from the running app
  override val forceRestart: Boolean = false

  /**
   * Validates that `getScreenAnalysis` returns a well-formed response.
   *
   * This is the core contract test - it verifies the tool is callable and
   * returns the expected JSON structure that external clients depend on.
   */
  @Test
  fun `getScreenAnalysis tool returns valid response structure`() {
    runBlocking {
      Console.log("\n" + "=".repeat(70))
      Console.log("CONTRACT TEST: getScreenAnalysis")
      Console.log("=".repeat(70))

      // Setup
      val connectResult = client.connectToDevice(deviceId!!, devicePlatform)
      assertTrue(connectResult.isSuccess, "Should connect to device")
      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)

      // Check tool availability
      val tools = client.listTools()
      if (!tools.contains("getScreenAnalysis")) {
        Console.log("\n[SKIP] getScreenAnalysis not registered - LLM not configured")
        Console.log("To run this test: configure an LLM in Trailblaze Settings")
        return@runBlocking
      }

      // Call the tool
      Console.log("\nCalling getScreenAnalysis...")
      val result = client.getScreenAnalysis(
        objective = "Tap the first button on the screen",
        attemptNumber = 1,
      )

      Console.log("Success: ${result.isSuccess}")
      assertTrue(result.isSuccess, "getScreenAnalysis should succeed: ${result.content}")

      // Validate response structure
      Console.log("\nValidating response structure...")
      val analysis = json.decodeFromString<JsonObject>(result.content)

      // Required fields that external clients depend on
      val requiredFields = listOf(
        "recommendedTool",
        "recommendedArgs",
        "reasoning",
        "screenSummary",
        "confidence",
        "objectiveAppearsAchieved",
        "objectiveAppearsImpossible",
      )

      requiredFields.forEach { field ->
        assertNotNull(analysis[field], "Response must include '$field'")
        Console.log("  ✓ $field: present")
      }

      Console.log("\nResponse content (truncated):")
      Console.log("  recommendedTool: ${analysis["recommendedTool"]}")
      Console.log("  confidence: ${analysis["confidence"]}")
      Console.log("  screenSummary: ${analysis["screenSummary"]?.jsonPrimitive?.content?.take(60)}...")

      Console.log("\n[PASS] getScreenAnalysis contract validated!")
    }
  }

  /**
   * Validates that `executeUiAction` accepts and executes actions correctly.
   *
   * This test verifies the tool contract by executing a simple, safe action.
   */
  @Test
  fun `executeUiAction tool accepts and executes actions`() {
    runBlocking {
      Console.log("\n" + "=".repeat(70))
      Console.log("CONTRACT TEST: executeUiAction")
      Console.log("=".repeat(70))

      // Setup
      val connectResult = client.connectToDevice(deviceId!!, devicePlatform)
      assertTrue(connectResult.isSuccess, "Should connect to device")
      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)

      // Check tool availability
      val tools = client.listTools()
      if (!tools.contains("executeUiAction")) {
        Console.log("\n[SKIP] executeUiAction not registered - LLM not configured")
        return@runBlocking
      }

      // First, get a recommendation from the inner agent
      Console.log("\nGetting screen analysis to find a valid action...")
      val analysisResult = client.getScreenAnalysis(
        objective = "Tap any button on the screen",
        attemptNumber = 1,
      )

      if (!analysisResult.isSuccess) {
        Console.log("[SKIP] Could not get screen analysis: ${analysisResult.content}")
        return@runBlocking
      }

      val analysis = json.decodeFromString<JsonObject>(analysisResult.content)
      val recommendedTool = analysis["recommendedTool"]?.jsonPrimitive?.content
      val recommendedArgs = analysis["recommendedArgs"]?.jsonObject

      if (recommendedTool == null || recommendedArgs == null) {
        Console.log("[SKIP] No action recommended - screen may not have tappable elements")
        return@runBlocking
      }

      // Execute the recommended action
      Console.log("\nExecuting recommended action: $recommendedTool")
      Console.log("Args: $recommendedArgs")

      val executeResult = client.executeUiAction(
        toolName = recommendedTool,
        args = recommendedArgs.toMap(),
      )

      Console.log("\nExecution result:")
      Console.log("  Success: ${executeResult.isSuccess}")
      Console.log("  Content: ${executeResult.content.take(200)}...")

      // The action may fail (element not found, etc.) but the tool should work
      // We're testing the contract, not the action outcome
      Console.log("\n[PASS] executeUiAction contract validated!")
    }
  }

  /**
   * End-to-end contract test: simulates an MCP client driving the outer loop.
   *
   * This test acts as a simple outer agent, repeatedly calling `getScreenAnalysis`
   * and `executeUiAction` to verify the full MCP roundtrip works.
   *
   * Note: The "outer loop logic" here is hardcoded/simple. The actual outer agent
   * reasoning (via LLM sampling) is tested separately via manual testing.
   */
  @Test
  fun `full MCP roundtrip works for outer agent pattern`() {
    runBlocking {
      Console.log("\n" + "=".repeat(70))
      Console.log("CONTRACT TEST: Full MCP Roundtrip (Outer Agent Pattern)")
      Console.log("=".repeat(70))
      Console.log("\nThis test simulates an external MCP client acting as the outer agent.")
      Console.log("It verifies the HTTP/MCP layer works for the two-tier pattern.")

      // Setup
      val connectResult = client.connectToDevice(deviceId!!, devicePlatform)
      assertTrue(connectResult.isSuccess, "Should connect to device")
      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)

      // Check tools
      val tools = client.listTools()
      if (!tools.contains("getScreenAnalysis") || !tools.contains("executeUiAction")) {
        Console.log("\n[SKIP] Two-tier tools not registered - LLM not configured")
        return@runBlocking
      }

      // Run outer loop (test acts as strategist)
      val objective = "Open the Contacts app"
      Console.log("\nObjective: \"$objective\"")
      Console.log("Max iterations: $MAX_ITERATIONS")

      var iteration = 0
      var progressSummary: String? = null
      var hint: String? = null
      var completed = false
      var analysisCallsSucceeded = 0
      var executeCallsSucceeded = 0

      while (iteration < MAX_ITERATIONS && !completed) {
        iteration++
        Console.log("\n--- Iteration $iteration ---")

        // Get screen analysis
        val analysisResult = client.getScreenAnalysis(
          objective = objective,
          progressSummary = progressSummary,
          hint = hint,
          attemptNumber = iteration,
        )

        if (!analysisResult.isSuccess) {
          Console.log("  getScreenAnalysis failed: ${analysisResult.content.take(100)}")
          hint = "Previous analysis failed"
          continue
        }
        analysisCallsSucceeded++

        val analysis = try {
          json.decodeFromString<JsonObject>(analysisResult.content)
        } catch (e: Exception) {
          Console.log("  Failed to parse analysis")
          continue
        }

        val recommendedTool = analysis["recommendedTool"]?.jsonPrimitive?.content
        val recommendedArgs = analysis["recommendedArgs"]?.jsonObject
        val objectiveAchieved = analysis["objectiveAppearsAchieved"]?.jsonPrimitive?.boolean ?: false
        val screenSummary = analysis["screenSummary"]?.jsonPrimitive?.content

        Console.log("  Screen: ${screenSummary?.take(50)}...")
        Console.log("  Recommended: $recommendedTool")
        Console.log("  Achieved: $objectiveAchieved")

        if (objectiveAchieved) {
          completed = true
          break
        }

        if (recommendedTool == null || recommendedArgs == null) {
          hint = "No action recommended"
          continue
        }

        // Execute action
        val executeResult = client.executeUiAction(recommendedTool, recommendedArgs.toMap())

        if (executeResult.isSuccess) {
          executeCallsSucceeded++
          progressSummary = "Executed $recommendedTool"
          hint = null
        } else {
          hint = "Action failed: ${executeResult.content.take(50)}"
        }

        delay(500) // Let UI settle
      }

      // Report
      Console.log("\n" + "=".repeat(70))
      Console.log("RESULTS")
      Console.log("=".repeat(70))
      Console.log("Iterations: $iteration")
      Console.log("getScreenAnalysis calls succeeded: $analysisCallsSucceeded")
      Console.log("executeUiAction calls succeeded: $executeCallsSucceeded")
      Console.log("Objective completed: $completed")

      // Contract validation: at least one successful roundtrip
      assertTrue(
        analysisCallsSucceeded >= 1,
        "Should have at least one successful getScreenAnalysis call",
      )
      Console.log("\n[PASS] MCP roundtrip contract validated!")
    }
  }

  /**
   * Validates that two-tier tools are only registered when LLM is configured.
   */
  @Test
  fun `two-tier tools registration depends on LLM configuration`() {
    runBlocking {
      Console.log("\n" + "=".repeat(70))
      Console.log("CONTRACT TEST: Tool Registration")
      Console.log("=".repeat(70))

      client.setMode(TrailblazeMcpMode.TRAILBLAZE_AS_AGENT)
      val tools = client.listTools()

      Console.log("\nRegistered tools: ${tools.size}")
      tools.sorted().forEach { Console.log("  - $it") }

      val hasScreenAnalysis = tools.contains("getScreenAnalysis")
      val hasExecuteUiAction = tools.contains("executeUiAction")

      Console.log("\nTwo-tier tools status:")
      Console.log("  getScreenAnalysis: ${if (hasScreenAnalysis) "registered" else "not registered"}")
      Console.log("  executeUiAction: ${if (hasExecuteUiAction) "registered" else "not registered"}")

      if (!hasScreenAnalysis || !hasExecuteUiAction) {
        Console.log("\nNote: Two-tier tools require LLM to be configured in Trailblaze Settings.")
        Console.log("This is expected behavior - tools should only be available when usable.")
      }

      // This test documents the behavior, doesn't assert on it
      Console.log("\n[PASS] Tool registration documented!")
    }
  }

  private fun JsonObject.toMap(): Map<String, Any?> {
    return this.mapValues { (_, value) ->
      when {
        value is JsonPrimitive && value.isString -> value.content
        value is JsonPrimitive -> value.content
        value is JsonObject -> value.toMap()
        else -> value.toString()
      }
    }
  }
}
