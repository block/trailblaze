package xyz.block.trailblaze.report

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * One report invocation hands the same argv to two commands that declare different flags, so the
 * split in [ReportCliArgs] is a real contract: a flag the receiving command doesn't declare aborts
 * the whole run rather than being ignored.
 */
class ReportCliArgsTest {

  /**
   * The full argv shape CI passes (the report step in its
   * upload-images-as-artifacts mode, plus the deferred-mode `--triage`).
   */
  private val ciArgs = arrayOf(
    "/logs",
    "--use-relative-image-urls",
    "--link-images",
    "--triage",
  )

  @Test
  fun `test-results command only ever receives options it declares`() {
    val declared = GenerateTestResultsCliCommand().registeredOptions()
      .flatMap { it.names }
      .toSet() + setOf("--help")

    val passedThrough = ReportCliArgs.forTestResults(ciArgs).filter { it.startsWith("--") }

    val undeclared = passedThrough.filterNot { it in declared }
    assertTrue(
      undeclared.isEmpty(),
      "These options reach the test-results command but it doesn't declare them, so it will exit " +
        "with \"no such option\": $undeclared. Add them to ReportCliArgs.",
    )
  }

  @Test
  fun `html-report-only flags are stripped for test results`() {
    assertEquals(
      listOf("/logs", "--triage"),
      ReportCliArgs.forTestResults(ciArgs).toList(),
    )
  }

  @Test
  fun `html report keeps its own flags and loses the test-results-only ones`() {
    assertEquals(
      listOf("/logs", "--use-relative-image-urls", "--link-images"),
      ReportCliArgs.forHtmlReport(ciArgs).toList(),
    )
  }

  @Test
  fun `retired dedup flag reaches neither command`() {
    val args = arrayOf("/logs", "--dedup")
    assertEquals(listOf("/logs"), ReportCliArgs.forHtmlReport(args).toList())
    assertEquals(listOf("/logs"), ReportCliArgs.forTestResults(args).toList())
  }

  @Test
  fun `unrecognized args pass through to both commands untouched`() {
    // ReportCliArgs routes the flags it knows about; anything else is the receiving command's
    // business (a subclass may declare it), so neither side may drop it.
    val args = arrayOf("/logs", "--some-downstream-flag", "value")
    assertEquals(args.toList(), ReportCliArgs.forHtmlReport(args).toList())
    assertEquals(args.toList(), ReportCliArgs.forTestResults(args).toList())
  }
}
