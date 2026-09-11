package xyz.block.trailblaze.device

/**
 * Facts read off `dumpsys package` output.
 *
 * For callers that have a device shell but no `PackageManager` — i.e. the host, over adb. A caller
 * running *inside* an instrumentation should ask `PackageManager` directly instead: one Binder
 * call, no text to parse, and no package name interpolated into a shell command.
 */
object AndroidPackageDump {

  /**
   * Whether a package is a debug build.
   *
   * [UNKNOWN] is an answer, not an error. A dump that never mentioned the package cannot say
   * either way, and a caller that reads "unknown" as "no" is acting on a fact it does not have —
   * so the distinction is in the type rather than left to each caller's `Boolean` default.
   */
  enum class Debuggable { YES, NO, UNKNOWN }

  /**
   * Whether [appId] is marked `DEBUGGABLE` in [dumpsysPackageOutput], read off the
   * `flags=[ … ]` / `pkgFlags=[ … ]` lines of that package's own `Package [<appId>]` entry.
   *
   * Scoped to [appId]'s section rather than matched against the whole text, because
   * `dumpsys package <appId>` does not reliably dump one package: an argument it does not
   * recognise as a package name makes it dump the entire package database instead. Matched
   * globally, any single debuggable package on the device would answer for the target — and on a
   * userdebug image most of them are.
   */
  fun debuggable(dumpsysPackageOutput: String, appId: String): Debuggable {
    val section = sectionFor(dumpsysPackageOutput, appId) ?: return Debuggable.UNKNOWN
    return if (DEBUGGABLE_FLAG_LINE.containsMatchIn(section)) Debuggable.YES else Debuggable.NO
  }

  /**
   * The body of [appId]'s `Package [<appId>] (<hash>):` block — every line after its header up to
   * the next package header — or null when the dump has no such block.
   *
   * Accumulates across blocks rather than stopping at the first, because a package can legitimately
   * appear twice in one dump (`Packages:` and `Hidden system packages:`), and the flags live under
   * whichever block the dump chose to fill in.
   */
  private fun sectionFor(dumpsysPackageOutput: String, appId: String): String? {
    var found = false
    var inTarget = false
    val body = StringBuilder()
    for (line in dumpsysPackageOutput.lineSequence()) {
      val header = PACKAGE_HEADER.find(line)
      if (header != null) {
        inTarget = header.groupValues[1] == appId
        if (inTarget) found = true
        continue
      }
      if (inTarget) body.appendLine(line)
    }
    return if (found) body.toString() else null
  }

  /** `  Package [com.example.app] (b0d1a2f):` — the start of one package's block. */
  private val PACKAGE_HEADER = Regex("""^\s*Package \[([^]]+)]""")

  private val DEBUGGABLE_FLAG_LINE = Regex("""(?m)^\s*(pkg)?[fF]lags=\[[^]]*\bDEBUGGABLE\b""")
}
