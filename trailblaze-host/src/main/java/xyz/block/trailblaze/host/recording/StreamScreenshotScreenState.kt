package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.host.screenstate.SecondaryTreeCarrier
import xyz.block.trailblaze.setofmark.SetOfMarkAnnotator

/**
 * [ScreenState] whose tree comes from the platform's own capture path but whose screenshot is
 * a frame from the live device stream (matched via [StreamFrameMonitor]). Platform-neutral —
 * the delegate carries the platform and the device dimensions.
 *
 * Set-of-mark annotation moves host-side: whatever renderer produced the delegate's
 * screenshot never saw this frame, so the stream frame is annotated here with the delegate's
 * own [annotationElements] (built from the tree that was just matched against the frame).
 * [SetOfMarkAnnotator] scales the element bounds from device coordinates onto the frame's
 * actual pixel size, so the stream's downscale doesn't misplace marks.
 */
class StreamScreenshotScreenState(
  private val delegate: ScreenState,
  private val streamJpegBytes: ByteArray,
) : ScreenState by delegate, SecondaryTreeCarrier {

  override val screenshotBytes: ByteArray = streamJpegBytes

  // The secondary capture already happened inside the delegate's build, before this frame was
  // even awaited; forward it rather than re-reading it here against a newer screen.
  private val carrier: SecondaryTreeCarrier? = delegate as? SecondaryTreeCarrier

  override val secondaryTreeCaptureRan: Boolean
    get() = carrier?.secondaryTreeCaptureRan == true

  override val driverMigrationTreeNode: TrailblazeNode?
    get() = carrier?.driverMigrationTreeNode

  override val secondaryTreeFailure: String?
    get() = carrier?.secondaryTreeFailure

  /** The delegate's stamp, so a re-match against this state compares against the tree read. */
  override val treeCapturedAtHostMs: Long
    get() = carrier?.treeCapturedAtHostMs ?: 0L

  private val _annotatedScreenshotBytes: ByteArray by lazy {
    SetOfMarkAnnotator.annotate(
      screenshotBytes = streamJpegBytes,
      screenWidth = delegate.deviceWidth,
      screenHeight = delegate.deviceHeight,
      platform = delegate.trailblazeDevicePlatform,
      annotationElements = delegate.annotationElements,
    ) ?: streamJpegBytes
  }

  override val annotatedScreenshotBytes: ByteArray
    get() = _annotatedScreenshotBytes
}
