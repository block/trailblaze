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

  /**
   * One `<abi>: [status=…] [reason=…]` line of a package's `Dexopt state:` entry — the state of
   * ART's compiled artifacts for one code path (`base.apk`, a split) on one ABI.
   *
   * [compiled] is whether ART has artifacts it will run from. `run-from-apk` (and its
   * `-fallback` twin) mean the dex is loaded and verified from the APK on **every** process start;
   * `extract` is the pre-Oreo equivalent. Every other status ART names — `verify`, `quicken`,
   * `space[-profile]`, `speed[-profile]`, `everything[-profile]`, `assume-verified` — is a filter
   * dex2oat completed. A token this list does not know reads as NOT compiled: a needless compile
   * costs seconds once, while a missed one costs every cold start of the run.
   */
  data class DexoptEntry(
    val abi: String,
    val status: String,
    val reason: String?,
    val primaryAbi: Boolean,
  ) {
    val compiled: Boolean get() = COMPILED_STATUSES.matches(status)
  }

  /**
   * The `Dexopt state:` entry of one package: every [DexoptEntry] under its `[<appId>]` block, in
   * dump order. Empty when the block exists but lists no status lines.
   */
  data class DexoptState(val entries: List<DexoptEntry>) {
    /**
     * The entries that decide whether the app is compiled: every entry whose ABI is marked
     * `[primary-abi]` on at least one path, when the dump marks any (a secondary-ABI status
     * describes code the process will not run), else all.
     *
     * Matched by ABI name rather than requiring the marker on every line: a split's status line
     * for the same ABI as a marked base entry is still code the process runs, whether or not that
     * particular line repeats the marker.
     */
    val decisive: List<DexoptEntry>
      get() {
        val primaryAbis = entries.filter { it.primaryAbi }.map { it.abi }.toSet()
        return primaryAbis.ifEmpty { null }
          ?.let { abis -> entries.filter { it.abi in abis } }
          ?: entries
      }

    /** Whether ART has artifacts for every decisive code path. False for an empty entry. */
    val compiled: Boolean get() = decisive.isNotEmpty() && decisive.all { it.compiled }

    /**
     * True when the block existed but held no status line in a shape this parser reads, so
     * [compiled] being false is the absence of evidence rather than evidence of absence.
     *
     * The known case is Android 8.x, which prints the same fact in a shape that predates
     * `[status=…]`: 8.1 prints `arm64: kOatUpToDate` under each `path:`, and 8.0 an
     * `Instruction Set:` header with `path:`/`status:` pairs beneath it. A caller that would
     * compile on "not compiled" must check this first, or every session on such a device pays a
     * full compile and then re-reads the same unreadable dump.
     */
    val statusUnreadable: Boolean get() = entries.isEmpty()

    /** `status=verify reason=install` style summary of the decisive entries, for a log line. */
    fun summary(): String = decisive.ifEmpty { entries }
      .map { e -> "${e.abi}: status=${e.status}" + (e.reason?.let { " reason=$it" } ?: "") }
      .distinct()
      .joinToString("; ")
      .ifEmpty { "no dexopt status lines" }
  }

  /**
   * [appId]'s `Dexopt state:` entry in [dumpsysPackageOutput], or null when the dump has no such
   * entry — the package is not installed, or this is not a `dumpsys package` dump at all.
   *
   * Scoped to the `[<appId>]` block for the same reason [debuggable] scopes to the `Package [...]`
   * block: an argument `dumpsys package` does not recognise makes it dump every package, and the
   * `Dexopt state:` section then lists them all. The block ends at the next `[<other.package>]`
   * header or at the next unindented section header (`Compiler stats:`).
   */
  fun dexoptState(dumpsysPackageOutput: String, appId: String): DexoptState? {
    var inSection = false
    var inTarget = false
    var inSecondaryDex = false
    var found = false
    val entries = mutableListOf<DexoptEntry>()
    for (line in dumpsysPackageOutput.lineSequence()) {
      if (!inSection) {
        if (DEXOPT_SECTION_HEADER.matches(line)) inSection = true
        continue
      }
      // An unindented, non-blank line is the next top-level section: the dexopt section is over.
      if (line.isNotBlank() && !line.first().isWhitespace()) break
      val header = DEXOPT_PACKAGE_HEADER.find(line)
      if (header != null) {
        inTarget = header.groupValues[1] == appId
        if (inTarget) found = true
        inSecondaryDex = false
        continue
      }
      if (!inTarget) continue
      if (DEXOPT_PATH_LINE.containsMatchIn(line)) {
        // A new primary code path ends any secondary-dex list before it. Android 8.1 through 13
        // print `known secondary dex files:` inside EVERY `path:` block (it is the package-level
        // map, repeated), so a flag that only cleared at the next package header would swallow
        // the status of every split after the base — an uncompiled split would read as absent and
        // the repair would be skipped.
        inSecondaryDex = false
        continue
      }
      if (SECONDARY_DEX_HEADER.containsMatchIn(line)) {
        // A secondary (multidex) file the app loads at runtime, not the base APK or a split.
        // `pm compile` without `--secondary-dex` never touches it, so its status is not evidence
        // about the app's own artifacts — an app that always ships one uncompiled would otherwise
        // read as permanently broken with no compile that could ever fix it.
        inSecondaryDex = true
        continue
      }
      if (inSecondaryDex) continue
      val status = DEXOPT_STATUS_LINE.find(line) ?: continue
      entries += DexoptEntry(
        abi = status.groupValues[1],
        status = status.groupValues[2],
        reason = DEXOPT_REASON.find(line)?.groupValues?.get(1),
        primaryAbi = "[primary-abi]" in line,
      )
    }
    return if (found) DexoptState(entries) else null
  }

  private val DEXOPT_SECTION_HEADER = Regex("""^Dexopt state:\s*$""")

  /** `  [com.example.app]` — one package's entry inside `Dexopt state:`. */
  private val DEXOPT_PACKAGE_HEADER = Regex("""^\s*\[([A-Za-z0-9._]+)]\s*$""")

  /** `      x86_64: [status=run-from-apk] [reason=unknown] [primary-abi]`. */
  private val DEXOPT_STATUS_LINE = Regex("""^\s*([A-Za-z0-9_-]+): \[status=([^]]+)]""")
  private val DEXOPT_REASON = Regex("""\[reason=([^]]+)]""")
  /** `    path: /data/app/~~ab==/com.example-cd==/base.apk` — one primary code path. */
  private val DEXOPT_PATH_LINE = Regex("""^\s*path: """)
  private val SECONDARY_DEX_HEADER = Regex("""known secondary dex files:""")

  private val COMPILED_STATUSES =
    Regex("""assume-verified|verify|quicken|space(-profile)?|speed(-profile)?|everything(-profile)?""")
}
