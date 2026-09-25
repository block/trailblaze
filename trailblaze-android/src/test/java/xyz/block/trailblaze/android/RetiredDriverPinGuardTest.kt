package xyz.block.trailblaze.android

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a trail's own `config.driver:` pin is allowed to do once that driver's runtime is deleted.
 *
 * The property under test is refusal, not selection: a pin naming a retired driver must stop the
 * run and say so. Before this guard a direct [AndroidTrailblazeRule] run read the pin nowhere — the
 * per-trail seam is a no-op in the base class — so an instrumentation-pinned trail replayed on the
 * accessibility driver and reported PASSED, the silent wrong-driver green that retiring a driver is
 * meant to make impossible.
 */
class RetiredDriverPinGuardTest {

  @Test
  fun `a trail pinned to a retired driver is refused`() {
    val refusal = retiredTrailDriverPinRefusal(
      pinnedDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name,
      forcedDriver = null,
    )

    assertNotNull(refusal, "a retired pin was allowed to run")
    // The message has to be actionable on its own: what is gone, and what to use instead.
    assertTrue(
      refusal.contains(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name),
      "refusal does not name the pinned driver: $refusal",
    )
    assertTrue(
      refusal.contains(TrailblazeDriverType.DEFAULT_ANDROID.name),
      "refusal does not name the replacement driver: $refusal",
    )
  }

  @Test
  fun `every retired driver is refused, not just the one that happens to be listed today`() {
    TrailblazeDriverType.RETIRED_DRIVERS.forEach { retired ->
      assertNotNull(
        retiredTrailDriverPinRefusal(pinnedDriver = retired.name, forcedDriver = null),
        "retired driver ${retired.name} is still runnable from a trail pin",
      )
    }
  }

  @Test
  fun `a pin naming a live driver runs`() {
    assertNull(
      retiredTrailDriverPinRefusal(
        pinnedDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY.name,
        forcedDriver = null,
      ),
    )
  }

  @Test
  fun `no pin runs`() {
    assertNull(retiredTrailDriverPinRefusal(pinnedDriver = null, forcedDriver = null))
  }

  @Test
  fun `a pin that names no driver at all is left to the flip path to report`() {
    // Refusing here would turn a typo into a retirement message, which sends the author looking for
    // a driver they never named.
    assertNull(retiredTrailDriverPinRefusal(pinnedDriver = "not-a-driver", forcedDriver = null))
  }

  @Test
  fun `a retired pin is refused whatever case it was written in`() {
    // Parsing matches the host resolver's case-insensitive name match. A lowercase pin that slipped
    // past an exact-name parse would otherwise run.
    assertNotNull(
      retiredTrailDriverPinRefusal(
        pinnedDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name.lowercase(),
        forcedDriver = null,
      ),
      "a lowercase retired pin was allowed to run",
    )
  }

  @Test
  fun `an instrumentation-arg force wins over the pin, exactly as it does over a live pin`() {
    // Force semantics are "this shard runs on that driver, the trail YAMLs don't get a vote" — the
    // pin is not read at all. Refusing here would fail every migration lane that forces the
    // surviving driver across a suite still carrying stale pins.
    assertNull(
      retiredTrailDriverPinRefusal(
        pinnedDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION.name,
        forcedDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ),
    )
  }
}
