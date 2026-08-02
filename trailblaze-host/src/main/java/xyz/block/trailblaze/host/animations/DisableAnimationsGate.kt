package xyz.block.trailblaze.host.animations

import xyz.block.trailblaze.host.isTrailblazeFlagEnabled

/**
 * Resolves whether the **experimental** session-scoped OS-animation disabling
 * ([SessionAnimationDisabler]) engages for a session, resolved once per session start.
 *
 * **Default off** — when neither source opts in, the device's animation settings are never
 * touched, so merging this feature changes no run's behavior until it's explicitly enabled.
 *
 * Two sources, env-over-config (mirrors [xyz.block.trailblaze.host.capture.IosBaguetteVideoGate]):
 * - `trailblaze config disable-animations true` — the discoverable, persistent toggle, read via
 *   the JVM-wide [EffectiveDisableAnimationsConfig] holder.
 * - `TRAILBLAZE_DISABLE_ANIMATIONS=1` — env override for one-off / CI use.
 *
 * Env values `1` / `true` (case-insensitive) enable, matching the other Trailblaze env toggles.
 * Enabling only *requests* the setup — a device where the mutation can't be applied (e.g. a
 * physical iOS device, where `simctl spawn` doesn't exist) declines per session and the run
 * proceeds with animations untouched, so a global opt-in is safe.
 */
object DisableAnimationsGate {
  fun enabled(): Boolean =
    fromValues(
      env = System.getenv(ENV_VAR),
      configEnabled = EffectiveDisableAnimationsConfig.enabled,
    )

  const val ENV_VAR: String = "TRAILBLAZE_DISABLE_ANIMATIONS"

  /** Pure seam for tests — [enabled] just feeds it the real environment + config holder. */
  internal fun fromValues(env: String?, configEnabled: Boolean): Boolean =
    env.isTrailblazeFlagEnabled() || configEnabled
}
