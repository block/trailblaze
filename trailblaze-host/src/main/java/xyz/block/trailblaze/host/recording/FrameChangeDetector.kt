package xyz.block.trailblaze.host.recording

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Perceptual (re-encode-tolerant) frame-change detector for live device streams.
 *
 * Byte-exact hashing is the wrong change signal for a continuously-encoding stream: baguette
 * (iOS) hardware-encodes ~60 fps even for a static screen, and each decode→mjpeg re-encode
 * carries enough codec noise that consecutive frames of an *unchanged* screen are never
 * byte-identical. Under a byte-exact detector the stream never goes content-quiet and
 * [StreamScreenshotGate] can never accept a frame. This detector instead compares a
 * downscaled luminance grid of each frame against the previous one and reports a change only
 * when enough of the screen moved by more than codec noise.
 *
 * Two-layer decision, both tunable:
 * - a per-cell tolerance ([pixelTolerance]) absorbs flat-region codec noise, and
 * - a changed-cell fraction ([changedFractionThreshold]) absorbs the handful of grid cells
 *   that ringing near hard edges can push past the tolerance on an unchanged screen.
 *
 * Real UI changes (a screen transition, a dialog, a keyboard) move large contiguous regions
 * far beyond both thresholds; the two layers are orders of magnitude apart from codec noise,
 * not a fine line.
 *
 * Frames that cannot be decoded are reported as **changed** — the detector can only prove
 * "unchanged", never assume it, and the gate's safe direction is to keep waiting.
 *
 * Classification is throttled to at most one decode per [minClassifyIntervalMs]; frames
 * inside the throttle window return [Verdict.UNCLASSIFIED] without touching the reference
 * grid, so the next classified frame still compares against the last *classified* one — a
 * real change is detected at most one throttle interval late, never missed. Callers must not
 * treat UNCLASSIFIED as "unchanged": the frame's content is simply unknown (see
 * [StreamFrameMonitor.recordFrame], which drops such frames rather than publishing them). The
 * quiet windows downstream are an order of magnitude larger than the throttle, so the added
 * latency is absorbed by [StreamScreenshotGate]'s existing allowances.
 *
 * Not thread-safe: it holds a mutable reference grid across calls. Callers must serialize every
 * [classify] call externally — [StreamFrameMonitor] does, under its lock, because it drives the
 * detector from [StreamFrameMonitor.recordFrame] and [StreamFrameMonitor.recordFeedAlive], which
 * can be invoked concurrently from different feed threads.
 */
