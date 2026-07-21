package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

  /** Android reuse trusts the session binding without a host-wide discovery reconnect. */
  @Test
  fun `ensureDevice skips Android reconnect when session-scoped INFO matches`() {
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
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Platform: Android\nInstance ID: emulator-5554"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"LIST\"" in body -> {
          deviceListCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Available devices:\n  - emulator-5554 (Android) - Pixel 9"}],"isError":false}}"""
        }
        "\"tools/call\"" in body && "\"device\"" in body && "\"ANDROID\"" in body -> {
          deviceConnectCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to emulator-5554 (Android). Session recording - save anytime with trail(action=SAVE, name='...')"}],"isError":false}}"""
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
    val sessionFile = CliMcpClient.sessionFile(port, sessionScope = "cli-android/emulator-5554")
      .also { createdFiles += it }
    // Simulate the state left by a prior `tool` invocation: persisted session id +
    // matching target.
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(
          port = port,
          targetAppId = "sampleapp",
          sessionScope = "cli-android/emulator-5554",
        ).use { client ->
          assertEquals("persisted-session", client.sessionId)
          assertTrue(client.hasExistingDevice, "INFO reported a bound device → hasExistingDevice")
          val error = client.ensureDevice("android/emulator-5554")
          assertNull(error, "ensureDevice should succeed when the spec matches the bound device")
        }
      }

      assertEquals(
        0,
        deviceConnectCalls.get(),
        "a verified reusable session must not re-issue device(action=IOS)",
      )
      // The reuse path must NOT issue device(action=LIST) — that's a cold-start concern
      // (validating instance IDs, picking a default when multiple are present). On a reused
      // session, we already know which device we're bound to. Re-listing would also force
      // the connectToDevice "Connecting to …" + "Connected: …" + session-menu banner to
      // print on every reused-session invocation, drowning the quiet "Reusing session …"
      // line.
      assertEquals(
        0,
        deviceListCalls.get(),
        "session reuse should not issue device(action=LIST) — the " +
          "extra roundtrip is unnecessary and pulling in the full connectToDevice banner " +
          "regresses the reused-session UX",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `ensureDevice rejects stale Android session binding`() {
    val deviceConnectCalls = AtomicInteger(0)
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
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected device:\n  Instance ID: emulator-5554\n  Platform: Android\n\nDriver status: Android device 'emulator-5554' is no longer connected to adb."}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"LIST\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Available devices:"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"ANDROID\"" in body -> {
          deviceConnectCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Error: Android device 'emulator-5554' not found."}],"isError":false}}"""
        }
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port, sessionScope = "cli-android/emulator-5554")
      .also { createdFiles += it }
    sessionFile.writeText("persisted-session\nsampleapp")

    try {
      runBlocking {
        CliMcpClient.connectReusable(
          port = port,
          targetAppId = "sampleapp",
          sessionScope = "cli-android/emulator-5554",
        ).use { client ->
          assertFalse(client.hasExistingDevice, "a disconnected driver must not use the hot path")
          val error = client.ensureDevice("android/emulator-5554")
          assertEquals("Error: Android device 'emulator-5554' not found.", error)
        }
      }

      assertEquals(1, deviceConnectCalls.get(), "stale bindings must be revalidated through connect")
    } finally {
      server.stop(0)
    }
  }

  /** A reused WEB session refreshes the browser selection used by screen-state tools. */
  @Test
  fun `ensureDevice rebinds matching WEB session`() {
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

      assertEquals(1, webRebindBodies.size, "matching WEB reuse must refresh the browser binding")
      assertTrue("\"deviceId\":\"playwright-native\"" in webRebindBodies.single())
      assertTrue("\"headless\":false" in webRebindBodies.single())
    } finally {
      server.stop(0)
    }
  }

  // ── session-transition message routing (eval-safety regression) ──────────
  //
  // `eval $(trailblaze device connect …)` requires the connect command's stdout
  // to contain ONLY the `export TRAILBLAZE_DEVICE=…` lines — any preceding
  // human-readable text makes the shell try to execute that text as a command
  // (e.g. `command not found: Target`). connectReusable emits status lines on
  // three transition paths: target change, daemon-doesn't-recognize-session,
  // and daemon-probe-failed. All three MUST go to stderr.
  //
  // As a second-layer defense, we also pin that those messages stay ASCII —
  // a stray em-dash (`—`) or Unicode arrow (`→`) inside what would otherwise
  // be a quoted shell token has historically broken zsh's eval with
  // `zsh: unmatched '`, so we want a hard contract that nothing routes
  // non-ASCII characters through these particular emissions.

  @Test
  fun `target-change transition message routes to stderr, not stdout, and stays ASCII`() {
    val server = stubServer(initialSessionId = "new-session-after-target-change")
    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("old-session\nsampleapp")

    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          CliMcpClient.connectReusable(port = port, targetAppId = "otherapp").use { /* noop */ }
        }
      }

      val stdout = out.toString(Charsets.UTF_8)
      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stdout.contains("Target app changed"),
        "transition message must NOT appear on stdout (it would poison `eval`). " +
          "Got stdout: <<<$stdout>>>",
      )
      assertTrue(
        stderr.contains("Target app changed"),
        "transition message must appear on stderr so the user sees it. " +
          "Got stderr: <<<$stderr>>>",
      )
      assertFalse(
        "—" in stderr || "→" in stderr,
        "transition message must stay ASCII — em-dash (U+2014) or right-arrow " +
          "(U+2192) inside a single-quoted shell token has historically broken " +
          "zsh's eval. Got stderr: <<<$stderr>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `device-switch transition message routes to stderr, not stdout, and stays ASCII`() {
    // ensureDevice() emits its own "Switching device --" line on a fourth transition
    // path (different from the three in connectReusable): when the persisted session
    // is alive AND has a bound device, but the user passed --device pointing at a
    // different platform/instance. This was the original site that broke
    // `eval $(trailblaze device connect …)` on iOS FTUX — same eval-safety contract
    // as the connectReusable-side transitions, separate test because the production
    // emission site is separate.
    val deviceConnectCalls = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "should-not-be-used")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        // INFO reports an iOS device is already bound — so the persisted session is
        // alive AND hasExistingDevice flips true.
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Platform: iOS\nInstance ID: SIM-UUID-1234"}],"isError":false}}"""
        // Spec mismatch triggers the "Switching device --" emission, then falls
        // through to connectToDevice(ANDROID) → device(LIST) + device(action=ANDROID).
        // Both need a successful stub so the call completes; the assertion just
        // checks where the "Switching device" line landed.
        "\"tools/call\"" in body && "\"device\"" in body && "\"LIST\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Available devices:\n  - emulator-5554 (Android) - Pixel 7"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"device\"" in body && "\"ANDROID\"" in body -> {
          deviceConnectCalls.incrementAndGet()
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Connected to emulator-5554 (Android)"}],"isError":false}}"""
        }
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"trailblaze-session-1\"}"}],"isError":false}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("persisted-session\nsampleapp")

    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { client ->
            assertNull(client.ensureDevice("android"))
            assertNull(client.ensureDevice("android"))
          }
        }
      }

      val stdout = out.toString(Charsets.UTF_8)
      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stdout.contains("Switching device"),
        "transition message must NOT appear on stdout (would poison `eval`). " +
          "Got stdout: <<<$stdout>>>",
      )
      assertEquals(
        1,
        stderr.lineSequence().count { "Switching device" in it },
        "only the first call should see the old cached iOS binding. Got stderr: <<<$stderr>>>",
      )
      assertEquals(2, deviceConnectCalls.get(), "both Android connection requests should complete")
      // Pin ASCII-only on the transition-line substring specifically — the
      // surrounding "Connecting to …" / "10–30s" emissions intentionally still
      // contain Unicode (they're already on stderr and predate this fix), so
      // a blanket "no em-dash anywhere in stderr" assertion would be wrong.
      val transitionLine = stderr.lineSequence().first { "Switching device" in it }
      assertFalse(
        "—" in transitionLine || "→" in transitionLine,
        "transition line must stay ASCII. Got line: <<<$transitionLine>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  // ── target-only-swap on warm reuse (the silent-target-change regression) ──
  //
  // `eval $(trailblaze device connect <same-device> --target Y)` after a prior
  // connect with `--target X` reuses the MCP session (file-tier targetAppId
  // matches the config tier, NOT the --target arg), then the daemon hot-swaps
  // the per-device override via `setSessionTargetForBoundDevice` — so the
  // four `connectReusable` transition emissions above never fire. Before this
  // fix, the user-visible output was just "Reusing session …" with no notice
  // that their target changed. The announcing variant of the setter probes
  // session(INFO) for the prior override-vs-daemon-wide source and emits a
  // stderr-routed `Target app changed (X -> Y) -- pinned on existing session.`
  // line when (and only when) it's swapping an existing per-device override.
  //
  // The three tests below pin: (a) the swap-of-existing-override case emits,
  // (b) first-time pins (daemon-wide source) stay silent, (c) no-op re-pins
  // of the same value stay silent.

  @Test
  fun `target-swap on warm reuse announces change to stderr with ASCII transition line`() {
    // Simulates the reported reproducer: prior connect set --target default
    // (so session(INFO) now reports `target=default, targetSource=session-override`),
    // current connect calls setSessionTargetForBoundDeviceAnnouncingChange("team").
    // Expect: stderr contains "Target app changed (default -> team) -- pinned on
    // existing session." in ASCII; stdout stays clean for `eval` safety.
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "warm-session")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          // Prior per-device override = "default". The announcing helper compares
          // this against the new requested target to decide whether to emit.
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"warm-session\",\"target\":\"default\",\"targetSource\":\"session-override\"}"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"setSessionTargetForBoundDevice\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Set session target to Team (team) for android/emulator-5556."}],"isError":false}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp")
          client.use {
            it.initialize()
            val error = it.setSessionTargetForBoundDeviceAnnouncingChange("team")
            assertNull(error)
          }
        }
      }

      val stdout = out.toString(Charsets.UTF_8)
      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stdout.contains("Target app changed"),
        "transition message must NOT appear on stdout (it would poison `eval`). " +
          "Got stdout: <<<$stdout>>>",
      )
      assertTrue(
        stderr.contains("Target app changed (default -> team) -- pinned on existing session."),
        "warm-reuse target swap must announce the change on stderr. " +
          "Got stderr: <<<$stderr>>>",
      )
      // ASCII-only contract — em-dash (U+2014) or right-arrow (U+2192) inside
      // a quoted shell token has historically broken zsh's eval (see other
      // transition tests). Check the transition line specifically rather than
      // the whole stderr (so any surrounding emissions that intentionally use
      // Unicode aren't false-positives).
      val transitionLine = stderr.lineSequence().first { "Target app changed" in it }
      assertFalse(
        "—" in transitionLine || "→" in transitionLine,
        "transition line must stay ASCII. Got line: <<<$transitionLine>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `first-time pin from daemon-wide fallback stays silent`() {
    // When session(INFO) reports `targetSource=daemon-wide` (no per-device
    // override yet), the announcing helper is on the *first* pin path — the
    // user just typed --target X and gets the device-bind + the daemon-side
    // override. Emitting "Target app changed (default -> X)" here would be
    // confusing because no override existed before; the daemon-wide value
    // was just an implicit fallback.
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "fresh-session")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"fresh-session\",\"target\":\"default\",\"targetSource\":\"daemon-wide\"}"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"setSessionTargetForBoundDevice\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Set session target to Team (team) for android/emulator-5556."}],"isError":false}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp")
          client.use {
            it.initialize()
            val error = it.setSessionTargetForBoundDeviceAnnouncingChange("team")
            assertNull(error)
          }
        }
      }

      val stdout = out.toString(Charsets.UTF_8)
      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stdout.contains("Target app changed"),
        "first-time pin must NOT emit any transition message on stdout. " +
          "Got stdout: <<<$stdout>>>",
      )
      assertFalse(
        stderr.contains("Target app changed"),
        "first-time pin (daemon-wide → session-override) is not a target *change* — " +
          "the helper should stay silent and let the daemon-side bind take effect quietly. " +
          "Got stderr: <<<$stderr>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `re-pinning the same value stays silent`() {
    // Defensive: the announcing helper is opt-in (DeviceConnectCommand calls
    // it, cliReusableWithDevice doesn't), but pin it anyway so a future caller
    // that re-applies the env-tier target on every action command doesn't
    // accidentally spam the user with a no-op "Target app changed (X -> X)" line.
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "warm-session")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"warm-session\",\"target\":\"team\",\"targetSource\":\"session-override\"}"}],"isError":false}}"""
        "\"tools/call\"" in body && "\"setSessionTargetForBoundDevice\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Set session target to Team (team) for android/emulator-5556."}],"isError":false}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp")
          client.use {
            it.initialize()
            val error = it.setSessionTargetForBoundDeviceAnnouncingChange("team")
            assertNull(error)
          }
        }
      }

      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stderr.contains("Target app changed"),
        "no-op re-pin (prior session-override == new target) must stay silent. " +
          "Got stderr: <<<$stderr>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `announcing helper propagates daemon set error without emitting`() {
    // If the daemon's setSessionTargetForBoundDevice tool returns isError=true
    // (unknown target id, no device bound, etc), the announcing helper must
    // propagate the error string AND stay silent on stderr. Without this pin,
    // a future refactor that emits BEFORE checking the set error would lie to
    // the user ("Target app changed (default -> bogus) -- pinned …") while
    // the underlying set actually failed.
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "warm-session")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body && "\"session\"" in body && "\"INFO\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"sessionId\":\"warm-session\",\"target\":\"default\",\"targetSource\":\"session-override\"}"}],"isError":false}}"""
        // Daemon refuses the swap — typical shape when the target id isn't a
        // known one. The MCP framework converts the exception thrown by the
        // server-side tool into a tool-call response with isError=true.
        "\"tools/call\"" in body && "\"setSessionTargetForBoundDevice\"" in body ->
          """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"'bogus' is not a known target id. Available: [default, team]"}],"isError":true}}"""
        else -> error("Unexpected POST body: $body")
      }
      send(exchange, response)
    }
    server.start()

    val port = server.address.port
    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          val client = CliMcpClient(serverUrl = "http://localhost:$port/mcp")
          client.use {
            it.initialize()
            val error = it.setSessionTargetForBoundDeviceAnnouncingChange("bogus")
            assertNotNull(error, "set failure must surface as a non-null error string")
            assertTrue(
              "bogus" in error && "not a known target id" in error,
              "error string must carry the daemon's reason. Got: <<<$error>>>",
            )
          }
        }
      }

      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stderr.contains("Target app changed"),
        "announcement must NOT fire when the underlying set failed — the helper " +
          "would otherwise mislead the user that a change took effect. Got stderr: <<<$stderr>>>",
      )
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `daemon-rejects-session transition message routes to stderr, not stdout`() {
    // Mirrors the "creates a new session when daemon does not recognize the persisted id"
    // test above but checks where the human-readable status line lands. Same eval-safety
    // contract as the target-change variant.
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "recovered-session")
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        "\"notifications/initialized\"" in body -> send(exchange, "{}")
        "\"tools/call\"" in body && "\"device\"" in body && "\"INFO\"" in body ->
          send(exchange, """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"Unknown session id"}],"isError":true}}""")
        else -> error("Unexpected POST body: $body")
      }
    }
    server.start()

    val port = server.address.port
    val sessionFile = CliMcpClient.sessionFile(port).also { createdFiles += it }
    sessionFile.writeText("stale-session\nsampleapp")

    CliOutCapture.install()
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    try {
      CliOutCapture.withCapture(out, err) {
        runBlocking {
          CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp").use { /* noop */ }
        }
      }

      val stdout = out.toString(Charsets.UTF_8)
      val stderr = err.toString(Charsets.UTF_8)
      assertFalse(
        stdout.contains("Daemon doesn't recognize"),
        "daemon-restart transition must NOT appear on stdout. Got stdout: <<<$stdout>>>",
      )
      assertTrue(
        stderr.contains("Daemon doesn't recognize"),
        "daemon-restart transition must appear on stderr. Got stderr: <<<$stderr>>>",
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
