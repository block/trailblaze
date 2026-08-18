package xyz.block.trailblaze.ui

import xyz.block.trailblaze.config.KnownTargetMessages
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * Chooses the effective target app for a daemon-dispatched `run`, given the trail's declared
 * `config.target` and the run caller's forwarded workspace dir.
 *
 * Precedence (matches what a CLI-local run resolves, so a delegated run agrees with
 * `trailblaze config get target`):
 *  1. The trail's `config.target`, when it names a loaded target ([findTargetById] non-null).
 *  2. Otherwise the persisted/workspace selection anchored at the CALLER's cwd
 *     ([resolveForCallerCwd] — see [TrailblazeSettingsRepo.getCurrentSelectedTargetAppForCallerCwd]).
 *
 * A `config.target` that names no loaded target falls through to (2) rather than erroring — a
 * stale / mistyped id degrades to the workspace default, matching the pre-extraction handler.
 * [onDeclaredTargetUnresolved] fires when that happens, so the run can say so: silently retargeting
 * makes a trail written for another workspace's target look like a correct run, and the mistake
 * resurfaces further down as an "unhandled tool" failure that names the tool rather than the target.
 *
 * Extracted from `TrailblazeDesktopApp.handleCliRunRequest` so the precedence AND the caller-cwd
 * threading (that `callerWorkspaceDir` actually reaches the resolver, not the daemon-anchored
 * no-arg one) are unit-testable without a live daemon — side effects are injected as lambdas.
 */
internal fun resolveDaemonRunTargetApp(
  configTarget: String?,
  callerWorkspaceDir: String?,
  findTargetById: (String) -> TrailblazeHostAppTarget?,
  resolveForCallerCwd: (String?) -> TrailblazeHostAppTarget?,
  onDeclaredTargetUnresolved: (declared: String, fallback: TrailblazeHostAppTarget?) -> Unit = { _, _ -> },
): TrailblazeHostAppTarget? {
  // Blank is "declared nothing", not "declared an unknown id" — it takes the fallback without a
  // warning, same as an absent `config.target`.
  val declared = configTarget?.takeIf { it.isNotBlank() }
    ?: return resolveForCallerCwd(callerWorkspaceDir)
  findTargetById(declared)?.let { return it }
  return resolveForCallerCwd(callerWorkspaceDir).also { fallback ->
    onDeclaredTargetUnresolved(declared, fallback)
  }
}

/**
 * The operator-facing warning for an unresolved declared target. One builder shared by every
 * record of the retargeting — console, CLI progress stream, and the deferred session-log
 * advisory — so all three carry the same text naming the declared target and the fallback.
 */
internal fun unresolvedDeclaredTargetWarning(
  declared: String,
  fallback: TrailblazeHostAppTarget?,
): String = buildString {
  val ranAgainst = fallback?.id ?: "no target"
  append("⚠️  This trail declares target '$declared', which this Trailblaze ")
  append("installation does not carry — running against '$ranAgainst' instead, so any ")
  append("'$declared'-specific tool in this trail will fail.")
  KnownTargetMessages.unavailableTargetHint(declared)?.let { append("\n$it") }
}
