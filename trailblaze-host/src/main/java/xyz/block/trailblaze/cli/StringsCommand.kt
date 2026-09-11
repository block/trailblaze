package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.report.strings.StepDiff
import xyz.block.trailblaze.report.strings.VisibleStringsDiff
import xyz.block.trailblaze.report.strings.VisibleStringsDiffResult
import xyz.block.trailblaze.report.strings.VisibleStringsLog
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * Read the human-visible text out of recorded sessions.
 *
 * Device-free and daemon-free: the runs already wrote a screenshot and the view tree that produced
 * it, so every subcommand here works on sessions sitting in the logs directory.
 */
@Command(
  name = "strings",
  mixinStandardHelpOptions = true,
  description = ["Extract the human-visible text a recorded session showed, for localization and copy diffs"],
  subcommands = [
    StringsExtractCommand::class,
    StringsDiffCommand::class,
  ],
)
class StringsCommand : Callable<Int> {

  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  /**
   * Injected by picocli so [call] renders the live parsed-tree usage, which carries the full
   * `trailblaze strings` qualifier. A detached `CommandLine(this)` prints a bare `Usage: strings …`
   * that does not match the checked-in help baseline.
   */
  @CommandLine.Spec
  internal lateinit var spec: CommandLine.Model.CommandSpec

  override fun call(): Int {
    spec.commandLine().usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
  }
}

/**
 * Examples:
 *   trailblaze strings extract                 - every session in the logs directory
 *   trailblaze strings extract 2026_09_09_17   - one session, by id or unambiguous prefix
 *   trailblaze strings extract abc --all       - keep repeated screens instead of collapsing them
 */
@Command(
  name = "extract",
  mixinStandardHelpOptions = true,
  description = [
    "Write visible-strings.ndjson into each session's directory. One line per screen capture, " +
      "keyed by the screenshot filename so every string traces back to its image.",
  ],
)
class StringsExtractCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: StringsCommand

  @Parameters(
    index = "0",
    arity = "0..1",
    paramLabel = "<session-id>",
    description = ["Session ID or unambiguous prefix. Defaults to every session in the logs directory."],
  )
  var sessionId: String? = null

  @Option(
    names = ["--all"],
    description = [
      "Keep the strings on every capture. By default a capture that repeats the previous " +
        "screen's text is recorded as a repeat with its strings omitted.",
    ],
  )
  var all: Boolean = false

  @Option(
    names = ["--logs-dir"],
    paramLabel = "<path>",
    description = ["Directory to read sessions from. Defaults to the configured logs directory."],
  )
  var logsDirOverride: File? = null

  override fun call(): Int {
    val logsRepo = LogsRepo(resolveLogsDir(), watchFileSystem = false, primeSessionCache = false)
    val allIds = logsRepo.getSessionIds()
    val requested = sessionId

    if (allIds.isEmpty()) {
      // Naming a session that cannot be there is bad input. Sweeping a directory that holds
      // nothing is not: a CI step that runs this after every job would fail on its first run.
      requested?.let {
        Console.error("Error: No session matching '$it' found.")
        return TrailblazeExitCode.MISUSE.code
      }
      Console.info("No sessions found in ${logsRepo.logsDir.absolutePath}.")
      return TrailblazeExitCode.SUCCESS.code
    }

    val targets = requested?.let { SessionIdResolver.resolve(allIds, it) ?: return TrailblazeExitCode.MISUSE.code }
      ?: allIds

    var written = 0
    for (id in targets) {
      val file = VisibleStringsLog.write(logsRepo, id, collapseRepeats = !all)
      if (file == null) {
        Console.info("${id.value}: no screen captures with a view hierarchy — skipped.")
      } else {
        written++
        Console.info("file://${file.absolutePath}")
      }
    }

    // Only a named session that produced nothing is a failure. Across a whole directory, sessions
    // without a view hierarchy are ordinary — a status-only session has none — and failing the
    // sweep on them would turn a routine CI step red for doing exactly what it was asked.
    if (written == 0 && requested != null) {
      Console.error("Error: '$requested' has no capture to read. Nothing written.")
      return TrailblazeExitCode.INFRA_FAILED.code
    }
    return TrailblazeExitCode.SUCCESS.code
  }

  /**
   * Path-only, and never `appProvider()`: this command reads finished sessions off disk, so
   * booting the desktop app would make an invalid workspace trailmap abort a read that does not
   * depend on one. `watchFileSystem = false` for the same reason `waypoint validate` uses it — a
   * watching [LogsRepo] spawns non-daemon threads that outlive a one-shot CLI. `primeSessionCache
   * = false` because the loop below reads one session at a time and never revisits one, so priming
   * would parse and retain every session in the directory before it even knows which was asked
   * for.
   */
  private fun resolveLogsDir(): File = logsDirOverride ?: try {
    parent.cliRoot.configProvider().logsDir
  } catch (_: UninitializedPropertyAccessException) {
    File("./logs")
  }
}

/**
 * Examples:
 *   trailblaze strings diff en/visible-strings.ndjson es/visible-strings.ndjson
 *   trailblaze strings diff before.ndjson after.ndjson --untranslated
 */
@Command(
  name = "diff",
  mixinStandardHelpOptions = true,
  description = [
    "Compare two visible-strings.ndjson files step by step. Meaningful only for a mechanical " +
      "trail: an LLM-driven objective takes a different path each run, so its step numbers do " +
      "not line up across runs.",
  ],
)
class StringsDiffCommand : Callable<Int> {

