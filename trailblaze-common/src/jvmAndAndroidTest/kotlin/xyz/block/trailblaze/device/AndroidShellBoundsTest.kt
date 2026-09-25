package xyz.block.trailblaze.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidShellBoundsTest {

  @Test
  fun `the dispatch bound stays above the read bound`() {
    // The order is the whole reason these two live together: the read bound is the only one that
    // ends the read and releases the UiAutomation monitor, so a dispatch bound that fired first
    // would report a failure and leave the device unusable anyway.
    assertTrue(
      AndroidShellBounds.ON_DEVICE_DISPATCH_TIMEOUT_MS > AndroidShellBounds.SHELL_READ_TIMEOUT_MS,
      "dispatch bound ${AndroidShellBounds.ON_DEVICE_DISPATCH_TIMEOUT_MS}ms must stay above the " +
        "read bound ${AndroidShellBounds.SHELL_READ_TIMEOUT_MS}ms",
    )
  }

  @Test
  fun `the host bound stays above the longest bound a caller of that transport sets for itself`() {
    // `pm compile` travels the host transport, and both the session-start hook and
    // `android_ensureAppCompiled` already wait EnsureAppCompiled.COMPILE_TIMEOUT_MS for it. Equal
    // numbers race, and the caller's message is the useful one — it names the app and the
    // compiler filter, where the transport can only name the argv.
    assertTrue(
      AndroidShellBounds.HOST_SHELL_TIMEOUT_MS > EnsureAppCompiled.COMPILE_TIMEOUT_MS,
      "host bound ${AndroidShellBounds.HOST_SHELL_TIMEOUT_MS}ms must stay above the compile bound " +
        "${EnsureAppCompiled.COMPILE_TIMEOUT_MS}ms its own callers enforce",
    )
  }

  @Test
  fun `the two transports give up at the same wall clock`() {
    // A trail step that hangs should fail the same way whether it ran host-side or on-device.
    // Asserted rather than left to the `=` in the source so the two cannot drift apart silently.
    assertEquals(
      AndroidShellBounds.ON_DEVICE_DISPATCH_TIMEOUT_MS,
      AndroidShellBounds.HOST_SHELL_TIMEOUT_MS,
    )
  }

  @Test
  fun `a second read is given what is left of the budget, not a fresh one`() {
    // `execShellCommand` runs a liveness probe after a command that answered nothing, and both
    // reads hold the process-wide UiAutomation monitor. Handing the probe the caller's full bound
    // is what let one call outlive the deadline its caller sized.
    assertEquals(600, AndroidShellBounds.remainingAfter(1_000, 400))
    assertTrue(
      AndroidShellBounds.remainingAfter(1_000, 400) < 1_000,
      "the probe must not get the whole budget over again",
    )
  }

  @Test
  fun `a spent budget leaves nothing, so the caller can tell not to start a second read`() {
    // Zero rather than a negative number: the caller's test is `<= 0`, and a bound of -600 handed
    // to a read would be indistinguishable from "no bound" at the call site.
    assertEquals(0, AndroidShellBounds.remainingAfter(1_000, 1_000))
    assertEquals(0, AndroidShellBounds.remainingAfter(1_000, 1_600))
  }
}
