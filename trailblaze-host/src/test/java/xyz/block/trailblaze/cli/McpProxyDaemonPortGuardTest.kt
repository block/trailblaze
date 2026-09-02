package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A port in [TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE] cannot host a daemon, and the MCP
 * proxy is the one daemon-facing entry point that can be handed one unchecked — `McpProxyMain`
 * reads `TRAILRUNNER_DAEMON_PORT` straight into the constructor, bypassing [CliConfigHelper].
 *
 * `McpProxy.refuseDeviceAllocatablePort` owns the reasoning for why this is refused where it is.
 */
class McpProxyDaemonPortGuardTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val inRangePort = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 137

  /** Fails the test if the proxy probes: an unusable port must be refused without asking. */
  private fun refuseIfProbed(): () -> Boolean = { error("must not probe a device-allocatable port") }

  @Test
  fun `an in-range port is refused, and says so in terms the user can act on`() {
    val proxy = McpProxy(port = inRangePort)
    val logs = mutableListOf<String>()

    assertTrue(proxy.refuseDeviceAllocatablePort { logs += it })

    val message = logs.single()
    assertTrue(message.contains("$inRangePort"), "expected the offending port; got: $message")
    // The range and the remediation come from TrailblazeDevicePort's single message source. Assert
    // they survive the trip through this reporting path rather than re-pinning the exact wording.
    assertTrue(
      message.contains("${TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first}") &&
        message.contains("${TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last}"),
      "expected the allocation range in the message; got: $message",
    )
    assertTrue(message.contains("adb forward"), "expected the cause named; got: $message")
  }

  @Test
  fun `a usable port is not refused and reports nothing`() {
    // Below the range: the shipped default. A guard that refused this would break every proxy
    // launch, so the negative case is as load-bearing as the positive one.
    val proxy = McpProxy(port = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT)
    val logs = mutableListOf<String>()

    assertFalse(proxy.refuseDeviceAllocatablePort { logs += it })

    assertEquals(emptyList(), logs, "a usable port should be silent")
    assertFalse(proxy.daemonStartupFailed.get())
  }

  /**
   * The ordering, stated as a consequence rather than a call count. A device bridged onto the port
   * with `adb forward` *answers* `/ping`, so a guard checked after the probe would take the
   * "Daemon is reachable." exit and proxy the agent to that device. With a probe that returns true,
   * only a guard that runs first can still refuse.
   */
  @Test
  fun `waitForDaemon refuses even when something is already answering on the port`() {
    val proxy = McpProxy(port = inRangePort, daemonReachableOverride = { true })
    val logs = mutableListOf<String>()

    assertFalse(proxy.waitForDaemon { logs += it }, "an unusable port must be fatal")

    assertTrue(
      logs.single().startsWith("Cannot reach a daemon on port $inRangePort"),
      "expected only the refusal, not a reachability result; got: $logs",
    )
  }

  @Test
  fun `waitForDaemon stops at the port guard without probing or starting a daemon`() {
    val proxy = McpProxy(port = inRangePort, daemonReachableOverride = refuseIfProbed())
    val logs = mutableListOf<String>()

    assertFalse(proxy.waitForDaemon { logs += it })

    assertEquals(1, logs.size, "expected only the refusal; got: $logs")
    assertTrue(proxy.daemonStartupFailed.get())
  }

  @Test
  fun `a usable port lets waitForDaemon proceed`() {
    val proxy = McpProxy(
      port = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      daemonReachableOverride = { true },
    )

    assertTrue(proxy.waitForDaemon {}, "a reachable daemon on a usable port must proceed")
    assertFalse(proxy.daemonStartupFailed.get())
  }

  /**
   * The outcome the guard actually buys, and the reason the fast-fail flag cannot deliver it on its
   * own: a device holding this port answers requests instead of refusing them, so `forwardRequest`
   * succeeds, clears the flag, and hands the agent the device's replies. Declining to start is what
   * surfaces the misconfiguration.
   *
   * Reaches [McpProxy.run] because everything it does before the guard is logging — no stdin read,
   * no threads — so the refusal path is the one exit that can be driven without an MCP client.
   */
  @Test
  fun `run exits with MISUSE rather than proxying on an unusable port`() {
    val priorUserHome = System.getProperty("user.home")
    System.setProperty("user.home", tempFolder.newFolder("home").absolutePath)
    try {
      val exitCode = McpProxy(port = inRangePort, daemonReachableOverride = refuseIfProbed()).run()
      assertEquals(TrailblazeExitCode.MISUSE.code, exitCode)
    } finally {
      System.setProperty("user.home", priorUserHome)
    }
  }
}
