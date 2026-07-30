package xyz.block.trailblaze.android.accessibility

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.util.Console

/**
 * Re-attaches the in-process idle (see [InProcessIdleSettleClient]) around `launchApp` so a launch that
 * stops or clears the target app doesn't leave the rest of the run settling at heuristic speed.
 *
 * The idle detector is a foreign instrumentation running INSIDE the target app's process, so anything
 * that kills that process — `am force-stop` from a FORCE_RESTART/REINSTALL launch, `pm clear`, a
 * trailhead that resets app state — detaches it. This helper makes `launchApp` self-healing:
 * when the settle-race sysprop is on and a convention-named idle detector package is installed for the
 * target, it re-runs `am instrument` before the foreground launch and confirms the idle detector serves
 * again afterwards.
 *
 * Attach ordering is load-bearing (same contract as the farm-bundle attachers):
 *  - `am instrument` itself restarts the target's process, so it must run BEFORE the foreground
 *    launch — attaching after would kill the UI the launch just brought up.
 *  - The foreground launch must follow PROMPTLY: `am instrument` cold-starts the target
 *    headless, and a heavy app's Application init can overrun the platform's ~20s background
 *    proc-start ANR watchdog unless an activity start keeps it visible.
 *
 * Everything here is best-effort and never throws: the idle detector is an accelerator, and the settle
 * gates race it against the standard heuristic ([InProcessIdleSettleClient.raceIdleAgainstHeuristic])
 * — a failed re-attach means gates settle exactly as they would with the feature off. Failures
 * are logged loudly (`[inprocess-idle-reattach]`) so a silently-degraded run is still diagnosable.
 *
 * Idle detector package convention: `xyz.block.trailblaze.inprocessidle.<last dotted label of the appId>`
 * (e.g. `com.example.app` → `xyz.block.trailblaze.inprocessidle.app`) — the same convention the
 * farm-bundle attachers and the idle detector APK build script use.
 */
object InProcessIdleLaunchReattacher {

  private const val IN_PROCESS_IDLE_PORT = 7777
  private const val IN_PROCESS_IDLE_PACKAGE_PREFIX = "xyz.block.trailblaze.inprocessidle."
  private const val IN_PROCESS_IDLE_INSTRUMENTATION_CLASS =
    "xyz.block.trailblaze.inprocessidle.InProcessIdleInstrumentation"

  /**
   * Bound on the post-launch PONG wait; the attach already started, this only confirms it. Wider
   * than the naive "warm process re-binds instantly" guess: the reattach follows a force-stop, so
   * `am instrument` cold-starts the target again, and a heavy Compose app's re-init on a loaded CI
   * emulator can take double-digit seconds even with dexopt/page-cache warm. Sized to the trail's
   * own post-reattach assertion window (30s) so a slow-but-successful reattach still confirms
   * rather than falling back to heuristic speed (and, on the farm shards, failing the reattach
   * assertion). Overshooting only delays the heuristic fallback on a genuine failure.
   */
  private const val PONG_WAIT_MS = 30_000L
  private const val PONG_POLL_INTERVAL_MS = 500L

  /** Convention-named idle detector package for a target applicationId. */
  fun inProcessIdlePackageFor(appId: String): String = IN_PROCESS_IDLE_PACKAGE_PREFIX + appId.substringAfterLast('.')

  /** What [attachBeforeLaunch] decided, pure over its inputs so the policy is unit-testable. */
  internal enum class Decision {
    /** Settle-race sysprop is off — the run never opted into idle detector mode. */
    SKIP_DISABLED,

    /** An idle detector already answers PING for this app (e.g. a RESUME launch) — nothing to do. */
    SKIP_ALREADY_ATTACHED,

    /**
     * Port 7777 serves a DIFFERENT app's idle detector. Only one idle detector can serve per device, and
     * detaching the other one means force-stopping that app — too destructive for a launch
     * side-effect (a multi-app trail may be mid-flow in it). Skip and say so.
     */
    SKIP_PORT_HELD_BY_OTHER,

    /** No convention-named idle detector package is installed for this target. */
    SKIP_NOT_INSTALLED,

    /** Idle detector mode is on, the package is present, and nothing serves — re-attach. */
    ATTACH,
  }

  /**
   * Pure attach policy. [pingReply] is the current `PING` answer (null when nothing serves);
   * checked before [inProcessIdleInstalled] — a lambda, so its shell probe only runs when the ping
   * didn't already decide (the common already-attached case costs one socket probe).
   */
  internal fun decide(
    syspropEnabled: Boolean,
    inProcessIdleInstalled: () -> Boolean,
    pingReply: String?,
    appId: String,
  ): Decision = when {
    !syspropEnabled -> Decision.SKIP_DISABLED
    pingReply == "PONG $appId" -> Decision.SKIP_ALREADY_ATTACHED
    pingReply?.startsWith("PONG ") == true -> Decision.SKIP_PORT_HELD_BY_OTHER
    !inProcessIdleInstalled() -> Decision.SKIP_NOT_INSTALLED
    else -> Decision.ATTACH
  }

