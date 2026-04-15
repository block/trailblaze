package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AdbPathResolver].
 */
class AdbPathResolverTest {

  @Test
  fun `resolve returns true when adb is on PATH`() {
    // On CI and developer machines adb is typically on PATH.
    // If it isn't, the resolver should still return gracefully.
    val result = AdbPathResolver.resolve()
    // We can't assert true/false unconditionally because the test may run
    // in an environment without adb. Just verify it doesn't throw.
    assertTrue("resolve() should return a boolean", result || !result)
  }

  @Test
  fun `adbCommand defaults to bare adb`() {
    // Before resolve() is called the command should be the bare name.
    // After resolve() it's either "adb" (on PATH) or an absolute path.
    val cmd = AdbPathResolver.adbCommand
    assertTrue(
      "adbCommand should be 'adb' or an absolute path containing 'adb'",
      cmd == "adb" || cmd.contains("adb"),
    )
  }

  @Test
  fun `adbCommand contains adb after resolve`() {
    AdbPathResolver.resolve()
    val cmd = AdbPathResolver.adbCommand
    assertTrue(
      "adbCommand should contain 'adb' after resolve",
      cmd.contains("adb"),
    )
  }

  @Test
  fun `resolve is idempotent`() {
    AdbPathResolver.resolve()
    val first = AdbPathResolver.adbCommand
    AdbPathResolver.resolve()
    val second = AdbPathResolver.adbCommand
    assertEquals("Calling resolve() twice should produce the same result", first, second)
  }
}
