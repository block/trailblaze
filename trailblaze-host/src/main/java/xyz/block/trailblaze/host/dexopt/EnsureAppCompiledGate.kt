package xyz.block.trailblaze.host.dexopt

import xyz.block.trailblaze.host.isTrailblazeFlagEnabled

/**
 * Whether a host-driven session start checks that the app under test has compiled ART artifacts
 * ([SessionAppCompileEnsurer]).
 *
 * **Default on.** The check is one `dumpsys package` read per session, and it only ever writes to a
 * device whose install has no artifacts at all — a state that costs a large app 7s on every cold
 * start until something compiles it. That is a repair, not an experiment, so unlike turbo it does
 * not wait to be asked for.
 *
 * `TRAILBLAZE_DISABLE_ENSURE_APP_COMPILED=1` (or `true`) switches it off — the same kill-switch shape
 * as the other `TRAILBLAZE_DISABLE_*` variables. Read from the environment of the process that
 * resolves the session, which for a daemon-served run is the daemon's, frozen when it started.
 */
object EnsureAppCompiledGate {
  const val ENV_VAR: String = "TRAILBLAZE_DISABLE_ENSURE_APP_COMPILED"

  fun enabled(): Boolean = fromEnv(System.getenv(ENV_VAR))

  /** Pure seam for tests — [enabled] just feeds it the real environment. */
  internal fun fromEnv(env: String?): Boolean = !env.isTrailblazeFlagEnabled()
}
