package xyz.block.trailblaze.ui.tabs.sessions

import org.junit.Test
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionImporterTest {

  // region determineStripSegments tests

  @Test
  fun `determineStripSegments returns empty for empty input`() {
    val result = SessionImporter.determineStripSegments(emptyList())
    assertEquals(emptyList(), result)
  }

  @Test
  fun `determineStripSegments returns empty for direct sessionId layout`() {
    // sessionId/file.json — no prefix to strip
    val pathParts = listOf(
      listOf("abc-session-1", "log.json"),
      listOf("abc-session-1", "screenshot.png"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(emptyList(), result)
  }

  @Test
  fun `determineStripSegments strips logs prefix`() {
    // logs/sessionId/file.json
    val pathParts = listOf(
      listOf("logs", "abc-session-1", "log.json"),
      listOf("logs", "abc-session-1", "screenshot.png"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(listOf("logs"), result)
  }

  @Test
  fun `determineStripSegments strips wrapper folder`() {
    // wrapper/sessionId/file.json — common wrapper folder with enough depth
    val pathParts = listOf(
      listOf("my-download", "abc-session-1", "log.json"),
      listOf("my-download", "abc-session-1", "screenshot.png"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(listOf("my-download"), result)
  }

  @Test
  fun `determineStripSegments strips both wrapper and logs prefix`() {
    // wrapper/logs/sessionId/file.json
    val pathParts = listOf(
      listOf("artifact-download", "logs", "abc-session-1", "log.json"),
      listOf("artifact-download", "logs", "abc-session-1", "screenshot.png"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(listOf("artifact-download", "logs"), result)
  }

  @Test
  fun `determineStripSegments does not strip when files are at different top-level dirs`() {
    // Different top-level folders — not a common wrapper
    val pathParts = listOf(
      listOf("folder-a", "session-1", "log.json"),
      listOf("folder-b", "session-2", "log.json"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(emptyList(), result)
  }

  @Test
  fun `determineStripSegments does not strip wrapper when depth is too shallow`() {
    // wrapper/file.json — only 2 parts, need >= 3 for wrapper detection
    val pathParts = listOf(
      listOf("wrapper", "log.json"),
    )
    val result = SessionImporter.determineStripSegments(pathParts)
    assertEquals(emptyList(), result)
  }

  // endregion

  // region collectSessionIds tests

  @Test
  fun `collectSessionIds extracts IDs from direct layout`() {
    val pathParts = listOf(
      listOf("session-1", "log.json"),
      listOf("session-1", "screenshot.png"),
      listOf("session-2", "log.json"),
    )
    val ids = SessionImporter.collectSessionIds(pathParts, emptyList())
    assertEquals(setOf("session-1", "session-2"), ids)
  }

  @Test
  fun `collectSessionIds extracts IDs after stripping logs prefix`() {
    val pathParts = listOf(
      listOf("logs", "session-1", "log.json"),
      listOf("logs", "session-2", "log.json"),
    )
    val ids = SessionImporter.collectSessionIds(pathParts, listOf("logs"))
    assertEquals(setOf("session-1", "session-2"), ids)
  }

  @Test
  fun `collectSessionIds extracts IDs after stripping wrapper and logs`() {
    val pathParts = listOf(
      listOf("artifact", "logs", "session-1", "log.json"),
      listOf("artifact", "logs", "session-2", "log.json"),
    )
    val ids = SessionImporter.collectSessionIds(pathParts, listOf("artifact", "logs"))
    assertEquals(setOf("session-1", "session-2"), ids)
  }

  @Test
  fun `collectSessionIds returns empty for empty input`() {
    val ids = SessionImporter.collectSessionIds(emptyList(), emptyList())
    assertTrue(ids.isEmpty())
  }

  // endregion

  // region normalizePathParts tests

  @Test
  fun `normalizePathParts returns original when no segments to strip`() {
    val parts = listOf("session-1", "log.json")
    val result = SessionImporter.normalizePathParts(parts, emptyList())
    assertEquals(parts, result)
  }

  @Test
  fun `normalizePathParts strips matching prefix segments`() {
    val parts = listOf("logs", "session-1", "log.json")
    val result = SessionImporter.normalizePathParts(parts, listOf("logs"))
    assertEquals(listOf("session-1", "log.json"), result)
  }

  @Test
  fun `normalizePathParts strips multiple prefix segments in order`() {
    val parts = listOf("wrapper", "logs", "session-1", "log.json")
    val result = SessionImporter.normalizePathParts(parts, listOf("wrapper", "logs"))
    assertEquals(listOf("session-1", "log.json"), result)
  }

  @Test
  fun `normalizePathParts does not strip when prefix does not match`() {
    val parts = listOf("other", "session-1", "log.json")
    val result = SessionImporter.normalizePathParts(parts, listOf("logs"))
    assertEquals(parts, result)
  }

  // endregion

  // region importSessionFromZip integration tests

  @Test
  fun `importSessionFromZip handles direct sessionId layout`() {
    val tempDir = createTempDir("import-test")
    try {
      val zipFile = createTestZip(
        tempDir,
        mapOf(
          "abc-session/log.json" to """{"type":"session"}""",
          "abc-session/step.json" to """{"type":"step"}""",
        )
      )
      val logsDir = File(tempDir, "logs").also { it.mkdirs() }
      val logsRepo = LogsRepo(logsDir)

      val result = SessionImporter.importSessionFromZip(zipFile, logsRepo)

      assertIs<SessionImportResult.Success>(result)
      assertEquals("abc-session", result.sessionId)
      assertEquals(2, result.fileCount)
      assertTrue(File(logsDir, "abc-session/log.json").exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `importSessionFromZip handles logs prefix layout`() {
    val tempDir = createTempDir("import-test")
    try {
      val zipFile = createTestZip(
        tempDir,
        mapOf(
          "logs/abc-session/log.json" to """{"type":"session"}""",
        )
      )
      val logsDir = File(tempDir, "logs-output").also { it.mkdirs() }
      val logsRepo = LogsRepo(logsDir)

      val result = SessionImporter.importSessionFromZip(zipFile, logsRepo)

      assertIs<SessionImportResult.Success>(result)
      assertEquals("abc-session", result.sessionId)
      assertTrue(File(logsDir, "abc-session/log.json").exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `importSessionFromZip handles wrapper plus logs prefix layout`() {
    val tempDir = createTempDir("import-test")
    try {
      val zipFile = createTestZip(
        tempDir,
        mapOf(
          "artifact-download/logs/abc-session/log.json" to """{"type":"session"}""",
          "artifact-download/logs/abc-session/step.json" to """{"type":"step"}""",
        )
      )
      val logsDir = File(tempDir, "logs-output").also { it.mkdirs() }
      val logsRepo = LogsRepo(logsDir)

      val result = SessionImporter.importSessionFromZip(zipFile, logsRepo)

      assertIs<SessionImportResult.Success>(result)
      assertEquals("abc-session", result.sessionId)
      assertEquals(2, result.fileCount)
      assertTrue(File(logsDir, "abc-session/log.json").exists())
      assertTrue(File(logsDir, "abc-session/step.json").exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `importSessionFromZip rejects zip with path traversal`() {
    val tempDir = createTempDir("import-test")
    try {
      val zipFile = createTestZip(
        tempDir,
        mapOf(
          // Attempt path traversal
          "abc-session/../../../etc/evil.json" to """{"evil":true}""",
          "abc-session/log.json" to """{"type":"session"}""",
        )
      )
      val logsDir = File(tempDir, "logs-output").also { it.mkdirs() }
      val logsRepo = LogsRepo(logsDir)

      val result = SessionImporter.importSessionFromZip(zipFile, logsRepo)

      // Should succeed but only import the safe file
      assertIs<SessionImportResult.Success>(result)
      assertEquals(1, result.fileCount)
      assertTrue(File(logsDir, "abc-session/log.json").exists())
      // The traversal entry should NOT have been written outside logsDir
      assertTrue(!File(tempDir, "etc/evil.json").exists())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  // endregion

  // region helpers

  private fun createTempDir(prefix: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
    dir.mkdirs()
    return dir
  }

  private fun createTestZip(parentDir: File, entries: Map<String, String>): File {
    val zipFile = File(parentDir, "test.zip")
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
      entries.forEach { (path, content) ->
        zos.putNextEntry(ZipEntry(path))
        zos.write(content.toByteArray())
        zos.closeEntry()
      }
    }
    return zipFile
  }

  // endregion
}
