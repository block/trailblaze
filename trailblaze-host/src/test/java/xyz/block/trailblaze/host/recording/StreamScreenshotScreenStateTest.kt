package xyz.block.trailblaze.host.recording

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.screenstate.SecondaryTreeCarrier

/**
 * Stream mode replaces the screenshot but keeps the delegate's trees. The secondary tree was read
 * inside that delegate's build, BEFORE this frame was awaited, so it has to be forwarded rather
 * than re-read — and the delegate's tree timestamp has to come through too, because that is what
 * the frame was matched against.
 */
class StreamScreenshotScreenStateTest {

  private val axeTree = TrailblazeNode(
    nodeId = 5,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = "Continue"),
  )

  private open class PlainDelegate : ScreenState {
    override val screenshotBytes: ByteArray? = byteArrayOf(1, 2, 3)
    override val deviceWidth: Int = 393
    override val deviceHeight: Int = 852
    override val viewHierarchy: ViewHierarchyTreeNode get() = error("not used by this test")
    override val trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val annotationElements = null
  }

  private class CarrierDelegate(
    override val driverMigrationTreeNode: TrailblazeNode?,
    override val secondaryTreeFailure: String?,
    override val treeCapturedAtHostMs: Long,
  ) : PlainDelegate(), SecondaryTreeCarrier {
    override val secondaryTreeCaptureRan: Boolean = true
  }

  private val streamJpeg = byteArrayOf(9, 9, 9)

  @Test
  fun `a carrier delegate's secondary capture is forwarded unchanged`() {
    val state = StreamScreenshotScreenState(
      delegate = CarrierDelegate(axeTree, secondaryTreeFailure = null, treeCapturedAtHostMs = 1_700L),
      streamJpegBytes = streamJpeg,
    )

    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isEqualTo(axeTree)
    assertThat(state.secondaryTreeFailure).isNull()
    assertThat(state.treeCapturedAtHostMs).isEqualTo(1_700L)
  }

  @Test
  fun `a failure reason is forwarded too, so the warning can name the cause`() {
    val state = StreamScreenshotScreenState(
      delegate = CarrierDelegate(null, secondaryTreeFailure = "axe is not available", treeCapturedAtHostMs = 42L),
      streamJpegBytes = streamJpeg,
    )

    assertThat(state.secondaryTreeCaptureRan).isTrue()
    assertThat(state.driverMigrationTreeNode).isNull()
    assertThat(state.secondaryTreeFailure).isEqualTo("axe is not available")
  }

  @Test
  fun `a delegate that ran no capture reports none`() {
    val state = StreamScreenshotScreenState(delegate = PlainDelegate(), streamJpegBytes = streamJpeg)

    assertThat(state.secondaryTreeCaptureRan).isFalse()
    assertThat(state.driverMigrationTreeNode).isNull()
    assertThat(state.secondaryTreeFailure).isNull()
  }

  @Test
  fun `the screenshot is the stream frame, not the delegate's`() {
    val delegate = PlainDelegate()
    val state = StreamScreenshotScreenState(delegate = delegate, streamJpegBytes = streamJpeg)

    assertThat(state.screenshotBytes).isSameInstanceAs(streamJpeg)
    assertThat(state.deviceWidth).isEqualTo(delegate.deviceWidth)
  }
}
