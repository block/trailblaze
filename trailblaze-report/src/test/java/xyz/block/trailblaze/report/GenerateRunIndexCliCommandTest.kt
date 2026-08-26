package xyz.block.trailblaze.report

import com.github.ajalt.clikt.core.parse
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.models.CiRunMetadata
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SOURCE_TYPE_HANDWRITTEN
import xyz.block.trailblaze.report.models.SessionResult

/**
 * Argument handling for `generate-run-index`, specifically the one mistake that is otherwise
 * invisible: a viewer URL the viewer will refuse.
 */
class GenerateRunIndexCliCommandTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val resultsFile: File by lazy {
    val report = CiSummaryReport(
      metadata = CiRunMetadata(target_app = "square", ci_build_number = "9224"),
      results = listOf(
        SessionResult(
          session_id = SessionId("checkout-pay-android-phone"),
          title = "checkout/pay",
          test_key = "checkout/pay",
          platform = "android",
          outcome = Outcome.PASSED,
          execution_mode = ExecutionMode.RECORDING_ONLY,
          trail_source = SOURCE_TYPE_HANDWRITTEN,
          device_classifier = "android-phone",
          duration_ms = 35_400,
          logs_zip_url = "https://cdn.example/runs/s1.zip",
        ),
      ),
    )
    tempFolder.newFile("test_report.json").apply { writeText(Json.encodeToString(report)) }
  }

  private fun generate(vararg args: String) =
    GenerateRunIndexCliCommand().parse(arrayOf(resultsFile.absolutePath, *args))

  @Test
  fun `a relative viewer URL is refused instead of producing an index of dead cells`() {
    // `viewer.html` is the obvious value to pass when the shell `trailblaze viewer` writes is
    // hosted beside the index. The viewer resolves reportUrl through a strict http(s) check, so
    // this would emit an index whose every cell is inert — and nothing would say so until someone
    // opened the artifact and clicked a cell.
    val message = try {
      generate("--viewer-base-url", "viewer.html", "--output", File(tempFolder.root, "out.html").absolutePath)
      fail("expected a relative --viewer-base-url to be rejected")
    } catch (e: IllegalArgumentException) {
      e.message.orEmpty()
    }

    assertTrue(message.contains("absolute http(s) URL"), "error should name the requirement, got: $message")
    assertTrue(message.contains("viewer.html"), "error should quote what was passed, got: $message")
  }

  @Test
  fun `a non-http scheme is refused the same way`() {
    val output = File(tempFolder.root, "scheme.html")
    for (url in listOf("file:///tmp/viewer.html", "javascript:alert(1)", "ftp://example.com/v.html")) {
      try {
        generate("--viewer-base-url", url, "--output", output.absolutePath)
        fail("expected $url to be rejected")
      } catch (e: IllegalArgumentException) {
        assertTrue(e.message.orEmpty().contains("absolute http(s) URL"), "unexpected message for $url: ${e.message}")
      }
    }
  }

  @Test
  fun `omitting the viewer URL stays legal and writes an index`() {
    // An index with no links still reports every outcome; that is the documented behavior, so it
    // must not be swept up by the validation above.
    val output = File(tempFolder.newFolder("no-viewer"), "index.html")

    generate("--output", output.absolutePath)

    assertTrue(output.isFile, "expected an index at ${output.absolutePath}")
    assertTrue(output.readText().contains("linkOut"), "rows should still be marked as stubs")
  }
}
