package xyz.block.trailblaze.mcp.utils

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolParameterDescriptor
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract tests for [TrailblazeToolToMcpBridge.registerTrailblazeToolDescriptors] — the
 * registration path that makes scripted (`.ts`) and YAML-defined tools first-class MCP tools.
 *
 * The observable contract under test:
 *  - a descriptor carrying a full JSON Schema ([TrailblazeToolDescriptor.inputSchema], a body
 *    property that `copy()` silently drops) advertises that schema verbatim — nested shapes
 *    included;
 *  - a descriptor without one (YAML-defined tools) advertises the flat parameter view with the
 *    same required/optional split class-backed registration uses;
 *  - `tools/call` routes through the caller-supplied executor and wraps its return in a success
 *    result;
 *  - [McpToolArgumentValidationException] from the executor surfaces as a clean `isError` result
 *    carrying the validator's message (no raw exception rendering — argument contract
 *    violations must never read as raw Kotlin exceptions);
 *  - any other executor exception gets the standard failure envelope.
 */
class TrailblazeToolToMcpBridgeDescriptorTest {

  // ── Schema advertisement ──────────────────────────────────────────────────

  @Test
  fun `descriptor with full inputSchema advertises nested properties and required verbatim`() {
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("order") {
          put("type", "object")
          putJsonObject("properties") {
            putJsonObject("sku") { put("type", "string") }
            putJsonObject("quantity") { put("type", "integer") }
          }
          putJsonArray("required") { add("sku") }
        }
        putJsonObject("note") { put("type", "string") }
      }
      putJsonArray("required") { add("order") }
    }
    // Built the way production builds it: schema set on the SAME instance that gets
    // registered. (TrailblazeToolDescriptor.inputSchema is a body property that copy()
    // drops — a copy() anywhere on this path would erase the schema and this test would
    // fail on the nested assertion below.)
    val descriptor = TrailblazeToolDescriptor(
      name = "placeOrder",
      description = "Places an order",
    ).also { it.inputSchema = schema }

    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(descriptor),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { _, _ -> "unused" },
    )

    val advertised = server.tools["placeOrder"]?.tool ?: fail("placeOrder must be registered")
    assertEquals("Places an order", advertised.description)
    assertEquals(listOf("order"), advertised.inputSchema.required)
    val properties = advertised.inputSchema.properties ?: fail("properties must be present")
    assertEquals(setOf("order", "note"), properties.keys)
    // The nested object shape must survive — this is what the flat-parameter fallback can't
    // express and why the full schema is used when present.
    val orderProperty = properties["order"]!!.jsonObject
    assertEquals(
      setOf("sku", "quantity"),
      orderProperty["properties"]!!.jsonObject.keys,
      "Nested object properties must be advertised verbatim from the descriptor's inputSchema",
    )
  }

  @Test
  fun `descriptor without inputSchema advertises the flat parameter view`() {
    val descriptor = TrailblazeToolDescriptor(
      name = "searchItems",
      description = "Searches items",
      requiredParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "query", type = "string", description = "Search query"),
      ),
      optionalParameters = listOf(
        TrailblazeToolParameterDescriptor(name = "limit", type = "integer", description = "Max results"),
      ),
    )

    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(descriptor),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { _, _ -> "unused" },
    )

    val advertised = server.tools["searchItems"]?.tool ?: fail("searchItems must be registered")
    val properties = advertised.inputSchema.properties ?: fail("properties must be present")
    assertEquals(setOf("query", "limit"), properties.keys)
    assertEquals("string", properties["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("integer", properties["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("query"),
      advertised.inputSchema.required,
      "Only required parameters may appear in `required` — optional ones must not",
    )
  }

  // ── tools/call dispatch ───────────────────────────────────────────────────

  @Test
  fun `tools-call routes arguments through the executor and returns its result`() {
    val executed = mutableListOf<Pair<String, JsonObject>>()
    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(TrailblazeToolDescriptor(name = "searchItems")),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { toolName, arguments ->
        executed += toolName to arguments
        "found 3 items"
      },
    )

    val arguments = buildJsonObject { put("query", "coffee") }
    val result = callTool(server, "searchItems", arguments)

    assertEquals("searchItems" to arguments, executed.single())
    assertNotEquals(true, result.isError, "Successful execution must not be an error result")
    assertTrue(
      resultText(result).contains("found 3 items"),
      "Result text must carry the executor's return value. Got: ${resultText(result)}",
    )
  }

  @Test
  fun `validation exception surfaces as a clean error result with the validator message`() {
    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(TrailblazeToolDescriptor(name = "searchItems")),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { _, _ ->
        throw McpToolArgumentValidationException("Unknown argument keys for searchItems: [bogus]")
      },
    )

    val result = callTool(server, "searchItems", buildJsonObject { put("bogus", "x") })

    assertEquals(true, result.isError)
    val text = resultText(result)
    assertTrue(
      text.contains("Unknown argument keys for searchItems: [bogus]"),
      "Error result must carry the validator's directed message. Got: $text",
    )
    assertTrue(
      !text.contains("McpToolArgumentValidationException"),
      "Validation errors must render as clean messages, not raw Kotlin exceptions. Got: $text",
    )
  }

  @Test
  fun `tool-reported failure surfaces its own message without the connection hint`() {
    // A TrailblazeToolResult.Error from the tool (assertion failed, element not found) is a
    // tool-level failure: the dispatch worked, so steering the caller to "check device
    // connection" would misdirect. The executor signals it as McpToolExecutionException.
    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(TrailblazeToolDescriptor(name = "assertBalance")),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { _, _ ->
        throw McpToolExecutionException("Expected balance $5.00 but found $4.20")
      },
    )

    val result = callTool(server, "assertBalance", null)

    assertEquals(true, result.isError)
    val text = resultText(result)
    assertTrue(
      text.contains("Expected balance $5.00 but found $4.20"),
      "Error result must carry the tool's own failure message. Got: $text",
    )
    assertTrue(
      !text.contains("device connection", ignoreCase = true),
      "A tool-level failure must not be blamed on the device connection. Got: $text",
    )
  }

  @Test
  fun `unexpected executor exception yields the standard failure envelope`() {
    val server = newMcpServer()
    newBridge().registerTrailblazeToolDescriptors(
      descriptors = listOf(TrailblazeToolDescriptor(name = "searchItems")),
      mcpServer = server,
      mcpSessionId = McpSessionId("test-session"),
      executeDescriptorTool = { _, _ -> throw RuntimeException("device went away") },
    )

    val result = callTool(server, "searchItems", null)

    assertEquals(true, result.isError)
    assertTrue(
      resultText(result).contains("device went away"),
      "Failure envelope must carry the underlying reason. Got: ${resultText(result)}",
    )
  }

  // ── Fixture ───────────────────────────────────────────────────────────────

  private fun newBridge(): TrailblazeToolToMcpBridge = TrailblazeToolToMcpBridge(
    mcpBridge = NoOpBridge(),
    sessionContext = null,
  )

  private fun newMcpServer(): Server = Server(
    Implementation(name = "test", version = "0.0.0"),
    ServerOptions(
      capabilities = ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = true),
      ),
    ),
  )

  private fun callTool(server: Server, name: String, arguments: JsonObject?): CallToolResult {
    val registered = server.tools[name] ?: fail("tool $name must be registered")
    return runBlocking {
      registered.handler.invoke(
        FakeClientConnection,
        CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
      )
    }
  }

  private fun resultText(result: CallToolResult): String =
    result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text.orEmpty() }
}

