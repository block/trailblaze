package xyz.block.trailblaze.util

import java.io.PrintStream

/**
 * JVM implementation of [Console].
 *
 * Outputs to stdout by default. Call [useStdErr] to redirect all output to
 * stderr for STDIO MCP transport mode.
 */
actual object Console {
  /** Stream for general log output. Suppressed in quiet mode. */
  @Volatile private var out: PrintStream = System.out

  /** Stream for user-facing output. Always points to a visible terminal stream. */
  @Volatile private var userOut: PrintStream = System.out

  @Volatile private var quietMode: Boolean = false

  /**
   * What [enableQuietMode] did to [userOut]: the stream it put there, and the one it displaced.
   *
   * One object rather than two more `PrintStream` fields, because these are bookkeeping and not
   * output sinks — anything that walks this object's streams, including a test that redirects
   * them all, should be able to tell the difference by type.
   */
  private class QuietRedirect(val installed: PrintStream, val displaced: PrintStream)

  @Volatile private var quietRedirect: QuietRedirect? = null

  @Volatile private var jsonMode: Boolean = false

  /** Streams saved by [enableJsonMode] so [disableJsonMode] can put them back. */
  @Volatile private var preJsonOut: PrintStream = System.out

  @Volatile private var preJsonUserOut: PrintStream = System.out

  actual fun log(message: String) {
    if (!quietMode) out.println(message)
  }

  actual fun info(message: String) {
    userOut.println(message)
  }

  actual fun error(message: String) {
    System.err.println(message)
  }

  actual fun appendLog(message: String) {
    if (!quietMode) {
      out.print(message)
      out.flush()
    }
  }

  actual fun appendInfo(message: String) {
    userOut.print(message)
    userOut.flush()
  }

  actual fun useStdErr() {
    out = System.err
    userOut = System.err
    // Safety net: redirect System.out so any raw println() calls
    // from our code or third-party libraries also go to stderr.
    System.setOut(System.err)
  }

  actual fun enableQuietMode() {
    if (quietMode) return
    // Point user-facing output at the general stream, which preserves the
    // DesktopLogFileWriter tee so info() still reaches both the terminal and the
    // log file. Saved so disableQuietMode can put it back: without that, a scope
    // that enables and disables quiet mode leaves userOut pointing somewhere the
    // caller did not choose, and info() keeps going there for the rest of the
    // process.
    quietRedirect = QuietRedirect(installed = out, displaced = userOut)
    userOut = out
    quietMode = true
    // Note: We intentionally do NOT redirect System.out here. Some libraries
    // (Ktor, HTTP clients) depend on System.out being functional. SLF4J/Logback
    // noise should be suppressed via logback.xml configuration instead.
  }

  actual fun disableQuietMode() {
    if (!quietMode) return
    quietMode = false
    val redirect = quietRedirect ?: return
    quietRedirect = null
    // Undo our own redirect, and only ours. If something retargeted the user-facing stream while
    // quiet mode was on — json mode, or STDIO framing moving everything to stderr — that target is
    // newer than ours, and putting ours back would send the newer caller's output somewhere it
    // deliberately moved away from. Matching by identity rather than restoring unconditionally
    // makes the two scopes independent instead of order-dependent.
    if (userOut === redirect.installed) userOut = redirect.displaced
    // Same redirect, in the one other place it can still be waiting: json mode that began inside
    // this scope snapshotted it, and would otherwise reinstate quiet mode's stream on its own way
    // out, after quiet mode had ended.
    if (jsonMode && preJsonUserOut === redirect.installed) preJsonUserOut = redirect.displaced
  }

  actual fun isQuietMode(): Boolean = quietMode

  actual fun enableJsonMode() {
    if (jsonMode) return
    preJsonOut = out
    preJsonUserOut = userOut
    // Both channels move to stderr so stdout carries nothing but the JSON document.
    // System.out is deliberately left alone (unlike useStdErr) — the command still
    // needs a working stdout to println the report onto.
    out = System.err
    userOut = System.err
    jsonMode = true
  }

  actual fun disableJsonMode() {
    if (!jsonMode) return
    out = preJsonOut
    userOut = preJsonUserOut
    jsonMode = false
  }
}
