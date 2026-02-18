package xyz.block.trailblaze.util

import java.io.PrintStream

/**
 * JVM implementation of [Console].
 *
 * Outputs to stdout by default. Call [useStdErr] to redirect all output to
 * stderr for STDIO MCP transport mode.
 */
actual object Console {
  @Volatile private var out: PrintStream = System.out

  actual fun log(message: String) {
    out.println(message)
  }

  actual fun error(message: String) {
    System.err.println(message)
  }

  actual fun appendLog(message: String) {
    out.print(message)
    out.flush()
  }

  actual fun useStdErr() {
    out = System.err
    // Safety net: redirect System.out so any raw println() calls
    // from our code or third-party libraries also go to stderr.
    System.setOut(System.err)
  }
}
