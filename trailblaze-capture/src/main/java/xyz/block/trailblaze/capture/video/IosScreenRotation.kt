package xyz.block.trailblaze.capture.video

/**
 * How far an iOS screen buffer must be turned to be shown the right way up.
 *
 * An iOS device — simulator or hardware — always hands out its framebuffer in the **native portrait
 * pixel orientation**, whatever the screen is actually showing. `xcrun simctl io screenshot` hides
 * this by rotating for you; the raw feeds do not. A landscape session recorded from the baguette
 * H.264 stream therefore plays back on its side unless something turns it.
 *
 * The enum is named for the operation applied to that portrait buffer, not for the device posture
 * that produced it, because that is what both consumers need to do with it:
 *
 * - [transposeFilter] bakes the turn into the pixels. Required for WebM, which has no way to carry
 *   a rotation out-of-band.
 * - [displayRotationDegrees] states the turn as an mp4 display matrix, which costs no re-encode.
 *   This is what `simctl io recordVideo` itself writes, so an mp4 tagged this way is the same shape
 *   the shipping iOS recorder has always produced.
 *
 * The two are calibrated against each other on real simulator output: a landscape `simctl`
 * recording stores portrait pixels tagged `rotation=90`, and decoding that tag produces exactly the
 * frame `transpose=2` produces from the same stored pixels. So `rotation=90` **is**
 * [COUNTER_CLOCKWISE_90], and every other value follows from that.
 */
enum class IosScreenRotation(
  /** ffmpeg `transpose` chain that turns the portrait buffer, or null when nothing needs turning. */
  val transposeFilter: String?,
  /** The same turn as an mp4 display-matrix angle, in ffmpeg's `-display_rotation` sign. */
  val displayRotationDegrees: Int,
) {
  /** Portrait, right way up — the buffer is already correct. */
  NONE(transposeFilter = null, displayRotationDegrees = 0),

  /** Landscape: turn the portrait buffer a quarter turn clockwise. */
  CLOCKWISE_90(transposeFilter = "transpose=1", displayRotationDegrees = -90),

  /** Landscape the other way — the turn `simctl` expresses as a `rotation=90` display matrix. */
  COUNTER_CLOCKWISE_90(transposeFilter = "transpose=2", displayRotationDegrees = 90),

  /** Upside-down portrait. */
  HALF_TURN(transposeFilter = "transpose=1,transpose=1", displayRotationDegrees = 180),
  ;

  /** Whether applying this rotation swaps the buffer's width and height. */
  val swapsAxes: Boolean get() = this == CLOCKWISE_90 || this == COUNTER_CLOCKWISE_90
}
