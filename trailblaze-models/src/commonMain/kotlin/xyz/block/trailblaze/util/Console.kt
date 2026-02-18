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
   */
  fun log(message: String)

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
   * Redirect [log] output to stderr.
   *
   * Call once at startup when using STDIO MCP transport to keep stdout
   * clean for the JSON-RPC protocol. Also redirects [System.out] as a
   * safety net for any raw [println] calls in the codebase or dependencies.
   *
   * No-op on Android and wasmJs.
   */
  fun useStdErr()
}
