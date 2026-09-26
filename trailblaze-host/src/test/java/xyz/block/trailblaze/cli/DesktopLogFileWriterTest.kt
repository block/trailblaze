package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A caller that filters the process's output on its way to disk sets
 * `TRAILBLAZE_DISABLE_DESKTOP_LOG_FILE`, and an unfiltered copy in the log dir would defeat it.
 */
class DesktopLogFileWriterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val marker = "desktop-log-marker-line"

  private fun installAndPrint(disableFlag: String?): File {
    val logDir = File(tempFolder.root, "desktop-logs")
    val originalOut = System.out
    val originalErr = System.err
    try {
      DesktopLogFileWriter.install(disableFlag = disableFlag, logDir = logDir)
      System.out.println(marker)
      System.err.println(marker)
    } finally {
      System.setOut(originalOut)
      System.setErr(originalErr)
    }
    return logDir
  }

  private fun File.logText(): String = listFiles().orEmpty().joinToString("\n") { it.readText() }

  @Test
  fun `1 and true write nothing to the log dir, case-insensitively`() {
    for (flag in listOf("1", "true", "TRUE")) {
      val logDir = installAndPrint(flag)
      assertFalse(logDir.logText().contains(marker), "flag=$flag")
      logDir.deleteRecursively()
    }
  }

  @Test
  fun `unset and other values tee output to the log file`() {
    for (flag in listOf(null, "0", "")) {
      val logDir = installAndPrint(flag)
      assertTrue(logDir.logText().contains(marker), "flag=$flag")
      logDir.deleteRecursively()
    }
  }
}