class FrameChangeDetector(
  /** Max per-cell luma delta (0–255) still attributed to codec noise. */
  private val pixelTolerance: Int = DEFAULT_PIXEL_TOLERANCE,
  /** Fraction of grid cells that must exceed [pixelTolerance] to call the frame changed. */
  private val changedFractionThreshold: Double = DEFAULT_CHANGED_FRACTION,
  /** Decode/compare at most this often; see class kdoc for why skipping is safe. */
  private val minClassifyIntervalMs: Long = DEFAULT_MIN_CLASSIFY_INTERVAL_MS,
) {

  private var lastGrid: IntArray? = null
  private var lastGridWidth = -1
  private var lastGridHeight = -1
  private var lastClassifiedAtMs = Long.MIN_VALUE

  /** Outcome of classifying one frame. */
  enum class Verdict {
    /** Confirmed content change (first frame, resolution change, undecodable payload, or a
     *  perceptual delta beyond codec noise). */
    CHANGED,

    /** Perceptually identical to the last classified frame (within codec-noise tolerance). */
    UNCHANGED,

    /** Not classified — the call landed inside the throttle window, so whether this frame's
     *  content changed is unknown. Neither "changed" nor "unchanged" may be assumed. */
    UNCLASSIFIED,
  }

  /** Classifies [jpegBytes] against the last classified frame; see [Verdict]. */
  fun classify(jpegBytes: ByteArray, nowMs: Long): Verdict {
    if (lastClassifiedAtMs != Long.MIN_VALUE && nowMs - lastClassifiedAtMs < minClassifyIntervalMs) {
      return Verdict.UNCLASSIFIED
    }
    val grid = decodeLumaGrid(jpegBytes)
      ?: run {
        // Undecodable frame: report changed (see class kdoc) and drop the reference grid so
        // the next good frame re-baselines rather than comparing against stale content.
        lastClassifiedAtMs = nowMs
        lastGrid = null
        return Verdict.CHANGED
      }
    lastClassifiedAtMs = nowMs
    val prev = lastGrid
    val changed = prev == null ||
      lastGridWidth != grid.width ||
      lastGridHeight != grid.height ||
      gridsDiffer(prev, grid.luma, pixelTolerance, changedFractionThreshold)
    lastGrid = grid.luma
    lastGridWidth = grid.width
    lastGridHeight = grid.height
    return if (changed) Verdict.CHANGED else Verdict.UNCHANGED
  }

  private class LumaGrid(val luma: IntArray, val width: Int, val height: Int)

  /**
   * Decodes the JPEG at a coarse subsampling into a small luminance grid. Subsampled decode
   * (rather than decode-then-scale) keeps the per-frame cost low enough for a 60 fps feed
   * under the classify throttle. Returns null when the payload isn't a decodable image.
   */
  private fun decodeLumaGrid(jpegBytes: ByteArray): LumaGrid? = try {
    ImageIO.createImageInputStream(ByteArrayInputStream(jpegBytes)).use { iis ->
      val readers = ImageIO.getImageReaders(iis)
      if (!readers.hasNext()) return null
      val reader = readers.next()
      try {
        reader.setInput(iis, true, true)
        val width = reader.getWidth(0)
        val height = reader.getHeight(0)
        if (width <= 0 || height <= 0) return null
        val subsampling = maxOf(1, maxOf(width, height) / GRID_LONG_SIDE)
        val param = reader.defaultReadParam.apply {
          setSourceSubsampling(subsampling, subsampling, 0, 0)
        }
        val image = reader.read(0, param)
        val gw = image.width
        val gh = image.height
        val luma = IntArray(gw * gh)
        var i = 0
        for (y in 0 until gh) {
          for (x in 0 until gw) {
            val rgb = image.getRGB(x, y)
            val r = (rgb ushr 16) and 0xff
            val g = (rgb ushr 8) and 0xff
            val b = rgb and 0xff
            luma[i++] = (r * 299 + g * 587 + b * 114) / 1000
          }
        }
        LumaGrid(luma, gw, gh)
      } finally {
        reader.dispose()
      }
    }
  } catch (_: Exception) {
    null
  }

  companion object {
    /** Long side of the comparison grid; short side follows the frame's aspect ratio. */
    private const val GRID_LONG_SIDE = 64

    const val DEFAULT_PIXEL_TOLERANCE = 16
    const val DEFAULT_CHANGED_FRACTION = 0.01
    const val DEFAULT_MIN_CLASSIFY_INTERVAL_MS = 50L

    /**
     * Pure comparison over two equal-size luma grids: true when the fraction of cells whose
     * absolute delta exceeds [pixelTolerance] is at least [changedFraction].
     */
    internal fun gridsDiffer(
      a: IntArray,
      b: IntArray,
      pixelTolerance: Int,
      changedFraction: Double,
    ): Boolean {
      require(a.size == b.size) { "grids must be the same size (${a.size} vs ${b.size})" }
      if (a.isEmpty()) return false
      // At least one cell must move even when the fraction rounds to zero on a tiny grid.
      val changedCellsNeeded = maxOf(1, kotlin.math.ceil(a.size * changedFraction).toInt())
      var changedCells = 0
      for (i in a.indices) {
        if (kotlin.math.abs(a[i] - b[i]) > pixelTolerance) {
          changedCells++
          if (changedCells >= changedCellsNeeded) return true
        }
      }
      return false
    }
  }
}
