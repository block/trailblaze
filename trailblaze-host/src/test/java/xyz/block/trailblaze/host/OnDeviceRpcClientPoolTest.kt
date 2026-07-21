package xyz.block.trailblaze.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

class OnDeviceRpcClientPoolTest {

  @Test
  fun `reuses a client until its device is evicted`() {
    val pool = OnDeviceRpcClientPool { _: TrailblazeDeviceId -> FakeClient() }
    val device = device("emulator-5554")

    val first = pool.get(device)
    val second = pool.get(device)
    assertSame(first, second)
    assertFalse(first.closed)

    pool.evict(device)
    assertTrue(first.closed)

    val replacement = pool.get(device)
    assertNotSame(first, replacement)
    assertFalse(replacement.closed)
    pool.close()
    assertTrue(replacement.closed)
  }

  @Test
  fun `keeps independent connections for different devices`() {
    val pool = OnDeviceRpcClientPool { _: TrailblazeDeviceId -> FakeClient() }

    val first = pool.get(device("emulator-5554"))
    val second = pool.get(device("emulator-5556"))

    assertNotSame(first, second)
    pool.close()
    assertTrue(first.closed)
    assertTrue(second.closed)
  }

  private fun device(instanceId: String) =
    TrailblazeDeviceId(instanceId, TrailblazeDevicePlatform.ANDROID)

  private class FakeClient : AutoCloseable {
    var closed = false

    override fun close() {
      closed = true
    }
  }
}
