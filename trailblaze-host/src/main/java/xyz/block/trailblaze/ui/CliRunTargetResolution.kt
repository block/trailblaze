package xyz.block.trailblaze.ui

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
): TrailblazeHostAppTarget? =
  configTarget?.let { findTargetById(it) } ?: resolveForCallerCwd(callerWorkspaceDir)
