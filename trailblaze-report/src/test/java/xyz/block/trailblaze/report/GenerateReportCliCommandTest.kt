package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.io.FileAlreadyExistsException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.BunBinaryResolver

/**
 * Covers the report wiring in [GenerateReportCliCommand.run] — the copy-to-canonical-filename +
 * cleanup glue around [RunReportGenerator], which is the CI-facing counterpart to the CLI/daemon
 * `trailblaze report` path already covered by
 * [xyz.block.trailblaze.report.RunReportGeneratorTest]. Skips (vacuous pass) when `bun` isn't
 * resolvable, matching that sibling test's pattern — CI always has bun (a hard build
 * prerequisite), so a bun-less local checkout doesn't fail here either.
 */
class GenerateReportCliCommandTest {

  @Test
  fun `run generates the report at the canonical filename and cleans up the timestamped original`() {
    if (BunBinaryResolver.resolveBunBinary() == null) return
    val rootDir = Files.createTempDirectory("generate-report-cli-command-test").toFile()
    try {
      val logsDir = seedOneSucceededSession(rootDir, SessionId("cli_command_test_session"))

      // parseArgs/run are `protected` (inherited from SimpleCliCommand); main() is the public
      // entry point that calls both in sequence, same as the real CLI dispatch path.
      GenerateReportCliCommand().main(arrayOf(logsDir.absolutePath))

      val report = File(logsDir, "trailblaze_report_interactive.html")
      assertTrue(
        report.exists() && report.length() > 0,
        "run() should produce a non-empty trailblaze_report_interactive.html in the logs dir",
      )
      assertTrue(
        report.readText().contains("RUN_REPORT_VIEWER"),
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

  @Test
  fun `a failure to land the canonical filename propagates instead of being swallowed`() {
    if (BunBinaryResolver.resolveBunBinary() == null) return
    val rootDir = Files.createTempDirectory("generate-report-canonical-copy-test").toFile()
    try {
      val logsDir = seedOneSucceededSession(rootDir, SessionId("canonical_copy_test_session"))
      // A non-empty directory squatting on the canonical filename makes the copy fail:
      // copyTo(overwrite = true) cannot delete a non-empty directory. Generation itself is
      // best-effort (caught and warned), but landing the artifact is not — the copy sits outside
      // that catch precisely so this surfaces rather than leaving a silently report-less run.
      File(File(logsDir, "trailblaze_report_interactive.html"), "occupant.txt").apply {
        parentFile.mkdirs()
        writeText("occupied")
      }

      assertFailsWith<FileAlreadyExistsException> {
        GenerateReportCliCommand().main(arrayOf(logsDir.absolutePath))
      }
    } finally {
      rootDir.deleteRecursively()
    }
  }

  private fun seedOneSucceededSession(rootDir: File, sessionId: SessionId): File {
    val logsDir = File(rootDir, "logs").apply { mkdirs() }
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    File(sessionDir, "001_TrailblazeSessionStatusChangeLog.json").writeText(
      TrailblazeJsonInstance.encodeToString<TrailblazeLog>(
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1_000),
          session = sessionId,
          timestamp = Instant.parse("2026-06-26T12:00:00Z"),
        ),
      ),
    )
    return logsDir
  }
}
