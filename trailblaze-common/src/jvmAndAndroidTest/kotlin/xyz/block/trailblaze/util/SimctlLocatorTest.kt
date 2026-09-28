package xyz.block.trailblaze.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [SimctlLocator] decides what every simctl invocation runs. The contract: run simctl's own path
 * once `xcrun` has found it, look it up once rather than per call, look again when that path goes
 * away, and fall back to `xcrun simctl` (never to nothing) when there is no path to use.
 */
class SimctlLocatorTest {

  private val simctl = "/Applications/Xcode.app/Contents/Developer/usr/bin/simctl"

  @Test
  fun `runs simctl directly once xcrun has found it, looking it up only once`() {
    var lookups = 0
    val locator = SimctlLocator(lookup = { lookups++; simctl }, isExecutable = { true })

    repeat(3) { assertEquals(listOf(simctl), locator.prefix()) }
    assertEquals(1, lookups, "the lookup is the cost being removed, so it must not repeat per call")
  }

  @Test
  fun `falls back to xcrun simctl when the lookup finds nothing, without asking again`() {
    var lookups = 0
    val locator = SimctlLocator(lookup = { lookups++; null }, isExecutable = { true })

    repeat(3) { assertEquals(listOf("xcrun", "simctl"), locator.prefix()) }
    assertEquals(1, lookups, "a host without Xcode must not pay a failing lookup on every call")
  }

  @Test
  fun `falls back to xcrun simctl when the found path is not an executable absolute path`() {
    assertEquals(
      listOf("xcrun", "simctl"),
      SimctlLocator(lookup = { "simctl" }, isExecutable = { true }).prefix(),
      "a relative path would resolve against the daemon's working directory",
    )
    assertEquals(
      listOf("xcrun", "simctl"),
      SimctlLocator(lookup = { simctl }, isExecutable = { false }).prefix(),
    )
  }

  @Test
  fun `looks again once the remembered path stops being executable`() {
    val moved = "/Applications/Xcode-beta.app/Contents/Developer/usr/bin/simctl"
    var answer = simctl
    val executable = mutableSetOf(simctl)
    val locator = SimctlLocator(lookup = { answer }, isExecutable = { it in executable })
    assertEquals(listOf(simctl), locator.prefix())

    executable.clear()
    executable += moved
    answer = moved

    assertEquals(listOf(moved), locator.prefix())
  }

  @Test
  fun `callers that miss at the same time share one lookup`() {
    val lookups = AtomicInteger()
    val release = CountDownLatch(1)
    val locator = SimctlLocator(
      lookup = {
        lookups.incrementAndGet()
        release.await(5, TimeUnit.SECONDS)
        simctl
      },
      isExecutable = { true },
    )
    val results = java.util.Collections.synchronizedList(mutableListOf<List<String>>())

    val callers = List(8) { thread { results += locator.prefix() } }
    // Long enough for every caller to reach the lookup if nothing holds them back.
    Thread.sleep(200)
    release.countDown()
    callers.forEach { it.join(5_000) }

    assertEquals(1, lookups.get(), "each concurrent miss would otherwise spawn its own xcrun")
    assertEquals(List(8) { listOf(simctl) }, results.toList())
  }
}
