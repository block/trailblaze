package xyz.block.trailblaze.host.yaml

import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner.Companion.SettlingAppId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which app a run's capture samples, when the device may not say which declared app it has until
 * it has connected.
 */
class DesktopYamlRunnerSettlingAppIdTest {

  @Test
  fun `a device that could not tell while connecting is asked again, and its answer is used`() {
    val answers = ArrayDeque(listOf(null, "com.example.app.internal"))
    val appId = SettlingAppId(fallback = "com.example.app.debug") { _ -> answers.removeFirst() }

    assertNull(appId.ifSettled(), "no guess before the device answers")
    assertEquals("com.example.app.internal", appId.get())
  }

  @Test
  fun `a device that never tells settles on the first declared app, asked only once more`() {
    val probes = AtomicInteger()
    val appId = SettlingAppId(fallback = "com.example.app.debug") { _ -> probes.incrementAndGet(); null }

    assertNull(appId.ifSettled())
    assertEquals("com.example.app.debug", appId.get())
    assertEquals("com.example.app.debug", appId.get())
    assertEquals(2, probes.get())
  }

  @Test
  fun `an answer found while connecting is kept for the run`() {
    val probes = AtomicInteger()
    val appId = SettlingAppId(fallback = "com.example.app.debug") { _ ->
      probes.incrementAndGet()
      "com.example.app.internal"
    }

    assertEquals("com.example.app.internal", appId.ifSettled())
    assertEquals("com.example.app.internal", appId.get())
    assertEquals(1, probes.get())
  }

  @Test
  fun `the early ask never settles on the last inventory seen, which can predate a reinstall`() {
    // Fresh probes fail; only the cached inventory, listing a variant since replaced, can answer.
    val appId = SettlingAppId(fallback = "com.example.app.debug") { freshOnly ->
      if (freshOnly) null else "com.example.app.internal"
    }

    assertNull(appId.ifSettled(), "capture waits for the connect rather than scope to a stale answer")
    assertEquals("com.example.app.internal", appId.get())
  }
}
