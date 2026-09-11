package xyz.block.trailblaze.host.turbo

import xyz.block.trailblaze.host.isTrailblazeFlagEnabled

/**
 * Resolves whether **experimental** turbo mode ([SessionTurboAttacher]) engages for a session,
 * resolved once per session start.
 *
 * **Default off** — when neither source opts in, no app is ever modified and no device state is
 * written, so merging this changes no run's behavior until someone explicitly enables it.
 *
 * Two sources, env-over-config (mirrors
 * [xyz.block.trailblaze.host.animations.DisableAnimationsGate]):
 * - `trailblaze config turbo true` — the discoverable, persistent toggle, read via the JVM-wide
 *   [EffectiveTurboConfig] holder.
 * - `TRAILBLAZE_TURBO=1` — env override for one-off / CI use. Read from the environment of the
 *   process that resolves the session, which for a daemon-served run is the daemon's — frozen when
 *   it started. Exporting it into a shell that talks to an already-running daemon does nothing;
 *   restart the daemon, or use `--turbo`, which travels with the run request.
 * - `trailblaze run --turbo` — one run's explicit choice, which outranks both of the above.
 *
 * Env values `1` / `true` (case-insensitive) enable, matching the other Trailblaze env toggles.
 * Enabling only *requests* the setup: an app Trailblaze has no matching signing key for declines
 * per session and the run proceeds at normal speed, so a global opt-in is safe.
 */
object TurboGate {
  /**
   * [runOverride] is one run's explicit choice (`trailblaze run --turbo` / `--turbo=false`) and
   * wins over both other sources, including a falsey one over an enabling env var — the same
   * precedence `--headless` has over its persisted setting. Null means "no per-run choice".
   */
  fun enabled(runOverride: Boolean? = null): Boolean =
    fromValues(
      env = System.getenv(ENV_VAR),
      configEnabled = EffectiveTurboConfig.enabled,
      runOverride = runOverride,
    )

  const val ENV_VAR: String = "TRAILBLAZE_TURBO"

  /** Pure seam for tests — [enabled] just feeds it the real environment + config holder. */
  internal fun fromValues(env: String?, configEnabled: Boolean, runOverride: Boolean? = null): Boolean =
    runOverride ?: (env.isTrailblazeFlagEnabled() || configEnabled)
}
