package xyz.block.trailblaze.events

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class FileEventSinkLossTest {

  private val sessionDir: File = Files.createTempDirectory("sink-loss").toFile()

  @AfterTest
  fun cleanUp() {
    SessionCaptureHealth.clear(sessionDir, "memory")
    SessionCaptureHealth.clear(sessionDir, "network")
    sessionDir.deleteRecursively()
  }

  @Test
  fun `a record the sink can't write marks the session's capture incomplete`() {
    // A file where the events folder goes: the stream can never be opened.
    File(sessionDir, SessionEvents.DIR_NAME).writeText("")

    FileEventSink(sessionDir, logLabel = "memory-capture").use { it.append("memory", 1L, JsonPrimitive(1)) }

    val failure = assertNotNull(SessionCaptureHealth.failure(sessionDir, "memory"))
    assertTrue(failure.startsWith("memory-capture: "), failure)
  }

  @Test
  fun `a record with an embedded newline marks the capture incomplete`() {
    FileEventSink(sessionDir).use { it.appendRaw("memory", "{\"a\":\n1}") }

    assertNotNull(SessionCaptureHealth.failure(sessionDir, "memory"))
  }

  @Test
  fun `a checked write that fails marks the capture incomplete as well as throwing`() {
    File(sessionDir, SessionEvents.DIR_NAME).writeText("")

    FileEventSink(sessionDir, logLabel = "network-capture").use { sink ->
      assertFailsWith<Exception> { sink.appendChecked("network", 1L, JsonPrimitive(1)) }
    }

    val failure = assertNotNull(SessionCaptureHealth.failure(sessionDir, "network"))
    assertTrue(failure.startsWith("network-capture: "), failure)
    assertNull(SessionCaptureHealth.failure(sessionDir, "memory"), "only the stream that lost the record is incomplete")
  }

  @Test
  fun `starting over in a reused directory forgets every stream's loss`() {
    File(sessionDir, SessionEvents.DIR_NAME).writeText("")
    FileEventSink(sessionDir).use { it.append("memory", 1L, JsonPrimitive(1)) }
    assertNotNull(SessionCaptureHealth.failure(sessionDir, "memory"))

    SessionCaptureHealth.clear(sessionDir)

    assertNull(SessionCaptureHealth.failure(sessionDir, "memory"))
    assertEquals(emptyList(), sessionDir.list()!!.filter { it.startsWith("capture-incomplete") })
  }

  @Test
  fun `records the sink writes leave the capture complete`() {
    FileEventSink(sessionDir).use { it.append("memory", 1L, JsonPrimitive(1)) }

    assertNull(SessionCaptureHealth.failure(sessionDir, "memory"))
    assertEquals(1, File(sessionDir, "${SessionEvents.DIR_NAME}/${SessionEvents.fileName("memory")}").readLines().size)
  }
}
