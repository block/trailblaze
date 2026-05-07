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
 * Concretely, this broke `waypoint --target <pack-id>`'s workspace-anchor lookup:
 * `TrailblazeWorkspaceConfigResolver.resolve(Paths.get(""))` walks up from the
 * daemon's cwd looking for `trails/config/trailblaze.yaml`, never finds a
 * workspace anchor that lives one or more directory levels deeper, and silently
 * falls back to default + classpath. The user's `--target` flag effectively does
 * nothing for workspace packs, even though it was designed to resolve them.
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
}
