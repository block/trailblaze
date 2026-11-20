package xyz.block.trailblaze.host.lease

import xyz.block.trailblaze.cli.DeviceClassifierResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.IOException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A CLI planning a run on a borrowed device classifies it the way its lender measured it. */
class BorrowedDeviceClassifiersTest {

  /** Unique per test, since the resolver caches by instance id for the life of the JVM. */
  private val tablet = "emulator-${UUID.randomUUID()}"
  private var reachable = true
  private val asked = mutableListOf<String>()

  init {
    BorrowedDeviceClassifiers.lenderUrls = { listOf("http://pool:31900") }
    BorrowedDeviceClassifiers.clientFor = { url ->
      object : DeviceLeaseClient(url) {
        override fun devices(): List<LeasableDeviceStatus> {
          asked += url
          if (!reachable) throw IOException("connection refused")
          return listOf(
            LeasableDeviceStatus(
              id = "android/$tablet",
              driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
              leased = true,
              classifiers = listOf("android", "tablet"),
            ),
          )
        }
      }
    }
  }

  @AfterTest
  fun restore() {
    BorrowedDeviceClassifiers.lenderUrls = RemoteDevicePools::configuredUrls
    BorrowedDeviceClassifiers.clientFor = ::DeviceLeaseClient
  }

  @Test
  fun `a borrowed device is classified as its lender measured it, and remembered`() {
    val classification = DeviceClassifierResolver.classificationFor(TrailblazeDevicePlatform.ANDROID, "$tablet@pool:31900")
    assertEquals(listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("tablet")), classification.classifiers)
    assertTrue(classification.definitive)
    DeviceClassifierResolver.classificationFor(TrailblazeDevicePlatform.ANDROID, "$tablet@pool:31900")
    assertEquals(1, asked.size)
  }

  @Test
  fun `an unreachable lender gives a platform-only guess that is asked again next time`() {
    reachable = false
    val classification = DeviceClassifierResolver.classificationFor(TrailblazeDevicePlatform.ANDROID, "$tablet@pool:31900")
    assertEquals(listOf(TrailblazeDeviceClassifier("android")), classification.classifiers)
    assertFalse(classification.definitive)
    reachable = true
    assertTrue(DeviceClassifierResolver.classificationFor(TrailblazeDevicePlatform.ANDROID, "$tablet@pool:31900").definitive)
  }

  @Test
  fun `a device that is not borrowed from a paired lender is not looked up`() {
    assertNull(BorrowedDeviceClassifiers.lookup(TrailblazeDevicePlatform.ANDROID, tablet))
    assertNull(BorrowedDeviceClassifiers.lookup(TrailblazeDevicePlatform.WEB, "checkout@example.com:443"))
    assertTrue(asked.isEmpty())
  }
}
