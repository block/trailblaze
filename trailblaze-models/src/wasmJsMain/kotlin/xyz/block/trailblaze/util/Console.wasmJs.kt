package xyz.block.trailblaze.util

/**
 * wasmJs implementation of [Console].
 *
 * All output goes through [println] which maps to `console.log` in the browser.
 */
actual object Console {

  actual fun log(message: String) {
    println(message)
  }

  actual fun error(message: String) {
    println("ERROR: $message")
  }

  actual fun appendLog(message: String) {
    println(message)
  }

  actual fun appendInfo(message: String) = appendLog(message)

  actual fun info(message: String) = log(message)

  actual fun useStdErr() {
    // No-op in browser — console.log is always the output.
  }

  actual fun enableQuietMode() {
    // No-op in browser — console.log is always the output.
  }
}
