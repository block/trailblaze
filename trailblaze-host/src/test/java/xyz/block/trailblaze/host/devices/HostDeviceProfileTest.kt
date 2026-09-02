package xyz.block.trailblaze.host.devices

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDeviceOrientation
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Pins that a device's own report of its size survives the trip into a session's device info.
 *
 * The host used to read only the classifiers out of this response and describe every RPC-driven
 * device as 0x0, which is not merely a blank field: `TrailblazeDeviceInfo` derives orientation from
 * the dimensions, so an unmeasured device reads as PORTRAIT no matter how it is held.
 */
class HostDeviceProfileTest {

  private fun response(
    width: Int,
    height: Int,
    classifiers: List<String>? = listOf("android", "phone"),
  ) = GetScreenStateResponse(
    viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
    screenshotBase64 = null,
    deviceWidth = width,
    deviceHeight = height,
    deviceClassifiers = classifiers,
  )

  @Test
  fun `a device's reported size is carried through, not dropped`() {
    val profile = HostDeviceProfile.fromScreenState(response(width = 1080, height = 2400))

    assertEquals(1080, profile.widthPixels)
    assertEquals(2400, profile.heightPixels)
  }

  @Test
  fun `size survives an agent too old to report classifiers`() {
    // `deviceClassifiers` is nullable and the dimensions are not, so these axes fail
    // independently. Falling back to a host probe for the classifiers must not also discard a
    // size the device did report.
    val profile = HostDeviceProfile.fromScreenState(
      response(width = 1080, height = 2400, classifiers = null),
    )

    assertTrue(profile.classifiers.isEmpty())
    assertEquals(1080, profile.widthPixels)
    assertEquals(2400, profile.heightPixels)
  }

  @Test
  fun `a half-measured size is discarded rather than passed through`() {
    // 1080x0 is not "mostly right": it would make TrailblazeDeviceInfo report LANDSCAPE for a
    // portrait phone. Unmeasured beats confidently wrong.
    val profile = HostDeviceProfile.fromScreenState(response(width = 1080, height = 0))

    assertEquals(HostDeviceProfile.UNMEASURED, profile.widthPixels)
    assertEquals(HostDeviceProfile.UNMEASURED, profile.heightPixels)
  }

  @Test
  fun `a failed probe reports unmeasured on both axes`() {
    val profile = HostDeviceProfile.unknown()

    assertTrue(profile.classifiers.isEmpty())
    assertEquals(HostDeviceProfile.UNMEASURED, profile.widthPixels)
    assertEquals(HostDeviceProfile.UNMEASURED, profile.heightPixels)
  }

  @Test
  fun `a rotation replaces the size the profile carries`() {
    // A session queries the profile once at start, but the size doesn't stay put: `setOrientation`
    // swaps the axes. Since orientation is derived from them, a start-only reading describes every
    // later log and `ctx.device` read in the orientation the run began in.
    val atStart = HostDeviceProfile.fromScreenState(response(width = 1200, height = 1920))

    val afterRotating = atStart.withMeasuredSizeFrom(response(width = 1920, height = 1200))

    assertEquals(1920, afterRotating.widthPixels)
    assertEquals(1200, afterRotating.heightPixels)
  }

  @Test
  fun `a refresh keeps the classifiers the start-of-session probe found`() {
    // Classifiers answer phone-vs-tablet, which keys on min(w, h) and so survives rotation — and a
    // response from an agent too old to report them carries none. Re-reading them here would erase
    // what the host fell back to probing.
    val atStart = HostDeviceProfile.fromScreenState(response(width = 1200, height = 1920))

    val afterRotating = atStart.withMeasuredSizeFrom(
      response(width = 1920, height = 1200, classifiers = null),
    )

    assertEquals(atStart.classifiers, afterRotating.classifiers)
  }

  @Test
  fun `a report with no usable size leaves the last real one standing`() {
    // Same rule as the initial read, applied to the refresh: a half-measured report would flip
    // orientation on a device whose size was already known good.
    val atStart = HostDeviceProfile.fromScreenState(response(width = 1080, height = 2400))

    val afterUnmeasuredReport = atStart.withMeasuredSizeFrom(response(width = 1080, height = 0))

    assertEquals(1080, afterUnmeasuredReport.widthPixels)
    assertEquals(2400, afterUnmeasuredReport.heightPixels)
  }

  @Test
  fun `a landscape device is described as landscape once its size is real`() {
    // Why the dimensions are load-bearing rather than cosmetic: this is the only input orientation
    // has, so an unmeasured landscape display (a tablet, or the customer-facing screen of a
    // dual-display terminal) reported as 0x0 comes out PORTRAIT.
    val profile = HostDeviceProfile.fromScreenState(response(width = 1920, height = 1200))

    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5560", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = profile.widthPixels,
      heightPixels = profile.heightPixels,
    )

    assertEquals(TrailblazeDeviceOrientation.LANDSCAPE, deviceInfo.orientation)
  }
}
