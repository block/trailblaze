package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Pins the observable contract of [SingleReadLogsRepoProvider]: the ordering the shared capture
 * hands the report, and the memoization that is the whole reason it exists.
 */
class SingleReadLogsRepoProviderTest {

  @Test
  fun `shared snapshots keep the same descending-name session order as getSessionIds`() {
    val logsDir = Files.createTempDirectory("single-read-logs-repo-test-").toFile()
    try {
      // Seeded out of order so a provider that just echoed listFiles() would fail here.
      listOf("session-a", "session-c", "session-b").forEach { File(logsDir, it).mkdirs() }

      val provider = SingleReadLogsRepoProvider()
      val capturedOrder = provider.snapshots(logsDir).map { it.sessionId }

      // The report embeds sessions in capture order, so the shared capture must deliver the
      // exact ordering the pre-snapshot path got from LogsRepo.getSessionIds.
      assertEquals(provider.get(logsDir).getSessionIds(), capturedOrder)
      assertEquals(
        listOf(SessionId("session-c"), SessionId("session-b"), SessionId("session-a")),
        capturedOrder,
      )
      provider.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `the same logs dir is captured once and served to every caller`() {
    val logsDir = Files.createTempDirectory("single-read-logs-repo-memoize-test-").toFile()
    try {
      File(logsDir, "session-a").mkdirs()
      val provider = SingleReadLogsRepoProvider()

      // Two commands run over the same argv in one process; re-parsing the directory for the
      // second is the cost this provider exists to remove. Identity, not equality: a repo that
      // merely compares equal would still mean a second read of every log file.
      val first = provider.get(logsDir)
      assertSame(first, provider.get(logsDir))
      assertSame(provider.snapshots(logsDir), provider.snapshots(logsDir))
      // Canonicalized, so a differently-spelled path to the same directory hits the same entry.
      assertSame(first, provider.get(File(logsDir, "session-a/..")))

      // close() drops the memo rather than leaving it holding repos whose file watchers are
      // already shut down, so a provider reused afterwards captures again.
      provider.close()
      assertNotSame(first, provider.get(logsDir))
      provider.close()
    } finally {
      logsDir.deleteRecursively()
    }
  }
}
