package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Wire-level contract for a `session start --bind` session's life after it starts.
 *
 * A bound session lives in the per-device MCP session scope, because that is where the daemon
 * keeps the named roster. Everything here is about that scope staying reachable:
 *
 *  - a bind that fails part-way releases the devices it already claimed,
 *  - the started session answers to the device spelling a follow-up command will actually pass,
 *  - `session stop` acts through the scope that holds the roster, not the unscoped session.
 *
 * Driven against a stub MCP daemon rather than mocks, so the assertions are on the requests the
 * CLI really emits and the session ids it really reattaches to.
 */
class SessionBindLifecycleTest {

  private val createdFiles = mutableListOf<File>()
  private val createdDirs = mutableListOf<File>()
  private val servers = mutableListOf<HttpServer>()

  @AfterTest
  fun cleanup() {
    createdDirs.forEach { it.deleteRecursively() }
    createdFiles.forEach { it.delete() }
    servers.forEach { it.stop(0) }
  }

  // ---------------------------------------------------------------------------
  // Partial bind failure unwinds the claims it already took
  // ---------------------------------------------------------------------------

  @Test
  fun `a bind that fails part-way unbinds the companions it already bound`() {
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      when {
        "\"BIND\"" in body && "kitchen" in body ->
          toolText("Error: Device 'emulator-5558' not found. Use LIST to see available devices.")
        else -> toolText("Bound.")
      }
    }
    sessionFileFor(port, scope = null).delete()

