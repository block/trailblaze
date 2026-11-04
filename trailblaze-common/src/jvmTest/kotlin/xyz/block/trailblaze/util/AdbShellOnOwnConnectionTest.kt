package xyz.block.trailblaze.util

import dadb.AdbStream
import dadb.Dadb
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * A call on its own connection costs only its own answer when the transport breaks: the device's
 * shared client, which instrumentation runs over, stays cached and open.
 */
class AdbShellOnOwnConnectionTest {

  private val serial = "own-connection-${UUID.randomUUID()}"
  private val deviceId = TrailblazeDeviceId(serial, TrailblazeDevicePlatform.ANDROID)
  private val shared = FakeDadb { error("the shared client must not be used") }
  private val release = CountDownLatch(1)

  @AfterTest
  fun tearDown() {
    release.countDown()
    AndroidHostAdbUtils.dadbClients.remove(serial)
  }

  private fun run(own: FakeDadb, timeoutMs: Long = 5_000L): String? {
    AndroidHostAdbUtils.dadbClients[serial] = shared
    return AndroidHostAdbUtils.execAdbShellCommandOnOwnConnection(
      deviceId,
      listOf("log", "-t", "Trailblaze", "marker"),
      timeoutMs,
      quiet = true,
      openClient = { own },
    )
  }

  @Test
  fun `a broken transport leaves the shared client cached and open`() {
    val own = FakeDadb { throw IOException("connection reset by peer") }

    assertNull(run(own))
    assertSame(shared, AndroidHostAdbUtils.dadbClients[serial])
    assertFalse(shared.closed)
    assertTrue(own.closed)
  }

  @Test
  fun `a call that never answers leaves the shared client alone and closes its own`() {
    val own = FakeDadb {
      release.await(30, TimeUnit.SECONDS)
      throw IOException("closed")
    }

    assertNull(run(own, timeoutMs = 100L))
    assertSame(shared, AndroidHostAdbUtils.dadbClients[serial])
    assertFalse(shared.closed)
    assertTrue(own.closed)
  }

  @Test
  fun `a connection that opens after the deadline is closed without sending anything`() {
    val own = FakeDadb { error("a call that gave up must not reach the device") }
    val opened = CountDownLatch(1)
    AndroidHostAdbUtils.dadbClients[serial] = shared

    val result = AndroidHostAdbUtils.execAdbShellCommandOnOwnConnection(
      deviceId,
      listOf("log", "-t", "Trailblaze", "marker"),
      timeoutMs = 50L,
      quiet = true,
      openClient = {
        // A socket connect, like this wait, ignores the interrupt a timeout sends.
        while (release.count > 0) Thread.onSpinWait()
        own.also { opened.countDown() }
      },
    )
    assertNull(result)
    release.countDown()

    assertTrue(opened.await(5, TimeUnit.SECONDS))
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!own.closed && System.nanoTime() < deadline) Thread.sleep(5)
    assertTrue(own.closed)
    assertFalse(own.opened)
  }

  private class FakeDadb(private val onOpen: () -> AdbStream) : Dadb {
    @Volatile var closed = false
    @Volatile var opened = false

    override fun open(destination: String): AdbStream {
      opened = true
      return onOpen()
    }

    override fun supportsFeature(feature: String): Boolean = true

    override fun close() {
      closed = true
    }
  }
}
