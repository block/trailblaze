package xyz.block.trailblaze.cli

import picocli.CommandLine
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import xyz.block.trailblaze.util.fileUrl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `trailblaze report --link-images`: the report links each session's images instead of embedding
 * them, and every link has to land on the session directory it names from wherever the HTML is
 * written. The tests resolve the links the way a browser does, against the report's own location.
 */
class ReportCommandLinkImagesTest {

  private val root = createTempDirectory("report-link-images").toFile()
  private val logsDir = File(root, "logs").apply { mkdirs() }

  @AfterTest
  fun cleanup() {
    root.deleteRecursively()
  }

  @Test
  fun `images are embedded unless --link-images is passed`() {
    assertFalse(parse().linkImages)
    assertTrue(parse("--link-images").linkImages)
  }

  @Test
  fun `a report in the default reports directory reaches the session directories`() {
    assertLinkReachesSession(reportDir = File(logsDir, "reports"))
  }

  @Test
  fun `a report written into the logs directory itself reaches the session directories`() {
    assertLinkReachesSession(reportDir = logsDir)
  }

  @Test
  fun `a report moved to an --output-dir elsewhere still reaches the session directories`() {
    assertLinkReachesSession(reportDir = File(root, "out/nested"))
  }

  @Test
  fun `the links are relative so the report and logs can be hosted together`() {
    assertFalse(URI(linkedImageBaseUrl(logsDir.toPath(), File(logsDir, "reports").toPath()) + "s/a.png").isAbsolute)
  }

  @Test
  fun `a report in a symlinked --output-dir links relative to the path the browser opens`() {
    val target = File(root, "elsewhere/out").apply { mkdirs() }
    val linked = File(root, "link-out")
    Files.createSymbolicLink(linked.toPath(), target.toPath())
    assertLinkReachesSession(reportDir = linked)
  }

  @Test
  fun `a symlinked reports directory inside the logs directory still links back to the logs`() {
    val target = File(root, "elsewhere/deeper/still/reports").apply { mkdirs() }
    Files.createSymbolicLink(File(logsDir, "reports").toPath(), target.toPath())
    assertLinkReachesSession(reportDir = File(logsDir, "reports"))
  }

  @Test
  fun `a directory name with spaces or URL-reserved characters still resolves to the file`() {
    val oddLogs = File(root, "my logs #1 100% ?a=b&c+d'e").apply { mkdirs() }
    assertLinkReachesSession(logsDir = oddLogs, reportDir = File(root, "out dir/50%/#frag"))
    assertLinkReachesSession(logsDir = oddLogs, reportDir = File(oddLogs, "reports"))
  }

  @Test
  fun `the prefix percent-encodes each segment and keeps the separators`() {
    assertEquals("../my%20logs/%231/", linkedImageBaseUrl(Paths.get("/a/my logs/#1"), Paths.get("/a/r")))
    assertEquals("../../x%3Fy/100%25/", linkedImageBaseUrl(Paths.get("/a/x?y/100%"), Paths.get("/a/b/c")))
    assertEquals("../caf%C3%A9/", linkedImageBaseUrl(Paths.get("/a/caf\u00e9"), Paths.get("/a/r")))
  }

  @Test
  fun `the prefix carries no character the viewer refuses or an attribute could end on`() {
    val prefix = linkedImageBaseUrl(Paths.get("/a/q\"u'o<t>e`s\\ \t"), Paths.get("/a/r"))
    assertEquals("../q%22u%27o%3Ct%3Ee%60s%5C%20%09/", prefix)
  }

  @Test
  fun `paths with no relative route between them give no prefix so the caller embeds the images`() {
    // The same failure a logs directory on C: and an --output-dir on D: hits on Windows.
    assertNull(linkedImageBaseUrl(Paths.get("/logs"), Paths.get("out")))
  }

  private fun assertLinkReachesSession(logsDir: File = this.logsDir, reportDir: File) {
    val shot = File(logsDir, "session_1/shot.png").apply {
      parentFile.mkdirs()
      writeText("png")
    }
    val link = linkedImageBaseUrl(logsDir.toPath(), reportDir.toPath()) + "session_1/shot.png"
    val resolved = URI(File(reportDir, "report.html").fileUrl()).resolve(link)
    assertEquals(URI(shot.fileUrl()).path, resolved.path)
    assertEquals(shot.canonicalFile, File(resolved).canonicalFile)
  }

  private fun parse(vararg args: String): ReportCommand =
    ReportCommand().also { CommandLine(it).parseArgs(*args) }
}
