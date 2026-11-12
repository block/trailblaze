package xyz.block.trailblaze.util

import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavior of [TrailblazeProcessBuilderUtils.isCommandAvailable]: the lookup's exit code decides
 * the answer, and a lookup that hangs is cut off at the timeout and reported as absent, so a slow
 * PATH search can't hold up whoever asked.
 */
class CommandAvailabilityTest {

  private val windows = isWindows()

  private fun exitWith(code: Int): List<String> =
    if (windows) listOf("cmd", "/c", "exit $code") else listOf("sh", "-c", "exit $code")

  private fun sleepFor(seconds: Int): List<String> =
    if (windows) {
      listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds $seconds")
    } else {
      listOf("sleep", "$seconds")
    }

  @Test
  fun `a lookup that succeeds reports the command as available`() {
    assertTrue(TrailblazeProcessBuilderUtils.isCommandAvailable("tool", exitWith(0), timeoutMs = 10_000))
  }

  @Test
  fun `a lookup that fails reports the command as absent`() {
    assertFalse(TrailblazeProcessBuilderUtils.isCommandAvailable("tool", exitWith(1), timeoutMs = 10_000))
  }

  @Test
  fun `a lookup that hangs is reported absent once the timeout passes`() {
    val (available, elapsed) = measureTimedValue {
      TrailblazeProcessBuilderUtils.isCommandAvailable("tool", sleepFor(30), timeoutMs = 300)
    }
    assertFalse(available)
    assertTrue("returned after $elapsed, not near the 300ms timeout", elapsed < 10.seconds)
  }

  @Test
  fun `a command that is not on the PATH is absent`() {
    assertFalse(TrailblazeProcessBuilderUtils.isCommandAvailable("trailblaze-no-such-command-7f3a"))
  }
}
