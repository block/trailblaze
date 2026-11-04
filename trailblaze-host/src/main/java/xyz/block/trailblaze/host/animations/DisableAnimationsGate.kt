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
 * Enabling only *requests* the setup — a device where the mutation can't be applied declines per
 * session and the run proceeds with animations untouched, so a global opt-in is safe.
 *
 * A driver can default it on ([driverDefault]): the in-process driver waits on the app's own idle
 * state, which every running animation holds busy, so Espresso users turn animations off as
 * standard. There an explicit `false` from either source (`trailblaze config disable-animations
 * false`, `TRAILBLAZE_DISABLE_ANIMATIONS=0`) still keeps the device untouched.
 */
object DisableAnimationsGate {
  fun enabled(driverDefault: Boolean = false): Boolean =
    fromValues(
      env = System.getenv(ENV_VAR),
      configEnabled = EffectiveDisableAnimationsConfig.enabled,
      configExplicitlyOff = EffectiveDisableAnimationsConfig.explicitlyOff,
      driverDefault = driverDefault,
    )

  const val ENV_VAR: String = "TRAILBLAZE_DISABLE_ANIMATIONS"

  /** Pure seam for tests — [enabled] just feeds it the real environment + config holder. */
  internal fun fromValues(
    env: String?,
    configEnabled: Boolean,
    configExplicitlyOff: Boolean = false,
    driverDefault: Boolean = false,
  ): Boolean {
    val envExplicitlyOff = env == "0" || env.equals("false", ignoreCase = true)
    // Where the driver defaults it on, either source's explicit `false` keeps the device untouched,
    // the env first, as it outranks config.
    if (driverDefault) return !envExplicitlyOff && (env.isTrailblazeFlagEnabled() || !configExplicitlyOff)
    return env.isTrailblazeFlagEnabled() || configEnabled
  }
}
