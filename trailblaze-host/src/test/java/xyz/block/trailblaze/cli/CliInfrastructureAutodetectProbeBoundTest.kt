package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Device autodetect ([autodetectSingleConnectedDevice]) runs before every device command that was
 * given no `--device`, as a read-only `device` LIST probe. It has its own short bound,
 * [CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS], so a daemon that completes the MCP handshake and then
 * never answers (alive but out of IO slots) is reported in seconds rather than after the request
 * deadline sized for a composed tool call — mirroring the bound `McpProxy` already puts on its
 * copy of the same probe.
 */
class CliInfrastructureAutodetectProbeBoundTest {

  @Test
  fun `a LIST probe the daemon never answers is reported within the probe bound`() {
    StarvedDaemonStub.start().use { daemon ->
      val startedAt = System.nanoTime()
      val result = runUnderPreflightGuard { autodetectSingleConnectedDevice(daemon.port) }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(1, daemon.deviceCalls.get(), "the probe must have reached the daemon before the bound cut it off")
      assertTrue(
        elapsedMs < PREFLIGHT_GUARD_MS,
        "probe returned after ${elapsedMs}ms; the bound is ${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS}ms",
      )
      // Nothing is printed at the probe: `trailblaze run` falls through to its configured default
      // device on this result, so the reason travels with it and only a caller that fails prints.
      val unreachable = assertIs<DeviceAutodetectResult.DaemonUnreachable>(result)
      assertFalse(unreachable.alreadyReported, "the probe must leave the envelope to its caller")
      val reason = unreachable.starvedDaemonReason.orEmpty()
      assertTrue(
        "accepted the connection" in reason && "did not answer" in reason,
        "reason should name the symptom: $reason",
      )
      assertTrue("trailblaze status" in reason, "reason should point at `trailblaze status`: $reason")
      assertTrue("TRAILBLAZE_IO_PARALLELISM" in reason, "reason should point at TRAILBLAZE_IO_PARALLELISM: $reason")
    }
  }

  /**
   * On the real daemon every `/mcp` POST waits for an IO slot, so a starved daemon hangs the
   * `initialize` handshake before any LIST is sent. Bounding only the LIST would leave autodetect
   * waiting the full request deadline on the connect; the handshake has to be inside the bound.
   */
  @Test
  fun `a handshake the daemon never answers is reported within the same bound`() {
    StarvedDaemonStub.start(handshake = StarvedDaemonStub.HandshakeMode.PARKS).use { daemon ->
      val startedAt = System.nanoTime()
      val result = runUnderPreflightGuard { autodetectSingleConnectedDevice(daemon.port) }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(1, daemon.initializeCalls.get(), "the handshake must have reached the daemon before the bound cut it off")
      assertEquals(0, daemon.deviceCalls.get(), "no LIST is sent on a session that never opened")
      assertTrue(
        elapsedMs < PREFLIGHT_GUARD_MS,
        "autodetect returned after ${elapsedMs}ms; the bound is ${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS}ms",
      )
      val unreachable = assertIs<DeviceAutodetectResult.DaemonUnreachable>(result)
      assertFalse(unreachable.alreadyReported, "the verdict travels on the result, not to stderr")
      val reason = unreachable.starvedDaemonReason.orEmpty()
      assertTrue("initialize" in reason, "reason should name the handshake: $reason")
      assertTrue("TRAILBLAZE_IO_PARALLELISM" in reason, "reason should point at TRAILBLAZE_IO_PARALLELISM: $reason")
    }
  }

  /**
   * The resolver behind every device command that was given no `--device` treats an unanswered
   * probe as an infrastructure failure, not as misuse: the user did nothing wrong, and the exit
   * code is what a script keys on.
   */
  @Test
  fun `the device resolver fails as infra when the LIST probe never answers`() {
    StarvedDaemonStub.start().use { daemon ->
      val resolution = runUnderPreflightGuard { resolveDeviceWithAutodetect(flag = null, port = daemon.port) }
      assertEquals(DeviceResolution.InfraFailed, resolution)
      assertEquals(1, daemon.deviceCalls.get(), "the resolver's one probe reached the daemon")
    }
  }

