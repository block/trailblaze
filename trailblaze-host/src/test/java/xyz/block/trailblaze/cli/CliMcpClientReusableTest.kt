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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the contract for [CliMcpClient.connectReusable]:
 * - persists session ID + target app ID under the per-port (and optional per-scope) state file,
 * - reuses the persisted session when the target is unchanged and the daemon still recognizes it,
 * - creates a fresh session when the target changes or the daemon was restarted,
 * - keeps each `sessionScope` isolated so different blaze devices don't share state.
 */
class CliMcpClientReusableTest {

  private val createdFiles = mutableListOf<java.io.File>()

  @AfterTest
  fun cleanup() {
    createdFiles.forEach { it.delete() }
  }

  @Test
  fun `first connectReusable writes session id and target to file`() {
    val server = stubServer(initialSessionId = "fresh-session")
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.delete()

    try {
      runBlocking {
        CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
          assertEquals("fresh-session", client.sessionId)
          assertEquals(false, client.hasExistingDevice)
        }
      }

      val lines = sessionFile.readLines()
      assertEquals("fresh-session", lines.getOrNull(0))
      assertEquals("sampleapp", lines.getOrNull(1))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `connectReusable reuses persisted session id when target unchanged`() {
    val initializeRequests = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          initializeRequests.incrementAndGet()
          exchange.responseHeaders.add("mcp-session-id", "should-not-be-used")
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        // device(INFO) response that the persisted session is alive but no device is connected.
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"No device connected"}],"isError":true}}""")
        else -> error("Unexpected POST body: $body")
      }
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
          assertEquals("persisted-session", client.sessionId, "should reuse the persisted MCP session id")
        }
      }

      assertEquals(0, initializeRequests.get(), "no fresh initialize handshake when reusing a live session")
      // File contents should remain unchanged.
      assertEquals("persisted-session\nsampleapp", sessionFile.readText())
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `connectReusable creates a new session when targetAppId changes`() {
    val server = stubServer(initialSessionId = "new-session-after-target-change")
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("old-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(port = port, targetAppId = "otherapp").use { client ->
          assertEquals("new-session-after-target-change", client.sessionId)
        }
      }

      assertEquals("new-session-after-target-change\notherapp", sessionFile.readText())
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `connectReusable creates a new session when daemon does not recognize the persisted id`() {
    val initializeCount = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          initializeCount.incrementAndGet()
          exchange.responseHeaders.add("mcp-session-id", "recovered-session")
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        // Simulate "daemon was restarted" — the persisted session id is unknown.
        // Anything that's an error AND not 'No device connected' triggers the recovery branch.
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Unknown session id"}],"isError":true}}""")
        else -> error("Unexpected POST body: $body")
      }
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("stale-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
          assertEquals("recovered-session", client.sessionId)
        }
      }

      assertEquals(1, initializeCount.get(), "exactly one fresh initialize after the daemon rejected the saved id")
      assertEquals("recovered-session\nsampleapp", sessionFile.readText())
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `different session scopes started concurrently get isolated MCP sessions`() {
    val requests = Collections.synchronizedList(mutableListOf<RequestRecord>())
    val nextSessionId = AtomicInteger(0)
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
            "\"tools/call\"" in body ->
              """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"No device connected"}],"isError":true}}"""
            else -> error("Unexpected POST body: $body")
          }
          if ("\"initialize\"" in body) {
            exchange.responseHeaders.add(
              "mcp-session-id",
              "scoped-session-${nextSessionId.incrementAndGet()}",
            )
          }
          val bytes = response.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
        else -> error("Unexpected method ${exchange.requestMethod}")
      }
    }
    server.start()

    val port = server.address.port
    val iosScope = "blaze-ios/SIM-UUID"
    val webScope = "blaze-web/playwright-native"
    val iosFile = CliMcpClient.sessionFile(port, iosScope).also { createdFiles += it }
    val webFile = CliMcpClient.sessionFile(port, webScope).also { createdFiles += it }

    try {
      runBlocking {
        coroutineScope {
          awaitAll(
            async { CliMcpClient.connectReusable(port = port, sessionScope = iosScope).use { } },
            async { CliMcpClient.connectReusable(port = port, sessionScope = webScope).use { } },
          )
        }
      }

      val initializeRequests = requests.filter { it.method == "POST" && "\"initialize\"" in it.body }
      assertEquals(2, initializeRequests.size)
      initializeRequests.forEach { request ->
        assertNull(
          request.sessionIdHeader,
          "fresh device-scoped sessions should not inherit another scope's MCP session id",
        )
      }

      assertTrue(iosFile.exists(), "iOS-scoped blaze session should persist separately")
      assertTrue(webFile.exists(), "web-scoped blaze session should persist separately")
      assertNotEquals(iosFile.absolutePath, webFile.absolutePath)
      assertNotEquals(iosFile.readText(), webFile.readText())
    } finally {
      server.stop(0)
    }
  }

  /**
   * Regression test for the `tool` → `blaze` handoff bug: when a prior CLI invocation
   * (e.g. `trailblaze tool tap …`) leaves the daemon's per-session device association
   * silently out of sync with what `device(INFO)` reports, the next `blaze` call would
   * see "No device connected" inside the daemon. ensureDevice must therefore always
   * issue a `device(action=PLATFORM, deviceId=ID)` call to re-register the device on
   * session reuse — even when the spec matches what INFO returns.
   */
  @Test
  fun `ensureDevice re-registers device on session reuse even when spec matches`() {
    val deviceConnectCalls = AtomicInteger(0)
    val deviceListCalls = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "should-not-be-used")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Platform: iOS\nInstance ID: SIM-UUID-1234"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"LIST\"" in body -> {
          deviceListCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Available devices:\n  - SIM-UUID-1234 (iOS) - iPhone 17 Pro"}],"isError":false}}"""
        }
        "\"tools/call\"" in body && "\"device\"" in body && "\"IOS\"" in body -> {
          deviceConnectCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to SIM-UUID-1234 (iOS). Session recording - save anytime with trail(action=SAVE, name='...')"}],"isError":false}}"""
        }
        // session(INFO) is fired by connectToDevice for menu output — but the lightweight
        // rebind path on session reuse should NOT need it. Return success so an accidental
        // call still completes; the assertion below catches the regression.
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"trailblaze-session-1\"}"}],"isError":false}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port, sessionScope = "cli-ios/sim-uuid-1234")
      .also { createdFiles += it }
    // Simulate the state left by a prior `tool` invocation: persisted session id +
    // matching target.
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(
          port = port,
          targetAppId = "sampleapp",
          sessionScope = "cli-ios/sim-uuid-1234",
        ).use { client ->
          assertEquals("persisted-session", client.sessionId)
          assertTrue(client.hasExistingDevice, "INFO reported a bound device → hasExistingDevice")
          val error = client.ensureDevice("ios/SIM-UUID-1234")
          assertNull(error, "ensureDevice should succeed when the spec matches the bound device")
        }
      }

      assertEquals(
        1,
        deviceConnectCalls.get(),
        "ensureDevice must re-issue device(action=IOS) on session reuse so the daemon-side " +
          "per-session device association is refreshed (otherwise a subsequent blaze() call " +
          "inside the same session sees 'No device connected')",
      )
      // The rebind path must NOT issue device(action=LIST) — that's a cold-start concern
      // (validating instance IDs, picking a default when multiple are present). On a reused
      // session, we already know which device we're bound to. Re-listing would also force
      // the connectToDevice "Connecting to …" + "Connected: …" + session-menu banner to
      // print on every reused-session invocation, drowning the quiet "Reusing session …"
      // line.
      assertEquals(
        0,
        deviceListCalls.get(),
        "lightweight rebind on session reuse should not issue device(action=LIST) — the " +
          "extra roundtrip is unnecessary and pulling in the full connectToDevice banner " +
          "regresses the reused-session UX",
      )
    } finally {
      server.stop(0)
    }
  }

  /**
   * Sibling regression test pinning the WEB-specific rebind contract: when reusing a
   * session bound to a WEB device, the rebind must include the `headless` flag so the
   * daemon's recorded per-browser headless preference stays in sync with the CLI's
   * `--headless` choice. Mobile rebinds (covered by the iOS test above) must NOT
   * include `headless`.
   */
  @Test
  fun `ensureDevice rebind passes headless flag for WEB but omits it for mobile`() {
    val webRebindBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "should-not-be-used")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Platform: Web Browser\nInstance ID: playwright-native"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"WEB\"" in body -> {
          webRebindBodies += body
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to playwright-native (Web)"}],"isError":false}}"""
        }
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port, sessionScope = "cli-web/playwright-native")
      .also { createdFiles += it }
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(
          port = port,
          targetAppId = "sampleapp",
          sessionScope = "cli-web/playwright-native",
        ).use { client ->
          val error = client.ensureDevice("web/playwright-native", webHeadless = false)
          assertNull(error)
        }
      }

      assertEquals(1, webRebindBodies.size, "exactly one device(action=WEB) call on reuse")
      val rebindBody = webRebindBodies.single()
      assertTrue(
        "\"headless\":false" in rebindBody,
        "WEB rebind must forward the caller's --headless choice; got body: $rebindBody",
      )
    } finally {
      server.stop(0)
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private fun stubServer(initialSessionId: String): HttpServer {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", initialSessionId)
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        else -> send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"No device connected"}],"isError":true}}""")
      }
    }
    server.start()
    return server
  }

  private fun send(exchange: HttpExchange, response: String) {
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
