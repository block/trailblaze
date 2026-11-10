package xyz.block.trailblaze.scripting

import java.io.File
import xyz.block.trailblaze.cli.CliCallerContext
import xyz.block.trailblaze.cli.SurveyCli
import xyz.block.trailblaze.cli.TrailblazeExitCode
import xyz.block.trailblaze.surveys.SurveyBundles
import xyz.block.trailblaze.util.Console
import kotlin.time.Duration.Companion.minutes

/**
 * Runs a workspace's warning surveys (`warning: true`) over the sessions a report is built from, and
 * stores their findings in each session's `surveys.json`, so the report shows each run's warnings
 * beside its verdict. They run when the report is built, not when the run ends: a run on a device
 * farm has no host to run them then, only the session it leaves on disk.
 *
 * A warning never changes the verdict, so nothing here fails the report. A workspace with no warning
 * survey costs only a read of its survey sources; a source it cannot read, surveys that fail, or no
 * `bun` is logged, and one whose surveys are still running after [TIMEOUT] is stopped, so the report is built.
 */
object RunWarningSurveys {

  /** Long past what warning surveys over a report's sessions take; only a survey that never settles reaches it. */
  val TIMEOUT = 10.minutes

  /**
   * Runs the warning surveys of [configDir] over [sessionDirs] through [runCli] (the surveys CLI,
   * given its arguments, from the caller's directory, which finds the same workspace). Returns the
   * surveys it ran for: the warning surveys, plus any whose spec the source does not show, and, by
   * file name, survey files whose exports it cannot read; empty when there were none, and [runCli]
   * was not called. [streamsReported] says a streams pass over these sessions already reported every
   * derived stream that failed, so this one leaves them out; without it, they are this pass's to report.
   */
  fun run(
    sessionDirs: List<File>,
    configDir: File?,
    streamsReported: Boolean = false,
    runCli: (args: List<String>) -> Int = { args ->
      SurveyCli.run(args, CliCallerContext.callerCwd().toAbsolutePath().toFile(), timeout = TIMEOUT)
    },
  ): List<String> {
    val sessions = sessionDirs.filter { it.isDirectory }
    if (configDir == null || sessions.isEmpty()) return emptyList()
    val files = WorkspaceSurveyBundleSource.surveyFiles(configDir)
      .mapNotNull { file ->
        runCatching { file to file.readText() }.getOrElse { e ->
          // Said here: the surveys CLI loads every survey in the workspace and stops on this one, and a
          // workspace whose only survey this is would otherwise look like one with none. On the error
          // channel, which a quiet `trailblaze run` still shows.
          Console.error(
            "[warnings] could not read ${file.path}: ${e.message ?: e.javaClass.simpleName}. " +
              "No warning survey runs until it can be read.",
          )
          null
        }
      }
    val candidates = files.flatMap { (_, text) -> warningSurveyCandidates(text) }
    // A re-export or a survey built by a helper: only the surveys CLI, which loads the file, sees it.
    val unread = files.filter { (_, text) -> SurveyBundles.hasUnreadExports(text) }.map { (file, _) -> file.name }
    if (candidates.isEmpty() && unread.isEmpty()) return emptyList()
    val names = candidates.map { it.name } + unread
    Console.log("[warnings] ${names.joinToString(", ")} over ${sessions.size} session(s)")
    // A workspace whose unread exports hold no warning survey has nothing to run, which is no failure.
    val args = listOf("--write", "--warnings", "--allow-none", "--quiet", "--format", "none") +
      // A failed stream leaves its readers short of rows; said once, by whichever pass derived it first.
      (if (streamsReported) listOf("--allow-stream-errors") else emptyList()) +
      sessions.flatMap { listOf("--sessions", it.absolutePath) }
    val code = runCatching { runCli(args) }.getOrElse { e ->
      Console.error("[warnings] could not run the warning surveys: ${e.message ?: e.javaClass.simpleName}")
      return names
    }
    if (code != TrailblazeExitCode.SUCCESS.code) {
      Console.error("[warnings] the warning surveys did not all run (exit $code); the report shows the rest, and no verdict changes")
    }
    return names
  }

  /**
   * The surveys that may be warning surveys: those whose spec says `warning: true`, and those whose
   * `warning` the source does not show (a spec built elsewhere, a spread, a computed value), so only
   * the surveys CLI, which loads it, can tell.
   */
  private fun warningSurveyCandidates(text: String): List<SurveyBundles.ExportedSurvey> =
    runCatching { SurveyBundles.exportedSurveys(text) }.getOrDefault(emptyList())
      .filter { it.warning || !it.warningInSource }
}