  /**
   * Whether `am instrument`'s output signals a failed attach. `am instrument` (without `-w`)
   * returns a zero exit code immediately, so an unresolvable component or a signature mismatch
   * surfaces only as an error line in its output ("... Exception ...", "unable to ..."). Pure over
   * the captured output so the classification is unit-testable.
   */
  internal fun amInstrumentReportedFailure(output: String): Boolean =
    output.contains("Exception", ignoreCase = true) || output.contains("unable", ignoreCase = true)

  /**
   * Phase 1 — call AFTER the launch's force-stop/clear, BEFORE the foreground launch.
   * Starts `am instrument` for the convention-named idle detector when the policy says to.
   *
   * Returns true when an attach was started and [awaitAttachedAfterLaunch] should be called
   * once the foreground launch has been dispatched.
   */
  fun attachBeforeLaunch(appId: String): Boolean {
    // Decided before any probing: launchApp runs on every trail, and a run that never opted
    // into idle detector mode must not pay a socket probe + shell exec per launch. [decide] keeps its
    // own SKIP_DISABLED leg so the policy stays complete (and unit-tested) on its own.
    if (!InProcessIdleSettleClient.isEnabled()) return false
    val pingReply = ping()
    val decision = try {
      decide(
        syspropEnabled = true,
        // `pm path` over the shell needs no <queries> package-visibility declaration.
        inProcessIdleInstalled = {
          AdbCommandUtil.execShellCommand("pm path ${inProcessIdlePackageFor(appId)}")
            .contains("package:")
        },
        pingReply = pingReply,
        appId = appId,
      )
    } catch (t: Throwable) {
      Console.log("[inprocess-idle-reattach] skipping for $appId — probe failed (${t.message})")
      return false
    }
    when (decision) {
      Decision.SKIP_DISABLED,
      Decision.SKIP_NOT_INSTALLED,
      -> return false

      Decision.SKIP_ALREADY_ATTACHED -> {
        Console.log("[inprocess-idle-reattach] idle detector already attached to $appId")
        return false
      }

      Decision.SKIP_PORT_HELD_BY_OTHER -> {
        Console.log(
          "[inprocess-idle-reattach] port $IN_PROCESS_IDLE_PORT serves another app's idle detector ($pingReply) — " +
            "skipping re-attach for $appId (one idle detector per device)",
        )
        return false
      }

      Decision.ATTACH -> {
        val inProcessIdlePackage = inProcessIdlePackageFor(appId)
        Console.log("[inprocess-idle-reattach] re-attaching $inProcessIdlePackage to $appId")
        val output = try {
          AdbCommandUtil.execShellCommand("am instrument $inProcessIdlePackage/$IN_PROCESS_IDLE_INSTRUMENTATION_CLASS")
        } catch (t: Throwable) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${t.message}")
          return false
        }
        if (amInstrumentReportedFailure(output)) {
          Console.log("[inprocess-idle-reattach] am instrument failed for $inProcessIdlePackage: ${output.trim()}")
          return false
        }
        return true
      }
    }
  }

  /**
   * Phase 2 — call after the foreground launch that follows a true [attachBeforeLaunch].
   * Confirms the idle detector serves again, logging the outcome either way. Best-effort: a timeout
   * logs a warning and returns; the settle gates keep racing and falling back regardless.
   */
  fun awaitAttachedAfterLaunch(appId: String) {
    val expected = "PONG $appId"
    val deadline = System.currentTimeMillis() + PONG_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      if (ping() == expected) {
        Console.log("[inprocess-idle-reattach] attached: $expected")
        return
      }
      Thread.sleep(PONG_POLL_INTERVAL_MS)
    }
    Console.log(
      "[inprocess-idle-reattach] idle detector for $appId never answered PING within ${PONG_WAIT_MS}ms — " +
        "settle gates will fall back to the event-quiet heuristic",
    )
  }

  private fun ping(): String? = try {
    Socket().use { socket ->
      socket.connect(InetSocketAddress("127.0.0.1", IN_PROCESS_IDLE_PORT), 250)
      socket.soTimeout = 2_000
      socket.getOutputStream().apply {
        write("PING\n".toByteArray())
        flush()
      }
      BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
    }
  } catch (t: Throwable) {
    null
  }
}
