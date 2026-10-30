package xyz.block.trailblaze.ui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RunSessionIdTest {

  private val logsDir = Files.createTempDirectory("run-session-id").toFile()

  @Test
  fun `a delegated run logs under the session id its caller pinned`() {
    assertEquals("loop_ab12cd34", runSessionId("loop_ab12cd34", "loop", logsDir).value)
  }

  @Test
  fun `a request retried with a finished run's session id gets a session of its own`() {
    logsDir.resolve("loop_ab12cd34").mkdirs()

    val id = runSessionId("loop_ab12cd34", "loop", logsDir).value

    assertNotEquals("loop_ab12cd34", id)
    assertTrue(id.startsWith("loop_"))
  }
}
