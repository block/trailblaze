package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import picocli.CommandLine

/**
 * `trailblaze survey` and `trailblaze report --surveys` over fixture sessions in a fixture
 * workspace: the workspace's surveys run, each session stores its findings and labels, and the
 * report written beside an HTML report lists labels, the findings behind them, and their runs.
 *
 * Needs bun, which runs the surveys; skipped without it, like [ScriptingSurveysSubpathTest].
 */
class SurveyCommandTest {

  private val root: File = createTempDirectory("trailblaze-survey-command-test").toFile()
  private val logsDir = File(root, "logs")
  private val workspace = File(root, "workspace")

  @AfterTest fun cleanup() {
    root.deleteRecursively()
  }

  @Test
  fun `session arguments resolve as paths first, then as ids or prefixes in the logs directory`() {
    val checkout = writeSession("2026_10_27_10_00_checkout", "Checkout", listOf("take-payment"))
    writeSession("2026_10_27_11_00_refund", "Refund", listOf("refund"))
    val zip = File(root, "elsewhere.zip").apply { writeText("") }

    assertEquals(listOf(logsDir), SurveyCommand.resolveSessionPaths(emptyList(), root, logsDir))
    assertEquals(listOf(checkout), SurveyCommand.resolveSessionPaths(listOf("2026_10_27_10"), root, logsDir))
    assertEquals(listOf(zip), SurveyCommand.resolveSessionPaths(listOf("elsewhere.zip"), root, logsDir))
    assertNull(SurveyCommand.resolveSessionPaths(listOf("2026_10_27"), root, logsDir), "an ambiguous prefix names no session")
    assertNull(SurveyCommand.resolveSessionPaths(listOf("nope"), root, logsDir))
  }

  @Test
  fun `survey runs the workspace's surveys and stores findings and labels in each session`() {
    assumeBun()
    writeWorkspace()
    val checkout = writeSession("run-checkout", "Checkout with a card", listOf("take-payment"))
    val refund = writeSession("run-refund", "Refund a sale", listOf("take-payment", "refund"))
    val out = File(root, "report.json")

    val exit = CliCallerContext.withCallerCwd(workspace.toPath()) {
      commandLine().execute("survey", "--logs-dir", logsDir.path, "--survey", "jobs", "-o", out.path, "run-checkout", "run-refund")
    }

    assertEquals(0, exit)
    val stored = File(refund, "surveys.json").readText()
    // `jobs` asked for `journeys` through `dependsOn`, so both are stored.
    assertTrue("\"jobs\"" in stored && "\"journeys\"" in stored, stored)
    assertTrue("\"name\": \"take-payment\"" in stored, stored)
    assertTrue(File(checkout, "surveys.json").isFile)
    assertTrue("\"labels\"" in out.readText())
  }

  @Test
  fun `a relative logs directory is the caller's, and an unknown survey is misuse`() {
    assumeBun()
    writeWorkspace()
    val checkout = writeSession("run-checkout", "Checkout with a card", listOf("take-payment"))

    fun survey(vararg args: String) = CliCallerContext.withCallerCwd(workspace.toPath()) {
      commandLine().execute("survey", "--logs-dir", "../logs", "--format", "none", *args)
    }

    // `../logs` from the workspace, not from wherever the JVM started.
    assertEquals(TrailblazeExitCode.SUCCESS.code, survey("run-checkout"))
    assertTrue(File(checkout, "surveys.json").isFile)
    assertEquals(TrailblazeExitCode.MISUSE.code, survey("--survey", "nosuchsurvey", "run-checkout"))
  }

  @Test
  fun `report --surveys writes the labels, their findings, and their runs beside the report`() {
    assumeBun()
    writeWorkspace()
    val checkout = writeSession("run-checkout", "Checkout with a card", listOf("take-payment"))
    val refund = writeSession("run-refund", "Refund a sale", listOf("take-payment", "refund"))
    val markdown = File(root, "surveys.md")
    val json = File(root, "surveys.json")

    val exit = CliCallerContext.withCallerCwd(workspace.toPath()) {
      writeSurveyReport(listOf(checkout, refund), names = emptyList(), markdown = markdown, json = json)
    }

    assertEquals(0, exit)
    val text = markdown.readText()
    assertTrue("### job: take-payment\n\nFrom **jobs**, 2 runs." in text, text)
    assertTrue("- run-refund — Refund a sale\n  - **journeys** — Completed take-payment" in text, text)
    assertTrue(json.isFile)
    assertTrue(File(refund, "surveys.json").isFile)
  }

  @Test
  fun `report --surveys that fails leaves no earlier run's results beside the report`() {
    assumeBun()
    writeWorkspace()
    val checkout = writeSession("run-checkout", "Checkout with a card", listOf("take-payment"))
    val markdown = File(root, "surveys.md").apply { writeText("# from an earlier run") }
    val json = File(root, "surveys.json").apply { writeText("{\"from\": \"an earlier run\"}") }

    val exit = CliCallerContext.withCallerCwd(workspace.toPath()) {
      writeSurveyReport(listOf(checkout), names = listOf("nosuchsurvey"), markdown = markdown, json = json)
    }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertTrue(!json.exists(), "the earlier run's surveys.json is gone")
    assertTrue(!markdown.exists() || "earlier run" !in markdown.readText())
  }

