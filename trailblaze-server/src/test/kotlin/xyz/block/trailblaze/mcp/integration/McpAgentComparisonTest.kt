package xyz.block.trailblaze.mcp.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import xyz.block.trailblaze.util.Console

/**
 * MCP Agent Comparison Test Harness.
 *
 * Tests both DIRECT and MCP_SAMPLING agent implementations:
 * - **DIRECT**: Uses LocalLlmSamplingSource - WORKS with local LLM
 * - **MCP_SAMPLING**: Requires MCP client sampling - fails without full MCP client
 *
 * The DIRECT agent with local LLM is the **recommended production path** because:
 * 1. It doesn't require MCP clients to support sampling
 * 2. It uses Trailblaze's configured LLM credentials
 * 3. It has better abstraction via SamplingSource interface
 *
 * Run with:
 * ```
 * ./gradlew :trailblaze-server:test --tests "xyz.block.trailblaze.mcp.integration.McpAgentComparisonTest"
 * ```
 *
 * Prerequisites:
 * - Trailblaze MCP server running on port 52525 (`./trailblaze`)
 * - Android device/emulator connected
 * - LLM credentials configured (in Trailblaze settings)
 */
class McpAgentComparisonTest {

  companion object {
    private const val MCP_URL = "http://localhost:52525/mcp"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @JvmStatic
    fun main(args: Array<String>) {
      McpAgentComparisonTest().runTests()
    }
  }

  private val client = HttpClient(CIO) {
    install(HttpTimeout) {
      requestTimeoutMillis = 180_000 // 3 minutes - agent execution takes time
      connectTimeoutMillis = 10_000
      socketTimeoutMillis = 180_000
    }
  }

  private var sessionId: String? = null
  private var requestId = 0

  @Test
  fun `compare KOOG and LEGACY agent implementations`() = runTests()

  fun runTests() = runBlocking {
    Console.log("=" .repeat(60))
    Console.log("MCP Agent Comparison Test Harness")
    Console.log("=" .repeat(60))
    Console.log("")
    Console.log("Testing DIRECT vs MCP_SAMPLING agent implementations:")
    Console.log("  - DIRECT: Uses LocalLlmSamplingSource - RECOMMENDED")
    Console.log("  - MCP_SAMPLING: Requires full MCP client sampling protocol")
    Console.log("")
    Console.log("DIRECT with local LLM is the production path - no MCP sampling needed.")

    try {
      // Initialize MCP session
      Console.log("\n[1] Initializing MCP session...")
      initialize()

      // List available tools
      Console.log("\n[2] Listing available tools...")
      val tools = listTools()
      Console.log("   Found ${tools.size} tools")

      val requiredTools = listOf("setLlmCallStrategy", "runPrompt", "getAgentMetrics", "clearAgentMetrics")
      val missingTools = requiredTools.filter { required -> tools.none { it == required } }
      if (missingTools.isNotEmpty()) {
        Console.log("   WARNING: Missing required tools: $missingTools")
      } else {
        Console.log("   All required tools available!")
      }

      // Get session config
      Console.log("\n[3] Getting session config...")
      val config = callTool("getSessionConfig", buildJsonObject {})
      Console.log("   $config")

      // List connected devices
      Console.log("\n[4] Listing connected devices...")
      val devices = callTool("listConnectedDevices", buildJsonObject {})
      Console.log("   $devices")

      // Clear metrics
      Console.log("\n[5] Clearing existing metrics...")
      callTool("clearAgentMetrics", buildJsonObject {})
      Console.log("   Metrics cleared")

      // Set mode to MCP_CLIENT_AS_AGENT (where agent selection happens)
      Console.log("\n[5.5] Setting mode to MCP_CLIENT_AS_AGENT...")
      val modeResult = callTool("setMode", buildJsonObject {
        put("mode", "MCP_CLIENT_AS_AGENT")
      })
      Console.log("   $modeResult")

      // Test DIRECT agent
      Console.log("\n[6] Testing DIRECT agent...")
      Console.log("   Setting agent implementation to DIRECT...")
      val koogSetResult = callTool("setLlmCallStrategy", buildJsonObject {
        put("implementation", "DIRECT")
      })
      Console.log("   $koogSetResult")

      Console.log("   Running test prompt with DIRECT agent...")
      val koogResult = try {
        callTool("runPrompt", buildJsonObject {
          put("steps", buildJsonArray {
            add(JsonPrimitive("Get the current screen state and describe what you see"))
          })
        })
      } catch (e: Exception) {
        "TIMEOUT/ERROR: ${e.message?.take(100)}"
      }
      Console.log("   DIRECT Result: ${koogResult.take(500)}...")

      // Test MCP_SAMPLING agent
      Console.log("\n[7] Testing MCP_SAMPLING agent...")
      Console.log("   Setting agent implementation to MCP_SAMPLING...")
      val legacySetResult = callTool("setLlmCallStrategy", buildJsonObject {
        put("implementation", "MCP_SAMPLING")
      })
      Console.log("   $legacySetResult")

      Console.log("   Running test prompt with MCP_SAMPLING agent...")
      val legacyResult = try {
        callTool("runPrompt", buildJsonObject {
          put("steps", buildJsonArray {
            add(JsonPrimitive("Get the current screen state and describe what you see"))
          })
        })
      } catch (e: Exception) {
        "TIMEOUT/ERROR: ${e.message?.take(100)}"
      }
      Console.log("   MCP_SAMPLING Result: ${legacyResult.take(500)}...")

      // Get metrics comparison
      Console.log("\n[8] Getting agent metrics comparison...")
      val metrics = callTool("getAgentMetrics", buildJsonObject {})
      Console.log("\n" + "=" .repeat(60))
      Console.log("METRICS COMPARISON")
      Console.log("=" .repeat(60))
      Console.log(metrics)

      Console.log("\n" + "=" .repeat(60))
      Console.log("TEST COMPLETE")
      Console.log("=" .repeat(60))

    } catch (e: Exception) {
      Console.log("\nERROR: ${e.message}")
      e.printStackTrace()
    } finally {
      client.close()
    }
  }

