package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import java.io.File
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
    val args = buildList {
      add("--write")
      paths.forEach { add("--sessions"); add(it.absolutePath) }
      surveyNames.forEach { add("--survey"); add(it) }
      format?.let { add("--format"); add(it) }
      out?.let {
        add("--out"); add((if (it.isAbsolute) it else File(cwd, it.path)).absolutePath)
        // The surveys CLI prints a summary when it writes a file; this command prints markdown
        // unless asked otherwise, whichever way the report is also saved.
        if (format == null) { add("--format"); add("markdown") }
      }
    }
    return SurveyCli.run(args, cwd)
  }

  /** Same reasoning as `strings extract`: path-only, never `appProvider()`. */
  private fun resolveLogsDir(): File = logsDirOverride ?: try {
    parent.configProvider().logsDir
  } catch (_: UninitializedPropertyAccessException) {
    File("./logs")
  }

  internal companion object {
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
