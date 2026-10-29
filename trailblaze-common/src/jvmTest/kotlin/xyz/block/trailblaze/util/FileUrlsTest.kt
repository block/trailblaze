package xyz.block.trailblaze.util

import java.io.File
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUrlsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `a file url parses as a URI and names the same file, even with a space and brackets`() {
    val report = File(tempFolder.newFolder("Trailblaze[ci] logs"), "report.html").apply { writeText("") }

    val url = report.fileUrl()

    assertTrue(url.startsWith("file:///"), url)
    assertEquals(report.canonicalFile, File(URI(url)).canonicalFile)
  }
}
