package xyz.block.trailblaze.host.devices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.host.axe.AxeCli

class AxeConnectRetryTest {
  private val notReady = AxeCli.Result(1, "", "Error: No translation object returned for simulator.")
  private val ready = AxeCli.Result(0, "{}", "")

  @Test
  fun `a screen that becomes readable soon after a SpringBoard restart connects`() {
    var now = 0L
    val answers = ArrayDeque(listOf(notReady, notReady, ready))

    val result = TrailblazeDeviceService.describeUiOnceReady(
      nanoTime = { now },
      sleep = { now += it * 1_000_000 },
    ) { answers.removeFirst() }

    assertTrue(result.success)
    assertTrue(answers.isEmpty())
  }

  @Test
  fun `a screen that never becomes readable fails after the retry window`() {
    var now = 0L
    var attempts = 0

    val result = TrailblazeDeviceService.describeUiOnceReady(
      nanoTime = { now },
      sleep = { now += it * 1_000_000 },
    ) { attempts++; notReady }

    assertFalse(result.success)
    assertEquals(notReady.stderr, result.stderr)
    assertTrue(now >= 10_000L * 1_000_000)
    assertTrue(attempts in 2..50)
  }
}
