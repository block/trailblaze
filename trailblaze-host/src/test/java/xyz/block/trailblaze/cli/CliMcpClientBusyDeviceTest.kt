package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Pins the same-device contention contract under the yield-unless-busy policy:
 * - The CLI no longer sends a `force` argument on the `device` tool call.
 * - When the daemon returns a `Device … is busy.` block (the
 *   [DeviceBusyException] format), the CLI surfaces it verbatim so the user
 *   can see what's actually running and decide to wait.
 */
class CliMcpClientBusyDeviceTest {

  @Test
  fun `ensureDevice surfaces the daemon's rich busy block verbatim`() {
    val capturedDeviceCalls = Collections.synchronizedList(mutableListOf<String>())
    val server = busyDeviceServer(capturedDeviceCalls)
    server.start()

    try {
      val error = runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          client.ensureDevice("android/emulator-5554")
        }
      }

      assertNotNull(error, "yield-unless-busy should still surface an error when the holder is busy")
      assertTrue(
        "is busy" in error,
        "busy error should include the daemon's `is busy` phrase. Got: $error",
      )
      assertTrue(
        "Held by:" in error && "Running:" in error && "Trace:" in error,
        "busy error should keep the rich block (Held by / Running / Trace). Got: $error",
      )

      val deviceCalls = capturedDeviceCalls.toList()
      assertTrue(deviceCalls.isNotEmpty(), "client should have attempted to bind the device")
      assertTrue(
        deviceCalls.none { "\"force\"" in it },
        "CLI must not send a `force` argument anymore. Got: $deviceCalls",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `device tool call does not include a force argument`() {
    val capturedDeviceCalls = Collections.synchronizedList(mutableListOf<String>())
    val server = successfulDeviceServer(capturedDeviceCalls)
    server.start()

    try {
      val error = runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          client.ensureDevice("android/emulator-5554")
        }
      }

      // ensureDevice may return null on success; we only care here that the
      // wire format omits `force` regardless.
      val deviceCalls = capturedDeviceCalls.toList()
      assertTrue(deviceCalls.isNotEmpty(), "client should have attempted to bind the device")
      assertFalse(
        deviceCalls.any { "\"force\"" in it },
        "force argument should be gone from the wire format. error=$error calls=$deviceCalls",
      )
    } finally {
      server.stop(0)
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private fun busyDeviceServer(captured: MutableList<String>): HttpServer {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "test-session")
          send(exchange, INITIALIZE_RESPONSE)
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        toolName(body) == "device" && toolAction(body) == "ANDROID" -> {
          captured += body
          // DeviceBusyException-shaped block surfaced as a successful tool result.
          send(
            exchange,
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Error: Device emulator-5554 is busy.\n  Held by: TrailblazeCLI (origin=blaze android \"login\") (session abcdef12…)\n  Running: blaze(\"login\") for 14s\n  Trace:   mcp-deadbeef\nWait for it to finish, or stop the holder before retrying."}],"isError":false}}""",
          )
        }
        toolName(body) == "device" && toolAction(body) == "LIST" ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"  - emulator-5554 (Android) - Pixel 8"}],"isError":false}}""")
        toolName(body) == "session" && toolAction(body) == "STOP" ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"status\":\"stopped\"}"}],"isError":false}}""")
        else -> error("Unexpected POST body: $body")
      }
    }
    return server
  }

  private fun successfulDeviceServer(captured: MutableList<String>): HttpServer {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "test-session")
          send(exchange, INITIALIZE_RESPONSE)
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        toolName(body) == "device" && toolAction(body) == "ANDROID" -> {
          captured += body
          send(
            exchange,
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to emulator-5554 (Android). Session recording - save anytime with trail(action=SAVE, name='...')"}],"isError":false}}""",
          )
        }
        toolName(body) == "device" && toolAction(body) == "LIST" ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"  - emulator-5554 (Android) - Pixel 8"}],"isError":false}}""")
        toolName(body) == "session" && toolAction(body) == "STOP" ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"status\":\"stopped\"}"}],"isError":false}}""")
        else -> error("Unexpected POST body: $body")
      }
    }
    return server
  }

  private fun send(exchange: HttpExchange, response: String) {
    val bytes = response.toByteArray()
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private fun toolName(body: String): String? {
    if ("\"tools/call\"" !in body) return null
    return runCatching {
      Json.parseToJsonElement(body).jsonObject["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content
    }.getOrNull()
  }

  private fun toolAction(body: String): String? {
    return runCatching {
      Json.parseToJsonElement(body).jsonObject["params"]
        ?.jsonObject?.get("arguments")
        ?.jsonObject?.get("action")
        ?.jsonPrimitive?.content
    }.getOrNull()
  }

  companion object {
    private const val INITIALIZE_RESPONSE =
      """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
  }
}