  @Test
  fun `report --surveys refuses to write over a session's stored results, and leaves them`() {
    assumeBun()
    writeWorkspace()
    val checkout = writeSession("run-checkout", "Checkout with a card", listOf("take-payment"))
    val stored = File(checkout, "surveys.json").apply { writeText("{\"format\": 1, \"surveys\": {}}") }

    val exit = CliCallerContext.withCallerCwd(workspace.toPath()) {
      writeSurveyReport(listOf(checkout), names = emptyList(), markdown = File(checkout, "surveys.md"), json = stored)
    }

    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertEquals("{\"format\": 1, \"surveys\": {}}", stored.readText())
  }

  @Test
  fun `report --surveys parses bare, with names, and is off by default`() {
    fun surveysOf(vararg args: String): List<String>? {
      val parsed = commandLine().parseArgs("report", *args)
      return (parsed.subcommand().commandSpec().userObject() as ReportCommand).surveys
    }
    assertNull(surveysOf("--id", "x"))
    assertEquals(emptyList(), surveysOf("--id", "x", "--surveys"), "bare means every survey")
    assertEquals(listOf("journeys", "jobs"), surveysOf("--id", "x", "--surveys=journeys,jobs"))
  }

  private fun assumeBun() = assumeTrue(
    "bun is not on PATH — surveys run under bun. Source bin/activate-hermit for the pinned bun.",
    CliPathUtils.isCommandOnPath("bun"),
  )

  private fun commandLine() = CommandLine(
    TrailblazeCliCommand(
      appProvider = { error("appProvider must not be invoked") },
      configProvider = { error("configProvider must not be invoked when --logs-dir is given") },
    ),
  )

  /** A workspace with a journeys survey that labels each journey, and a label-only jobs survey that depends on it. */
  private fun writeWorkspace() {
    val config = File(workspace, "trailblaze-config").apply { mkdirs() }
    File(config, "trailblaze.yaml").writeText("trailmaps: []\n")
    val surveys = File(config, "surveys").apply { mkdirs() }
    File(surveys, "journeys.survey.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting/surveys";
      export const journeys = trailblaze.survey({
        description: "Journeys the run completed",
        find: (session) => session.analytics.filter({ name: "Journey Complete" }).map((e) => ({
          summary: "Completed " + String(e.properties.journey),
          evidence: [e],
          data: { journey: String(e.properties.journey) },
        })),
        label: (batch) => batch.groupBy((f) => f.data.journey).map(({ key, findings }) => ({ type: "journey", name: key, findings })),
      });
      """.trimIndent() + "\n",
    )
    File(surveys, "jobs.survey.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting/surveys";
      import { journeys } from "./journeys.survey.ts";
      export const jobs = trailblaze.survey({
        description: "Jobs the runs did",
        dependsOn: { journeys },
        label: ({ dependsOn }) => dependsOn.journeys.labels
          .filter((l) => l.name === "take-payment")
          .map((l) => ({ type: "job", name: "take-payment", findings: l.findings })),
      });
      """.trimIndent() + "\n",
    )
  }

  /** A finished run whose analytics stream logged one `Journey Complete` per journey given. */
  private fun writeSession(id: String, title: String, journeys: List<String>): File {
    val dir = File(logsDir, id).apply { File(this, "events").mkdirs() }
    val log = "xyz.block.trailblaze.logs.client.TrailblazeLog"
    val status = "xyz.block.trailblaze.logs.model.SessionStatus"
    val t0 = 1_700_000_000_000L
    fun statusLog(ms: Long, body: String) =
      """{"class":"$log.TrailblazeSessionStatusChangeLog","session":"$id","timestamp":"${java.time.Instant.ofEpochMilli(ms)}","sessionStatus":$body}"""
    File(dir, "000_TrailblazeSessionStatusChangeLog.json").writeText(
      statusLog(
        t0,
        """{"class":"$status.Started","trailConfig":{"id":"trail-$id","title":"$title","platform":"ANDROID"},""" +
          """"trailblazeDeviceInfo":{"platform":"ANDROID","classifiers":["android"]}}""",
      ),
    )
    File(dir, "001_TrailblazeSessionStatusChangeLog.json").writeText(
      statusLog(t0 + 1000, """{"class":"$status.Ended.Succeeded","durationMs":1000}"""),
    )
    File(dir, "events/app.analytics.ndjson").writeText(
      journeys.mapIndexed { i, name ->
        """{"timeMs":${t0 + 100 + i},"data":{"name":"Journey Complete","properties":{"journey":"$name"}}}"""
      }.joinToString("\n") + "\n",
    )
    return dir
  }
}