  private suspend fun initialize() {
    val id = ++requestId
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", "initialize")
      putJsonObject("params") {
        put("protocolVersion", "2025-11-25")
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
          put("name", "McpAgentComparisonTest")
          put("version", "1.0.0")
        }
      }
    }

    val response = client.post(MCP_URL) {
      contentType(ContentType.Application.Json)
      headers {
        append("Accept", "application/json, text/event-stream")
      }
      setBody(body.toString())
    }

    sessionId = response.headers["mcp-session-id"]
    Console.log("   Session ID: $sessionId")

    val responseText = response.bodyAsText()
    val jsonText = parseSSEResponse(responseText)
    val jsonResponse = json.decodeFromString<JsonObject>(jsonText)
    Console.log("   Server: ${jsonResponse["result"]?.jsonObject?.get("serverInfo")}")
  }

  private suspend fun listTools(): List<String> {
    val response = mcpRequest("tools/list", buildJsonObject {})
    val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray ?: return emptyList()
    return tools.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
  }

  private suspend fun callTool(name: String, arguments: JsonObject): String {
    val response = mcpRequest("tools/call", buildJsonObject {
      put("name", name)
      put("arguments", arguments)
    })

    val result = response["result"]?.jsonObject
    val content = result?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
    return content?.get("text")?.jsonPrimitive?.content ?: response.toString()
  }

  private suspend fun mcpRequest(method: String, params: JsonObject): JsonObject {
    val id = ++requestId
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", id)
      put("method", method)
      put("params", params)
    }

    val response = client.post(MCP_URL) {
      contentType(ContentType.Application.Json)
      headers {
        append("Accept", "application/json, text/event-stream")
        sessionId?.let { append("mcp-session-id", it) }
      }
      setBody(body.toString())
    }

    val jsonText = parseSSEResponse(response.bodyAsText())
    return try {
      json.decodeFromString<JsonObject>(jsonText)
    } catch (e: Exception) {
      buildJsonObject { put("error", e.message ?: "Parse error") }
    }
  }

  private fun parseSSEResponse(text: String): String {
    return if (text.startsWith("data:")) {
      text.lines()
        .filter { it.startsWith("data:") }
        .map { it.removePrefix("data:").trim() }
        .filter { it.isNotEmpty() && it != "[DONE]" }
        .lastOrNull() ?: "{}"
    } else {
      text
    }
  }
}
