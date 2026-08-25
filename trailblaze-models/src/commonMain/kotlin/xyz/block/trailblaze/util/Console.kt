package xyz.block.trailblaze.util

/**
 * Platform-aware console output for Trailblaze.
 *
 * A simple abstraction over [println] that allows output to be redirected
 * depending on the runtime context:
 *
 * - **JVM (desktop)**: Outputs to stdout by default. Call [useStdErr] to redirect
 *   all output to stderr — required for STDIO MCP transport where stdout is
 *   reserved for the JSON-RPC protocol stream.
 * - **Android**: Outputs to Logcat via [android.util.Log].
 * - **wasmJs**: Outputs via [println] which maps to `console.log` in the browser.
 *
 * ## Usage
 * ```kotlin
 * Console.log("Starting server on port $port")
 * Console.error("Failed to connect: ${e.message}")
 * ```
 */
expect object Console {

  /**
   * General-purpose output. Replaces [println].
   *
   * On JVM, this goes to stdout (or stderr if [useStdErr] was called).
   * On Android, this goes to Logcat at INFO level.
   *
   * Suppressed when [enableQuietMode] is active. Use [info] for messages
   * that must always be visible to the user.
   */
  fun log(message: String)

  /**
   * User-facing output that is always visible, even in quiet mode.
   *
   * Use this for progress messages, results, and other output that the
   * end user should always see. Falls back to [log] on platforms that
   * don't support quiet mode.
   */
  fun info(message: String)

  /**
   * Error output. Replaces `System.err.println()`.
   *
   * On JVM, this always goes to stderr regardless of [useStdErr].
   * On Android, this goes to Logcat at ERROR level.
   */
  fun error(message: String)

  /**
   * Partial-line output without a trailing newline. Replaces `print()`.
   *
   * Useful for progress indicators (e.g., printing dots while waiting).
   * On Android and wasmJs, this falls back to [log] since Logcat and
   * `console.log` don't support partial-line output.
   */
  fun appendLog(message: String)

  /**
   * User-facing partial-line output without a trailing newline.
   *
   * Like [appendLog] but always visible (even in quiet mode), similar to [info].
   * Useful for animated progress indicators in CLI output.
   */
  fun appendInfo(message: String)

  /**
   * Redirect [log] output to stderr.
   *
   * Call once at startup when using STDIO MCP transport to keep stdout
   * clean for the JSON-RPC protocol. Also redirects [System.out] as a
   * safety net for any raw [println] calls in the codebase or dependencies.
   *
   * No-op on Android and wasmJs.
   */
  fun useStdErr()

  /**
   * Suppress [log] output and direct library noise (SLF4J, etc.) to /dev/null.
   *
   * After this call, only [info] and [error] produce visible terminal output.
   * Use for CLI commands where clean, minimal output is desired.
   *
   * No-op on Android and wasmJs.
   */
  fun enableQuietMode()

  /**
   * Restore normal [log] output after a prior [enableQuietMode].
   *
   * Required for callers that toggle quiet mode around a bounded operation —
   * notably the daemon's in-process CLI path, where a forwarded `snapshot`
   * would otherwise leave the long-lived daemon permanently silenced.
   *
   * No-op on Android and wasmJs.
   */
  fun disableQuietMode()

  /**
   * Current quiet-mode flag. Used by bounded-scope toggles to save-and-restore
   * the prior state rather than blanket-reset via [disableQuietMode].
   *
   * Always `false` on Android and wasmJs (quiet mode is a no-op there).
   */
  fun isQuietMode(): Boolean

  /**
   * Redirect [log] and [info] output to stderr, keeping stdout clean for JSON.
   *
   * Call this from CLI commands that use `--json` so that progress messages don't
   * pollute the machine-readable JSON document on stdout. After this call, only
   * explicit [println] writes to stdout.
   *
   * Unlike [useStdErr] this does not touch `System.out`, so the command can still
   * `println` its report. Unlike [enableQuietMode] the messages are not dropped —
   * they stay visible on stderr, where a `| jq` consumer never sees them.
   *
   * Pair with [disableJsonMode], or use [runJsonOutput]. A CLI process exits soon
   * after printing, but the daemon runs commands in-process on a long-lived JVM,
   * where leaving json mode on would silently move all later output to stderr.
   *
   * JVM only — a no-op on Android, iOS and wasmJs, none of which have a stdout a
   * caller could pipe.
   */
  fun enableJsonMode()

  /**
   * Restore the output streams [enableJsonMode] redirected. No-op if json mode is
   * not active, and a no-op on Android, iOS and wasmJs.
   */
  fun disableJsonMode()
}

/**
 * Runs [block] with [Console.enableJsonMode] active and restores the prior streams in a
 * `finally`, so a throw inside [block] cannot leave a long-lived JVM (the daemon) writing
 * every subsequent [Console.log] to stderr.
 *
 * Not re-entrant: an inner scope's exit restores the outer scope's streams too. Json mode
 * belongs at the top of a single command, so there is nothing to nest.
 */
inline fun <T> Console.runJsonOutput(block: () -> T): T {
  enableJsonMode()
  try {
    return block()
  } finally {
    disableJsonMode()
  }
}

/**
 * Runs [block] with [Console.enableQuietMode] active and restores the prior quiet-mode
 * state in a `finally` so an exception inside [block] cannot leave the daemon's Console
 * permanently silenced.
 *
 * Prefer this over a bare [Console.enableQuietMode] / [Console.disableQuietMode] pair —
 * any throw between the two leaves the JVM in a state where every subsequent
 * [Console.log] is suppressed, which is silent and painful to debug. This helper
 * captures the prior state via [Console.isQuietMode] so it composes correctly when
 * called from within an already-quiet scope.
 */
inline fun <T> Console.runQuiet(block: () -> T): T {
  val wasQuiet = isQuietMode()
  if (!wasQuiet) enableQuietMode()
  try {
    return block()
  } finally {
    if (!wasQuiet) disableQuietMode()
  }
}
