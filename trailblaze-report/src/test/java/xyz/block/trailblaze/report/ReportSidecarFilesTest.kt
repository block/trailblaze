package xyz.block.trailblaze.report

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportSidecarFilesTest {
  @Test
  fun `recognizes the events file a linked report writes and its in-progress temp file`() {
    assertTrue(ReportSidecarFiles.isSidecar("report-events-0123456789abcdef.js"))
    assertTrue(ReportSidecarFiles.isSidecar("report-events-0123456789abcdef.js.4242.tmp"))
  }

  @Test
  fun `recognizes any casing, which names the same file on macOS and Windows`() {
    listOf(
      "report-events-0123456789abcdef.JS",
      "report-events-0123456789ABCDEF.js",
      "Report-Events-0123456789abcdef.js",
      "REPORT-EVENTS-0123456789ABCDEF.JS.4242.TMP",
    ).forEach { assertTrue(ReportSidecarFiles.isSidecar(it), it) }
  }

  @Test
  fun `leaves every other session file alone`() {
    listOf(
      "report-events.js",
      "report-events-0123456789abcde.js",
      "report-events-0123456789abcdefg.js",
      "my-report-events-0123456789abcdef.js",
      "0001.json",
      "events.ndjson",
    ).forEach { assertFalse(ReportSidecarFiles.isSidecar(it), it) }
  }
}
