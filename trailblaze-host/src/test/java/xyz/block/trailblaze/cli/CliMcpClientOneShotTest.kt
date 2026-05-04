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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract for [CliMcpClient.connectOneShot]:
 * - never reads or writes the persisted session file,
 * - always initializes a fresh MCP session,
 * - tears down the MCP session on [CliMcpClient.close] (session STOP then DELETE /mcp).
 */
class CliMcpClientOneShotTest {

  @Test
  fun `connectOneShot ignores persisted session file and deletes session on close`() {
    val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      requests += RequestRecord(
        method = exchange.requestMethod,
        body = body,
        sessionIdHeader = exchange.requestHeaders.getFirst("mcp-session-id"),
      )
      when (exchange.requestMethod) {
        "POST" -> handlePost(exchange, body)
        "DELETE" -> {
          exchange.sendResponseHeaders(204, -1)
          exchange.close()
        }
        else -> error("Unexpected method ${exchange.requestMethod}")
      }
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port)
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectOneShot(port = port).use { client ->
          assertEquals("test-session", client.sessionId)
        }
      }

      assertEquals(
        "persisted-session\nsampleapp",
        sessionFile.readText(),
        "one-shot sessions should not overwrite the shared session file",
      )

      val initialize = requests.first { it.method == "POST" && "\"initialize\"" in it.body }
      assertNull(
        initialize.sessionIdHeader,
        "one-shot sessions should initialize without reusing a persisted MCP session ID",
      )

      val delete = requests.single { it.method == "DELETE" }
      assertEquals("test-session", delete.sessionIdHeader)
      assertTrue(
        requests.any { it.method == "POST" && "\"notifications/initialized\"" in it.body },
        "initialize handshake should still send the required initialized notification",
      )
    } finally {
      sessionFile.delete()
      server.stop(0)
    }
  }

  @Test
  fun `connectOneShot ends the Trailblaze session before deleting the MCP session`() {
    val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      requests += RequestRecord(
        method = exchange.requestMethod,
        body = body,
        sessionIdHeader = exchange.requestHeaders.getFirst("mcp-session-id"),
      )
      when (exchange.requestMethod) {
        "POST" -> {
          val response = when {
            "\"initialize\"" in body ->
              """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
            "\"notifications/initialized\"" in body -> "{}"
            toolCallAction(body) == "LIST" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"- emulator-5554 (Android) - Pixel 8"}],"isError":false}}"""
            toolCallAction(body) == "ANDROID" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to emulator-5554 (Android)"}],"isError":false}}"""
            toolCallAction(body) == "STOP" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"status\":\"stopped\"}"}],"isError":false}}"""
            else -> error("Unexpected POST body: $body")
          }
          if ("\"initialize\"" in body) {
            exchange.responseHeaders.add("mcp-session-id", "test-session")
          }
          val bytes = response.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
        "DELETE" -> {
          exchange.sendResponseHeaders(204, -1)
          exchange.close()
        }
        else -> error("Unexpected method ${exchange.requestMethod}")
      }
    }
    server.start()

    try {
      runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          assertNull(client.ensureDevice("android/emulator-5554"))
        }
      }

      val stopIndex = requests.indexOfFirst(::isSessionStopRequest)
      val deleteIndex = requests.indexOfFirst { it.method == "DELETE" }
      assertTrue(
        stopIndex >= 0,
        "close should stop the active Trailblaze session before deleting MCP session. Requests=${requests.joinToString()}",
      )
      assertTrue(deleteIndex >= 0, "close should still delete the MCP session")
      assertTrue(stopIndex < deleteIndex, "session STOP must happen before DELETE /mcp")
    } finally {
      server.stop(0)
    }
  }

  private fun handlePost(exchange: HttpExchange, body: String) {
    val response = when {
      "\"initialize\"" in body ->
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
      "\"notifications/initialized\"" in body -> "{}"
      else -> error("Unexpected POST body: $body")
    }
    if ("\"initialize\"" in body) {
      exchange.responseHeaders.add("mcp-session-id", "test-session")
    }
    val bytes = response.toByteArray()
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  private data class RequestRecord(
    val method: String,
    val body: String,
    val sessionIdHeader: String?,
  )

  private fun isSessionStopRequest(request: RequestRecord): Boolean {
    if (request.method != "POST") return false
    return runCatching {
      val body = Json.parseToJsonElement(request.body).jsonObject
      val params = body["params"]?.jsonObject ?: return false
      body["method"]?.jsonPrimitive?.content == "tools/call" &&
        params["name"]?.jsonPrimitive?.content == "session" &&
        params["arguments"]?.jsonObject?.get("action")?.jsonPrimitive?.content == "STOP"
    }.getOrDefault(false)
  }

  private fun toolCallAction(body: String): String? {
    return runCatching {
      Json.parseToJsonElement(body).jsonObject["params"]
        ?.jsonObject
        ?.get("arguments")
        ?.jsonObject
        ?.get("action")
        ?.jsonPrimitive
        ?.content
    }.getOrNull()
  }
}
