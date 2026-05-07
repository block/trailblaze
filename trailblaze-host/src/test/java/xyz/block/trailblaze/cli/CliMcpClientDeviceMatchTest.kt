package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pins the lookup-and-match contract that `session stop` and `session end` rely on.
 *
 * Two pieces, deliberately split:
 *  - [CliMcpClient.getBoundDeviceId] — pure I/O, asks the daemon's `device(INFO)` what
 *    it's bound to and returns a typed [TrailblazeDeviceId] (or null). Tested against
 *    a stub daemon (HttpServer) the same way [CliMcpClientReusableTest] tests its
 *    endpoints.
 *  - [deviceArgMatches] — pure function, applies the matching policy. Tested in
 *    isolation with no I/O at all.
 *
 * Splitting keeps each test focused: the lookup test cares about the wire format and
 * the matcher test cares about the comparison rules.
 */
class CliMcpClientDeviceMatchTest {

  private val createdFiles = mutableListOf<java.io.File>()
  private val servers = mutableListOf<HttpServer>()

  @AfterTest
  fun cleanup() {
    createdFiles.forEach { it.delete() }
    servers.forEach { it.stop(0) }
  }

  // ── getBoundDeviceId — daemon I/O ─────────────────────────────────────────

  @Test
  fun `getBoundDeviceId returns the typed device id when daemon reports both platform and instance`() {
    val server = stubDeviceInfoServer(infoText = "Platform: Android\nInstance ID: emulator-5554")
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("test-session\nsampleapp")

    runBlocking {
      CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
        val bound = client.getBoundDeviceId()
        assertNotNull(bound)
        assertEquals("emulator-5554", bound.instanceId)
        assertEquals(TrailblazeDevicePlatform.ANDROID, bound.trailblazeDevicePlatform)
        // toFullyQualifiedDeviceId() is the canonical user-visible form
        assertEquals("android/emulator-5554", bound.toFullyQualifiedDeviceId())
      }
    }
  }

  @Test
  fun `getBoundDeviceId returns null when daemon has no device bound`() {
    val server = stubDeviceInfoServer(infoText = "No device connected", infoIsError = true)
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("test-session\nsampleapp")

    runBlocking {
      CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
        assertNull(client.getBoundDeviceId())
      }
    }
  }

  @Test
  fun `getBoundDeviceId returns null when platform is reported but instance is missing`() {
    // The daemon is in a half-bound state — known platform but no committed instance
    // (e.g. before a Playwright page is attached). For session-stop semantics this is
    // the same as "no session for this device" — there's no specific instance to act on.
    val server = stubDeviceInfoServer(infoText = "Platform: Android")
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("test-session\nsampleapp")

    runBlocking {
      CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
        assertNull(client.getBoundDeviceId())
      }
    }
  }

  // ── deviceArgMatches — pure matching policy ──────────────────────────────

  private val androidEmu5554 = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val iosUuid = TrailblazeDeviceId(
    instanceId = "SIM-UUID-1234",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
  )

  @Test
  fun `deviceArgMatches accepts a full match`() {
    assertTrue(deviceArgMatches("android/emulator-5554", androidEmu5554))
  }

  @Test
  fun `deviceArgMatches is case-insensitive on platform and instance`() {
    assertTrue(deviceArgMatches("ANDROID/Emulator-5554", androidEmu5554))
    assertTrue(deviceArgMatches("Android", androidEmu5554))
  }

  @Test
  fun `deviceArgMatches accepts platform-only user spec against any instance`() {
    assertTrue(deviceArgMatches("android", androidEmu5554))
    assertTrue(deviceArgMatches("ios", iosUuid))
  }

  @Test
  fun `deviceArgMatches rejects different instance ids on the same platform`() {
    assertFalse(deviceArgMatches("android/emulator-5556", androidEmu5554))
  }

  @Test
  fun `deviceArgMatches rejects different platforms`() {
    assertFalse(deviceArgMatches("ios/whatever", androidEmu5554))
    assertFalse(deviceArgMatches("web", androidEmu5554))
    assertFalse(deviceArgMatches("android", iosUuid))
  }

  @Test
  fun `deviceArgMatches rejects unparseable user platform`() {
    assertFalse(deviceArgMatches("blackberry", androidEmu5554))
    assertFalse(deviceArgMatches("xbox/console-1", androidEmu5554))
  }

  @Test
  fun `deviceArgMatches rejects malformed user input like a trailing slash`() {
    // "android/" matches neither the bound full spec ("android/emulator-5554") nor the
    // bare platform name ("ANDROID"), so we reject it. Strict on purpose — if the user
    // typed a slash they meant to specify an instance and didn't.
    assertFalse(deviceArgMatches("android/", androidEmu5554))
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /**
   * Spins up a stub HTTP server that pretends to be the Trailblaze daemon. Every
   * `device(INFO)` `tools/call` returns [infoText] in `content[0].text`; the rest of
   * the MCP handshake is faked just enough for `connectReusable` to attach.
   */
  private fun stubDeviceInfoServer(infoText: String, infoIsError: Boolean = false): HttpServer {
    val server = HttpServer.create(InetSocketAddress(0), 0)
    val isError = if (infoIsError) "true" else "false"
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "test-session")
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          send(
            exchange,
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"${
              jsonEscape(infoText)
            }"}],"isError":$isError}}""",
          )
        else -> send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[],"isError":false}}""")
      }
    }
    server.start()
    servers += server
    return server
  }

  private fun send(exchange: HttpExchange, response: String) {
    val bytes = response.toByteArray()
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }

  /** Minimal JSON-string escaper for the small set of chars our test fixtures use. */
  private fun jsonEscape(s: String): String =
    s.replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
}
