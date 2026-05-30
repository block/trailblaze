package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Regression coverage for the per-device session-scoped target model.
 *
 * The bug this guards against: in a multi-device interactive session, running
 * `tool web_navigate --device=web --target=myapp` used to flip the daemon-wide
 * `selectedTargetAppId` to `myapp`. A subsequent `snapshot --device=android`
 * (with no `--target`) would then read `myapp` back through the global default
 * and trip `Target app changed (default → myapp) — creating new session` on
 * the android side, killing whatever session the user was building there.
 *
 * The fix moves `--target X --device Y` off the persistent config entirely.
 * The CLI now calls a session-scoped MCP tool (`setSessionTargetForBoundDevice`)
 * after binding the device, which mutates the daemon's in-memory per-device
 * target map. Nothing writes to `~/.trailblaze/trailblaze-settings.json` —
 * persistence is reserved for the explicit `trailblaze config target <id>`
 * subcommand.
 *
 * These tests verify the CLI-side wire contract: the helper sends the correct
 * MCP request and surfaces the daemon's error path. The daemon-side per-device
 * resolution chain (session map → daemon-wide) is verified separately in
 * `trailblaze-server` tests of the bridge.
 */
class PerDeviceTargetTest {

  private data class RecordedToolCall(val name: String?, val body: String)

  private val createdFiles = mutableListOf<java.io.File>()

  @kotlin.test.AfterTest
  fun cleanup() {
    createdFiles.forEach { it.delete() }
  }

