package xyz.block.trailblaze.host.driver

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
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
  fun `a driver this app did not plug in has no descriptor`() {
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
   * The lookup every call site uses has no fallback, so its failure has to say what to do about
   * it — this is the message someone sees when a driver is enabled but unplugged, and since
   * `validateCovers` is gone it is the only place that says so.
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
   * The obligation the compiler cannot state: `runYaml` being abstract forces a new driver to
   * answer whether it runs on the host, but nothing stops it from answering with
   * [HostDriverDescriptor.OnDeviceTools] when dispatch does route it to `runHostYaml`. That
   * combination is invisible until the first host run, which is why the registry rejects it at
   * construction.
   */
  @Test
  fun `declining a host run body fails for a driver that reaches the host run path`() {
    val exception = assertFailsWith<IllegalArgumentException> {
      HostDriverDescriptorRegistry(
        setOf(FakeOnDeviceHostDriverDescriptor(TrailblazeDriverType.COMPOSE)),
      )
    }

    val message = exception.message!!
    assertTrue(message.contains("COMPOSE"), "must name the driver: $message")
    assertTrue(
      message.contains("FakeOnDeviceHostDriverDescriptor"),
      "must name the descriptor to fix: $message",
    )
    assertTrue(message.contains("runYaml"), "must name the remedy: $message")
  }

  /** The converse, so the check above cannot be satisfied by rejecting every on-device driver. */
  @Test
  fun `declining a host run body is allowed for a driver whose tools run on the device`() {
    val descriptor = FakeOnDeviceHostDriverDescriptor(TrailblazeDriverType.ANDROID_TEST)

    val registry = HostDriverDescriptorRegistry(setOf(descriptor))

    assertSame(descriptor, registry.forDriver(TrailblazeDriverType.ANDROID_TEST))
  }
}
