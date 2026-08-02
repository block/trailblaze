package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.api.ScreenState

/**
 * Driver-neutral contract for a host-native iOS device manager — the single interface a new
 * iOS driver implements to plug into Trailblaze (see
 * [xyz.block.trailblaze.devices.TrailblazeDriverType.Companion.IOS_HOST_NATIVE_DRIVER_TYPES]).
 * [xyz.block.trailblaze.host.axe.AxeDeviceManager] is the reference implementation; everything above this seam (the
 * Maestro-command converter, [IosDriverTrailRunner], [IosDriverTrailblazeAgent], selector resolution,
 * toolsets) is transport-agnostic and reused as-is.
 *
 * Behavioral guarantees an implementation must honor, so recorded trails replay identically
 * across drivers:
 *
 *  - **Selector actions poll until timeout.** [IosDriverAction.TapOnElement], [IosDriverAction.AssertVisible]
 *    and [IosDriverAction.AssertNotVisible] re-capture the tree until `timeoutMs` rather than reading
 *    once. On multiple matches, warn and act on the first.
 *  - **Miss handling, in order:** recorded coordinate fallback (`fallbackX/Y`) when present →
 *    `optional` skip (logged no-op) → error. `optional` mirrors Maestro's `optional: true`.
 *  - **Launch flags are load-bearing.** `clearState` must give clean-reinstall semantics and
 *    hard-fail on error; `clearKeychain` must actually reset the keychain (it survives
 *    reinstall) and hard-fail; `stopFirst` is best-effort. Silently dropping any of these
 *    produces trails that "pass" against dirty state.
 *  - **Errors throw.** A failed action throws; the runner converts it to a
 *    [xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error] and short-circuits the batch.
 */
interface IosDeviceManager {

  /**
   * Fresh [ScreenState] for the current UI — must expose a
   * [xyz.block.trailblaze.api.TrailblazeNode] tree (`trailblazeNodeTree`) for selector
   * resolution plus the compact text/screenshot the LLM loop reads. Cheap to construct;
   * expensive members should be lazy.
   */
  fun getScreenState(): ScreenState

  /** Dispatches one action, returning the resolved tap point when one exists. */
  fun execute(action: IosDriverAction): ExecutionResult

  data class ExecutionResult(val resolvedX: Int? = null, val resolvedY: Int? = null)
}
