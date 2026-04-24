package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.messageContains
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

class StderrCaptureTest {

  private val tmpDir = Files.createTempDirectory("stderr-capture-test").toFile()

  @AfterTest fun cleanup() {
    tmpDir.deleteRecursively()
  }

  @Test fun `tail snapshot retains most recent N lines`() {
    val capture = StderrCapture(tailLines = 3)
    listOf("a", "b", "c", "d", "e").forEach { capture.accept(it) }
    assertThat(capture.tailSnapshot()).containsExactly("c", "d", "e")
  }

  @Test fun `tail snapshot is safe to call before any accept`() {
    val capture = StderrCapture(tailLines = 8)
    assertThat(capture.tailSnapshot()).hasSize(0)
  }

  @Test fun `writes lines into the log file on disk`() {
    val logFile = File(tmpDir, "session-123/subprocess_stderr.log")
    val capture = StderrCapture(logFile = logFile)
    capture.accept("boot complete")
    capture.accept("tool registered: myapp_login")
    capture.close()

    assertThat(logFile.exists()).isEqualTo(true)
    val body = logFile.readText()
    assertThat(body).contains("boot complete")
    assertThat(body).contains("tool registered: myapp_login")
  }

  @Test fun `silently tolerates a log-file path that cannot be opened`() {
    // /dev/null/cannot-exist is guaranteed-not-writable on POSIX; the capture should keep working.
    val logFile = File("/dev/null/cannot-exist/subprocess_stderr.log")
    val capture = StderrCapture(logFile = logFile)
    capture.accept("still captured in memory")
    assertThat(capture.tailSnapshot()).containsExactly("still captured in memory")
  }

  @Test fun `non-positive tailLines is rejected with a clear message`() {
    assertFailure { StderrCapture(tailLines = 0) }.messageContains("must be positive")
    assertFailure { StderrCapture(tailLines = -3) }.messageContains("got -3")
  }
}
