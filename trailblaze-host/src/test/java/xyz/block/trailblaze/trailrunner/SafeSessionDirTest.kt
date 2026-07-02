package xyz.block.trailblaze.trailrunner

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SafeSessionDirTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun logsDir(): File = tmp.newFolder("logs")

  @Test
  fun `resolves a real session directory under the logs root`() {
    val logs = logsDir()
    val session = File(logs, "2026_01_01_session_1").apply { mkdirs() }
    val resolved = resolveSafeSessionDir(logs, "2026_01_01_session_1")
    assertEquals(session.canonicalPath, resolved?.canonicalPath)
  }

  @Test
  fun `rejects parent-traversal id`() {
    val logs = logsDir()
    File(tmp.root, "secret").apply { mkdirs() }
    assertNull(resolveSafeSessionDir(logs, "../secret"))
  }

  @Test
  fun `rejects deep traversal id`() {
    val logs = logsDir()
    assertNull(resolveSafeSessionDir(logs, "../../../../../../tmp"))
  }

  @Test
  fun `rejects forward-slash separators`() {
    val logs = logsDir()
    File(logs, "a/b").apply { mkdirs() }
    assertNull(resolveSafeSessionDir(logs, "a/b"))
  }

  @Test
  fun `rejects backslash separators`() {
    val logs = logsDir()
    assertNull(resolveSafeSessionDir(logs, "a\\b"))
  }

  @Test
  fun `rejects control characters`() {
    val logs = logsDir()
    assertNull(resolveSafeSessionDir(logs, "sess\u0000ion"))
    assertNull(resolveSafeSessionDir(logs, "sess\nion"))
  }

  @Test
  fun `rejects empty id`() {
    assertNull(resolveSafeSessionDir(logsDir(), ""))
  }

  @Test
  fun `rejects a safe id that does not exist`() {
    assertNull(resolveSafeSessionDir(logsDir(), "no_such_session"))
  }

  @Test
  fun `rejects a safe id that resolves to a file rather than a directory`() {
    val logs = logsDir()
    File(logs, "afile.txt").writeText("x")
    assertNull(resolveSafeSessionDir(logs, "afile.txt"))
  }
}
