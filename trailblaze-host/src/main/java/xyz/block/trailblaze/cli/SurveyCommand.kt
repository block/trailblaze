package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable

/**
 * Runs the workspace's surveys over finished sessions and stores what they found in each session's
 * `surveys.json`: the findings, with the evidence behind them, and the labels the run earned.
 *
 * Device-free and daemon-free, like `strings`: it reads sessions already on disk.
 *
 * Examples:
 *   trailblaze survey                          - every survey over every session in the logs directory
 *   trailblaze survey 2026_10_27_14            - one session, by id or unambiguous prefix
 *   trailblaze survey --survey journeys        - one survey, plus any survey it lists in `dependsOn`
 *   trailblaze survey ./ci-sessions -o r.json  - a directory of sessions or zips, the report also saved
 *   trailblaze survey --survey journeys --update-trails trails/
 *                                              - also add the labels to each run's trail file
 */
@Command(
  name = "survey",
  mixinStandardHelpOptions = true,
  description = [
    "Run surveys over finished sessions, store their findings and labels in each session, and print a report",
    "Surveys load from the workspace you run this in. Each survey's find phase reads every session, " +
      "then label phases run over the batch. A survey that a selected survey lists in `dependsOn` runs " +
      "too, reusing a session's stored findings while they are current.",
  ],
)
class SurveyCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  @Parameters(
    arity = "0..*",
    paramLabel = "<session>",
    description = [
      "Session id or unambiguous prefix, or a path: a session directory, a directory of sessions, " +
        "or a session zip. Defaults to every session in the logs directory.",
    ],
  )
  var sessions: List<String> = emptyList()

  @Option(
    names = ["--survey"],
    paramLabel = "<name>",
    description = ["Only run the survey exported under this name (repeatable)."],
  )
  var surveyNames: List<String> = emptyList()

  @Option(
    names = ["--warnings"],
    description = ["Only run warning surveys (`warning: true`), whose findings mark a run that passed but needs a look."],
  )
  var warningsOnly: Boolean = false

  @Option(
    names = ["--format"],
    paramLabel = "<format>",
    description = ["What to print: markdown (default), summary, json, or none."],
  )
  var format: String? = null

  @Option(
    names = ["-o", "--out"],
    paramLabel = "<file>",
    description = ["Also write the JSON report to this file."],
  )
  var out: File? = null

  @Option(
    names = ["--update-trails"],
    paramLabel = "<path>",
    description = [
      "Also add the labels the selected surveys found to each run's trail, in its generatedLabels: a " +
        "trail file, or a directory searched for trails by config.id (repeatable). Only adds; edits the " +
        "files in place and commits nothing.",
    ],
  )
  var updateTrails: List<File> = emptyList()

  @Option(
    names = ["--logs-dir"],
    paramLabel = "<path>",
    description = ["Directory session ids resolve in. Defaults to the configured logs directory."],
  )
  var logsDirOverride: File? = null

  override fun call(): Int {
    val cwd = CliCallerContext.callerCwd().toAbsolutePath().toFile()
    // Against the caller's directory, not the JVM's: the two differ when the CLI runs through Gradle.
    // Loading settings logs; on stderr, so a JSON report on stdout stays parseable.
    val logsDir = Console.runJsonOutput { resolveLogsDir() }.let { if (it.isAbsolute) it else File(cwd, it.path) }
    val paths = resolveSessionPaths(sessions, cwd, logsDir) ?: return TrailblazeExitCode.MISUSE.code
    val trailRoots = updateTrails.map { if (it.isAbsolute) it else File(cwd, it.path) }
    trailRoots.firstOrNull { !it.exists() }?.let {
      Console.error("Error: --update-trails ${it.path} does not exist.")
      return TrailblazeExitCode.MISUSE.code
    }
    val requested = out?.let { if (it.isAbsolute) it else File(cwd, it.path) }
    // Writing labels reads the report from a file of this run's own, so the surveys CLI never sees
    // -o and can't refuse one that would replace a session's stored results. Refused here instead,
    // before --write stores anything.
    if (trailRoots.isNotEmpty() && requested != null && isStoredResultsFile(requested, paths)) {
      Console.error("Error: --out ${requested.path} is where a session stores its survey results; write the report somewhere else.")
      return TrailblazeExitCode.MISUSE.code
    }
    // A report an earlier run left at -o can never be mistaken for this one's; it is copied there
    // afterwards. Owner-only, since it holds every finding.
    val report = if (trailRoots.isEmpty()) requested else Files.createTempFile("trailblaze-survey-report", ".json").toFile()
    val args = buildList {
      add("--write")
      paths.forEach { add("--sessions"); add(it.absolutePath) }
      surveyNames.forEach { add("--survey"); add(it) }
      if (warningsOnly) add("--warnings")
      format?.let { add("--format"); add(it) }
      report?.let {
        add("--out"); add(it.absolutePath)
        // The surveys CLI prints a summary when it writes a file; this command prints markdown
        // unless asked otherwise, whichever way the report is also saved.
        if (format == null) { add("--format"); add("markdown") }
      }
    }
    try {
      val exit = SurveyCli.run(args, cwd)
      // Misuse labels nothing, even when the CLI wrote a report before rejecting a flag; a CLI
      // that never started wrote none. A failed session (exit 1) still leaves labels worth writing.
      if (trailRoots.isEmpty() || report == null || exit == TrailblazeExitCode.MISUSE.code || report.length() == 0L) return exit
      val parsed = SurveyTrailUpdate.parse(report.readText())
      if (requested != null) {
        // A backstop for a session found inside a directory given, which the check above can't name.
        val clash = parsed.storedResultFiles().firstOrNull { it.canonicalFile == requested.canonicalFile }
        if (clash != null) {
          Console.error("Error: --out ${requested.path} is where a session stores its survey results; write the report somewhere else.")
          return TrailblazeExitCode.MISUSE.code
        }
        report.copyTo(requested, overwrite = true)
      }
      val outcome = SurveyTrailUpdate.apply(parsed, trailRoots)
      // Stderr, like the CLI's progress, so a `--format json` report on stdout stays parseable.
      Console.error(SurveyTrailUpdate.render(outcome, cwd).trimEnd())
      val notWritten = outcome.failed.isNotEmpty() || outcome.ambiguous.isNotEmpty()
      return if (notWritten) chooseWorseExitCode(exit, TrailblazeExitCode.INFRA_FAILED.code) else exit
    } finally {
      if (report != requested) report?.delete()
    }
  }

  /** Same reasoning as `strings extract`: path-only, never `appProvider()`. */
  private fun resolveLogsDir(): File = logsDirOverride ?: try {
    parent.configProvider().logsDir
  } catch (_: UninitializedPropertyAccessException) {
    File("./logs")
  }

  internal companion object {
    /** Whether [file] is the `surveys.json` of a session in, or directly under, one of [sessionPaths]. */
    fun isStoredResultsFile(file: File, sessionPaths: List<File>): Boolean {
      if (file.name != "surveys.json") return false
      val dir = file.absoluteFile.parentFile?.canonicalFile ?: return false
      return sessionPaths.map { it.canonicalFile }.any { it == dir || it == dir.parentFile }
    }

    /**
     * Each argument as the directory or zip the surveys CLI reads: an existing path as given
     * (relative to [cwd]), else a session id or prefix in [logsDir]. No arguments means all of
     * [logsDir]. Null when an argument names nothing; the reason is already printed.
     */
    fun resolveSessionPaths(args: List<String>, cwd: File, logsDir: File): List<File>? {
      if (args.isEmpty()) return listOf(logsDir)
      val ids by lazy { LogsRepo(logsDir, watchFileSystem = false, primeSessionCache = false).getSessionIds() }
      return args.flatMap { arg ->
        val path = File(arg).let { if (it.isAbsolute) it else File(cwd, arg) }
        if (path.exists()) return@flatMap listOf(path)
        if (ids.isEmpty()) {
          Console.error("Error: '$arg' is not a path, and ${logsDir.absolutePath} holds no sessions.")
          return null
        }
        val resolved = SessionIdResolver.resolve(ids, arg) ?: return null
        resolved.map { File(logsDir, it.value) }
      }
    }
  }
}
