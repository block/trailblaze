package xyz.block.trailblaze.cli

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Thread-local context for the *caller's* current working directory.
 *
 * **Why this exists.** The Trailblaze CLI runs in two modes:
 *
 *  - **Direct JVM (one-shot)**: the user invokes the CLI as a normal subprocess.
 *    `Paths.get("")` resolves to the user's shell cwd because the JVM IS the
 *    user's process — fine for everything that needs to walk relative paths.
 *  - **Daemon-forwarded (`/cli/exec`)**: the bash shim POSTs argv to a long-lived
 *    daemon that runs the picocli command in-process. The daemon's cwd is wherever
 *    `app start` was launched from (typically the repo root), NOT the user's
 *    interactive cwd. Any code that asks `Paths.get("")` gets the daemon's cwd
 *    instead of the user's, and walks the wrong tree.
 *
 * Concretely, this broke `waypoint --target <trailmap-id>`'s workspace-anchor lookup:
 * `TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))` walks up from the
 * daemon's cwd looking for `trails/config/trailblaze.yaml`, never finds a
 * workspace anchor that lives one or more directory levels deeper, and silently
 * falls back to default + classpath. The user's `--target` flag effectively does
 * nothing for workspace trailmaps, even though it was designed to resolve them.
 *
 * **The fix.** The bash shim sends `$PWD` in the `CliExecRequest`. The daemon's
 * `executeForDaemon` wraps the picocli invocation in `withCallerCwd(request.cwd)`,
 * which sets a thread-local for the duration of the run. CLI code that needs
 * the caller's actual cwd reads it via [callerCwd] instead of `Paths.get("")`.
 *
 * **Backward-compat.** Older shims that don't send `cwd` produce a null thread-local;
 * [callerCwd] falls back to `Paths.get("")` which is the previous behavior. Older
 * daemons that don't know about the `cwd` field ignore it (the JSON deserializer
 * has `ignoreUnknownKeys=true`). Both directions of mismatch are silent no-ops, so
 * a freshly-deployed shim against a stale daemon — or vice versa — degrades to the
 * pre-fix experience without breaking.
 *
 * **Direct-JVM behavior is unchanged.** The thread-local is null on direct-JVM
 * invocations (no `withCallerCwd` wrapping), so [callerCwd] returns `Paths.get("")`
 * which still resolves to the user's process cwd. Same as before.
 */
internal object CliCallerContext {

  private val callerCwdLocal = ThreadLocal<Path?>()

  /**
   * Caller-shell env vars forwarded by the bash shim through `/cli/exec`. The
   * map is authoritative when non-null: present keys hold the user's shell
   * values, absent keys mean the user has the var unset. Only when the map
   * itself is `null` (older shim or direct-JVM path) does [callerEnv] fall
   * back to `System.getenv`.
   *
   * Allowlisted keys (the bash shim only forwards these — keep this list in
   * sync with the `env_json` allowlist in `scripts/trailblaze`):
   *
   *  - `TRAILBLAZE_DEVICE` — consumed by `resolveCliDevice` in
   *    `CliInfrastructure.kt`. Set via `eval $(trailblaze device connect <id>)`.
   *  - `TRAILBLAZE_TARGET` — consumed by `envTrailblazeTarget` in
   *    `CliInfrastructure.kt`. Set via `eval $(trailblaze device connect <id>
   *    --target <name>)`.
   *
   * Adding a new key requires three coordinated edits: this kdoc, the bash
   * shim's allowlist, and a `resolveCli*`/`env*` consumer in
   * `CliInfrastructure.kt` (or wherever the read happens). Without all three,
   * the key won't reach the daemon-forwarded path.
   */
  private val callerEnvLocal = ThreadLocal<Map<String, String>?>()

  /**
   * Run [block] with the caller's [cwd] pinned to the current thread. Restores the
   * previous binding on exit, so nested calls can shadow correctly (the daemon
   * serializes CLI exec, but the helper stays correct under hypothetical concurrent
   * use).
   */
  fun <T> withCallerCwd(cwd: Path?, block: () -> T): T {
    val prev = callerCwdLocal.get()
    callerCwdLocal.set(cwd)
    try {
      return block()
    } finally {
      callerCwdLocal.set(prev)
    }
  }

  /**
   * Returns the caller's cwd if pinned for this thread, else `Paths.get("")`. CLI
   * code should call this in place of `Paths.get("")` when walking relative paths
   * that should be anchored at the user's interactive cwd.
   */
  fun callerCwd(): Path = callerCwdLocal.get() ?: Paths.get("")

  /**
   * Run [block] with the caller's [env] vars pinned to the current thread. Same
   * save/restore shape as [withCallerCwd] so nested calls shadow correctly.
   *
   * Mirrors the cwd-forwarding fix for the env-var-on-daemon problem: the daemon's
   * JVM env was captured at `app start` time, so anything the user `export`s in
   * their shell afterward never reaches `System.getenv` on a forwarded subcommand.
   * The bash shim sends the relevant vars in `CliExecRequest.env`; the daemon's
   * `executeForDaemon` wraps the run in `withCallerEnv(request.env)`; CLI code
   * that needs a caller-shell env var reads it via [callerEnv] which prefers the
   * thread-local before falling back to `System.getenv` for direct-JVM invocations.
   */
  fun <T> withCallerEnv(env: Map<String, String>?, block: () -> T): T {
    val prev = callerEnvLocal.get()
    callerEnvLocal.set(env)
    try {
      return block()
    } finally {
      callerEnvLocal.set(prev)
    }
  }

  /**
   * Returns the value of caller-shell env var [name].
   *
   * The thread-local IS the source of truth when it's set. The bash shim
   * sends `CliExecRequest.env` as an authoritative snapshot of every
   * allowlisted var in the user's shell — present means "user has this
   * value," absent means "user has this var unset." So if the thread-
   * local map exists, we read ONLY from it; we do NOT fall back to
   * [System.getenv] because that would consult the daemon's stale,
   * frozen-at-`app start` env and resurrect a value the user explicitly
   * unset in their shell (e.g. via `eval $(trailblaze device disconnect)`).
   *
   * Only when the thread-local is `null` — i.e. the JVM-spawn path where
   * this JVM IS the user's process and inherits their shell env directly,
   * OR an older bash shim that predates the env field and sends nothing —
   * do we fall back to [System.getenv]. That preserves the pre-fix
   * behavior for those paths (the JVM-spawn case was always correct;
   * older-shim-on-newer-daemon is no worse than before).
   *
   * Returning `null` means "the caller's shell doesn't have this var set" —
   * callers should treat that the same as the var being unset. Blank strings
   * are returned as-is; callers that need "treat blank as unset" should
   * filter with `takeIf { it.isNotBlank() }` to keep the policy consistent
   * across resolution surfaces.
   */
  fun callerEnv(name: String): String? {
    val pinned = callerEnvLocal.get()
    return if (pinned != null) pinned[name] else System.getenv(name)
  }
}
