package xyz.block.trailblaze.android.test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.test.AndroidTestTrailblazeRule.Companion.applyLocaleAndAwaitIfActivityRunning

class AndroidTestLocaleSetupTest {

  @Test
  fun `Activity access waits through a transient recreation gap`() {
    var reads = 0
    var localeWaits = 0
    val gate = ActivityLocaleGate<String> { locale, activityProvider ->
      localeWaits += 1
      assertEquals("es", locale)
      assertNull(activityProvider())
      activityProvider() ?: error("Activity did not resume")
    }
    gate.configure(
      locale = "es",
      activityProvider = { error("No Activity has launched") },
      applyLocale = {},
    )

    val activity = gate.activity {
      reads += 1
      if (reads == 1) error("Activity is recreating")
      "localized"
    }

    assertEquals("localized", activity)
    assertEquals(1, localeWaits)
    assertEquals("still-localized", gate.activity { "still-localized" })
    assertEquals(1, localeWaits)
  }

  @Test
  fun `Activity access stays immediate without a locale request`() {
    val gate = ActivityLocaleGate<String> { _, _ -> error("No locale wait expected") }

    assertEquals("running", gate.activity { "running" })
  }

  @Test
  fun `observational access skips a cold start without failing locale propagation`() {
    var localeWaits = 0
    val gate = ActivityLocaleGate<String> { _, activityProvider ->
      localeWaits += 1
      activityProvider() ?: error("Activity did not resume")
    }
    gate.configure(
      locale = "es",
      activityProvider = { error("No Activity has launched") },
      applyLocale = {},
    )

    assertNull(gate.activityIfAvailable { null })
    assertEquals(0, localeWaits)
    assertEquals("localized", gate.activity { "localized" })
    assertEquals(1, localeWaits)
  }

  @Test
  fun `observational propagation failure leaves the locale requirement armed`() {
    var localeWaits = 0
    val gate = ActivityLocaleGate<String> { _, activityProvider ->
      localeWaits += 1
      if (localeWaits == 1) error("Activity is still recreating")
      activityProvider() ?: error("Activity did not resume")
    }
    gate.configure(
      locale = "es",
      activityProvider = { error("No Activity has launched") },
      applyLocale = {},
    )

    assertNull(gate.activityIfAvailable { "unlocalized" })
    assertEquals(1, localeWaits)
    assertEquals("localized", gate.activity { "localized" })
    assertEquals(2, localeWaits)
  }

  @Test
  fun `eager locale propagation leaves later Activity access immediate`() {
    var localeWaits = 0
    val gate = ActivityLocaleGate<String> { _, activityProvider ->
      localeWaits += 1
      activityProvider() ?: error("Activity did not resume")
    }
    gate.configure(
      locale = "es",
      activityProvider = { "localized" },
      applyLocale = {},
    )

    assertEquals(1, localeWaits)
    assertEquals("still-localized", gate.activity { "still-localized" })
    assertEquals(1, localeWaits)
  }

  @Test
  fun `failed eager propagation disarms later Activity access`() {
    var localeWaits = 0
    var activityReads = 0
    val gate = ActivityLocaleGate<String> { _, _ ->
      localeWaits += 1
      error("Locale did not propagate")
    }

    assertFailsWith<IllegalStateException> {
      gate.configure(
        locale = "es",
        activityProvider = { "running" },
        applyLocale = {},
      )
    }

    assertFailsWith<IllegalStateException> {
      gate.activity {
        activityReads += 1
        "failure-capture"
      }
    }
    assertEquals(1, localeWaits)
    assertEquals(0, activityReads)

    gate.clear()
    assertEquals("next-trail", gate.activity { "next-trail" })
  }

  @Test
  fun `failed deferred propagation disarms later Activity access`() {
    var localeWaits = 0
    var activityReads = 0
    val gate = ActivityLocaleGate<String> { _, _ ->
      localeWaits += 1
      error("Locale did not propagate")
    }
    gate.configure(
      locale = "es",
      activityProvider = { error("No Activity has launched") },
      applyLocale = {},
    )

    assertFailsWith<IllegalStateException> { gate.activity { "stale" } }

    assertFailsWith<IllegalStateException> {
      gate.activity {
        activityReads += 1
        "failure-capture"
      }
    }
    assertEquals(1, localeWaits)
    assertEquals(0, activityReads)

    gate.clear()
    assertEquals("next-trail", gate.activity { "next-trail" })
  }

  @Test
  fun `capture reports not ready while locale mutation is pending`() {
    val localeApplyStarted = CountDownLatch(1)
    val finishLocaleApply = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    val gate = ActivityLocaleGate<String> { _, activityProvider ->
      activityProvider() ?: error("Activity did not resume")
    }

    try {
      val configuration = executor.submit {
        gate.configure(
          locale = "es",
          activityProvider = { error("No Activity has launched") },
          applyLocale = {
            localeApplyStarted.countDown()
            check(finishLocaleApply.await(10, TimeUnit.SECONDS))
          },
        )
      }
      assertTrue(localeApplyStarted.await(10, TimeUnit.SECONDS))

      assertNull(gate.tryWithStableConfiguration { "captured" })

      finishLocaleApply.countDown()
      configuration.get(10, TimeUnit.SECONDS)
      assertEquals("captured", gate.tryWithStableConfiguration { "captured" })
    } finally {
      finishLocaleApply.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `locale applies without waiting when no Activity is running`() {
    val events = mutableListOf<String>()

    applyLocaleAndAwaitIfActivityRunning<String>(
      locale = "es",
      activityProvider = { error("No Activity has launched") },
      applyLocale = { events += "apply:$it" },
      awaitLocale = { _, _ -> events += "await" },
    )

    assertEquals(listOf("apply:es"), events)
  }

  @Test
  fun `locale applies before waiting once for an existing Activity`() {
    val events = mutableListOf<String>()

    applyLocaleAndAwaitIfActivityRunning(
      locale = "es",
      activityProvider = {
        events += "activity"
        "running"
      },
      applyLocale = { events += "apply:$it" },
      awaitLocale = { locale, activityProvider ->
        events += "await:$locale:${activityProvider()}"
      },
    )

    assertEquals(listOf("activity", "apply:es", "activity", "await:es:running"), events)
  }
}
