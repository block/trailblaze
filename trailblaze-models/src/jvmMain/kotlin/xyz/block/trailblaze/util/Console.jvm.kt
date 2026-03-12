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
    // Save current output stream for user-facing messages.
    // This preserves the DesktopLogFileWriter tee so info() still reaches both
    // the terminal and the log file.
    userOut = out
    quietMode = true
    // Note: We intentionally do NOT redirect System.out here. Some libraries
    // (Ktor, HTTP clients) depend on System.out being functional. SLF4J/Logback
    // noise should be suppressed via logback.xml configuration instead.
  }
}
