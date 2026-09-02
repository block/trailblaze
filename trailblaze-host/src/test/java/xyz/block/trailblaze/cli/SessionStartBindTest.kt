package xyz.block.trailblaze.cli

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import org.junit.Test
import picocli.CommandLine

/**
 * Behavioral contract of `trailblaze session start --bind NAME=DEVICE_ID`.
 *
 * The cast of a bound session is exactly the `--bind` entries, ordered: the FIRST bind is the
 * start device (mirroring a trail's `config.devices:` ordered map and
 * `SessionDeviceBindings`' start-first semantics), unless `--device` names one bind's
 * DEVICE_ID — then that entry starts. A `--device` matching no bind is MISUSE: the flags
 * contradict each other, and honoring either one silently would drive a device the user
 * didn't ask for. There is deliberately no `--as` flag.
 *
 * The no-binds path is pinned too: `session start` without `--bind` must parse exactly as
 * before, leaving the single-device flow untouched.
 */
class SessionStartBindTest {

  // ---------------------------------------------------------------------------
  // picocli parsing: ordering, repeatable + comma forms, no-binds default
  // ---------------------------------------------------------------------------

  @Test
  fun `repeated --bind preserves declaration order`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs(
      "--bind", "seller=emulator-5554",
      "--bind", "buyer=emulator-5556",
      "--bind", "kitchen=emulator-5558",
    )
    assertEquals(
      listOf("seller=emulator-5554", "buyer=emulator-5556", "kitchen=emulator-5558"),
      cmd.deviceBinds,
    )
  }

  @Test
  fun `comma-separated --bind splits like the run command's flag`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--bind", "seller=emulator-5554,buyer=emulator-5556")
    assertEquals(listOf("seller=emulator-5554", "buyer=emulator-5556"), cmd.deviceBinds)
  }

  @Test
  fun `session start without --bind parses with an empty binds list`() {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--device", "android")
    assertEquals(emptyList(), cmd.deviceBinds)
  }

  // ---------------------------------------------------------------------------
  // Start-device resolution
  // ---------------------------------------------------------------------------

  private val binds = linkedMapOf(
    "seller" to "emulator-5554",
    "buyer" to "emulator-5556",
  )

  @Test
  fun `without --device the first bind is the start device`() {
    val resolved = assertIs<SessionBindStartResolution.Resolved>(
      resolveSessionStartBinds(binds, deviceArg = null),
    )
    assertEquals(
      listOf("seller" to "emulator-5554", "buyer" to "emulator-5556"),
      resolved.orderedBinds,
    )
    assertEquals("emulator-5554", resolved.startDeviceSpec)
  }

  @Test
  fun `--device naming a later bind makes that entry the start device`() {
    val resolved = assertIs<SessionBindStartResolution.Resolved>(
      resolveSessionStartBinds(binds, deviceArg = "emulator-5556"),
    )
    assertEquals(
      listOf("buyer" to "emulator-5556", "seller" to "emulator-5554"),
      resolved.orderedBinds,
    )
    assertEquals("emulator-5556", resolved.startDeviceSpec)
  }

  @Test
  fun `a fully-qualified --device matches a bind's bare DEVICE_ID and keeps its spelling`() {
    val resolved = assertIs<SessionBindStartResolution.Resolved>(
      resolveSessionStartBinds(binds, deviceArg = "android/emulator-5556"),
    )
    assertEquals("buyer" to "emulator-5556", resolved.orderedBinds.first())
    // The as-typed spec is what ensureDevice connects and what keys the per-device
    // session scope, so a qualified arg must survive resolution unmodified.
    assertEquals("android/emulator-5556", resolved.startDeviceSpec)
  }

  @Test
  fun `--device matching no bind is refused and names the cast`() {
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(binds, deviceArg = "emulator-9999"),
    )
    assertTrue("emulator-9999" in misuse.message, misuse.message)
    assertTrue("seller=emulator-5554" in misuse.message, misuse.message)
    assertTrue("buyer=emulator-5556" in misuse.message, misuse.message)
  }

  @Test
  fun `a platform-only --device is not a bound DEVICE_ID`() {
    // `-d android` names a platform, not a serial — with --bind the start device must be
    // one of the bound DEVICE_IDs, so this is the same contradiction as an unknown serial.
    assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(binds, deviceArg = "android"),
    )
  }

  // ---------------------------------------------------------------------------
  // One device, one name
  // ---------------------------------------------------------------------------

  @Test
  fun `two names on the same device are refused and both roles are named`() {
    // The daemon reads the second bind as RENAMING the first and reports success, so without
    // this refusal the CLI prints two bound names over a roster holding one device — and the
    // name that disappears is the start device's.
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(
        linkedMapOf("seller" to "emulator-5554", "buyer" to "emulator-5554"),
        deviceArg = null,
      ),
    )
    assertTrue("seller" in misuse.message, misuse.message)
    assertTrue("buyer" in misuse.message, misuse.message)
    assertTrue("emulator-5554" in misuse.message, misuse.message)
  }

  @Test
  fun `the same device spelled two ways is still the same device`() {
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(
        linkedMapOf("seller" to "emulator-5554", "buyer" to "android/emulator-5554"),
        deviceArg = null,
      ),
    )
    assertTrue("android/emulator-5554" in misuse.message, misuse.message)
  }

  @Test
  fun `one instance id on two platforms is refused because BIND carries no platform`() {
    // Two devices to the CLI, one device to the daemon: `bindSessionCast` strips the qualifier
    // because `bindNamedDevice` matches on `instanceId` alone, so the second BIND lands on the
    // first device and succeeds as a rename. Accepting the pair would report a two-name cast
    // over a one-device roster — the exact silent-wrong-roster this refusal exists to prevent.
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(
        linkedMapOf("phone" to "android/sim-1", "tablet" to "ios/sim-1"),
        deviceArg = null,
      ),
    )
    assertTrue("phone" in misuse.message, misuse.message)
    assertTrue("tablet" in misuse.message, misuse.message)
    // The message must say why the differing qualifier didn't separate them, or the refusal
    // reads as a bug to anyone who can see the two specs are not equal strings.
    assertTrue("BIND carries only the instance id" in misuse.message, misuse.message)
  }

  @Test
  fun `a differing platform qualifier is not called out when the specs already match`() {
    // The qualifier clause is specific to the collapse case. On two identical specs it would be
    // noise, and on a bare-vs-qualified pair it would be wrong — those ARE the same device.
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(
        linkedMapOf("seller" to "emulator-5554", "buyer" to "android/emulator-5554"),
        deviceArg = null,
      ),
    )
    assertTrue("BIND carries only the instance id" !in misuse.message, misuse.message)
  }

  @Test
  fun `--device still distinguishes two platforms sharing an instance id`() {
    // `sameBoundDevice` keeps platform in the identity for `--device` matching: an arg that
    // disagrees on platform names no bind. Widening the DUPLICATE check must not widen this one,
    // or `-d ios/sim-1` would silently start an Android device.
    assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(
        linkedMapOf("phone" to "android/sim-1"),
        deviceArg = "ios/sim-1",
      ),
    )
    assertIs<SessionBindStartResolution.Resolved>(
      resolveSessionStartBinds(
        linkedMapOf("phone" to "android/sim-1"),
        deviceArg = "android/sim-1",
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // call()-level MISUSE exits (fail fast, before any daemon or device work)
  // ---------------------------------------------------------------------------

  /** Runs [block] with no caller-shell env so a developer's TRAILBLAZE_DEVICE can't leak in. */
  private fun <T> withNoCallerEnv(block: () -> T): T =
    CliCallerContext.withCallerEnv(emptyMap(), block)

  @Test
  fun `a malformed --bind entry exits MISUSE`() = withNoCallerEnv {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--bind", "sellerWithoutValue")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `a repeated bind NAME exits MISUSE`() = withNoCallerEnv {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--bind", "seller=emulator-5554", "--bind", "seller=emulator-5556")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `--device matching no bind exits MISUSE`() = withNoCallerEnv {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs(
      "--bind", "seller=emulator-5554",
      "--device", "emulator-9999",
    )
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `a duplicate DEVICE_ID exits MISUSE`() = withNoCallerEnv {
    val cmd = SessionStartCommand()
    CommandLine(cmd).parseArgs("--bind", "seller=emulator-5554", "--bind", "buyer=emulator-5554")
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  // ---------------------------------------------------------------------------
  // session info roster extraction
  // ---------------------------------------------------------------------------

  @Test
  fun `roster lines are extracted from a device INFO block`() {
    val content = """
      |Connected device: android/emulator-5554
      |Driver: androidAccessibility
      |
      |Named devices in this session:
      |  - seller: android/emulator-5554 (target: pos) [ACTIVE]
      |  - buyer: android/emulator-5556
      |Hand the session over with switchDevice(name="…").
      |
      |Available tools: …
    """.trimMargin()
    assertEquals(
      listOf(
        "- seller: android/emulator-5554 (target: pos) [ACTIVE]",
        "- buyer: android/emulator-5556",
      ),
      extractNamedDeviceRoster(content),
    )
  }

  @Test
  fun `a single-device INFO block yields no roster`() {
    // The daemon omits the roster block entirely for a session with no named bindings, so
    // `session info -d` must add nothing to today's output on the single-device path.
    val content = """
      |Connected device: android/emulator-5554
      |Driver: androidAccessibility
    """.trimMargin()
    assertEquals(emptyList(), extractNamedDeviceRoster(content))
  }

  // ---------------------------------------------------------------------------
  // session info: which sessions get the live roster
  // ---------------------------------------------------------------------------

  private val rosterInfo = """
    |Connected device: android/emulator-5554
    |
    |Named devices in this session:
    |  - seller: android/emulator-5554 [ACTIVE]
    |  - buyer: android/emulator-5556
  """.trimMargin()

  @Test
  fun `the live roster is rendered for a device-scoped inspection of the current session`() {
    val roster = assertIs<LiveDeviceRoster.Bound>(
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = null) {
          CliMcpClient.ToolResult(rosterInfo)
        }
      },
    )
    assertEquals(
      listOf("- seller: android/emulator-5554 [ACTIVE]", "- buyer: android/emulator-5556"),
      roster.lines,
    )
  }

  @Test
  fun `an explicit session id suppresses the live roster`() {
    // The metadata above it is read from the logs and can be any past session, while the roster
    // is whatever is bound right now — printing them together attributes a live cast to a
    // session that may never have had one.
    assertEquals(
      LiveDeviceRoster.NotRequested,
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = "abc123") {
          CliMcpClient.ToolResult(rosterInfo)
        }
      },
    )
  }

  @Test
  fun `a failed roster lookup is reported rather than read as a single-device session`() {
    val unavailable = assertIs<LiveDeviceRoster.Unavailable>(
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = null) {
          CliMcpClient.ToolResult("Error: No device connected.", isError = true)
        }
      },
    )
    assertTrue("No device connected" in unavailable.reason, unavailable.reason)
  }

  @Test
  fun `no --device means no roster block at all`() {
    assertEquals(
      LiveDeviceRoster.NotRequested,
      runBlocking {
        liveDeviceRoster(requestedDevice = null, requestedSessionId = null) {
          CliMcpClient.ToolResult(rosterInfo)
        }
      },
    )
  }

  @Test
  fun `a roster lookup that throws degrades the block instead of failing the command`() {
    // The session metadata has already been printed by the time the roster is fetched, so a
    // transport failure must not turn an informational command into an error envelope.
    val unavailable = assertIs<LiveDeviceRoster.Unavailable>(
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = null) {
          throw IllegalStateException("daemon closed the connection")
        }
      },
    )
    assertTrue("daemon closed the connection" in unavailable.reason, unavailable.reason)
  }

  @Test
  fun `a validation error the daemon reports without isError is still a failed lookup`() {
    val unavailable = assertIs<LiveDeviceRoster.Unavailable>(
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = null) {
          // The daemon's validation failures arrive as plain text with isError = false, and
          // several of them append a newline before the message.
          CliMcpClient.ToolResult("\nError: No device connected.", isError = false)
        }
      },
    )
    assertTrue("No device connected" in unavailable.reason, unavailable.reason)
  }

  @Test
  fun `a session with no named bindings adds no roster block`() {
    assertEquals(
      LiveDeviceRoster.NotRequested,
      runBlocking {
        liveDeviceRoster("emulator-5554", requestedSessionId = null) {
          CliMcpClient.ToolResult("Connected device: android/emulator-5554")
        }
      },
    )
  }

  // ---------------------------------------------------------------------------
  // Reading the cast back out of a roster block
  // ---------------------------------------------------------------------------

  @Test
  fun `a roster's device ids survive the target and ACTIVE suffixes`() {
    assertEquals(
      listOf("android/emulator-5554", "android/emulator-5556"),
      rosterDeviceIds(
        listOf(
          "- seller: android/emulator-5554 (target: pos) [ACTIVE]",
          "- buyer: android/emulator-5556",
        ),
      ),
    )
  }

  @Test
  fun `a device id containing a space is not truncated`() {
    // `web/iPhone 14` is a legal instance id, and splitting the line on whitespace would drop a
    // bound device out of the cast — which is what decides whether a stop is refused.
    assertEquals(
      listOf("web/iPhone 14"),
      rosterDeviceIds(listOf("- shopper: web/iPhone 14 (target: checkout) [ACTIVE]")),
    )
  }

  @Test
  fun `a roster's names are the names switchDevice accepts`() {
    assertEquals(
      listOf("seller", "buyer"),
      rosterDeviceNames(
        listOf(
          "- seller: android/emulator-5554 (target: pos) [ACTIVE]",
          "- buyer: android/emulator-5556",
        ),
      ),
    )
  }

  // ---------------------------------------------------------------------------
  // Which session owns a device
  // ---------------------------------------------------------------------------

  private val companion = TrailblazeDeviceId(
    instanceId = "emulator-5556",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  @Test
  fun `every member of a cast identifies the session that holds it`() {
    // A `switchDevice` handover makes the daemon report the companion, but the documented
    // lifecycle spelling stays the start device.
    assertTrue(
      sessionOwnsDevice(
        expectedDevice = "android/emulator-5554",
        reportedDevice = companion,
        sessionDevices = listOf("android/emulator-5554", "android/emulator-5556"),
      ),
    )
  }

  @Test
  fun `a device outside the cast does not own the session`() {
    assertEquals(
      false,
      sessionOwnsDevice(
        expectedDevice = "android/emulator-9999",
        reportedDevice = companion,
        sessionDevices = listOf("android/emulator-5554", "android/emulator-5556"),
      ),
    )
  }

  @Test
  fun `with no cast the reported device is the whole answer`() {
    // `device disconnect` passes no cast, and must keep refusing to stop another shell's session.
    assertEquals(
      false,
      sessionOwnsDevice("android/emulator-5554", companion, sessionDevices = emptyList()),
    )
    assertTrue(sessionOwnsDevice("android/emulator-5556", companion, sessionDevices = emptyList()))
  }

  // ---------------------------------------------------------------------------
  // Bind values the daemon cannot act on
  // ---------------------------------------------------------------------------

  @Test
  fun `a bind with no DEVICE_ID is refused and names the offending entry`() {
    // The shared parser leaves a blank value to "the resolver", which `run --bind` has and
    // `session start` does not — unchecked it becomes ensureDevice("") and a `cli-` scope.
    val misuse = assertIs<SessionBindStartResolution.Misuse>(
      resolveSessionStartBinds(linkedMapOf("buyer" to ""), deviceArg = null),
    )
    assertTrue("buyer" in misuse.message, misuse.message)
  }

  @Test
  fun `an empty cast is refused rather than thrown`() {
    assertIs<SessionBindStartResolution.Misuse>(resolveSessionStartBinds(emptyMap(), deviceArg = null))
  }

  @Test
  fun `a bare --device matches a qualified bind`() {
    // The reverse direction was already covered; matching only one way made this legal
    // combination exit MISUSE claiming the arg named none of the bound devices.
    val resolved = assertIs<SessionBindStartResolution.Resolved>(
      resolveSessionStartBinds(
        linkedMapOf("seller" to "android/emulator-5554", "buyer" to "emulator-5556"),
        deviceArg = "emulator-5554",
      ),
    )
    assertEquals("seller" to "android/emulator-5554", resolved.orderedBinds.first())
  }

  @Test
  fun `a comma-split --bind entry is not bound under a name with a leading space`() {
    // picocli's `split = ","` does not trim, and `switchDevice(name="buyer")` cannot address
    // a name spelled " buyer".
    assertEquals(
      mapOf("seller" to "emulator-5554", "buyer" to "emulator-5556"),
      TrailCommand.parseDeviceBinds(listOf("seller=emulator-5554", " buyer=emulator-5556 ")),
    )
  }

  // ---------------------------------------------------------------------------
  // The two shapes a daemon refusal arrives in
  // ---------------------------------------------------------------------------

  @Test
  fun `a refusal is recognized through a leading newline`() {
    // Several `device` responses appendLine() before their message, and a prefix check that
    // does not trim reads such a refusal as a successful bind.
    assertTrue(CliMcpClient.ToolResult("\nError: Device not found.", isError = false).isFailure)
    assertTrue(CliMcpClient.ToolResult("boom", isError = true).isFailure)
    assertEquals(false, CliMcpClient.ToolResult("Bound 'seller'.").isFailure)
  }
}