/** The bridge is only consulted for screenshots on success paths these tests don't enable. */
private class NoOpBridge : TrailblazeMcpBridge {
  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
    error("not used")
  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> = emptySet()
  override suspend fun executeTrailblazeTool(
    tool: TrailblazeTool,
    blocking: Boolean,
    traceId: TraceId?,
  ): String = "[OK]"
  override suspend fun getInstalledAppIds(): Set<String> = emptySet()
  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = emptySet()
  override suspend fun runYaml(
    yaml: String,
    startNewSession: Boolean,
    agentImplementation: AgentImplementation,
  ): String = ""
  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? = null
  override suspend fun getCurrentScreenState(): ScreenState? = null
  override fun getDirectScreenStateProvider(skipScreenshot: Boolean): ((ScreenshotScalingConfig) -> ScreenState)? = null
  override suspend fun endSession(): Boolean = true
  override fun isOnDeviceInstrumentation(): Boolean = false
  override fun getDriverType(): TrailblazeDriverType? = null
  override fun getDriverConnectionStatus(deviceId: TrailblazeDeviceId?): String? = null
  override suspend fun getScreenStateViaRpc(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
    includeAnnotatedScreenshot: Boolean,
    includeAllElements: Boolean,
  ): GetScreenStateResponse? = null
  override fun getActiveSessionId(): SessionId? = null
  override fun cancelAutomation(deviceId: TrailblazeDeviceId) {}
  override fun selectAppTarget(appTargetId: String): String? = null
  override fun getCurrentAppTargetId(): String? = null
}

/** MCP handlers are `suspend ClientConnection.(CallToolRequest) -> CallToolResult`; the
 *  descriptor handlers never touch the connection, so every member can be inert. */
private object FakeClientConnection : ClientConnection {
  override val sessionId: String = "fake-client-connection"
  override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) {}
  override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult = error("not used")
  override suspend fun createMessage(
    request: CreateMessageRequest,
    options: RequestOptions?,
  ): CreateMessageResult = error("not used")
  override suspend fun listRoots(
    request: ListRootsRequest,
    options: RequestOptions?,
  ): ListRootsResult = error("not used")
  override suspend fun createElicitation(
    message: String,
    requestedSchema: ElicitRequestParams.RequestedSchema,
    options: RequestOptions?,
  ): ElicitResult = error("not used")
  override suspend fun createElicitation(
    message: String,
    elicitationId: String,
    url: String,
    options: RequestOptions?,
  ): ElicitResult = error("not used")
  override suspend fun createElicitation(
    request: ElicitRequest,
    options: RequestOptions?,
  ): ElicitResult = error("not used")
  override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) {}
  override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) {}
  override suspend fun sendResourceListChanged() {}
  override suspend fun sendToolListChanged() {}
  override suspend fun sendPromptListChanged() {}
  override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) {}
}
