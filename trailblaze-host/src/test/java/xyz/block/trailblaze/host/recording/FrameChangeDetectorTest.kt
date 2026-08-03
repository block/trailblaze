package xyz.block.trailblaze.host.recording

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.host.recording.FrameChangeDetector.Verdict

/**
 * Behavioral contract of the perceptual change detector: codec/re-encode noise on an
 * unchanged screen must NOT read as a content change (that's the iOS-baguette failure mode
 * this class exists for), while a real UI change must. Exercised with real JPEG payloads —
 * the same rendered screen encoded at different JPEG qualities stands in for a stream
 * re-encode of a static screen.
 */
class FrameChangeDetectorTest {

  @Test
  fun `first frame is a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 0)
    assertEquals(Verdict.CHANGED, detector.classify(screenJpeg(quality = 0.9f), nowMs = 0))
  }

  @Test
  fun `re-encode of an unchanged screen is not a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 0)
    detector.classify(screenJpeg(quality = 0.9f), nowMs = 0)
    // Same rendered content, different encoder settings — byte-different, perceptually equal.
    assertEquals(Verdict.UNCHANGED, detector.classify(screenJpeg(quality = 0.5f), nowMs = 100))
    assertEquals(Verdict.UNCHANGED, detector.classify(screenJpeg(quality = 0.7f), nowMs = 200))
  }

  @Test
  fun `a real region change is a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 0)
    detector.classify(screenJpeg(quality = 0.9f), nowMs = 0)
    assertEquals(
      Verdict.CHANGED,
      detector.classify(screenJpeg(quality = 0.9f, withDialog = true), nowMs = 100),
    )
  }

  @Test
  fun `undecodable payload is a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 0)
    detector.classify(screenJpeg(quality = 0.9f), nowMs = 0)
    assertEquals(Verdict.CHANGED, detector.classify(byteArrayOf(1, 2, 3), nowMs = 100))
  }

  @Test
  fun `resolution change is a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 0)
    detector.classify(screenJpeg(quality = 0.9f), nowMs = 0)
    assertEquals(
      Verdict.CHANGED,
      detector.classify(screenJpeg(quality = 0.9f, width = 320, height = 640), nowMs = 100),
    )
  }

  @Test
  fun `throttled frames defer, not lose, a change`() {
    val detector = FrameChangeDetector(minClassifyIntervalMs = 50)
    detector.classify(screenJpeg(quality = 0.9f), nowMs = 0)
    // Inside the throttle window the frame is UNCLASSIFIED — unknown, not "unchanged" — even
    // though the content moved…
    assertEquals(
      Verdict.UNCLASSIFIED,
      detector.classify(screenJpeg(quality = 0.9f, withDialog = true), nowMs = 10),
    )
    // …but the next classified frame compares against the last CLASSIFIED grid, so the
    // change is still detected once the window elapses.
    assertEquals(
      Verdict.CHANGED,
      detector.classify(screenJpeg(quality = 0.9f, withDialog = true), nowMs = 60),
    )
  }

  @Test
  fun `gridsDiffer tolerates per-cell noise below the tolerance`() {
    val a = IntArray(100) { 100 }
    val b = IntArray(100) { 104 } // uniform +4: codec-noise scale
    assertFalse(FrameChangeDetector.gridsDiffer(a, b, pixelTolerance = 16, changedFraction = 0.01))
  }

  @Test
  fun `gridsDiffer flags a region that moved beyond tolerance`() {
    val a = IntArray(100) { 100 }
    val b = IntArray(100) { i -> if (i < 10) 220 else 100 } // 10% of cells moved hard
    assertTrue(FrameChangeDetector.gridsDiffer(a, b, pixelTolerance = 16, changedFraction = 0.01))
  }

  @Test
  fun `gridsDiffer ignores isolated cells below the changed fraction`() {
    val a = IntArray(1000) { 100 }
    val b = IntArray(1000) { i -> if (i == 0) 220 else 100 } // one ringing cell out of 1000
    assertFalse(FrameChangeDetector.gridsDiffer(a, b, pixelTolerance = 16, changedFraction = 0.01))
  }

  /**
   * Renders a synthetic app screen (title bar, buttons, text rows) and encodes it as JPEG at
   * [quality]. [withDialog] overlays a centered dialog — a realistic "screen changed" delta.
   */
  private fun screenJpeg(
    quality: Float,
    withDialog: Boolean = false,
    width: Int = 400,
    height: Int = 800,
  ): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      g.color = Color.WHITE
      g.fillRect(0, 0, width, height)
      g.color = Color(33, 33, 33)
      g.fillRect(0, 0, width, height / 10) // title bar
      g.color = Color(66, 133, 244)
      g.fillRect(width / 10, height / 5, width * 8 / 10, height / 12) // button
      g.color = Color(120, 120, 120)
      for (row in 0 until 8) { // text rows
        g.fillRect(width / 10, height * 2 / 5 + row * height / 20, width * 7 / 10, height / 60)
      }
      if (withDialog) {
        g.color = Color(250, 250, 250)
        g.fillRect(width / 8, height * 3 / 8, width * 3 / 4, height / 4)
        g.color = Color(200, 60, 60)
        g.fillRect(width / 4, height / 2, width / 2, height / 16)
      }
    } finally {
      g.dispose()
    }
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
      val param = writer.defaultWriteParam.apply {
        compressionMode = ImageWriteParam.MODE_EXPLICIT
        compressionQuality = quality
      }
      ImageIO.createImageOutputStream(out).use { ios ->
        writer.output = ios
        writer.write(null, IIOImage(image, null, null), param)
      }
    } finally {
      writer.dispose()
    }
    return out.toByteArray()
  }
}
