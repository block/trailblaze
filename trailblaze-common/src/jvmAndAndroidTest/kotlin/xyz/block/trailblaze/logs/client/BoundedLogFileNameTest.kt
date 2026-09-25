package xyz.block.trailblaze.logs.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared bound on log and screenshot file names.
 *
 * Tested here rather than through its callers because the property that matters — two sessions
 * never producing the same name — is invisible from [OnDeviceLogFileName], whose per-call counter
 * makes every name unique on its own. A new process restarts that counter, so a bound that
 * collapsed two sessions to one name would still lose logs.
 */
class BoundedLogFileNameTest {

  private val suffix = "_1758512345678_0000000042.json"

  @Test
  fun `a name that fits keeps the whole session id`() {
    // The session id in the name is what makes a pulled directory readable by a human, so it is
    // only given up when it has to be.
    assertEquals(
      "my_test_2026_09_23$suffix",
      BoundedLogFileName.of("my_test_2026_09_23", suffix),
    )
  }

  @Test
  fun `a name that would not fit is brought under the limit`() {
    val name = BoundedLogFileName.of("a".repeat(250), suffix)

    assertTrue(
      "expected at most ${BoundedLogFileName.NAME_MAX} bytes, got ${name.toByteArray().size}",
      name.toByteArray(Charsets.UTF_8).size <= BoundedLogFileName.NAME_MAX,
    )
  }

  @Test
  fun `two long session ids that share a prefix still get different names`() {
    // Shortening by truncation would pass the length check and silently merge these two, which is
    // the same overwrite the counter exists to prevent — the difference between session ids can
    // sit anywhere in them, including past whatever a truncation would keep.
    val shared = "a".repeat(250)

    val first = BoundedLogFileName.of(shared + "one", suffix)
    val second = BoundedLogFileName.of(shared + "two", suffix)

    assertTrue("$first and $second must differ", first != second)
  }

  @Test
  fun `the suffix survives shortening`() {
    // The suffix carries the timestamp, counter and extension — everything that orders the
    // directory and identifies the file's type. Only the session part may be given up.
    val name = BoundedLogFileName.of("a".repeat(250), suffix)

    assertTrue("$name must keep its suffix", name.endsWith(suffix))
  }

  @Test
  fun `a multi-byte session id is measured in bytes, not characters`() {
    // 200 characters, 600 bytes: a limit checked in characters would pass this straight through
    // to a write that fails.
    val name = BoundedLogFileName.of("経".repeat(200), suffix)

    assertTrue(
      "expected at most ${BoundedLogFileName.NAME_MAX} bytes, got ${name.toByteArray().size}",
      name.toByteArray(Charsets.UTF_8).size <= BoundedLogFileName.NAME_MAX,
    )
  }
}
