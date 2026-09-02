package xyz.block.trailblaze.host.driver

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostDriverDescriptorRegistryTest {

  @Test
  fun `a descriptor answers for every driver it claims`() {
    val descriptor = FakeHostDriverDescriptor(
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS,
    )
    val registry = HostDriverDescriptorRegistry(setOf(descriptor))

    assertSame(descriptor, registry.forDriver(TrailblazeDriverType.REVYL_ANDROID))
    assertSame(descriptor, registry.forDriver(TrailblazeDriverType.REVYL_IOS))
  }

  @Test
  fun `an unconverted driver has no descriptor so its caller can fall back`() {
    val registry = HostDriverDescriptorRegistry(
      setOf(FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID)),
    )

    assertNull(registry.forDriverOrNull(TrailblazeDriverType.PLAYWRIGHT_NATIVE))
  }

  /**
   * Two descriptors claiming one driver is not a merge — one of them silently never runs, and
   * which one depends on iteration order of the set the app config happened to build.
   */
  @Test
  fun `two descriptors cannot claim the same driver`() {
    val failure = assertFailsWith<IllegalArgumentException> {
      HostDriverDescriptorRegistry(
        setOf(
          FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID),
          FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
        ),
      )
    }
    assertTrue(
      failure.message!!.contains("REVYL_ANDROID"),
      "the error must name the contested driver, got: ${failure.message}",
    )
  }

  /**
   * The lookup that converted call sites use has no fallback left, so its failure has to say what
   * to do about it — this is the message someone sees when a driver is enabled but unplugged.
   */
  @Test
  fun `the strict lookup names the driver and the fix`() {
    val registry = HostDriverDescriptorRegistry(
      setOf(FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID)),
    )

    val message = assertFailsWith<IllegalStateException> {
      registry.forDriver(TrailblazeDriverType.REVYL_IOS)
    }.message!!

    assertTrue(message.contains("REVYL_IOS"), "must name the missing driver: $message")
    assertTrue(message.contains("hostDriverDescriptors"), "must name where to add it: $message")
    assertTrue(message.contains("REVYL_ANDROID"), "must list what IS registered: $message")
  }

  /**
   * The startup check that replaces the compile-time exhaustiveness a `when` used to give
   * converted drivers.
   */
  @Test
  fun `supporting a converted driver without registering it fails the startup check`() {
    val registry = HostDriverDescriptorRegistry(
      setOf(FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID)),
    )

    val message = assertFailsWith<IllegalStateException> {
      registry.validateCovers(
        setOf(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
      )
    }.message!!

    assertTrue(message.contains("REVYL_IOS"), "must name the unplugged driver: $message")
  }

  /**
   * The check must stay silent about drivers that still have their `when` arms, or every app would
   * have to register a descriptor for all ten before any one of them converted.
   */
  @Test
  fun `an unconverted driver needs no descriptor`() {
    val unconverted = TrailblazeDriverType.entries
      .filterNot { it in HostDriverDescriptorRegistry.convertedDriverTypes }
      .toSet()
    assertTrue(unconverted.isNotEmpty(), "this test is vacuous once every driver has converted")

    HostDriverDescriptorRegistry.EMPTY.validateCovers(unconverted)
  }

  /**
   * A descriptor for a driver the app has switched off in settings is how a driver stays plugged
   * in while disabled — checking that direction too would break the settings toggle.
   */
  @Test
  fun `registering more than the app supports is allowed`() {
    val registry = HostDriverDescriptorRegistry(
      setOf(
        FakeHostDriverDescriptor(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
      ),
    )

    registry.validateCovers(setOf(TrailblazeDriverType.REVYL_ANDROID))
  }

  /**
   * `convertedDriverTypes` drives [HostDriverDescriptorRegistry.validateCovers], so a driver listed
   * there without its call sites actually converted would demand a descriptor that does nothing,
   * and one converted without being listed would skip the startup check entirely.
   */
  @Test
  fun `the converted set is exactly the drivers with descriptor-backed call sites`() {
    assertEquals(
      setOf(
        TrailblazeDriverType.REVYL_ANDROID,
        TrailblazeDriverType.REVYL_IOS,
        TrailblazeDriverType.COMPOSE,
        TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
      ),
      HostDriverDescriptorRegistry.convertedDriverTypes,
    )
  }
}
