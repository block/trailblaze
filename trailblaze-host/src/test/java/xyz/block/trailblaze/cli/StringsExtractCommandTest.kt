package xyz.block.trailblaze.cli

import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the user-facing semantics of `trailblaze strings extract`: which id resolves to which
 * session, and which flavor of "nothing to extract" is a failure.
 *
 * Runs the real command tree with throw-on-invoke `appProvider` / `configProvider`. The command
 * reads finished sessions off disk, so reaching either provider is itself the regression — the
 * lambdas would surface it rather than letting a heavier code path pass silently.
 */
class StringsExtractCommandTest {

  @get:Rule
  val tmp = TemporaryFolder()

  /** A session directory holding one driver log with a screenshot and a one-node tree. */
  private fun session(id: String, text: String = "Checkout"): File =
    File(tmp.root, id).apply {
      mkdirs()
      val log: TrailblazeLog = TrailblazeLog.AgentDriverLog(
        viewHierarchy = null,
        trailblazeNodeTree = TrailblazeNode(
          nodeId = 1,
          driverDetail = DriverNodeDetail.AndroidAccessibility(text = text),
          bounds = TrailblazeNode.Bounds(0, 0, 200, 50),
        ),
        screenshotFile = "shot-a.png",
        action = AgentDriverAction.BackPress,
        durationMs = 10,
        session = SessionId(id),
        timestamp = Instant.parse("2026-09-09T17:04:11Z"),
        deviceHeight = 1920,
        deviceWidth = 1080,
      )
      File(this, "0a1b2c3d_AgentDriverLog.json")
        .writeText(TrailblazeJsonInstance.encodeToString(log))
    }

  private fun outputFile(id: String) = File(File(tmp.root, id), "visible-strings.ndjson")

  @Test
  fun `an exact id wins over a longer session that starts with it`() {
    session("2026_09_09_1704")
    session("2026_09_09_1704_checkout")

    assertEquals(TrailblazeExitCode.SUCCESS.code, extract("2026_09_09_1704"))
    assertTrue(outputFile("2026_09_09_1704").isFile)
    assertFalse(outputFile("2026_09_09_1704_checkout").isFile)
  }

  @Test
  fun `an unambiguous prefix resolves to the one session it names`() {
    session("2026_09_09_1704_checkout")

    assertEquals(TrailblazeExitCode.SUCCESS.code, extract("2026_09_09"))
    assertTrue(outputFile("2026_09_09_1704_checkout").isFile)
  }

  @Test
  fun `a prefix matching two sessions is a misuse, not a guess`() {
    session("2026_09_09_1704_checkout")
    session("2026_09_09_1705_refund")

    assertEquals(TrailblazeExitCode.MISUSE.code, extract("2026_09_09"))
    assertFalse(outputFile("2026_09_09_1704_checkout").isFile)
  }

  @Test
  fun `an id matching nothing is a misuse`() {
    session("2026_09_09_1704_checkout")

    assertEquals(TrailblazeExitCode.MISUSE.code, extract("nope"))
  }

  @Test
  fun `naming a session in an empty logs directory is a misuse, not a silent success`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, extract("2026_09_09"))
  }

  @Test
  fun `sweeping an empty logs directory succeeds, because there was nothing to do`() {
    assertEquals(TrailblazeExitCode.SUCCESS.code, extract())
  }

  @Test
  fun `a sweep does not fail on sessions that carry no capture`() {
    File(tmp.root, "2026_09_09_1704_empty").mkdirs()
    session("2026_09_09_1705_checkout")

    assertEquals(TrailblazeExitCode.SUCCESS.code, extract())
    assertTrue(outputFile("2026_09_09_1705_checkout").isFile)
  }

  @Test
  fun `a named session that carries no capture fails, because it was asked for by name`() {
    File(tmp.root, "2026_09_09_1704_empty").mkdirs()

    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, extract("2026_09_09_1704_empty"))
  }

  private fun extract(vararg args: String): Int {
    val root = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("strings extract reads sessions off disk and must not boot the app") },
        configProvider = { error("--logs-dir was passed, so the configured logs dir must not be read") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)
    installTrailblazeExceptionHandlers(root)
    return root.execute("strings", "extract", *args, "--logs-dir", tmp.root.absolutePath)
  }
}