    val outcome = runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        bindSessionCast(
          client,
          listOf(
            "seller" to "emulator-5554",
            "buyer" to "emulator-5556",
            "kitchen" to "emulator-5558",
          ),
        )
      }
    }

    val failure = assertIs<BindCastResult.Failed>(outcome)
    assertTrue("emulator-5558" in failure.message, failure.message)

    // The companion bound before the failure is released — its claim would otherwise be held by
    // an MCP session scope that never opened a session.
    assertEquals(
      listOf("buyer"),
      recorded.filter { "\"UNBIND\"" in it }.map { unboundName(it) },
      "only the companions bound after the start device may be unbound, most recent first",
    )
    // The start device is kept on purpose (the daemon refuses to unbind a session's last named
    // device), so the failure has to say so and name the way out.
    assertTrue("emulator-5554" in failure.message, failure.message)
    assertTrue("device disconnect" in failure.message, failure.message)
  }

  @Test
  fun `a failure on the very first bind releases nothing`() {
    // Nothing was claimed yet, so an unwind here would be an UNBIND against an empty roster —
    // an error the user can do nothing with, reported after the one that matters.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { toolText("Error: Device 'emulator-5554' not found.") }
    sessionFileFor(port, scope = null).delete()

    val outcome = runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        bindSessionCast(client, listOf("seller" to "emulator-5554", "buyer" to "emulator-5556"))
      }
    }

    assertIs<BindCastResult.Failed>(outcome)
    assertEquals(
      emptyList<String>(),
      recorded.filter { "\"UNBIND\"" in it },
      "no bind succeeded, so there is nothing to release",
    )
  }

  // ---------------------------------------------------------------------------
  // The started session answers to the spelling follow-ups will pass
  // ---------------------------------------------------------------------------

  @Test
  fun `a session started under a bare DEVICE_ID is reachable under the fully-qualified one`() {
    // `--bind seller=emulator-5554` scopes on `cli-emulator-5554`, but a follow-up `step`
    // resolving through this terminal's `device connect` pin passes `android/emulator-5554`.
    // Both spellings must reattach to the session that holds the roster.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText("Connected device: android/emulator-5554") }
    val started = cliDeviceSessionScope("emulator-5554")
    val followUp = cliDeviceSessionScope("android/emulator-5554")
    sessionFileFor(port, started).writeText("bound-session\nsampleapp")
    sessionFileFor(port, followUp).delete()

    CliMcpClient.publishSessionFileAlias(port, fromSessionScope = started, toSessionScope = followUp)

    val reattachedId = runBlocking {
      CliMcpClient.connectReusable(port = port, targetAppId = "sampleapp", sessionScope = followUp)
        .use { it.sessionId }
    }
    assertEquals(
      "bound-session",
      reattachedId,
      "the fully-qualified spelling must reattach to the session the bind started",
    )
    assertEquals(
      "bound-session",
      sessionFileFor(port, started).readLines().first(),
      "publishing an alias must not move the original pointer — the as-typed spelling still works",
    )
  }

  // ---------------------------------------------------------------------------
  // Lifecycle commands act through the scope that holds the roster
  // ---------------------------------------------------------------------------

  @Test
  fun `session lifecycle acts through the device scope when it holds a bound roster`() {
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      if (mcpSessionOf(body) == SCOPED_SESSION) toolText(ROSTER_INFO) else toolText(PLAIN_INFO)
    }
    val scope = cliDeviceSessionScope("emulator-5554")
    sessionFileFor(port, scope).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertEquals(scope, opened.sessionScope)
      assertEquals(
        SCOPED_SESSION,
        it.sessionId,
        "a bound session's stop must reach the MCP session that owns its capture and claims",
      )
    }
  }

  @Test
  fun `session lifecycle stays on the unscoped session when the device scope holds no roster`() {
    // A scope created by `step` has a session with no named bindings. A plain `session start`
    // opened the unscoped session and kept its capture callback there, so stopping through the
    // step scope would leave that recording unfinalized.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertNull(opened.sessionScope, "no roster means today's unscoped lifecycle, unchanged")
      assertEquals(UNSCOPED_SESSION, it.sessionId)
    }
  }

  @Test
  fun `session lifecycle stays on the unscoped session when the device never had a scope`() {
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("emulator-5554")).delete()
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertNull(opened.sessionScope)
      assertEquals(UNSCOPED_SESSION, it.sessionId)
    }
  }

  @Test
  fun `reading the roster costs no round trip beyond the connect probe`() {
    // `connectReusable` already fetches this exact INFO block to verify the session it
    // reattaches to. Asking again would put a second round trip on every stop.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { toolText(ROSTER_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertEquals(
        listOf("android/emulator-5554", "android/emulator-5556"),
        opened.rosterDevices,
        "the cast must be read off the probe connectReusable already made",
      )
    }
    assertEquals(1, recorded.size, "one device INFO, not two: $recorded")
  }

  // ---------------------------------------------------------------------------
  // `session save` needs to know a session is multi-device BEFORE delegating
  // ---------------------------------------------------------------------------

  @Test
  fun `a bound session reports that it holds a named roster`() {
    // `session save`'s capability gate keys on this: a daemon that predates roster-based save would
    // write a cast-less trail and report a pass, and the configuration name DEFAULTS, so there is
    // no flag to gate on instead.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      if (mcpSessionOf(body) == SCOPED_SESSION) toolText(ROSTER_INFO) else toolText(PLAIN_INFO)
    }
    sessionFileFor(port, cliDeviceSessionScope("emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertTrue(opened.holdsNamedRoster(), "a bound session's save must be recognized as multi-device")
    }
  }

  @Test
  fun `a single-device session reports no named roster`() {
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("emulator-5554")).delete()
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { openSessionLifecycleClient(port, "emulator-5554") }
    opened.client.use {
      assertEquals(
        false,
        opened.holdsNamedRoster(),
        "an ordinary save must not be gated, and must not pay for a capability read",
      )
    }
  }

  @Test
  fun `a roster bound on the UNSCOPED session is still recognized`() {
    // `session start --bind` always scopes, but a raw MCP `device(action=BIND)` can leave a roster
    // on the unscoped session — where rosterDevices was never populated, so the probe answers.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(ROSTER_INFO) }
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val opened = runBlocking { SessionLifecycleClient(CliMcpClient.connectReusable(port), null) }
    opened.client.use {
      assertTrue(
        opened.holdsNamedRoster(),
        "an unscoped save must fall back to the probe rather than assume single-device",
      )
    }
  }

  @Test
  fun `a scope whose session the daemon forgot is not answered with a fresh one`() {
    // Inspecting a scope must not create one: a minted session is an orphan on the daemon, and
    // persisting it overwrites the scope's pointer and its stored target app.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText("Error: Unknown session", isError = true) }
    val scope = cliDeviceSessionScope("emulator-5554")
    val scopeFile = sessionFileFor(port, scope).also { it.writeText("$SCOPED_SESSION\nsampleapp") }

    assertFailsWith<CliMcpClient.CliMcpException> {
      runBlocking { CliMcpClient.connectReusable(port, sessionScope = scope, createIfMissing = false).close() }
    }
    assertEquals(
      "$SCOPED_SESSION\nsampleapp",
      scopeFile.readText(),
      "a read-only probe must leave the scope's pointer and target app untouched",
    )
  }

  // ---------------------------------------------------------------------------
  // A handover must not make the documented stop path refuse
  // ---------------------------------------------------------------------------

  @Test
  fun `stop reaches a bound session through any member of its cast`() {
    // After `switchDevice`, the daemon reports the COMPANION as the session's current device.
    // The documented lifecycle spelling is the START device, so the ACTIVE member cannot be what
    // decides whether this session is the one the user named.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      when {
        "\"sessionOnly\"" in body -> toolText(HANDED_OVER_ROSTER_INFO)
        "\"STOP\"" in body -> toolText("""{"sessionId":"$SCOPED_SESSION","status":"stopped","message":"Session stopped."}""")
        else -> toolText(COMPANION_IS_CURRENT)
      }
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5554") }
    lifecycle.client.use { client ->
      val stopped = runBlocking {
        stopBoundSessionIfMatches(
          client = client,
          expectedDevice = "android/emulator-5554",
          sessionDevices = lifecycle.rosterDevices,
        )
      }
      assertIs<StopBoundSessionResult.Stopped>(stopped, "the start device still owns this session")
      assertTrue(recorded.any { "\"STOP\"" in it }, "a STOP must actually be issued: $recorded")

      // Same daemon state, no cast: the strict check `device disconnect` relies on is intact.
      val refused = runBlocking {
        stopBoundSessionIfMatches(
          client = client,
          expectedDevice = "android/emulator-5554",
          sessionDevices = emptyList(),
        )
      }
      assertIs<StopBoundSessionResult.DeviceMismatch>(refused)
    }
  }

  @Test
  fun `a handed-over session is not replaced when a step addresses it by a cast member`() {
    // The documented interactive flow addresses every follow-up with `-d <startDevice>`. After
    // `switchDevice`, the daemon reports the COMPANION as the session's current device, so the
    // start device no longer matches it — and a replacing CONNECT from that mismatch tears the
    // whole roster down. Any cast member must reuse the session; the active device stays where
    // the handover put it.
    // On the wire the replacing connect is `device(action=ANDROID, deviceId=…)` — a platform
    // action carrying the instance, not a literal CONNECT — so that is what both assertions key
    // on: its absence for a cast member, its presence for the outsider control.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      when {
        "\"INFO\"" in body -> toolText(HANDED_OVER_SESSION_INFO)
        "\"deviceId\"" in body -> toolText("Connected to emulator-5599 (Android)")
        else -> toolText("")
      }
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")

    val client = runBlocking {
      CliMcpClient.connectReusable(
        port,
        sessionScope = cliDeviceSessionScope("android/emulator-5554"),
        createIfMissing = false,
      )
    }
    client.use {
      val error = runBlocking { it.ensureDevice("android/emulator-5554") }
      assertNull(error, "a cast member addressing its own session is not an error")
      assertEquals(SCOPED_SESSION, it.sessionId, "the roster-holding session must survive the step")
      assertTrue(
        recorded.none { body -> "\"deviceId\"" in body },
        "a replacing connect drops the daemon-side roster: $recorded",
      )

      // Control on the same session: a device in NO roster entry still takes the replace path.
      // Without it, this test cannot tell a working roster check from ensureDevice reusing
      // every session unconditionally.
      runBlocking { it.ensureDevice("android/emulator-5599") }
      assertTrue(
        recorded.any { body -> "\"deviceId\":\"emulator-5599\"" in body },
        "a device outside the cast must still switch the connection: $recorded",
      )
    }
  }

  @Test
  fun `a device released from the cast after connect is not matched against the stale probe`() {
    // `session start --bind` can release a name the reused MCP session held from an earlier,
    // larger cast — AFTER connectReusable's probe already captured that roster as text. Matching
    // against the cached text would reuse the session and silently land the command on the
    // ACTIVE device. ensureDevice must gate on the LIVE cast, so the released device takes the
    // replace path.
    val infoCalls = AtomicInteger(0)
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      when {
        "\"INFO\"" in body ->
          if (infoCalls.incrementAndGet() == 1) toolText(STALE_CAST_CONNECT_INFO) else toolText(LIVE_TRIMMED_CAST_INFO)
        "\"deviceId\"" in body -> toolText("Connected to emulator-5558 (Android)")
        else -> toolText("")
      }
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")

    val client = runBlocking {
      CliMcpClient.connectReusable(
        port,
        sessionScope = cliDeviceSessionScope("android/emulator-5554"),
        createIfMissing = false,
      )
    }
    client.use {
      runBlocking { it.ensureDevice("android/emulator-5558") }
      assertTrue(
        recorded.any { body -> "\"deviceId\":\"emulator-5558\"" in body },
        "a device the live cast no longer names must replace the connection, " +
          "not match the connect-time probe: $recorded",
      )
    }
  }

  @Test
  fun `a cast-member reuse does not rebind when the active device is not Android`() {
    // The non-Android reuse path ordinarily refreshes the runtime selection with a
    // device(action=<PLATFORM>, deviceId=…) call — which the daemon handles as a REPLACING
    // connect and answers by clearing every named binding. For a roster match the reuse must
    // therefore be a wire no-op on EVERY platform, not just Android's shortcut.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      if ("\"INFO\"" in body) toolText(HANDED_OVER_WEB_ACTIVE_INFO) else toolText("")
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554")).writeText("$SCOPED_SESSION\nsampleapp")

    val client = runBlocking {
      CliMcpClient.connectReusable(
        port,
        sessionScope = cliDeviceSessionScope("android/emulator-5554"),
        createIfMissing = false,
      )
    }
    client.use {
      val error = runBlocking { it.ensureDevice("android/emulator-5554") }
      assertNull(error, "a cast member addressing its own session is not an error")
      assertTrue(
        recorded.none { body -> "\"deviceId\"" in body },
        "a platform rebind is a replacing connect and drops the roster: $recorded",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // A cast whose session never opened is released
  // ---------------------------------------------------------------------------

  @Test
  fun `a bind failure names the start device even when the FIRST bind is what failed`() {
    // Nothing is bound yet, but `ensureDevice` has already connected and claimed the start
    // device — so this is the case where the recovery line matters most.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText("Error: Device 'emulator-5554' is busy.") }
    sessionFileFor(port, scope = null).delete()

    val outcome = runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        bindSessionCast(client, listOf("seller" to "emulator-5554", "buyer" to "emulator-5556"))
      }
    }
    val failure = assertIs<BindCastResult.Failed>(outcome)
    assertTrue("device disconnect -d emulator-5554" in failure.message, failure.message)
  }

  @Test
  fun `an UNBIND that the daemon refuses is reported as still bound`() {
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      when {
        "\"UNBIND\"" in body -> toolText("Error: 'buyer' is not bound in this session.")
        "kitchen" in body -> toolText("Error: Device 'emulator-5558' not found.")
        else -> toolText("Bound.")
      }
    }
    sessionFileFor(port, scope = null).delete()

    val outcome = runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        bindSessionCast(
          client,
          listOf("seller" to "emulator-5554", "buyer" to "emulator-5556", "kitchen" to "emulator-5558"),
        )
      }
    }
    val failure = assertIs<BindCastResult.Failed>(outcome)
    assertTrue("Could not release 'buyer'" in failure.message, failure.message)
    assertTrue("Released" !in failure.message, "nothing was released, so nothing may claim to be: $failure")
  }

  @Test
  fun `a session START that fails after every bind succeeded releases the companions`() {
    // The claims a failed BIND unwinds are exactly the claims a failed START strands.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      if ("\"START\"" in body) toolText("Error: capture could not start", isError = true) else toolText("Bound.")
    }
    sessionFileFor(port, scope = null).delete()

    val report = runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        val cast = listOf("seller" to "emulator-5554", "buyer" to "emulator-5556")
        assertIs<BindCastResult.Bound>(bindSessionCast(client, cast))
        client.callTool("session", mapOf("action" to "START"))
        unwindCastReport(client = client, cast = cast, bound = cast, cause = "Error: capture could not start")
      }
    }
    assertTrue("Released 'buyer'" in report, report)
    assertEquals(listOf("buyer"), recorded.filter { "\"UNBIND\"" in it }.map { unboundName(it) })
  }

  @Test
  fun `a qualified bind DEVICE_ID is sent to the daemon as its bare instance id`() {
    // BIND looks the device up by instanceId, so a `platform/`-qualified value would never match.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { toolText("Bound.") }
    sessionFileFor(port, scope = null).delete()

    runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        assertIs<BindCastResult.Bound>(bindSessionCast(client, listOf("seller" to "android/emulator-5554")))
      }
    }
    val bind = recorded.first { "\"BIND\"" in it }
    assertTrue("\"deviceId\":\"emulator-5554\"" in bind, bind)
  }

  @Test
  fun `a name left over from an earlier cast is released`() {
    // `--bind` promises the cast is exactly the bound devices, and a reused MCP session keeps the
    // roster it already had — neither ensureDevice nor session START clears it.
    val recorded = Collections.synchronizedList(mutableListOf<String>())
    val port = stubDaemon(recorded) { body ->
      if ("\"sessionOnly\"" in body) toolText(STALE_ROSTER_INFO) else toolText("Bound.")
    }
    sessionFileFor(port, scope = null).delete()

    runBlocking {
      CliMcpClient.connectReusable(port = port).use { client ->
        assertIs<BindCastResult.Bound>(bindSessionCast(client, listOf("seller" to "emulator-5554")))
      }
    }
    assertEquals(
      listOf("kitchen"),
      recorded.filter { "\"UNBIND\"" in it }.map { unboundName(it) },
      "only the name this cast does not name may be released",
    )
  }

  // ---------------------------------------------------------------------------
  // Session pointers
  // ---------------------------------------------------------------------------

  @Test
  fun `an alias onto a scope with no session reports failure instead of writing a pointer`() {
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText("") }
    val from = cliDeviceSessionScope("emulator-5554")
    val to = cliDeviceSessionScope("android/emulator-5554")
    sessionFileFor(port, from).delete()
    val target = sessionFileFor(port, to).also { it.delete() }

    assertEquals(false, CliMcpClient.publishSessionFileAlias(port, from, to))
    assertEquals(false, target.exists(), "no source session means no alias to publish")
  }

  @Test
  fun `an alias that cannot be written is reported, not announced`() {
    // Writing a session pointer is best-effort by design, so the caller telling a user which -d
    // value reattaches has to read the pointer back rather than assume the write landed.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText("") }
    val from = cliDeviceSessionScope("emulator-5554")
    val to = cliDeviceSessionScope("android/emulator-5554")
    sessionFileFor(port, from).writeText("$SCOPED_SESSION\nsampleapp")
    // A directory at the pointer's path makes writeText fail the way a permissions problem would.
    val target = sessionFileFor(port, to).also { it.delete() }
    createdDirs += target.also { it.mkdirs() }

    assertEquals(false, CliMcpClient.publishSessionFileAlias(port, from, to))
  }

  @Test
  fun `ending a bound session drops every pointer that named it`() {
    // A bound session is reachable through its start device's other spelling and through each
    // companion's own scope, so clearing only the scope the stop went through leaves the rest
    // pointing at a session that no longer exists.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(ROSTER_INFO) }
    val startScope = cliDeviceSessionScope("android/emulator-5554")
    val companionScope = cliDeviceSessionScope("android/emulator-5556")
    val startFile = sessionFileFor(port, startScope).also { it.writeText("$SCOPED_SESSION\nsampleapp") }
    val companionFile = sessionFileFor(port, companionScope).also { it.writeText("$SCOPED_SESSION\nsampleapp") }
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5554") }
    lifecycle.client.use { clearSessionPointersFor(port, lifecycle) }

    assertEquals(false, startFile.exists(), "the scope the stop acted through is cleared")
    assertEquals(false, companionFile.exists(), "a companion pointer naming the ended session is cleared")
  }

  @Test
  fun `a companion pointer another terminal has repointed survives the stop`() {
    // Deleting it blindly would strand a live session belonging to a different shell.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(ROSTER_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("$SCOPED_SESSION\nsampleapp")
    val other = sessionFileFor(port, cliDeviceSessionScope("android/emulator-5556"))
      .also { it.writeText("other-terminal-session\nsampleapp") }
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5554") }
    assertEquals(SCOPED_SESSION, lifecycle.client.sessionId, "guard: the stop must act on the bound session")
    lifecycle.client.use { clearSessionPointersFor(port, lifecycle) }

    assertEquals("other-terminal-session\nsampleapp", other.readText())
  }

  // ---------------------------------------------------------------------------
  // `session info -d` reads the scope that holds a roster, without letting a dead
  // pointer hide a live plain session
  // ---------------------------------------------------------------------------

  @Test
  fun `a stale scoped pointer does not hide the live unscoped session`() {
    // A scope pointer outlives its session whenever the daemon restarts. Reporting "no active
    // session" then would deny a live plain `session start -d <device>` session sitting right
    // there in the unscoped scope.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      if (mcpSessionOf(body) == SCOPED_SESSION) {
        toolText("Error: Unknown session id.", isError = true)
      } else {
        toolText(ROSTER_INFO)
      }
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val sessionId = runBlocking {
      openSessionInfoClient(port, "android/emulator-5554")?.use { it.sessionId }
    }

    assertEquals(UNSCOPED_SESSION, sessionId)
  }

  @Test
  fun `a live scoped pointer is read instead of the unscoped session`() {
    // The fallback must fire only on failure: reading the unscoped session while the device's own
    // scope is alive would print a roster-less block for a session that has one.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(ROSTER_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val sessionId = runBlocking {
      openSessionInfoClient(port, "android/emulator-5554")?.use { it.sessionId }
    }

    assertEquals(SCOPED_SESSION, sessionId)
  }

  @Test
  fun `a rejected unscoped session is not reported as the live one`() {
    // No scope was asked for, so there is nothing to fall back FROM. A read with no scope still
    // creates on miss — matching `session info`'s pre-`--device` behavior — so what is pinned here
    // is that the DEAD saved id is not handed back as if it were alive.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) {
      toolText("Error: Unknown session id.", isError = true)
    }
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val sessionId = runBlocking { openSessionInfoClient(port, deviceArg = null)?.use { it.sessionId } }

    assertTrue(sessionId != UNSCOPED_SESSION, "a rejected session id must not be reported as live")
  }

  // ---------------------------------------------------------------------------
  // A companion has no scope of its own, but still belongs to the cast
  // ---------------------------------------------------------------------------

  @Test
  fun `stopping through a companion reaches the session whose roster holds it`() {
    // Only the start device's scope points at the roster-owning session, so a companion misses the
    // direct lookup. Falling through to the unscoped session is not harmless: `getBoundDeviceId()`
    // reads the PROCESS-WIDE device, which during a live cast is a cast member, so the ownership
    // check can pass and STOP goes out through a context with no capture callback — success
    // reported, capture never finalized.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      if (mcpSessionOf(body) == SCOPED_SESSION) toolText(ROSTER_INFO) else toolText(PLAIN_INFO)
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")
    // Guard: the companion really has no scope of its own.
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5556")).delete()

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5556") }

    lifecycle.client.use {
      assertEquals(SCOPED_SESSION, it.sessionId, "the stop must act through the roster-owning session")
    }
    assertTrue(
      lifecycle.rosterDevices.any { sameBoundDevice(it, "android/emulator-5556") },
      "the roster must come along so the ownership check passes: ${lifecycle.rosterDevices}",
    )
  }

  @Test
  fun `a device in no roster still falls back to the unscoped session`() {
    // Control on the search: a device that belongs to no cast must not be adopted by whichever
    // bound session happens to have a pointer, or a single-device stop would act on someone
    // else's cast.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      if (mcpSessionOf(body) == SCOPED_SESSION) toolText(ROSTER_INFO) else toolText(PLAIN_INFO)
    }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-9999") }

    lifecycle.client.use { assertEquals(UNSCOPED_SESSION, it.sessionId) }
    assertNull(lifecycle.sessionScope, "an unscoped fallback must report no scope to clear")
  }

  @Test
  fun `a candidate the daemon has forgotten does not end the search`() {
    // The scopes walked belong to other commands and other terminals, so the search meets scopes it
    // cannot use. One of those must not decide the answer for the device the user named — and
    // because scopes are walked in sorted order, the dead one here is reached FIRST.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { body ->
      when (mcpSessionOf(body)) {
        // The daemon was restarted out from under this pointer.
        "aaa-dead-session" -> toolText("Unknown session id", isError = true)
        SCOPED_SESSION -> toolText(ROSTER_INFO)
        else -> toolText(PLAIN_INFO)
      }
    }
    sessionFileFor(port, "cli-aaa-stale").writeText("aaa-dead-session\nsampleapp")
    sessionFileFor(port, "cli-zzz-live").writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5556") }

    lifecycle.client.use {
      assertEquals(
        SCOPED_SESSION,
        it.sessionId,
        "the stale scope is reached first and must be skipped, not answered with",
      )
    }
    assertEquals("cli-zzz-live", lifecycle.sessionScope)
  }

  @Test
  fun `a roster-less scope is not claimed as a companion's owner`() {
    // A scope created by `step` holds a session with no bindings. It has a pointer file, so the
    // search sees it — and must skip it rather than route a lifecycle command through it.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554"))
      .writeText("step-session\nsampleapp")
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val lifecycle = runBlocking { openSessionLifecycleClient(port, "android/emulator-5556") }

    lifecycle.client.use { assertEquals(UNSCOPED_SESSION, it.sessionId) }
  }

  // ---------------------------------------------------------------------------
  // Post-start reporting must never fail a start that already succeeded
  // ---------------------------------------------------------------------------

  @Test
  fun `a failed post-start probe does not fail the start it reports on`() {
    // `session(action=START)` has already succeeded by the time this runs, so a probe that times
    // out or finds a disconnected daemon must not throw out of the command — the user would be told
    // their session did not open when it did. `getBoundDeviceId`'s own error contract covers only
    // `isError` and unparseable content, both of which come back as a value; a transport failure
    // throws instead, so the daemon is taken away here after the session was already established.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    val startedScope = cliDeviceSessionScope("android/emulator-5554")
    sessionFileFor(port, startedScope).writeText("$SCOPED_SESSION\nsampleapp")
    sessionFileFor(port, null).delete()

    runBlocking {
      CliMcpClient.connectReusable(port, sessionScope = startedScope, createIfMissing = false).use { client ->
        stopDaemon(port)
        aliasAndPrintFollowUpDevice(
          client = client,
          port = port,
          startedScope = startedScope,
          startDeviceSpec = "android/emulator-5554",
        )
      }
    }

    // The started session is still reachable under the spelling the user passed.
    assertEquals(
      SCOPED_SESSION,
      CliMcpClient.sessionFile(port, startedScope).readLines().first(),
      "a failed probe must not disturb the pointer the start wrote",
    )
  }

  // ---------------------------------------------------------------------------
  // Inspecting a device must not leave a session behind
  // ---------------------------------------------------------------------------

  @Test
  fun `session info for a device with no session creates none`() {
    // An inspection that mints and persists an unscoped session has changed the thing it was asked
    // to describe, and leaves an orphan on the daemon.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    val unscopedPointer = sessionFileFor(port, null).also { it.delete() }
    sessionFileFor(port, cliDeviceSessionScope("android/emulator-5554")).delete()

    val client = runBlocking { openSessionInfoClient(port, "android/emulator-5554") }

    assertNull(client, "no pointer anywhere means no session to report")
    assertTrue(!unscopedPointer.exists(), "no unscoped session may be created just to answer -d")
  }

  // ---------------------------------------------------------------------------
  // A cancelled command is not a device without a session
  // ---------------------------------------------------------------------------

  @Test
  fun `a cancelled connect is not reported as a missing session`() {
    // The scope search and the info read both degrade an unreachable session to "none". On the JVM
    // CancellationException is a RuntimeException, so a bare `catch (Exception)` would swallow a
    // Ctrl-C and let the caller keep probing scopes — or answer "No active session." about a
    // session that is very much alive.
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) {
      Thread.sleep(30_000) // never answers within the test; the client is cancelled mid-call
      toolText(PLAIN_INFO)
    }
    val scope = cliDeviceSessionScope("android/emulator-5554")
    sessionFileFor(port, scope).writeText("$SCOPED_SESSION\nsampleapp")

    var outcome: Result<CliMcpClient?>? = null
    runBlocking {
      val probe = launch(Dispatchers.IO) {
        outcome = runCatching { connectReusableOrNull(port, sessionScope = scope, createIfMissing = false) }
      }
      // Long enough for the probe to be suspended inside the tool call.
      delay(1_000)
      probe.cancel()
      probe.join()
    }

    assertIs<CancellationException>(
      outcome?.exceptionOrNull(),
      "cancellation must reach the caller, not be answered as `no session`: $outcome",
    )
  }

  // ---------------------------------------------------------------------------
  // Which session a device resolves to must not vary between runs
  // ---------------------------------------------------------------------------

  @Test
  fun `the scopes a lifecycle search walks come back in a stable order`() {
    // `sessionOwningBoundDevice` stops at its first match, so an unordered listing would let one
    // `session stop` reach a different session than the next — a difference with no visible cause.
    // Asserted on the contract rather than only on its effect: `File.listFiles` specifies no order,
    // so a sort-free implementation still passes on a filesystem that happens to return them
    // sorted (it does not on APFS, where dropping the sort fails this).
    val port = stubDaemon(Collections.synchronizedList(mutableListOf())) { toolText(PLAIN_INFO) }
    val scopes = listOf("cli-zulu", "cli-alpha", "cli-mike").onEach {
      sessionFileFor(port, it).writeText("$SCOPED_SESSION\nsampleapp")
    }
    sessionFileFor(port, null).writeText("$UNSCOPED_SESSION\nsampleapp")

    val found = CliMcpClient.scopesWithSessionFiles(port)

    assertEquals(scopes.sorted(), found, "the unscoped pointer carries no suffix and is excluded")
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private fun sessionFileFor(port: Int, scope: String?): File =
    CliMcpClient.sessionFile(port, scope).also { createdFiles += it }

  /** Takes the daemon away mid-test, so the next call fails at the transport rather than in-band. */
  private fun stopDaemon(port: Int) {
    servers.first { it.address.port == port }.stop(0)
  }

  /**
   * Minimal MCP-protocol stub. Answers `initialize` with a fresh session id, records every
   * `tools/call` body, and delegates the tool response to [respond] so a test can vary it by
   * request shape or by the `mcp-session-id` the CLI reattached with.
   */
  private fun stubDaemon(
    recorded: MutableList<String>,
    respond: (String) -> String,
  ): Int {
    val nextSessionId = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress(0), 0)
    server.createContext("/mcp") { exchange ->
      val body = exchange.requestBody.bufferedReader().use { it.readText() }
      val withSession = "${exchange.requestHeaders.getFirst("mcp-session-id")}|$body"
      val response = when {
        "\"initialize\"" in body -> {
          exchange.responseHeaders.add("mcp-session-id", "fresh-session-${nextSessionId.incrementAndGet()}")
          """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""
        }
        "\"notifications/initialized\"" in body -> "{}"
        "\"tools/call\"" in body -> {
          recorded += withSession
          respond(withSession)
        }
        else -> toolText("")
      }
      val bytes = response.toByteArray()
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    servers += server
    return server.address.port
  }

  private fun toolText(text: String, isError: Boolean = false): String {
    val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    return """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"$escaped"}],"isError":$isError}}"""
  }

  private fun mcpSessionOf(recordedBody: String): String = recordedBody.substringBefore('|')

  /** The `name` argument of a recorded tool call — the last `"name"` key, inside `arguments`. */
  private fun unboundName(recordedBody: String): String =
    recordedBody.substringAfterLast("\"name\":\"").substringBefore('"')

  private companion object {
    const val SCOPED_SESSION = "scoped-session"
    const val UNSCOPED_SESSION = "unscoped-session"

    val ROSTER_INFO = """
      |Connected device: android/emulator-5554
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554 [ACTIVE]
      |  - buyer: android/emulator-5556
      |Hand the session over with switchDevice(name="…").
    """.trimMargin()

    const val PLAIN_INFO = "Connected device: android/emulator-5554"

    /** The roster after `switchDevice(name="buyer")` — the START device is no longer ACTIVE. */
    val HANDED_OVER_ROSTER_INFO = """
      |Named devices in this session:
      |  - seller: android/emulator-5554
      |  - buyer: android/emulator-5556 [ACTIVE]
    """.trimMargin()

    /** What `getBoundDeviceId` reads after a handover: the companion is the current device. */
    const val COMPANION_IS_CURRENT = "Platform: Android\nInstance ID: emulator-5556"

    /**
     * The full `device(INFO)` block after a handover: the COMPANION is the connected device
     * (what `connectReusable` parses into `existingDeviceId`) and the roster still names the
     * start device. This is the state `ensureDevice(-d <startDevice>)` walks into.
     */
    val HANDED_OVER_SESSION_INFO = """
      |Platform: Android
      |Instance ID: emulator-5556
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554
      |  - buyer: android/emulator-5556 [ACTIVE]
    """.trimMargin()

    /** A reused MCP session still holding a name from a previous, larger cast. */
    val STALE_ROSTER_INFO = """
      |Named devices in this session:
      |  - seller: android/emulator-5554 [ACTIVE]
      |  - kitchen: android/emulator-5558
    """.trimMargin()

    /**
     * Connect-time probe for a session whose roster still names a device an earlier cast held —
     * the text `reusedSessionProbeContent` caches. The kitchen entry is released right after.
     */
    val STALE_CAST_CONNECT_INFO = """
      |Platform: Android
      |Instance ID: emulator-5554
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554 [ACTIVE]
      |  - kitchen: android/emulator-5558
    """.trimMargin()

    /** The same session's live roster after the stale name was released. */
    val LIVE_TRIMMED_CAST_INFO = """
      |Platform: Android
      |Instance ID: emulator-5554
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554 [ACTIVE]
    """.trimMargin()

    /**
     * A handed-over heterogeneous cast whose ACTIVE device is a web browser — the state where a
     * reuse routed through the ordinary non-Android path would issue a platform rebind.
     */
    val HANDED_OVER_WEB_ACTIVE_INFO = """
      |Platform: Web Browser
      |Instance ID: playwright-native
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554
      |  - screen: web/playwright-native [ACTIVE]
    """.trimMargin()
  }
}
