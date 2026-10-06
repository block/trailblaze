package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.util.Console

/**
 * Host-side gate for dual-tree capture: when on, each screen capture carries a SECOND
 * view-hierarchy tree from another driver's dialect on a side channel, so a recorded trail's
 * selectors can later be rewritten deterministically into that driver's dialect.
 *
 * Android reads the on-device counterpart `trailblaze.captureSecondaryTree` instrumentation arg;
 * the host bridges this env var to it, so both sides toggle from one source of truth.
 *
 * Also owns [recordedToolHooks], the per-tool snapshot pair every host replay path installs, so
 * the RPC path and the in-host (iOS / web) path can't drift on which tools get a snapshot.
 */
object HostMigrationCapture {

  const val ENV_VAR: String = "TRAILBLAZE_CAPTURE_SECONDARY_TREE"

  /**
   * True only for `true` (case-insensitive, surrounding whitespace trimmed) — unset and every
   * other value stay off.
   *
   * Deliberately strict rather than accepting `1` / `yes` / `on`: the on-device counterpart
   * `InstrumentationArgUtil.shouldCaptureSecondaryTree` is strict the same way, and a host that
   * accepted more spellings than the device would silently capture on one side only. An
   * unrecognized non-empty value is called out rather than ignored, because "I set the variable
   * and nothing was captured" is otherwise invisible.
   */
  fun enabled(env: (String) -> String? = System::getenv): Boolean {
    val raw = env(ENV_VAR)?.trim() ?: return false
    if (raw.equals("true", ignoreCase = true)) return true
    if (raw.isNotEmpty()) {
      Console.log("[migration-capture] ignoring $ENV_VAR=$raw — only 'true' enables dual-tree capture")
    }
    return false
  }

  /**
   * Whether this run has a host-side producer that can actually attach a secondary tree.
   *
   * Today that is the Maestro-over-XCTest iOS path, whose producer lives in
   * [MaestroHostRunnerImpl]. Without a producer the pre/post-tool hooks would add a snapshot per
   * recorded tap and assert that carries only one tree — pure session-log weight that
   * `migrate-trail` then skips — so the hooks are gated on this rather than on the switch alone.
   */
  fun hasHostProducer(device: TrailblazeConnectedDevice?): Boolean =
    device is MaestroConnectedDevice && iosProducerUdid(device.trailblazeDeviceId) != null

  /**
   * The simulator udid the iOS producer reads the Axe tree for, or null when there is none. The
   * producer ([MaestroHostRunnerImpl.iosUdid]) and the hook gate ([hasHostProducer]) both read this,
   * so the hooks can't install on a device the producer would then decline (a blank udid).
   */
  fun iosProducerUdid(deviceId: TrailblazeDeviceId): String? =
    deviceId.instanceId.takeIf {
      it.isNotBlank() && deviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS
    }

  /** The pre/post snapshot hooks a replay path hands to `TrailblazeRunnerUtil`. */
  data class RecordedToolHooks(
    val onBefore: suspend (TrailblazeTool) -> Unit,
    val onAfter: suspend (TrailblazeTool) -> Unit,
  )

  /**
   * Builds the per-tool snapshot hooks, or null when dual-tree capture is off (the caller then
   * passes no hooks and replay is untouched).
   *
   * [hasProducer] is asked per tool, not once: a multi-device run can `switchDevice` to a device
   * with no secondary-tree producer (a web companion), and a snapshot there would carry one tree
   * only — pure session-log weight that `migrate-trail` then skips.
   *
   * Pre fires for taps and asserts only: recordings also contain launch / custom-flow / verify
   * tools a migration never rewrites, and a snapshot each would inflate the session log for no
   * benefit. Post fires for asserts only, because an assert waits for its target to appear — the
   * pre snapshot often catches a mid-transition frame that lacks it, while the post snapshot
   * always has it. A tap's post-state is the NEXT screen, where the tapped target is gone, so a
   * post snapshot there would be useless for resolving the original selector.
   *
   * Capture failures are swallowed — the hooks are observational and must never fail a replay —
   * but cancellation propagates so a trail abort or timeout still unwinds.
   */
  fun recordedToolHooks(
    enabled: Boolean = enabled(),
    /** Whether the device the next tool runs on produces a secondary tree — see [hasHostProducer]. */
    hasProducer: () -> Boolean,
    captureScreenState: suspend () -> ScreenState?,
    sessionProvider: () -> TrailblazeSession?,
    logSnapshot: (session: TrailblazeSession, screenState: ScreenState, displayName: String) -> Unit,
  ): RecordedToolHooks? {
    if (!enabled) return null

    suspend fun snapshot(tool: TrailblazeTool, phase: String, label: String) {
      try {
        if (!hasProducer()) return
        val session = sessionProvider() ?: return
        val screen = captureScreenState() ?: return
        logSnapshot(session, screen, "$phase: ${tool::class.simpleName ?: "unknown"}")
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Console.log("[migration-capture] $label snapshot failed: ${e.message}")
      }
    }

    return RecordedToolHooks(
      onBefore = { tool ->
        if (tool is TapOnByElementSelector || tool is AssertVisibleBySelectorTrailblazeTool) {
          snapshot(tool, phase = "preTool", label = "pre-tool")
        }
      },
      onAfter = { tool ->
        if (tool is AssertVisibleBySelectorTrailblazeTool) {
          snapshot(tool, phase = "postTool", label = "post-tool")
        }
      },
    )
  }
}
