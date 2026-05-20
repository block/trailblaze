package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable

@Command(
  name = "locate",
  mixinStandardHelpOptions = true,
  description = [
    "Given a captured screen state, report which waypoint(s) match.",
  ],
)
class WaypointLocateCommand : Callable<Int> {

  @Option(names = ["--session"], description = ["Session log directory. With --step, locates a single step; without --step, batch-locates every screen-state log in the dir and emits one TSV row per step."])
  var session: File? = null

  @Option(names = ["--step"], description = ["1-based index of the step within the session (single-step mode; selects from *_TrailblazeLlmRequestLog.json files)"])
  var step: Int? = null

  @Option(names = ["--file"], description = ["Direct path to a *_TrailblazeLlmRequestLog.json file (alternative to --session/--step)"])
  var file: File? = null

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id to operate on. Resolves --root to <workspace>/packs/<id>/waypoints/. " +
        "Mutually exclusive with --root (--root wins if both given).",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Additional directory to scan for *.waypoint.yaml files. Overrides --target. Pack waypoints are always included regardless. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--rel-base"],
    paramLabel = "<path>",
    description = [
      "Batch mode only: emit log paths relative to this directory. Must be an existing directory. Default: relative to the session dir's parent (yielding <session-name>/<step-filename>).",
    ],
  )
  var relBase: File? = null

  @Option(
    names = ["--log-suffix"],
    paramLabel = "<suffix>",
    description = [
      "Batch mode only: restrict the walk to logs whose filename ends with this suffix " +
        "(e.g. `_AgentDriverLog.json`). Default: every screen-state log type " +
        "(`_AgentDriverLog.json`, `_TrailblazeSnapshotLog.json`, `_TrailblazeLlmRequestLog.json`). " +
        "Use to pin row accounting against a specific log type when the session dir may carry multiple.",
    ],
  )
  var logSuffix: String? = null

  @Option(names = ["--live"], description = ["Pull screen state from the connected device (not yet implemented)"])
  var live: Boolean = false

  override fun call(): Int {
    if (live) {
      Console.error("--live is not yet implemented for waypoint locate. Use --session/--step or --file.")
      return CommandLine.ExitCode.USAGE
    }
    // Validate argument combinations up-front, before the expensive
    // WaypointDiscovery.discover() call. A user who fat-fingers --file or omits both
    // --file/--session shouldn't pay the discovery cost (and read the "Loaded
    // trailblaze.yaml…" chatter) before learning their args are wrong.
    validateArgs()?.let { return it }

    // Batch mode commits stdout to TSV. WaypointDiscovery loads the workspace pack
    // manifest, which routes through TrailblazeProjectConfigLoader's "Loaded
    // trailblaze.yaml from …" Console.log line on first touch — that's stdout chatter
    // and it corrupts the TSV stream callers pipe into cat/sort/awk. Flip Console into
    // quiet mode so Console.log is a no-op for the rest of this invocation; Console.error
    // still reaches stderr, and println() (used for TSV rows) is untouched. Try/finally
    // restores the prior state so back-to-back in-process invocations (tests) aren't
    // permanently silenced.
    //
    // Concurrency: `Console` is a process-global singleton, so the toggle is shared
    // across all threads in this JVM. In production, `waypoint locate` is NOT in
    // `TrailblazeCli.FORWARDABLE_SUBCOMMANDS` — it runs in its own short-lived JVM per
    // invocation (no daemon `/cli/exec` forwarding), so there's nothing else in the
    // process that could race the toggle. In tests, picocli's `execute()` is synchronous
    // and tests run sequentially, so the same single-threaded invariant holds. The
    // save/restore pattern only matters because subsequent picocli invocations in the
    // SAME test JVM would otherwise inherit quiet mode. If `waypoint locate` is ever
    // added to FORWARDABLE_SUBCOMMANDS, that path's own save/restore (TrailblazeCli.kt:263)
    // covers it — but this toggle would need to compose with the daemon's existing one.
    val batchMode = isBatchMode()
    val wasQuiet = Console.isQuietMode()
    if (batchMode && !wasQuiet) Console.enableQuietMode()
    try {
      val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
      val discovery = WaypointDiscovery.discover(root)
      reportLoadFailures(discovery.rootFailures)
      val defs = discovery.definitions
      if (defs.isEmpty()) {
        val suffix = if (discovery.packLoadFailed) {
          " (some packs failed to load — see warnings above)"
        } else {
          ""
        }
        Console.error("No waypoint definitions found in active packs or under ${root.absolutePath}.$suffix")
        maybeWarnNoTarget(rootOverride, targetId, resultIsEmpty = true)
        return CommandLine.ExitCode.USAGE
      }
      val target = when (val r = resolveTargetTemplateContext(targetId = targetId)) {
        is TargetContextResolution.Error -> {
          Console.error(r.message)
          return CommandLine.ExitCode.USAGE
        }
        is TargetContextResolution.Resolved -> r.context
        is TargetContextResolution.NoTarget -> null
      }

      return if (batchMode) {
        runBatch(defs, target)
      } else {
        runSingleStep(defs, target)
      }
    } finally {
      if (batchMode && !wasQuiet) Console.disableQuietMode()
    }
  }

  /**
   * Validates the option combinations a user can express. Returns null when all is well,
   * or a USAGE exit code after writing a clear stderr message. Run up-front so users
   * get the error before pack-discovery I/O.
   */
  private fun validateArgs(): Int? {
    if (file != null && session != null) {
      Console.error("--file and --session are mutually exclusive. Pass one.")
      return CommandLine.ExitCode.USAGE
    }
    if (file == null && session == null) {
      Console.error("Provide either --file or --session.")
      return CommandLine.ExitCode.USAGE
    }
    if (relBase != null) {
      if (!isBatchMode()) {
        Console.error("--rel-base is only valid in batch mode (--session without --step). Got --file or --step.")
        return CommandLine.ExitCode.USAGE
      }
      val r = relBase!!
      if (!r.exists() || !r.isDirectory) {
        Console.error("--rel-base must be an existing directory: ${r.absolutePath}")
        return CommandLine.ExitCode.USAGE
      }
    }
    if (logSuffix != null) {
      if (!isBatchMode()) {
        Console.error("--log-suffix is only valid in batch mode (--session without --step).")
        return CommandLine.ExitCode.USAGE
      }
      // An empty suffix would make `endsWith("")` true for every log file — a no-op
      // filter that silently looks like it's doing something. Reject explicitly so a
      // user passing `--log-suffix ""` (perhaps thinking it disables filtering) sees
      // the misuse immediately rather than getting unfiltered results back.
      if (logSuffix!!.isEmpty()) {
        Console.error("--log-suffix must be non-empty. Omit the flag entirely to disable filtering.")
        return CommandLine.ExitCode.USAGE
      }
    }
    return null
  }

  /**
   * Batch mode triggers when `--session` is supplied without `--step` (and `--file` is absent).
   * Emits one TSV row per matched waypoint (or a single `NONE` / `ERROR` row) per step, so
   * the entire session can be processed inside a single JVM invocation — the perf prereq
   * captured in 2026-05-18-waypoint-pack-maintenance-loop-plan.md phase (4). The wrapper-side
   * `xargs -n1 -P8` over individual steps was bottlenecked by JVM cold-start (~7.9s/step);
   * iterating in-process collapses that to per-step matcher work.
   */
  private fun isBatchMode(): Boolean = file == null && session != null && step == null

  private fun runSingleStep(defs: List<WaypointDefinition>, target: TargetTemplateContext?): Int {
    val logFile = resolveLogFile() ?: return CommandLine.ExitCode.USAGE
    val screen = SessionLogScreenState.loadStep(logFile)
    Console.log("Locating against ${defs.size} waypoint(s); screen state: ${logFile.name}")
    val results = defs.map { WaypointMatcher.match(it, screen, target) }
    val matched = results.filter { it.matched }
    if (matched.isEmpty()) {
      Console.log("  no waypoints match this screen.")
    } else {
      for (r in matched) Console.log("  MATCH ${r.definitionId}")
    }
    val nearMisses = results.filter { !it.matched && it.skipped == null && it.missingRequired.size <= 1 && it.presentForbidden.isEmpty() }
    if (nearMisses.isNotEmpty()) {
      Console.log("")
      Console.log("Near-misses (failed by exactly one required entry):")
      for (r in nearMisses) {
        Console.log("  ~ ${r.definitionId}")
        for (miss in r.missingRequired) {
          val descr = miss.entry.description ?: miss.entry.selector.description()
          Console.log("      missing: $descr (got ${miss.matchCount} matches, need ${miss.entry.minCount})")
        }
      }
    }
    val skipped = results.filter { it.skipped != null }
    if (skipped.isNotEmpty()) {
      Console.log("")
      Console.log("Skipped (no trailblazeNodeTree in screen state):")
      for (r in skipped) Console.log("  · ${r.definitionId}")
    }
    return CommandLine.ExitCode.OK
  }

  /**
   * Walks every screen-state log file in the session directory, runs the matcher in-process,
   * and emits one TSV row per outcome:
   *   - `<path>\t<waypoint-id>` for every matched waypoint (multiple rows per step OK)
   *   - `<path>\tNONE`          when no waypoints match (or every waypoint skipped because
   *                              the step has no `trailblazeNodeTree`)
   *   - `<path>\tERROR`         when the step file fails to load or process
   *
   * Exit code: `SOFTWARE` only when *every* step in the session errored — partial failures
   * stay `OK` and the pipeline shard surfaces them via ERROR rows. A single-step session
   * with that one step failing also returns SOFTWARE (it's a 100%-failure case).
   *
   * Path format: by default relative to the session dir's parent — yields
   * `<session-name>/<step-filename>`, matching the `${log#${workdir}/logs/}` slicing the
   * pipeline shard script does for its per-step output. `--rel-base <dir>` overrides; a
   * log file that's not actually beneath the base falls through to its absolute path so
   * callers don't see a confusing `../../...` rel.
   *
   * Diagnostic chatter (the "Locating N step(s)…" banner and per-error stderr) goes
   * through `Console.error` so stdout stays strictly TSV and is safe to pipe into
   * `cat`/`sort`/`awk` without filtering. Console is also in quiet mode for the duration
   * (set by `call()` above) so pack-loader chatter from discovery can't leak in.
   */
  private fun runBatch(defs: List<WaypointDefinition>, target: TargetTemplateContext?): Int {
    val sessionDir = validateSessionDir(session!!) ?: return CommandLine.ExitCode.USAGE
    val allLogs = SessionLogScreenState.listScreenStateLogs(sessionDir)
    // `--log-suffix` filter (set by the pipeline shard to pin AgentDriverLog-only accounting,
    // since the pre-batch flow walked AgentDriverLog files exclusively). Unset → no filter
    // and the framework default (every screen-state log type) is used.
    val logs = logSuffix?.let { suffix -> allLogs.filter { it.name.endsWith(suffix) } } ?: allLogs
    if (logs.isEmpty()) {
      val detail = if (logSuffix != null) {
        "No log files matching --log-suffix '$logSuffix' in: ${sessionDir.absolutePath}"
      } else {
        "No screen-state log files found in: ${sessionDir.absolutePath}"
      }
      Console.error(detail)
      return CommandLine.ExitCode.USAGE
    }
    val baseDir = relBase ?: sessionDir.parentFile ?: sessionDir
    Console.error("Locating ${logs.size} step(s) against ${defs.size} waypoint(s) in ${sessionDir.absolutePath}")
    var errorCount = 0
    for (logFile in logs) {
      val relPath = relativizePath(baseDir, logFile)
      val results = try {
        val screen = SessionLogScreenState.loadStep(logFile)
        defs.map { WaypointMatcher.match(it, screen, target) }
      } catch (e: Exception) {
        errorCount++
        Console.error("ERROR loading ${logFile.absolutePath}: ${e.message}")
        emitTsvRow(relPath, "ERROR")
        continue
      }
      val matched = results.filter { it.matched }
      if (matched.isEmpty()) {
        // Note: this collapses true "no match" and "all skipped (no trailblazeNodeTree)"
        // into the same NONE row. Pipeline coverage accounting treats both as
        // "screen didn't carry a recognized waypoint" — the distinction matters for
        // human debugging (use --step N to see SKIPPED detail) but not for shard math.
        emitTsvRow(relPath, "NONE")
      } else {
        for (r in matched) {
          emitTsvRow(relPath, r.definitionId)
        }
      }
    }
    // Surface non-zero exit only when EVERY step failed — partial failures should still
    // produce a usable shard. Previous version checked `logs.size == 1`, which silently
    // greenlit fully-failed multi-step sessions.
    return if (errorCount == logs.size) CommandLine.ExitCode.SOFTWARE else CommandLine.ExitCode.OK
  }

  /**
   * TSV rows are `<path>\t<status>`. Tab/newline in the rel-path corrupt the format for any
   * consumer doing line-oriented parsing (`cat`/`awk`/`sort`/loop-while-read). Truly
   * malicious paths are unlikely (session dirs are framework-emitted) but a misconfigured
   * `--rel-base` could in theory yield odd relative segments. Sanitize by escaping the
   * standard whitespace characters; falling back to a synthetic placeholder if the path
   * is itself unprintable would mask the underlying bug, so the C-style escape preserves
   * traceability while keeping the line shape sane.
   */
  internal fun emitTsvRow(path: String, status: String) {
    println("${escapeTsvField(path)}\t$status")
  }

  /**
   * C-style escapes for `\\` / `\t` / `\n` / `\r`. The backslash-first ordering preserves
   * round-trippability — a literal `\\t` pair in the input becomes `\\\\t` (two-char escape
   * for the backslash, then the literal `t`), distinct from a real tab's `\\t` escape.
   * `internal` for direct test coverage; the production caller is `emitTsvRow`.
   */
  internal fun escapeTsvField(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

  private fun relativizePath(baseDir: File, logFile: File): String {
    val basePath = baseDir.absoluteFile.toPath().normalize()
    val logPath = logFile.absoluteFile.toPath().normalize()
    return try {
      val rel = basePath.relativize(logPath).toString()
      // Guard against logs that don't sit beneath baseDir: relativize emits `../...` in that
      // case, which is a confusing rel-path. Fall back to the absolute path in that scenario.
      if (rel.startsWith("..")) logFile.absolutePath else rel
    } catch (_: IllegalArgumentException) {
      // Reachable on Windows when paths sit on different roots (`C:` vs `D:`); on POSIX
      // both inputs are absolute so relativize doesn't throw. Either way, abs path is the
      // safe fallback.
      logFile.absolutePath
    }
  }

  private fun resolveLogFile(): File? {
    file?.let { return validateLogFile(it, label = "--file") }
    // validateArgs() guarantees session != null when we reach this branch (--file XOR --session).
    val validated = validateSessionDir(session!!) ?: return null
    val logs = SessionLogScreenState.listLlmRequestLogs(validated)
    if (logs.isEmpty()) {
      Console.error("No *_TrailblazeLlmRequestLog.json files found in: ${validated.absolutePath}")
      return null
    }
    val idx = step?.let { it - 1 } ?: (logs.size - 1)
    if (idx !in logs.indices) {
      Console.error("--step out of range: 1..${logs.size}")
      return null
    }
    return logs[idx]
  }
}
