package xyz.block.trailblaze.cli

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Smoke test for the WebP encoder half of [ReportWebpExporter]. Avoids the full Playwright
 * capture loop (heavyweight, requires Chromium install) by feeding the assembler a handful
 * of synthetic PNG frames and asserting that ffmpeg produced a syntactically valid
 * animated-WebP container.
 *
 * Skipped via `assumeTrue` when `ffmpeg` isn't on PATH or its build lacks `libwebp_anim`.
 * The production code does the same probe via `ReportWebpExporter.requireLibwebpAnim()`,
 * called by the orchestrator in `ReportCommand.kt` before the shared frame capture so
 * we fail fast on a missing encoder instead of after 30s of screenshotting — the test's
 * skip path and the runtime's fail path agree on what counts as "encoder available,"
 * just with different responses to "not available" (skip vs throw).
 */
class ReportWebpExporterTest {

  @get:org.junit.Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test fun `assembleWebp emits a valid animated WebP from PNG frames`() {
    assumeTrue("ffmpeg with libwebp_anim must be on PATH", ffmpegHasLibwebpAnim())

    val framesDir = tempFolder.newFolder("frames")
    writeSentinelFrames(framesDir, frameCount = 6, widthPx = 64, heightPx = 48)
    val out = File(tempFolder.root, "out.webp")

    ReportWebpExporter.assembleWebp(framesDir, out, fps = 5, targetWidthPx = null)

    assertTrue(out.exists() && out.length() > 0, "WebP file is missing or empty")
    val bytes = out.readBytes()
    // WebP container layout: "RIFF" + 4-byte size + "WEBP" + chunks. Animated WebP must
    // carry an "ANIM" global chunk (animation parameters) and at least one "ANMF" frame
    // chunk. We assert both — a still WebP would pass the RIFF/WEBP check but be wrong
    // for an exporter that's meant to emit motion.
    assertTrue(bytes.size >= 12, "WebP file is shorter than the RIFF header")
    assertTrue(
      bytes.sliceArray(0..3).decodeToString() == "RIFF",
      "Missing RIFF magic — output is not a WebP container",
    )
    assertTrue(
      bytes.sliceArray(8..11).decodeToString() == "WEBP",
      "Missing WEBP magic — output is not a WebP container",
    )
    assertTrue(bytes.containsAscii("ANIM"), "Missing ANIM chunk — output is not animated")
    assertTrue(bytes.containsAscii("ANMF"), "Missing ANMF frame chunk — no frames encoded")
  }

  @Test fun `assembleWebp with a targetWidthPx scales the output smaller than the unscaled run`() {
    // Pins the scale-down behavior `--max-size` relies on: re-encoding the SAME source
    // frames at a smaller width must produce a meaningfully smaller WebP. Without this
    // test, a regression in the scale filter (wrong dimension arg, dropped flag) would
    // silently produce a file that's the SAME size and `MaxArtifactSize.enforce` would
    // loop until the floor without ever making progress.
    assumeTrue("ffmpeg with libwebp_anim must be on PATH", ffmpegHasLibwebpAnim())

    val framesDir = tempFolder.newFolder("frames")
    // 320×240 source so the rescaled (64px wide) output has ~5× fewer pixels — plenty
    // of headroom over WebP's per-frame container overhead.
    writeSentinelFrames(framesDir, frameCount = 6, widthPx = 320, heightPx = 240)

    val fullSize = File(tempFolder.root, "full.webp")
    ReportWebpExporter.assembleWebp(framesDir, fullSize, fps = 5, targetWidthPx = null)
    val rescaled = File(tempFolder.root, "rescaled.webp")
    ReportWebpExporter.assembleWebp(framesDir, rescaled, fps = 5, targetWidthPx = 64)

    assertTrue(fullSize.exists() && fullSize.length() > 0, "full-resolution WebP missing")
    assertTrue(rescaled.exists() && rescaled.length() > 0, "rescaled WebP missing")
    assertTrue(
      rescaled.length() < fullSize.length(),
      "Rescaled WebP (${rescaled.length()}B at 64px) is not smaller than full (${fullSize.length()}B at 320px) — scale filter regressed",
    )
    assertTrue(
      rescaled.readBytes().containsAscii("ANMF"),
      "Rescaled WebP is missing ANMF frames — encoder produced a still",
    )
  }

  private fun writeSentinelFrames(dir: File, frameCount: Int, widthPx: Int = 64, heightPx: Int = 48) {
    val palette = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)
    repeat(frameCount) { i ->
      val img = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
      val g = img.createGraphics()
      g.color = palette[i % palette.size]
      g.fillRect(0, 0, img.width, img.height)
      g.dispose()
      ImageIO.write(img, "png", File(dir, "frame_%05d.png".format(i)))
    }
  }

  /** Probe `ffmpeg -encoders` for `libwebp_anim`. Returns false on any failure / missing tool. */
  private fun ffmpegHasLibwebpAnim(): Boolean = runCatching {
    val proc = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
      .redirectErrorStream(true)
      .start()
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    out.contains("libwebp_anim")
  }.getOrDefault(false)

  private fun ByteArray.containsAscii(marker: String): Boolean {
    val needle = marker.toByteArray(Charsets.US_ASCII)
    outer@ for (i in 0..this.size - needle.size) {
      for (j in needle.indices) {
        if (this[i + j] != needle[j]) continue@outer
      }
      return true
    }
    return false
  }
}
