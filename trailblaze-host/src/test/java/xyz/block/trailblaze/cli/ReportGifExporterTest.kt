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
 * Smoke test for the GIF encoder half of [ReportGifExporter]. Avoids the full Playwright
 * capture loop by feeding the assembler synthetic PNG frames and asserting that ffmpeg
 * produced a syntactically valid GIF89a container — and that the `--max-size` rescale
 * path actually shrinks the output.
 *
 * Skipped via `assumeTrue` when `ffmpeg` isn't on PATH. (`palettegen`/`paletteuse` ship
 * with every modern ffmpeg build, so there's no separate encoder probe needed — unlike
 * the WebP test which has to check for `libwebp_anim`.)
 */
class ReportGifExporterTest {

  @get:org.junit.Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test fun `assembleGif emits a valid GIF89a from PNG frames`() {
    assumeTrue("ffmpeg must be on PATH", ffmpegAvailable())

    val framesDir = tempFolder.newFolder("frames")
    writeSentinelFrames(framesDir, frameCount = 6, widthPx = 64, heightPx = 48)
    val out = File(tempFolder.root, "out.gif")

    ReportGifExporter.assembleGif(framesDir, out, fps = 5, targetWidthPx = null)

    assertTrue(out.exists() && out.length() > 0, "GIF file is missing or empty")
    val bytes = out.readBytes()
    // GIF89a magic: 6-byte ASCII header "GIF89a" (or "GIF87a", but palettegen emits 89a).
    assertTrue(bytes.size >= 6, "GIF file is shorter than the magic header")
    val magic = bytes.sliceArray(0..5).decodeToString()
    assertTrue(
      magic == "GIF89a" || magic == "GIF87a",
      "Missing GIF magic header — got '$magic'",
    )
    // GIF trailer: terminating byte 0x3B at end-of-stream. A truncated GIF (interrupted
    // ffmpeg mid-write) will lack this — and the temp-file-rename pattern is designed
    // to make that impossible, so we assert the invariant the pattern is meant to hold.
    assertTrue(
      bytes.last() == 0x3B.toByte(),
      "Missing GIF trailer byte — output may be truncated",
    )
  }

  @Test fun `assembleGif with a targetWidthPx scales the output smaller than the unscaled run`() {
    // Pins the scale-down behavior `--max-size` relies on: re-encoding the SAME source
    // frames at a smaller width must produce a meaningfully smaller GIF. Without this
    // test, a regression in the scale filter (wrong dimension arg, lost from the
    // palettegen chain) would silently produce a file that's the SAME size and
    // `MaxArtifactSize.enforce` would loop until the floor without ever making progress.
    assumeTrue("ffmpeg must be on PATH", ffmpegAvailable())

    val framesDir = tempFolder.newFolder("frames")
    // 320×240 source so the rescaled (64px wide) output has ~5× fewer pixels — plenty
    // of headroom over GIF's per-frame palette + LZW overhead.
    writeSentinelFrames(framesDir, frameCount = 6, widthPx = 320, heightPx = 240)

    val fullSize = File(tempFolder.root, "full.gif")
    ReportGifExporter.assembleGif(framesDir, fullSize, fps = 5, targetWidthPx = null)
    val rescaled = File(tempFolder.root, "rescaled.gif")
    ReportGifExporter.assembleGif(framesDir, rescaled, fps = 5, targetWidthPx = 64)

    assertTrue(fullSize.exists() && fullSize.length() > 0, "full-resolution GIF missing")
    assertTrue(rescaled.exists() && rescaled.length() > 0, "rescaled GIF missing")
    assertTrue(
      rescaled.length() < fullSize.length(),
      "Rescaled GIF (${rescaled.length()}B at 64px) is not smaller than full (${fullSize.length()}B at 320px) — scale filter regressed",
    )
    assertTrue(
      rescaled.readBytes().sliceArray(0..2).decodeToString() == "GIF",
      "Rescaled GIF is missing GIF magic — encoder produced something else",
    )
  }

  @Test fun `assembleGif writes via temp-file so a missing dest extension still works`() {
    // Reproduces the `--gif out` (no extension) bug Copilot caught in a prior review
    // round: ffmpeg infers the muxer from the output filename, so an extensionless dest
    // would fail without the explicit -f gif + .gif temp file. This test passes a dest
    // path with no extension and asserts a valid GIF lands there anyway.
    assumeTrue("ffmpeg must be on PATH", ffmpegAvailable())

    val framesDir = tempFolder.newFolder("frames")
    writeSentinelFrames(framesDir, frameCount = 4, widthPx = 64, heightPx = 48)
    val extensionless = File(tempFolder.root, "out")

    ReportGifExporter.assembleGif(framesDir, extensionless, fps = 5, targetWidthPx = null)

    assertTrue(extensionless.exists() && extensionless.length() > 0, "GIF missing at extensionless dest")
    val magic = extensionless.readBytes().sliceArray(0..5).decodeToString()
    assertTrue(
      magic == "GIF89a" || magic == "GIF87a",
      "Expected GIF magic at extensionless dest, got '$magic'",
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

  /** Probe that ffmpeg is on PATH. Returns false on any failure. */
  private fun ffmpegAvailable(): Boolean = runCatching {
    val proc = ProcessBuilder("ffmpeg", "-version")
      .redirectErrorStream(true)
      .start()
    proc.inputStream.bufferedReader().readText()
    proc.waitFor() == 0
  }.getOrDefault(false)
}
