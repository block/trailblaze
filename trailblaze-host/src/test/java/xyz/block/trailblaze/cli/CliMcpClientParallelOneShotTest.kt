package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pins the different-device parallel isolation contract: two concurrent
 * [CliMcpClient.connectOneShot] calls must each get their own MCP session id,
 * never read or write the persisted session file, and clean up their own MCP
 * session on close.
 */
class CliMcpClientParallelOneShotTest {

  @Test
  fun `parallel connectOneShot calls get isolated MCP sessions and clean up independently`() {
    val nextSessionId = AtomicInteger(0)
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
        "POST" -> handlePost(exchange, body, nextSessionId)
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
    sessionFile.delete()

    try {
      runBlocking {
        coroutineScope {
          awaitAll(
            async { CliMcpClient.connectOneShot(port = port).use { } },
            async { CliMcpClient.connectOneShot(port = port).use { } },
          )
        }
      }

      val initializeRequests = requests.filter { it.method == "POST" && "\"initialize\"" in it.body }
      assertEquals(2, initializeRequests.size, "each one-shot must do its own initialize handshake")
      initializeRequests.forEach { request ->
        assertNull(
          request.sessionIdHeader,
          "fresh one-shot sessions must not inherit each other's MCP session id",
        )
      }

      val deletes = requests.filter { it.method == "DELETE" }
      val deleteSessionIds = deletes.mapNotNull { it.sessionIdHeader }.toSet()
      assertEquals(2, deletes.size, "each one-shot must DELETE /mcp on close")
      assertEquals(2, deleteSessionIds.size, "the two clients must terminate distinct MCP sessions")
      assertNotEquals(
        deleteSessionIds.elementAt(0),
        deleteSessionIds.elementAt(1),
        "the two MCP session ids must not overlap",
      )

      assertFalse(sessionFile.exists(), "one-shot sessions must never write the persisted session file")
    } finally {
      sessionFile.delete()
      server.stop(0)
    }
  }

  private fun handlePost(exchange: HttpExchange, body: String, nextSessionId: AtomicInteger) {
    val response = when {
      "\"initialize\"" in body ->
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
      "\"notifications/initialized\"" in body -> "{}"
      else -> error("Unexpected POST body: $body")
    }
    if ("\"initialize\"" in body) {
      exchange.responseHeaders.add("mcp-session-id", "one-shot-session-${nextSessionId.incrementAndGet()}")
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
}
