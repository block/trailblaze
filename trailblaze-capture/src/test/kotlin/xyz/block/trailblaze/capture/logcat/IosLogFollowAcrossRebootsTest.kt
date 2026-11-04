package xyz.block.trailblaze.capture.logcat

import kotlin.test.Test
import kotlin.test.assertEquals

class IosLogFollowAcrossRebootsTest {

  @Test
  fun `a log stream that ends with the simulator starts again once it has booted`() {
    var stopped = false
    var exits = 0
    val restarts = followIosLogAcrossReboots(
      isStopped = { stopped },
      // The first stream ends with a reboot; capture is stopped while the second runs.
      awaitExit = { if (++exits == 2) stopped = true },
      awaitBoot = { true },
      relaunch = { true },
    )

    assertEquals(1, restarts)
  }

  @Test
  fun `a stopped capture is not started again`() {
    var relaunched = false
    val restarts = followIosLogAcrossReboots(
      isStopped = { true },
      awaitExit = {},
      awaitBoot = { true },
      relaunch = { relaunched = true; true },
    )

    assertEquals(0, restarts)
    assertEquals(false, relaunched)
  }

  @Test
  fun `a simulator that does not come back ends capture`() {
    val restarts = followIosLogAcrossReboots(isStopped = { false }, awaitExit = {}, awaitBoot = { false }, relaunch = { true })

    assertEquals(0, restarts)
  }

  @Test
  fun `a stream that keeps ending stops restarting at the limit`() {
    val restarts = followIosLogAcrossReboots(isStopped = { false }, awaitExit = {}, awaitBoot = { true }, relaunch = { true }, maxRestarts = 3)

    assertEquals(3, restarts)
  }
}