  @Test
  fun `setSessionTargetForBoundDevice sends the bound-device MCP tool call`() {
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = sessionTargetStubServer(
      recorded = recorded,
      responseText = "Set session target to MyApp (myapp) for web/playwright-native.",
      isError = false,
    )

    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val err = client.setSessionTargetForBoundDevice("myapp")
          assertNull(err, "happy path should return null on success")
        }
      }

      val toolCall = recorded.singleOrNull {
        "setSessionTargetForBoundDevice" in it.body
      }
      assertNotNull(toolCall, "exactly one MCP tool call must hit setSessionTargetForBoundDevice")
      assertTrue(
        "\"appTargetId\":\"myapp\"" in toolCall.body,
        "the call must carry the user-supplied target id; got: ${toolCall.body}",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `setSessionTargetForBoundDevice surfaces errors via isError flag`() {
    // The daemon-side MCP tool throws on unknown target / no device bound;
    // the MCP framework converts the exception into a tool-call response
    // with `isError=true`. The CLI helper detects failure via that flag —
    // no text-marker parsing. This test pins that contract.
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = sessionTargetStubServer(
      recorded = recorded,
      responseText = "'nope' is not a known target id. Available: [myapp, default]",
      isError = true,
    )

    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val err = client.setSessionTargetForBoundDevice("nope")
          assertNotNull(err, "unknown target id (isError=true) must surface as a CLI error")
          assertTrue(
            "Error setting session target" in err,
            "wrapper must prefix protocol-level errors; got: $err",
          )
          assertTrue(
            "'nope' is not a known target id" in err,
            "wrapper must pass through the daemon's error body; got: $err",
          )
        }
      }
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `setSessionTargetForBoundDevice treats isError=false as success regardless of body wording`() {
    // Regression: the CLI helper used to text-match `startsWith("Failed to
    // set session target")` to detect failures. That coupling broke if the
    // daemon-side wording changed. The new contract is "trust the isError
    // flag" — even if the body literally says "Failed to ...", as long as
    // isError=false, the CLI must NOT report an error.
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = sessionTargetStubServer(
      recorded = recorded,
      responseText = "Set session target to MyApp (myapp).",
      isError = false,
    )

    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val err = client.setSessionTargetForBoundDevice("myapp")
          assertNull(err, "isError=false must be treated as success even with arbitrary body")
        }
      }
    } finally {
      server.stop(0)
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /**
   * Minimal MCP-protocol stub that responds to `initialize`, `notifications/
   * initialized`, and any `tools/call` for `setSessionTargetForBoundDevice`.
   * Records every POST body so callers can assert on the tool-call shape.
   */
  private fun sessionTargetStubServer(
    recorded: MutableList<RecordedToolCall>,
    responseText: String,
    isError: Boolean,
  ): HttpServer {
    val nextSessionId = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      recorded += RecordedToolCall(name = null, body = body)
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add(
            "mcp-session-id",
            "stub-session-${nextSessionId.incrementAndGet()}",
          )
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "setSessionTargetForBoundDevice" in body -> {
          val escaped = responseText.replace("\"", "\\\"")
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"$escaped"}],"isError":$isError}}"""
        }
        else -> """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":""}],"isError":false}}"""
      }
      sendOk(exchange, response)
    }
    server.start()
    return server
  }

  private fun sendOk(exchange: HttpExchange, response: String) {
    val bytes = response.toByteArray()
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  @Test
  fun `rebindBoundDevice issues device action=PLATFORM with the bound deviceId`() {
    // Regression for the post-stop rebind bug flagged by Copilot + Codex on
    // PR #3437: `device rebind` originally went `stop session → set target`,
    // but `SessionToolSet.handleStop` clears `sessionContext.associatedDeviceId`
    // before returning. Without the rebind between those two steps, the
    // follow-up `setSessionTargetForBoundDevice` would throw "No device is
    // bound" and the whole rebind would fail with INFRA_FAILED.
    //
    // The fix adds [CliMcpClient.rebindBoundDevice], which issues a single
    // `device(action=PLATFORM, deviceId=ID)` MCP call. This test pins that
    // wire shape — the action MUST be the bound device's platform (so the
    // daemon's per-platform handler can repopulate `associatedDeviceId` via
    // `setAssociatedDevice`) and the `deviceId` argument MUST carry through.
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = deviceActionStubServer(
      recorded = recorded,
      responseText = "Connected to emulator-5554 (Android).",
      isError = false,
    )

    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val err = client.rebindBoundDevice(
            TrailblazeDeviceId(
              instanceId = "emulator-5554",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            ),
          )
          assertNull(err, "happy path should return null on success")
        }
      }

      val toolCall = recorded.singleOrNull { "tools/call" in it.body && "\"device\"" in it.body }
      assertNotNull(toolCall, "exactly one MCP tool call must hit the device tool")
      assertTrue(
        "\"action\":\"ANDROID\"" in toolCall.body,
        "rebind must use the platform-scoped action so the daemon repopulates " +
          "associatedDeviceId via setAssociatedDevice; got: ${toolCall.body}",
      )
      assertTrue(
        "\"deviceId\":\"emulator-5554\"" in toolCall.body,
        "rebind must pass the bound instanceId so the daemon re-selects the same device; " +
          "got: ${toolCall.body}",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `rebindBoundDevice surfaces daemon errors via isError flag`() {
    // Same protocol contract as setSessionTargetForBoundDevice: an isError=true
    // response from the daemon (e.g. the device disappeared between stop and
    // rebind) must surface as a non-null error string the CLI can render.
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = deviceActionStubServer(
      recorded = recorded,
      responseText = "Error: Android device 'emulator-9999' not found.",
      isError = true,
    )

    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val err = client.rebindBoundDevice(
            TrailblazeDeviceId(
              instanceId = "emulator-9999",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            ),
          )
          assertNotNull(err, "isError=true must surface as a non-null CLI error")
          assertTrue(
            "Error rebinding device" in err,
            "wrapper must prefix the rebind-specific error envelope; got: $err",
          )
        }
      }
    } finally {
      server.stop(0)
    }
  }

  /**
   * Variant of [sessionTargetStubServer] that responds to any `tools/call` for
   * `device` (any action). Used by the rebind tests because they exercise the
   * unified device tool rather than the target-only tool.
   */
  private fun deviceActionStubServer(
    recorded: MutableList<RecordedToolCall>,
    responseText: String,
    isError: Boolean,
  ): HttpServer {
    val nextSessionId = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      recorded += RecordedToolCall(name = null, body = body)
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add(
            "mcp-session-id",
            "stub-session-${nextSessionId.incrementAndGet()}",
          )
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"name\":\"device\"" in body -> {
          val escaped = responseText.replace("\"", "\\\"")
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"$escaped"}],"isError":$isError}}"""
        }
        else -> """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":""}],"isError":false}}"""
      }
      sendOk(exchange, response)
    }
    server.start()
    return server
  }

  @Test
  fun `wire contract — daemon JSON response is parsed correctly`() {
    // Belt-and-braces sanity check that the JSON shape we build above is the
    // shape CliMcpClient actually understands. Failing this means future
    // protocol changes might silently invalidate the other tests.
    val recorded = Collections.synchronizedList(mutableListOf<RecordedToolCall>())
    val server = sessionTargetStubServer(
      recorded = recorded,
      responseText = "OK",
      isError = false,
    )
    try {
      val port = server.address.port
      val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
      sessionFile.delete()
      runBlocking {
        CliMcpClient.connectReusable(port = port).use { client ->
          val result = client.callTool(
            "setSessionTargetForBoundDevice",
            mapOf("appTargetId" to "myapp"),
          )
          assertEquals(false, result.isError)
          assertEquals("OK", result.content)
        }
      }
    } finally {
      server.stop(0)
    }
  }
}
