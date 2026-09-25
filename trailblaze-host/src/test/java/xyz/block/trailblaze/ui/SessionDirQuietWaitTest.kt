package xyz.block.trailblaze.ui

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Pins how long `handleCliRunRequest` waits for a pinned session's trailing files after `Ended`.
 *
 * Time is a fake clock advanced only by the wait's own sleeps, so each test reads as "how long did
 * the wait hold the CLI" without real sleeping. [writesAt] schedules file writes at fake times, the
 * way an on-device runner's uploads trail its end status.
 */
class SessionDirQuietWaitTest {

  private val sessionDir: File = Files.createTempDirectory("session-quiet-wait").toFile()

  @AfterTest fun cleanup() {
    sessionDir.deleteRecursively()
  }

  @Test
  fun `a session whose files are all written returns after the quiet window, not the full cap`() {
    File(sessionDir, "019_TrailblazeSessionStatusChangeLog.json").writeText("{}")

    assertEquals(SESSION_TRAILING_FILES_QUIET_MS, heldForMs())
  }

  @Test
  fun `a file landing inside the window restarts it`() {
    val heldFor = heldForMs(writesAt = mapOf(300L to "screenshot.webp"))

    // The upload at 300ms is still arriving, so the window runs a full quiet period past it.
    assertEquals(300L + SESSION_TRAILING_FILES_QUIET_MS, heldFor)
    assertEquals(true, File(sessionDir, "screenshot.webp").isFile)
  }

  @Test
  fun `a growing file counts as still being written`() {
    File(sessionDir, "device.log").writeText("a")
    val heldFor = heldForMs(appendsAt = mapOf(400L to "device.log"))

    assertEquals(400L + SESSION_TRAILING_FILES_QUIET_MS, heldFor)
  }

  @Test
  fun `a directory that never settles is released at the cap`() {
    val everyPoll = (100L..10_000L step 100L).associateWith { "upload-$it.json" }

    assertEquals(SESSION_TRAILING_FILES_MAX_WAIT_MS, heldForMs(writesAt = everyPoll))
  }

  private fun heldForMs(
    writesAt: Map<Long, String> = emptyMap(),
    appendsAt: Map<Long, String> = emptyMap(),
  ): Long {
    var now = 0L
    runBlocking {
      awaitSessionDirQuiet(
        sessionDir = sessionDir,
        nowMs = { now },
        sleep = { ms ->
          now += ms
          writesAt[now]?.let { File(sessionDir, it).writeText(it) }
          appendsAt[now]?.let { File(sessionDir, it).appendText("more") }
        },
      )
    }
    return now
  }
}
