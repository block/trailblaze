package xyz.block.trailblaze.inprocessidle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared facts three objects agree on. Worth pinning off-device because a disagreement here
 * doesn't fail — it drops a run back to heuristic settle speed while every log line still claims
 * the detector is in play.
 */
class InProcessIdleTest {

  @Test
  fun flavorIsTheAppIdsLastDottedLabel() {
    assertEquals("app", InProcessIdle.flavorFor("com.example.app"))
    // No dot at all: the whole id is the label. The detector APK build derives the same way, so a
    // single-segment appId is a valid target rather than an error.
    assertEquals("app", InProcessIdle.flavorFor("app"))
  }

  @Test
  fun packageNameFollowsTheLastDottedLabelConvention() {
    assertEquals("xyz.block.trailblaze.inprocessidle.app", InProcessIdle.packageFor("com.example.app"))
    assertEquals("xyz.block.trailblaze.inprocessidle.app", InProcessIdle.packageFor("app"))
  }

  @Test
  fun assetPathAndPackageNameAlwaysNameTheSameBuild() {
    // The attach installs the package named by `packageFor` FROM the asset named by `assetPathFor`.
    // If those two ever derive the flavor differently, the attach installs one detector build
    // under another's package name and the failure surfaces only on a device shard, as a
    // missing-asset error naming a flavor nobody wrote.
    for (appId in listOf("com.example.app", "app", "com.example.staging.app")) {
      val flavor = InProcessIdle.flavorFor(appId)
      assertEquals("xyz.block.trailblaze.inprocessidle.$flavor", InProcessIdle.packageFor(appId))
      assertEquals(
        "inprocess-idle-apks/trailblaze-inprocess-idle-$flavor.apk",
        InProcessIdle.assetPathFor(appId),
      )
    }
  }

  @Test
  fun keepAliveThroughAnrNamesTheTargetAsAPersistentDebugApp() {
    // `--persistent` is load-bearing: every re-attach restarts the target, and a transient debug
    // app is consumed by the first process start after it is set. Drop it and the first trail of a
    // run is protected while every later launch is back to "a stall while attached kills the app".
    assertEquals(
      listOf("am", "set-debug-app", "--persistent", "com.example.app"),
      InProcessIdle.keepAliveThroughAnrShellArgs("com.example.app"),
    )
  }

  @Test
  fun setDebugAppIsTreatedAsRefusedWheneverItPrintsAnything() {
    // The command says nothing on success, so "silent" is the ONLY success signal an attach gets.
    assertFalse(InProcessIdle.setDebugAppReportedFailure(""))
    // A shell that echoed a trailing newline still succeeded — treating that as a refusal would
    // turn every attach on such a device into a failure.
    assertFalse(InProcessIdle.setDebugAppReportedFailure("  \n "))
    // The shape a missing package takes. Attaching over this gives an instrumented target with no
    // ANR protection, which is the exact combination the debug app exists to prevent.
    assertTrue(InProcessIdle.setDebugAppReportedFailure("Error: Unknown package"))
  }

  @Test
  fun clearDebugAppUndoesTheKeepAliveWithoutNamingAnApp() {
    // `am clear-debug-app` takes no package: the slot is device-wide. The callers that run it
    // (attach rejected, turbo turned off) have no attached app to name, so the command must not
    // need one.
    assertEquals(listOf("am", "clear-debug-app"), InProcessIdle.clearDebugAppShellArgs())
  }

  @Test
  fun attachTurnsErrorDialogsBackOnRatherThanOff() {
    // The value is the whole point: `1` here is what made ActivityManager kill an ANR'd app on the
    // farm phone instead of showing the dialog the trails dismiss with Wait.
    assertEquals(
      listOf("settings", "put", "global", "hide_error_dialogs", "0"),
      InProcessIdle.showErrorDialogsShellArgs(),
    )
  }

  @Test
  fun pingSendsTheLineTheDetectorParsesAndReturnsItsReply() {
    // Port 0 so this never contends with a real detector on 7777, or with another test on the
    // same agent.
    ServerSocket(0).use { server ->
      val received = StringBuilder()
      val serverThread = Thread {
        server.accept().use { socket ->
          received.append(BufferedReader(InputStreamReader(socket.getInputStream())).readLine())
          socket.getOutputStream().apply {
            write("PONG com.example.app\n".toByteArray())
            flush()
          }
        }
      }.apply { isDaemon = true; start() }

      val reply = InProcessIdle.ping(connectTimeoutMs = 2_000, port = server.localPort)
      serverThread.join(10_000)

      // The detector reads one line and matches it exactly; a trailing `\r` or a missing newline
      // would leave it waiting forever.
      assertEquals("PING", received.toString())
      assertEquals("PONG com.example.app", reply)
    }
  }

  @Test
  fun pingReturnsNullWhenNothingIsListening() {
    // Bind then close, so the port is one nothing holds — the normal state before an attach, which
    // callers read as "no detector" rather than as an error.
    val deadPort = ServerSocket(0).use { it.localPort }
    assertNull(InProcessIdle.ping(connectTimeoutMs = 2_000, port = deadPort))
  }

  @Test
  fun pingReturnsNullWhenTheHolderAcceptsButNeverReplies() {
    // A wedged detector: the port is held, so `awaitPortFree` sees the connect succeed, but no
    // line ever arrives. Must read as null and not hang the attach past the read bound.
    ServerSocket(0).use { server ->
      Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
      val startMs = System.currentTimeMillis()
      assertNull(InProcessIdle.ping(connectTimeoutMs = 2_000, port = server.localPort))
      // Hang containment only — asserts the read bound applied at all, not that it was fast.
      assertTrue(System.currentTimeMillis() - startMs < 60_000)
    }
  }

  @Test
  fun pingHonorsACallerSuppliedReadBound() {
    // A caller on a latency-sensitive path (the settle gate's identity probe) needs the whole
    // probe bounded, not just the connect. Against a holder that never replies, its own read
    // bound is what caps the wait — the default two seconds would land in front of every settle.
    ServerSocket(0).use { server ->
      Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
      val startMs = System.currentTimeMillis()
      assertNull(
        InProcessIdle.ping(
          connectTimeoutMs = 2_000,
          port = server.localPort,
          readTimeoutMs = 250,
        ),
      )
      // Generous headroom over the 250 ms bound, but far under the 2 s default it replaces.
      assertTrue(System.currentTimeMillis() - startMs < 1_500)
    }
  }
}
