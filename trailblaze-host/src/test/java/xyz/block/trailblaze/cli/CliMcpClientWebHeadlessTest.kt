package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies that [CliMcpClient.ensureDevice] forwards the `webHeadless` flag to the
 * daemon's `device(action=WEB)` MCP tool only for WEB devices, and never for
 * mobile platforms. A future refactor that renames [DeviceManagerToolSet.PARAM_HEADLESS]
 * or moves the conditional would silently break the `--headless` flag if these tests
 * weren't here — only manual end-to-end runs would catch it otherwise.
 */
class CliMcpClientWebHeadlessTest {

  @Test
  fun `ensureDevice forwards webHeadless=false to the daemon for web devices`() {
    val toolCallArgs = Collections.synchronizedList(mutableListOf<JsonObject>())
    val server = stubServer(toolCallArgs)
    server.start()

    try {
      runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          assertNull(client.ensureDevice("web/foo", webHeadless = false))
        }
      }

      val webCall = toolCallArgs.single { it["action"]?.jsonPrimitive?.content == "WEB" }
      assertEquals("foo", webCall["deviceId"]?.jsonPrimitive?.content)
      assertEquals(false, webCall["headless"]?.jsonPrimitive?.boolean)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `ensureDevice forwards webHeadless=true to the daemon for web devices`() {
    val toolCallArgs = Collections.synchronizedList(mutableListOf<JsonObject>())
    val server = stubServer(toolCallArgs)
    server.start()

    try {
      runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          assertNull(client.ensureDevice("web/bar", webHeadless = true))
        }
      }

      val webCall = toolCallArgs.single { it["action"]?.jsonPrimitive?.content == "WEB" }
      assertEquals(true, webCall["headless"]?.jsonPrimitive?.boolean)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `ensureDevice does NOT forward headless arg for non-web platforms`() {
    val toolCallArgs = Collections.synchronizedList(mutableListOf<JsonObject>())
    val server = stubServer(toolCallArgs)
    server.start()

    try {
      runBlocking {
        CliMcpClient.connectOneShot(port = server.address.port).use { client ->
          // The default webHeadless=true must NOT leak into Android/iOS connect calls.
          assertNull(client.ensureDevice("android/emulator-5554", webHeadless = true))
        }
      }

      val androidCall = toolCallArgs.single { it["action"]?.jsonPrimitive?.content == "ANDROID" }
      assertEquals(
        null,
        androidCall["headless"]?.takeUnless { it is JsonNull },
        "Mobile device connect calls must not include the headless arg",
      )
    } finally {
      server.stop(0)
    }
  }

  /**
   * A minimal MCP server that records every `tools/call` arguments object the
   * client sends, then returns canned success responses for the actions
   * [ensureDevice] uses (LIST/ANDROID/IOS/WEB) and the session STOP teardown.
   */
  private fun stubServer(toolCallArgs: MutableList<JsonObject>): HttpServer {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when (exchange.requestMethod) {
        "POST" -> {
          // Record the tools/call arguments so tests can assert on them.
          runCatching {
            val parsed = Json.parseToJsonElement(body).jsonObject
            if (parsed["method"]?.jsonPrimitive?.content == "tools/call") {
              parsed["params"]?.jsonObject?.get("arguments")?.jsonObject?.let(toolCallArgs::add)
            }
          }
          val response = when {
            "\"initialize\"" in body ->
              """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
            "\"notifications/initialized\"" in body -> "{}"
            toolCallAction(body) == "LIST" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"- emulator-5554 (Android) - Pixel 8\n- playwright-native (Web Browser)"}],"isError":false}}"""
            toolCallAction(body) == "ANDROID" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to emulator-5554 (Android)"}],"isError":false}}"""
            toolCallAction(body) == "WEB" ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to playwright-native (Web Browser)"}],"isError":false}}"""
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
    return server
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

  @Suppress("unused")
  private fun argsOrNull(json: JsonElement?): JsonObject? = json?.jsonObject
}