  /**
   * The bound is a promise about how long the user waits before their command starts, so the
   * session teardown on the way out has to fit inside it. That teardown is a request to the daemon
   * that just failed to answer, and on its own fresh budget it waited the same 5 s again -- the
   * bound said 5 s and delivered 10 s. Invisible until the stub stopped answering DELETEs
   * instantly, which is what the real starved daemon does: it dispatches the DELETE on the same
   * exhausted pool as everything else.
   */
  @Test
  fun `the session teardown after an expired probe fits inside the bound, not after it`() {
    StarvedDaemonStub.start(parksDelete = true).use { daemon ->
      val startedAt = System.nanoTime()
      val result = runUnderPreflightGuard { autodetectSingleConnectedDevice(daemon.port) }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertIs<DeviceAutodetectResult.DaemonUnreachable>(result)
      assertEquals(1, daemon.deviceCalls.get(), "the probe reached the daemon")
      assertEquals(1, daemon.deleteCalls.get(), "the teardown is still attempted, just not waited out")
      // Threshold sits between the two outcomes: the bound plus a short cleanup (~5.3 s) against
      // the bound plus a second full cleanup (10 s). The slack is for scheduling, not for the bug.
      val ceiling = CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS + CliMcpClient.CLEANUP_TIMEOUT_MS - 2_000
      assertTrue(
        elapsedMs < ceiling,
        "autodetect took ${elapsedMs}ms; a second full cleanup budget would put it near " +
          "${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS + CliMcpClient.CLEANUP_TIMEOUT_MS}ms",
      )
    }
  }

  /**
   * The point of carrying the reason instead of printing it: the two verdicts must not collapse
   * into one message. "Try `trailblaze app start`" is the one hint that is certainly wrong for a
   * daemon that accepted the connection, and it is the hint the fallback wording gives.
   */
  @Test
  fun `the starved reason replaces the start-the-daemon hint, and only when there is one`() {
    val starved = captureStderrText {
      DeviceAutodetectResult.DaemonUnreachable(
        alreadyReported = false,
        starvedDaemonReason = "the daemon did not answer the MCP initialize handshake",
      ).reportIfOwed("fallback reason nobody should see")
    }
    assertTrue("did not answer" in starved, "the carried reason is what gets printed: $starved")
    assertFalse("app start" in starved, "must not tell the user to start a daemon that is up: $starved")
    assertFalse("fallback reason nobody should see" in starved, starved)

    val plain = captureStderrText {
      DeviceAutodetectResult.DaemonUnreachable(alreadyReported = false).reportIfOwed("could not list devices")
    }
    assertTrue("could not list devices" in plain, "no carried reason means the caller's own wording: $plain")

    val quiet = captureStderrText {
      DeviceAutodetectResult.DaemonUnreachable(alreadyReported = true).reportIfOwed("already said")
    }
    assertEquals("", quiet, "a verdict already reported at the probe must not be printed twice")
  }

  /**
   * [reportDaemonConnectFailure] is what the call sites that do not go through the connect-or-start
   * wrappers use. It exists because [CliMcpClient.DaemonStarvedException] is an ordinary exception
   * a `catch (Exception)` would collapse into the wrong hint.
   */
  @Test
  fun `a connect failure picks the envelope that matches what went wrong`() {
    val starved = captureStderrText {
      reportDaemonConnectFailure(
        CliMcpClient.DaemonStarvedException("the daemon on port 1 accepted the connection but did not answer"),
      )
    }
    assertTrue("did not answer" in starved, starved)
    assertFalse("app start" in starved, "a daemon that accepted the connection is running: $starved")

    val down = captureStderrText { reportDaemonConnectFailure(RuntimeException("connection refused")) }
    assertTrue("app start" in down, "a refused connection is the case where starting one is the fix: $down")
  }
}
