package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the `TRAILBLAZE_DISABLE_DAEMON_AUTOSTART` parse contract. Load-bearing detail: only
 * `"1"`/`"true"` (case-insensitive) disable auto-start — `:trailblaze-server:integrationTest`
 * opts back out of the repo-wide test isolation by setting the variable to `"0"`, which must
 * parse as *enabled* (not merely "set"). A refactor to a null-check would silently flip that.
 */
class DaemonAutoStartFlagTest {

  @Test
  fun `1 and true disable auto-start, case-insensitively`() {
    assertTrue(isDaemonAutoStartDisabled("1"))
    assertTrue(isDaemonAutoStartDisabled("true"))
    assertTrue(isDaemonAutoStartDisabled("TRUE"))
    assertTrue(isDaemonAutoStartDisabled("True"))
  }

  @Test
  fun `unset leaves auto-start on`() {
    assertFalse(isDaemonAutoStartDisabled(null))
  }

  @Test
  fun `the integrationTest opt-out value 0 leaves auto-start on`() {
    assertFalse(isDaemonAutoStartDisabled("0"))
  }

  @Test
  fun `other values leave auto-start on`() {
    assertFalse(isDaemonAutoStartDisabled(""))
    assertFalse(isDaemonAutoStartDisabled("yes"))
    assertFalse(isDaemonAutoStartDisabled(" 1 "))
  }
}