  @Parameters(index = "0", paramLabel = "<baseline.ndjson>", description = ["The run to compare against."])
  lateinit var baseline: File

  @Parameters(index = "1", paramLabel = "<candidate.ndjson>", description = ["The run to check."])
  lateinit var candidate: File

  @Option(
    names = ["--untranslated"],
    description = [
      "Report the strings both runs show verbatim instead of the ones that changed. Run the " +
        "same trail at two locales and this is the list of screens nobody translated.",
    ],
  )
  var untranslated: Boolean = false

  override fun call(): Int {
    val unreadable = listOf(baseline, candidate).filterNot { it.isFile }
    if (unreadable.isNotEmpty()) {
      unreadable.forEach {
        Console.error(
          if (it.isDirectory) {
            "Error: ${it.path} is a directory. Point at the ${VisibleStringsLog.FILE_NAME} inside it."
          } else {
            "Error: No such file: ${it.path}"
          },
        )
      }
      return TrailblazeExitCode.MISUSE.code
    }

    // Parsed one file at a time so a malformed line can be blamed on the file it came from.
    val parsed = listOf(baseline, candidate).map { file ->
      try {
        VisibleStringsDiff.parse(file.readText())
      } catch (e: VisibleStringsDiff.MalformedFile) {
        Console.error("Error: ${file.path}: ${e.message}")
        return TrailblazeExitCode.MISUSE.code
      }
    }

    val result = VisibleStringsDiff.diff(parsed[0], parsed[1])
    if (result.steps.isEmpty()) {
      Console.error("Error: The two files share no step numbers, so there is nothing to compare.")
      return TrailblazeExitCode.MISUSE.code
    }

    Console.info(
      "Comparing ${result.baselineLocale ?: "baseline"} → ${result.candidateLocale ?: "candidate"} " +
        "across ${result.steps.size} shared step(s).",
    )
    if (!result.aligned) {
      Console.info(
        "⚠️  The runs took different paths: " +
          "${result.baselineOnlySteps.size} step(s) only in the baseline, " +
          "${result.candidateOnlySteps.size} only in the candidate. Those are skipped.",
      )
    }

    return if (untranslated) reportUntranslated(result) else reportChanges(result)
  }

  /**
   * Exits non-zero when a step shows identical text in both runs, which is the untranslated case.
   *
   * Matched on text alone, not on the text-and-property pair the change report uses: a caption
   * that moved to `contentDescription` between runs is still a string nobody translated.
   */
  private fun reportUntranslated(result: VisibleStringsDiffResult): Int {
    val suspect = result.steps.filter { it.unchangedText.isNotEmpty() }
    // A partial capture truncates one side's list, and this mode reports the overlap — so an
    // untranslated string can go missing here with nothing else on screen to suggest it.
    result.steps.filter { it.inconclusive }.forEach { step ->
      Console.info("⚠️  ${step.partialSides()} at step ${step.stepIndex}; untranslated text there may be missing below.")
    }
    if (suspect.isEmpty()) {
      Console.info("✅ Every step's text differs between the two runs.")
      return TrailblazeExitCode.SUCCESS.code
    }
    suspect.forEach { step ->
      Console.info("\nStep ${step.stepIndex}${step.action?.let { " ($it)" } ?: ""} — ${step.candidateCaptureId}")
      step.unchangedText.forEach { Console.info("  = $it") }
    }
    Console.info("\n${suspect.size} of ${result.steps.size} step(s) show identical text in both runs.")
    return TrailblazeExitCode.ASSERTION_FAILED.code
  }

  /** Exits non-zero when any step's text changed. */
  private fun reportChanges(result: VisibleStringsDiffResult): Int {
    val changed = result.steps.filter { it.changed }
    if (changed.isEmpty()) {
      // "Nothing changed" and "nothing changed in the part we could see" are different claims, and
      // a partial capture only supports the second. Still exits zero: a truncated Android tree is
      // a capture-quality problem, and failing the build on it would make a copy diff unusable as
      // a CI gate for a reason that has nothing to do with copy.
      val partial = result.steps.filter { it.inconclusive }
      if (partial.isNotEmpty()) {
        Console.info(
          "⚠️  No text changed in any shared step, but ${partial.size} of ${result.steps.size} " +
            "step(s) had a partial capture (${partial.joinToString { "step ${it.stepIndex}" }}), " +
            "so text could be missing from both sides. Not a clean result.",
        )
        return TrailblazeExitCode.SUCCESS.code
      }
      Console.info("✅ No text changed in any shared step.")
      return TrailblazeExitCode.SUCCESS.code
    }
    changed.forEach { step ->
      Console.info("\nStep ${step.stepIndex}${step.action?.let { " ($it)" } ?: ""} — ${step.candidateCaptureId}")
      if (step.inconclusive) {
        Console.info("  ⚠️  ${step.partialSides()}, so the lines below may not be real changes.")
      }
      step.removed.forEach { Console.info("  - $it") }
      step.added.forEach { Console.info("  + $it") }
    }
    Console.info("\n${changed.size} of ${result.steps.size} step(s) changed.")
    return TrailblazeExitCode.ASSERTION_FAILED.code
  }

  /** Which run lost part of its tree, since that decides whether to distrust `-` lines or `+` lines. */
  private fun StepDiff.partialSides(): String = when {
    baselinePartial && candidatePartial -> "Both captures are partial"
    baselinePartial -> "The baseline capture is partial"
    else -> "The candidate capture is partial"
  }
}
