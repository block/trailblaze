package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.BunBinaryResolver

/**
 * Covers the interactive-report wiring added to [GenerateReportCliCommand.run] — the
 * copy-to-canonical-filename + cleanup glue around [RunReportGenerator], which is the CI-facing
 * counterpart to the CLI/daemon `trailblaze report` path already covered by
 * [xyz.block.trailblaze.report.RunReportGeneratorTest]. Skips (vacuous pass) when `bun` isn't
 * resolvable, matching that sibling test's pattern — CI always has bun (a hard build
 * prerequisite), so a bun-less local checkout doesn't fail here either.
 */
class GenerateReportCliCommandTest {

  @Test
  fun `run generates the interactive report at the canonical filename and cleans up the timestamped original`() {
    if (BunBinaryResolver.resolveBunBinary() == null) return
    val rootDir = Files.createTempDirectory("generate-report-cli-command-test").toFile()
    try {
      // WasmReport.generate() (called unconditionally by run(), ahead of the interactive report)
      // falls back to building from raw WASM UI project files when no template is found — which
      // requires a real trailblaze-ui checkout and throws otherwise. A template file (even a
      // trivial one) routes it through the template-substitution path instead, which only needs
      // the file to be readable.
      File(rootDir, "trailblaze_report_template.html").writeText(
        "<html><script>window.trailblaze_report_compressed = {};</script></html>",
      )

      val logsDir = File(rootDir, "logs").apply { mkdirs() }
      val sessionId = SessionId("cli_command_test_session")
      val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
      val started = Instant.parse("2026-06-26T12:00:00Z")
      File(sessionDir, "001_TrailblazeSessionStatusChangeLog.json").writeText(
        TrailblazeJsonInstance.encodeToString<TrailblazeLog>(
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1_000),
            session = sessionId,
            timestamp = started,
          ),
        ),
      )

      // parseArgs/run are `protected` (inherited from SimpleCliCommand); main() is the public
      // entry point that calls both in sequence, same as the real CLI dispatch path.
      GenerateReportCliCommand().main(arrayOf(logsDir.absolutePath))

      val interactiveReport = File(logsDir, "trailblaze_report_interactive.html")
      assertTrue(
        interactiveReport.exists() && interactiveReport.length() > 0,
        "run() should produce a non-empty trailblaze_report_interactive.html in the logs dir",
      )
      assertTrue(
        interactiveReport.readText().contains("RUN_REPORT_VIEWER"),
        "should embed the standalone viewer, matching RunReportGenerator's own contract",
      )

      // RunReportGenerator.generate() writes its output under logs/reports/ with a timestamped
      // name; run() copies it to the canonical filename above and deletes the original so a
      // repeated run() call doesn't accumulate timestamped duplicates.
      val timestampedOriginals = File(logsDir, "reports").listFiles { f ->
        f.name.startsWith("trailblaze_report_interactive_") && f.name.endsWith(".html")
      }
      assertTrue(
        timestampedOriginals.isNullOrEmpty(),
        "the timestamped original should have been deleted after being copied to the canonical filename, found: ${timestampedOriginals?.map { it.name }}",
      )
    } finally {
      rootDir.deleteRecursively()
    }
  }
}
