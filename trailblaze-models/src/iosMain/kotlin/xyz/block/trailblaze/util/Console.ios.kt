package xyz.block.trailblaze.util

/**
 * iOS implementation of [Console].
 *
 * Everything goes through [println], which on Kotlin/Native writes to stdout — visible in the
 * Xcode / `simctl` console. Quiet and JSON modes are no-ops, as on wasmJs: they exist for the
 * CLI's stdout contracts (MCP STDIO framing, `--json`), and an on-device agent has no stdout
 * protocol to protect. If iOS ever grows one, the state they'd need must be thread-safe here —
 * unlike wasmJs, this runtime is multi-threaded (see PlatformConcurrency.ios.kt).
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
    // No-op — there is no separate protocol stream to keep clean on-device.
  }

  actual fun enableQuietMode() {
    // No-op — see the class KDoc.
  }

  actual fun disableQuietMode() {
    // No-op — see the class KDoc.
  }

  actual fun isQuietMode(): Boolean = false

  actual fun enableJsonMode() {
    // No-op — see the class KDoc.
  }
}
