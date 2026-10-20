package xyz.block.trailblaze.util

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProcessTimeoutTreeTest {

  /**
   * A wrapper (a `.bat` through cmd.exe, or a shell script) whose child does the real work. Killing
   * only the wrapper on timeout leaves the child running. On Windows, where killing a process does
   * not close its pipes, the child also holds the output open, so the timeout never returns.
   */
  @Test
  fun `a timeout also stops the wrapper's children`() = runBlocking {
    assumeFalse("needs POSIX sh", isWindows())
    val processBuilder = TrailblazeProcessBuilderUtils.createProcessBuilder(
      listOf("sh", "-c", "sleep 600 & printf '%s\\n' \$!; wait"),
    )
    val (result, timedOut) = with(TrailblazeProcessBuilderUtils) {
      processBuilder.runProcessWithTimeout(timeoutSeconds = 1)
    }
    assertTrue(timedOut)
    val child = assertNotNull(result.outputLines.firstOrNull()?.trim()?.toLongOrNull(), "the wrapper never printed its child")
    val handle = ProcessHandle.of(child)
    try {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      while (handle.map { it.isAlive }.orElse(false) && System.nanoTime() < deadline) Thread.sleep(50)
      assertFalse(handle.map { it.isAlive }.orElse(false), "child $child outlived the timeout")
    } finally {
      handle.ifPresent { it.destroyForcibly() }
    }
  }
}
