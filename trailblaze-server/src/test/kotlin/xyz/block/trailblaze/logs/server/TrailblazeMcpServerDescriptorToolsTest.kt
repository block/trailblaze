package xyz.block.trailblaze.logs.server

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.mcp.utils.McpToolArgumentValidationException
import xyz.block.trailblaze.mcp.utils.McpToolExecutionException
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Contract tests for the descriptor-backed first-class MCP tool surface on
 * [TrailblazeMcpServer]:
 *
 *  - [TrailblazeMcpServer.executeDescriptorBackedTool] — the `tools/call` dispatch for
 *    scripted/YAML tools: argument validation gates dispatch (nothing reaches the bridge on a
 *    contract violation), valid calls route through the same bridge executor recorded replay
 *    uses, and violations surface as [McpToolArgumentValidationException] (rendered as a clean
 *    MCP error by the bridge, never as a raw Kotlin exception).
 *  - [TrailblazeMcpServer.registerTools] — the bound target's YAML-defined and scripted tools
 *    register as real MCP tools with their parameter schemas, the target's `excluded_tools:`
 *    surface is honored, and a re-registration after a target switch (what
 *    [TrailblazeMcpServer.refreshToolsForSession] triggers) swaps the descriptor surface
 *    because the per-session name tracking covers descriptor tools too.
 *
 * `eraseText` (a global YAML-defined tool with one optional integer parameter) is the subject
 * throughout: on this driver it arrives via the always-enabled `core_interaction` catalog
 * toolset, so the target-switch test exercises the exclusion side — a target whose
 * `excluded_tools:` names it must not advertise it.
 */
class TrailblazeMcpServerDescriptorToolsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val androidDevice = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val androidDeviceB = TrailblazeDeviceId(
    instanceId = "emulator-5556",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val eraseTarget = YamlToolTarget(
    id = "erasetarget",
    displayName = "Erase Target",
    yamlNames = setOf(ToolName("eraseText")),
  )
  private val excludingTarget = YamlToolTarget(
    id = "excludingtarget",
    displayName = "Excluding Target",
    yamlNames = emptySet(),
    excludedYamlNames = setOf(ToolName("eraseText")),
  )

  private fun newServer(bridge: TrailblazeMcpBridge): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = bridge,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  private fun ctx(sessionId: String): TrailblazeMcpSessionContext = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = McpSessionId(sessionId),
  ).also {
    it.mcpClientName = "Claude Code"
    it.setAssociatedDevice(androidDevice)
  }

  // ── executeDescriptorBackedTool dispatch contracts ────────────────────────

  @Test
  fun `valid YAML tool call dispatches through the bridge executor`() {
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)

    // No session context installed → no scripted runtime → the YAML-only fallback repo path.
    val result = runBlocking {
      server.executeDescriptorBackedTool(
        sessionId = "no-such-session",
        toolName = "eraseText",
        arguments = buildJsonObject { put("charactersToErase", 5) },
      )
    }

    assertEquals("[OK] executed", result, "Executor result must be returned to the MCP handler")
    assertEquals(1, bridge.executedTools.size, "Exactly one tool must reach the bridge executor")
  }

  @Test
  fun `descriptor dispatch binds each session to its own device`() {
    // Two sessions bound to two different devices. Device-bound dispatch must resolve the CALLING
    // session's device — without the per-session McpDeviceContext binding, the bridge falls back to
    // the global last-selected device and one session's call could land on the other's device.
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)

    fun installBoundSession(sessionId: String, device: TrailblazeDeviceId) {
      server.installSessionContextForTest(
        sessionId,
        TrailblazeMcpSessionContext(mcpServerSession = null, mcpSessionId = McpSessionId(sessionId)).also {
          it.setAssociatedDevice(device)
        },
      )
      // Pre-install the runtime so ensureSessionScriptToolRuntime is a cache hit (no launcher) —
      // this test is about which device gets bound at dispatch, not runtime construction.
      server.installSessionScriptToolRuntimeForTest(
        sessionId = sessionId,
        targetId = eraseTarget.id,
        driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        toolRepo = TrailblazeToolRepo(
          TrailblazeToolSet.DynamicTrailblazeToolSet(
            name = "DeviceBindingTestToolSet",
            toolClasses = emptySet(),
            yamlToolNames = setOf(ToolName("eraseText")),
            scriptedToolNames = emptySet(),
          ),
        ),
      )
    }
    installBoundSession("session-a", androidDevice)
    installBoundSession("session-b", androidDeviceB)

    runBlocking {
      server.executeDescriptorBackedTool("session-a", "eraseText", buildJsonObject { put("charactersToErase", 1) })
      server.executeDescriptorBackedTool("session-b", "eraseText", buildJsonObject { put("charactersToErase", 2) })
    }

    assertEquals(
      listOf<String?>(androidDevice.instanceId, androidDeviceB.instanceId),
      bridge.dispatchedDeviceInstanceIds.toList(),
      "Each descriptor call must dispatch with its own session's device bound in McpDeviceContext",
    )
  }

  @Test
  fun `unknown argument key is rejected before anything reaches the device`() {
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)

    val exception = assertFailsWith<McpToolArgumentValidationException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = "no-such-session",
          toolName = "eraseText",
          arguments = buildJsonObject { put("bogus", 1) },
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("bogus"),
      "Validation error must name the offending key. Got: ${exception.message}",
    )
    assertTrue(
      bridge.executedTools.isEmpty(),
      "A contract violation must be rejected BEFORE dispatch — nothing may reach the bridge",
    )
  }

  @Test
  fun `unknown tool name is rejected with a directed message`() {
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)

    val exception = assertFailsWith<McpToolArgumentValidationException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = "no-such-session",
          toolName = "noSuchTool",
          arguments = buildJsonObject {},
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("noSuchTool"),
      "Unknown-tool error must name the tool. Got: ${exception.message}",
    )
    assertTrue(bridge.executedTools.isEmpty())
  }

  @Test
  fun `arguments that pass key validation but fail decoding are rejected as a validation error`() {
    // Known keys, undecodable values: the key-level validator lets the payload through, the
    // registration's decoder throws SerializationException — which must surface as the clean
    // validation error naming the tool, not a raw Kotlin exception, and nothing may dispatch.
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "hostlocal_probe",
    )
    val bridge = RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id)
    val server = newServer(bridge)
    val sessionId = "decode-failure-session"
    server.installSessionContextForTest(sessionId, ctx(sessionId))
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "DecodeFailureToolSet",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
        scriptedToolNames = emptySet(),
      ),
    )
    repo.addDynamicTools(
      listOf(
        FakeDynamicRegistration(
          name = "hostlocal_probe",
          schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("message") { put("type", "string") } }
          },
          decode = { throw SerializationException("expected string for 'message'") },
        ),
      ),
    )
    server.installSessionScriptToolRuntimeForTest(
      sessionId = sessionId,
      targetId = inlineTarget.id,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      toolRepo = repo,
    )

    val exception = assertFailsWith<McpToolArgumentValidationException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = sessionId,
          toolName = "hostlocal_probe",
          arguments = buildJsonObject { put("message", "hi") },
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("hostlocal_probe"),
      "Decode-failure error must name the tool. Got: ${exception.message}",
    )
    assertTrue(
      bridge.executedTools.isEmpty(),
      "Undecodable arguments must be rejected BEFORE dispatch — nothing may reach the bridge",
    )
  }

  @Test
  fun `scripted tool with no runtime available fails with a directed runtime message`() {
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)

    // A name that is NOT a known YAML-defined tool and has no scripted runtime for the
    // session (no session context installed). Falling through to YAML resolution would
    // produce a misleading "unknown tool"; the real condition is "scripted runtime gone".
    val exception = assertFailsWith<McpToolArgumentValidationException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = "no-such-session",
          toolName = "workspace_echo",
          arguments = buildJsonObject { put("message", "hi") },
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("runtime is not available"),
      "A scripted tool whose runtime is gone must say so, not report a generic unknown tool. " +
        "Got: ${exception.message}",
    )
    assertTrue(bridge.executedTools.isEmpty())
  }

  // ── Host-local (scripted) tool inline execution ───────────────────────────

  @Test
  fun `host-local tool executes on the host with target identity and can nest device calls`() {
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "hostlocal_probe",
    )
    val bridge = RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id)
    val server = newServer(bridge)
    val nestedTool = NestedProbeTool()
    val hostLocalTool = FakeHostLocalTool(name = "hostlocal_probe") { context ->
      // What a scripted tool does under the hood: a nested framework call routed back
      // through the bridge to the bound device.
      context.nestedToolExecutor?.invoke(nestedTool)
      TrailblazeToolResult.Success(message = "host says hi")
    }
    val result = runBlocking {
      server.executeDescriptorBackedTool(
        sessionId = installHostLocalFixture(server, inlineTarget, hostLocalTool),
        toolName = "hostlocal_probe",
        arguments = buildJsonObject {},
      )
    }

    assertEquals("host says hi", result, "The tool's Success message is the MCP result")
    assertEquals(
      listOf<TrailblazeTool>(nestedTool),
      bridge.executedTools,
      "Only the NESTED framework call may reach the device bridge — the host-local tool " +
        "itself must execute on the host JVM",
    )
    val context = hostLocalTool.capturedContext ?: fail("The tool must have executed")
    assertEquals(
      inlineTarget.id,
      context.resolvedTarget?.id,
      "The execution context must carry the session's current target so scripted tools " +
        "see ctx.target (same contract as the trail-run path)",
    )
    assertTrue(
      context.toolRepo != null,
      "The execution context must carry the session repo so Kotlin tools can compose " +
        "framework tools by name",
    )
  }

  @Test
  fun `host-local tool with only structuredContent returns it as the MCP result`() {
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "hostlocal_probe",
    )
    val server = newServer(RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id))
    val structured = buildJsonObject { put("count", 3) }
    val hostLocalTool = FakeHostLocalTool(name = "hostlocal_probe") {
      TrailblazeToolResult.Success(message = null, structuredContent = structured)
    }

    val result = runBlocking {
      server.executeDescriptorBackedTool(
        sessionId = installHostLocalFixture(server, inlineTarget, hostLocalTool),
        toolName = "hostlocal_probe",
        arguments = buildJsonObject {},
      )
    }

    assertEquals(structured.toString(), result)
  }

  @Test
  fun `host-local tool failure surfaces the tool's own error message`() {
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "hostlocal_probe",
    )
    val server = newServer(RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id))
    val hostLocalTool = FakeHostLocalTool(name = "hostlocal_probe") {
      TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(RuntimeException("assertion boom"), this)
    }

    val exception = assertFailsWith<McpToolExecutionException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = installHostLocalFixture(server, inlineTarget, hostLocalTool),
          toolName = "hostlocal_probe",
          arguments = buildJsonObject {},
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("assertion boom"),
      "The tool's own failure message must survive to the MCP error. Got: ${exception.message}",
    )
  }

  @Test
  fun `host-local tool that throws surfaces as a tool failure, not a connection error`() {
    // A scripted tool that THROWS (rather than returning a typed Error) is still a tool-side
    // failure — it must surface as McpToolExecutionException carrying the throw's message, so the
    // MCP handler renders the tool's own message instead of the generic "check device connection".
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "hostlocal_probe",
    )
    val server = newServer(RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id))
    val hostLocalTool = FakeHostLocalTool(name = "hostlocal_probe") {
      throw IllegalStateException("scripted kaboom")
    }

    val exception = assertFailsWith<McpToolExecutionException> {
      runBlocking {
        server.executeDescriptorBackedTool(
          sessionId = installHostLocalFixture(server, inlineTarget, hostLocalTool),
          toolName = "hostlocal_probe",
          arguments = buildJsonObject {},
        )
      }
    }

    assertTrue(
      exception.message.orEmpty().contains("scripted kaboom"),
      "A raw throw from the tool must surface its message as a tool failure. Got: ${exception.message}",
    )
  }

  /**
   * Installs the full session fixture for host-local dispatch: session context bound to the
   * Android device, plus a runtime repo (via the test seam) whose dynamic registration decodes
   * to [hostLocalTool]. Returns the session id.
   */
  private fun installHostLocalFixture(
    server: TrailblazeMcpServer,
    target: TrailblazeHostAppTarget,
    hostLocalTool: FakeHostLocalTool,
  ): String {
    val sessionId = "hostlocal-session"
    server.installSessionContextForTest(sessionId, ctx(sessionId))
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "HostLocalTestToolSet",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
        scriptedToolNames = emptySet(),
      ),
    )
    repo.addDynamicTools(
      listOf(
        FakeDynamicRegistration(
          name = hostLocalTool.advertisedToolName,
          schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
          },
          decode = { hostLocalTool },
        ),
      ),
    )
    server.installSessionScriptToolRuntimeForTest(
      sessionId = sessionId,
      targetId = target.id,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      toolRepo = repo,
    )
    return sessionId
  }

  // ── registerTools: descriptor tools on the MCP surface + target switch ────

  @Test
  fun `registerTools registers the target's descriptor tools and a target switch honors exclusions`() {
    val bridge = RecordingBridge(
      targets = setOf(eraseTarget, excludingTarget),
      currentTargetId = eraseTarget.id,
    )
    val server = newServer(bridge)
    val sessionContext = ctx("descriptor-session")
    server.installSessionContextForTest("descriptor-session", sessionContext)
    val mcpServer = server.configureMcpServer()

    server.registerTools(mcpServer, McpSessionId("descriptor-session"), sessionContext)

    val registered = mcpServer.tools["eraseText"]?.tool
      ?: fail(
        "eraseText must be registered as a first-class MCP tool while the bound target " +
          "surfaces it. Registered: ${mcpServer.tools.keys.sorted()}",
      )
    val properties = registered.inputSchema.properties ?: fail("inputSchema.properties must be present")
    assertTrue(
      "charactersToErase" in properties.keys,
      "The YAML tool's parameter must be advertised in the MCP inputSchema. Got: ${properties.keys}",
    )
    assertFalse(
      registered.inputSchema.required.orEmpty().contains("charactersToErase"),
      "charactersToErase is optional in the YAML config and must not be advertised as required",
    )
    // The scripted (.ts) side of the descriptor surface: openUrl arrives via the same catalog
    // toolset and must advertise its analyzer-extracted schema (a `url` property), not an
    // empty placeholder.
    val openUrl = mcpServer.tools["openUrl"]?.tool
      ?: fail("Scripted catalog tool openUrl must be registered. Registered: ${mcpServer.tools.keys.sorted()}")
    assertTrue(
      "url" in (openUrl.inputSchema.properties?.keys ?: emptySet()),
      "openUrl must advertise its real JSON Schema. Got: ${openUrl.inputSchema.properties?.keys}",
    )

    // Target switch: the new target EXCLUDES eraseText via its `excluded_tools:` surface.
    // refreshToolsForSession routes back through registerTools — the per-session tracked name
    // set must remove it, while non-excluded descriptor tools re-register.
    bridge.currentTargetId = excludingTarget.id
    server.registerTools(mcpServer, McpSessionId("descriptor-session"), sessionContext)

    assertFalse(
      mcpServer.tools.containsKey("eraseText"),
      "After switching to a target that excludes the tool, re-registration must remove " +
        "eraseText from the MCP surface (per-session name tracking must cover descriptor tools)",
    )
    assertTrue(
      mcpServer.tools.containsKey("openUrl"),
      "Non-excluded descriptor tools must survive the target switch",
    )
  }

  @Test(timeout = 30_000)
  fun `registerTools survives a wedged descriptor collection and keeps the class-backed surface`() {
    // Live failure mode: a cold scripted-tool runtime build that never completes (wedged
    // device) used to park registerTools' dispatcher thread indefinitely — the per-call MCP
    // timeout can't cancel a thread parked inside runBlocking's separate job tree. The
    // contract: registerTools must RETURN within its descriptor-collection deadline, keep the
    // class-backed surface it already registered, and simply omit the descriptor tools until
    // a later refresh retries.
    val bridge = RecordingBridge(
      targets = setOf(eraseTarget),
      currentTargetId = eraseTarget.id,
      hangDeviceDiscovery = true,
    )
    val server = newServer(bridge)
    server.descriptorCollectionTimeoutMsForTest = 250
    val sessionContext = ctx("wedged-session")
    server.installSessionContextForTest("wedged-session", sessionContext)
    val mcpServer = server.configureMcpServer()

    server.registerTools(mcpServer, McpSessionId("wedged-session"), sessionContext)

    assertFalse(
      mcpServer.tools.containsKey("eraseText"),
      "The wedged descriptor collection must not have produced descriptor tools",
    )
    assertTrue(
      mcpServer.tools.isNotEmpty(),
      "The class-backed tool surface must survive a timed-out descriptor collection",
    )
  }

  // ── tools/list_changed on the custom SSE notification channel ─────────────

  @Test
  fun `a tools refresh emits list_changed on the session's custom SSE channel`() {
    // The SDK's own ToolListChangedNotification is unreachable in this deployment
    // (enableJsonResponse=true routes it to a standalone GET stream the daemon never
    // registers), so the refresh path must emit through the custom SSE sender — the same
    // channel progress notifications use. This is the client-visible contract that makes a
    // mid-session target switch actually reach a connected MCP client.
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)
    val sessionContext = ctx("sse-session")
    val sentNotifications = mutableListOf<String>()
    sessionContext.customSseNotificationSender = { sentNotifications += it }
    server.installSessionContextForTest("sse-session", sessionContext)
    server.installSessionMcpServerForTest("sse-session", server.configureMcpServer())

    server.refreshToolsForSession("sse-session")

    assertTrue(
      sentNotifications.any { it.contains("notifications/tools/list_changed") },
      "A tools refresh must emit tools/list_changed on the custom SSE channel. " +
        "Sent: $sentNotifications",
    )
  }

  @Test
  fun `a tools refresh with no notification stream open completes without error`() {
    // Documented contract: sendToolListChangedNotification no-ops when the client has no
    // custom SSE stream open — such a client sees the new surface on its next tools/list.
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)
    val sessionContext = ctx("no-stream-session") // customSseNotificationSender left null
    server.installSessionContextForTest("no-stream-session", sessionContext)
    server.installSessionMcpServerForTest("no-stream-session", server.configureMcpServer())

    server.refreshToolsForSession("no-stream-session")
    // Reaching here without an exception IS the contract under test.
  }

  @Test
  fun `a failing notification stream does not fail the tools refresh`() {
    // Documented contract: a sender failure (client stream torn down mid-send) is swallowed —
    // the refresh itself (re-registration) must still complete.
    val bridge = RecordingBridge(targets = setOf(eraseTarget), currentTargetId = eraseTarget.id)
    val server = newServer(bridge)
    val sessionContext = ctx("failing-stream-session")
    sessionContext.customSseNotificationSender = { error("stream torn down") }
    server.installSessionContextForTest("failing-stream-session", sessionContext)
    val mcpServer = server.configureMcpServer()
    server.installSessionMcpServerForTest("failing-stream-session", mcpServer)

    server.refreshToolsForSession("failing-stream-session")

    assertTrue(
      mcpServer.tools.containsKey("eraseText"),
      "The refresh's re-registration must complete even when the notification send fails",
    )
  }

  // ── Inline (target.tools:) scripted tools on the descriptor surface ───────

  @Test
  fun `inline scripted tools from the target's tools declaration surface as MCP tools`() {
    val inlineTarget = InlineToolTarget(
      id = "inlinetarget",
      displayName = "Inline Target",
      inlineToolName = "workspace_echo",
    )
    val bridge = RecordingBridge(targets = setOf(inlineTarget), currentTargetId = inlineTarget.id)
    val server = newServer(bridge)
    val sessionContext = ctx("inline-session")
    server.installSessionContextForTest("inline-session", sessionContext)

    // A runtime repo carrying the inline tool's dynamic registration — what the subprocess
    // synthesizer produces in production, installed via the test seam so no `bun` spawns.
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("message") { put("type", "string") }
      }
      putJsonArray("required") { add("message") }
    }
    val repo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "InlineTestToolSet",
        toolClasses = emptySet(),
        yamlToolNames = emptySet(),
        scriptedToolNames = emptySet(),
      ),
    )
    repo.addDynamicTools(listOf(FakeDynamicRegistration(name = "workspace_echo", schema = schema)))
    server.installSessionScriptToolRuntimeForTest(
      sessionId = "inline-session",
      targetId = inlineTarget.id,
      driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      toolRepo = repo,
    )

    val descriptors = runBlocking { server.collectSessionTargetDescriptorTools(sessionContext) }

    val inlineDescriptor = descriptors.firstOrNull { it.name == "workspace_echo" }
      ?: fail(
        "An inline tool declared via target.tools: must be included in the descriptor surface " +
          "(its name comes from getInlineScriptTools, not the custom/toolset accessors). " +
          "Got: ${descriptors.map { it.name }}",
      )
    assertEquals(
      schema,
      inlineDescriptor.inputSchema,
      "The inline tool's descriptor must carry its full inputSchema for MCP advertisement",
    )
  }

  // ── Fixture ───────────────────────────────────────────────────────────────

  /** App-target stub declaring one inline scripted tool (the `target.tools:` shape). */
  private class InlineToolTarget(
    id: String,
    displayName: String,
    private val inlineToolName: String,
  ) : TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getInlineScriptTools(): List<InlineScriptToolConfig> = listOf(
      InlineScriptToolConfig(script = "tools/$inlineToolName.ts", name = inlineToolName),
    )
  }

  /**
   * Dynamic registration exposing a descriptor and (optionally) a decoder for dispatch tests.
   * Descriptor-collection tests leave [decode] null — they never dispatch.
   */
  private class FakeDynamicRegistration(
    name: String,
    schema: kotlinx.serialization.json.JsonObject,
    private val decode: ((String) -> TrailblazeTool)? = null,
  ) : DynamicTrailblazeToolRegistration {
    override val name: ToolName = ToolName(name)
    override val trailblazeDescriptor: TrailblazeToolDescriptor = TrailblazeToolDescriptor(
      name = name,
      description = "Inline test tool",
    ).also { it.inputSchema = schema }

    override fun buildKoogTool(
      trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
    ): TrailblazeKoogTool<out TrailblazeTool> = error("not used in this test")

    override fun decodeToolCall(argumentsJson: String): TrailblazeTool =
      decode?.invoke(argumentsJson) ?: error("not used in this test")
  }

  /**
   * Host-local tool double: [execute] delegates to [onExecute] (which receives the tool itself
   * so error fixtures can build [TrailblazeToolResult.Error.ExceptionThrown]) and captures the
   * execution context — the tool's input contract — for assertions.
   */
  private class FakeHostLocalTool(
    name: String,
    private val onExecute: suspend FakeHostLocalTool.(TrailblazeToolExecutionContext) -> TrailblazeToolResult,
  ) : HostLocalExecutableTrailblazeTool {
    override val advertisedToolName: String = name
    var capturedContext: TrailblazeToolExecutionContext? = null

    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      capturedContext = toolExecutionContext
      return onExecute(toolExecutionContext)
    }
  }

  /** Inert tool identity used to observe nested dispatch reaching the bridge. */
  private class NestedProbeTool : TrailblazeTool

  /** App-target stub with configurable YAML-tool inclusions and exclusions. */
  private class YamlToolTarget(
    id: String,
    displayName: String,
    private val yamlNames: Set<ToolName>,
    private val excludedYamlNames: Set<ToolName> = emptySet(),
  ) : TrailblazeHostAppTarget(id, displayName) {
    override fun getPossibleAppIdsForPlatform(
      platform: TrailblazeDevicePlatform,
    ): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getCustomYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = yamlNames

    override fun getExcludedYamlToolNamesForDriver(
      driverType: TrailblazeDriverType,
    ): Set<ToolName> = excludedYamlNames
  }

  /**
   * Bridge exposing [targets] with [currentTargetId] as both the per-device and daemon-wide
   * target, one connected Android device, and a recorder for every tool that reaches
   * [executeTrailblazeTool] — the observable dispatch boundary these tests assert on.
   */
  private class RecordingBridge(
    private val targets: Set<TrailblazeHostAppTarget>,
    var currentTargetId: String?,
    /**
     * Simulates a wedged device runtime: [getAvailableDevices] suspends until cancelled,
     * the live failure mode of a stuck cold scripted-tool runtime build (device discovery /
     * on-device session launch that never completes).
     */
    private val hangDeviceDiscovery: Boolean = false,
  ) : TrailblazeMcpBridge {
    val executedTools = mutableListOf<TrailblazeTool>()

    /** Device id bound in [McpDeviceContext] at the moment each tool dispatched (null if unbound). */
    val dispatchedDeviceInstanceIds = mutableListOf<String?>()

    override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> = targets
    override fun getCurrentAppTargetId(): String? = currentTargetId
    override fun getSessionTargetAppIdForDevice(deviceId: TrailblazeDeviceId): String? = currentTargetId

    override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
      if (hangDeviceDiscovery) awaitCancellation()
      return setOf(
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          instanceId = "emulator-5554",
          description = "test-emulator-5554",
        ),
      )
    }

    override fun getDriverType(): TrailblazeDriverType? = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    override suspend fun executeTrailblazeTool(
      tool: TrailblazeTool,
      blocking: Boolean,
      traceId: TraceId?,
    ): String {
      executedTools += tool
      dispatchedDeviceInstanceIds += McpDeviceContext.currentDeviceId.get()?.instanceId
      return "[OK] executed"
    }

    // ── Unused — no-ops just to satisfy the interface ───────────────────────
    override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary =
      error("not used in this test")
    override suspend fun getInstalledAppIds(): Set<String> = emptySet()
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
  }
}
