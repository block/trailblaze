package xyz.block.trailblaze.cli

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coverage for [ReportWebpExporter] at two levels:
 *
 *  - **Pure argv builders** ([ReportWebpExporter.frameDurationMs], [img2webpArgs],
 *    [cwebpResizeArgs], [webpmuxArgs]) — the observable contract of this exporter is the
 *    command lines it hands to libwebp. These run with no subprocess and no device, so
 *    they pin the flags (lossy vs lossless, loop, per-frame duration, resize) that a
 *    behavior-preserving refactor must keep.
 *  - **A subprocess smoke test** that feeds the assembler synthetic PNG frames and asserts
 *    a syntactically valid animated-WebP container came out. Skipped via `assumeTrue` when
 *    the libwebp tools aren't on PATH — the same health check the production preflight
 *    ([ReportWebpExporter.requireWebpTools]) uses (`-version` must exit 0); the test's skip
 *    path and the runtime's fail path agree on what counts as "tooling available," just
 *    with different responses to "not available" (skip vs throw).
 */
class ReportWebpExporterTest {

  @get:org.junit.Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  // ---- pure argv-builder contracts (no subprocess) ----

  @Test fun `frameDurationMs is the per-frame ms for the fps, clamped to at least 1`() {
    assertEquals(200, ReportWebpExporter.frameDurationMs(5))
    assertEquals(83, ReportWebpExporter.frameDurationMs(12))
    // A very high fps rounds toward 0; clamp keeps it a valid (non-default) 1ms hold.
    assertEquals(1, ReportWebpExporter.frameDurationMs(5000))
  }

  @Test fun `img2webpArgs builds a lossy infinite-loop animation with per-frame duration`() {
    val frames = listOf(File("/frames/frame_00000.png"), File("/frames/frame_00001.png"))
    val args = ReportWebpExporter.img2webpArgs(frames, File("/out/anim.webp"), durationMs = 83)

    assertEquals("img2webp", args.first())
    // img2webp defaults to LOSSLESS — the explicit -lossy is the regression guard.
    assertTrue(args.containsInOrder("-lossy"), "must force lossy: $args")
    assertTrue(args.containsInOrder("-loop", "0"), "must loop forever: $args")
    assertTrue(args.containsInOrder("-d", "83"), "must set per-frame duration: $args")
    assertTrue(args.containsInOrder("-q", "75"), "must pin quality: $args")
    // Frames appear in order, before the -o output.
    assertTrue(
      args.containsInOrder("/frames/frame_00000.png", "/frames/frame_00001.png", "-o", "/out/anim.webp"),
      "frames must precede -o output, in order: $args",
    )
  }

  @Test fun `cwebpResizeArgs resizes to the target width preserving aspect`() {
    val args = ReportWebpExporter.cwebpResizeArgs(File("/in/f.png"), File("/out/f.webp"), widthPx = 720)
    assertEquals("cwebp", args.first())
    // height 0 = preserve aspect ratio.
    assertTrue(args.containsInOrder("-resize", "720", "0"), "must resize width-only: $args")
    assertTrue(args.containsInOrder("-q", "75"), "must pin quality: $args")
    assertTrue(args.containsInOrder("/in/f.png", "-o", "/out/f.webp"), "input before -o output: $args")
  }

  @Test fun `webpmuxArgs muxes each frame with its duration into an infinite loop`() {
    val frames = listOf(File("/w/f_00000.webp"), File("/w/f_00001.webp"))
    val args = ReportWebpExporter.webpmuxArgs(frames, File("/out/anim.webp"), durationMs = 100)
    assertEquals("webpmux", args.first())
    assertTrue(args.containsInOrder("-frame", "/w/f_00000.webp", "+100"), "frame 0 with duration: $args")
    assertTrue(args.containsInOrder("-frame", "/w/f_00001.webp", "+100"), "frame 1 with duration: $args")
    assertTrue(args.containsInOrder("-loop", "0", "-o", "/out/anim.webp"), "loop + output: $args")
  }

  @Test fun `frameDurationMs rejects a non-positive fps`() {
    assertFailsWith<IllegalArgumentException> { ReportWebpExporter.frameDurationMs(0) }
    assertFailsWith<IllegalArgumentException> { ReportWebpExporter.frameDurationMs(-5) }
  }

  @Test fun `requireWebpTools throws naming every missing tool`() {
    // Inject a probe so the throw path is exercised without depending on what's installed.
    val ex = assertFailsWith<IllegalStateException> {
      ReportWebpExporter.requireWebpTools(probe = { it == "img2webp" }) // only img2webp "present"
    }
    assertTrue(ex.message!!.contains("cwebp"), "must name missing cwebp: ${ex.message}")
    assertTrue(ex.message!!.contains("webpmux"), "must name missing webpmux: ${ex.message}")
    assertTrue(!ex.message!!.contains("img2webp"), "must not list the present tool: ${ex.message}")
  }

  @Test fun `requireWebpTools passes when every tool is present`() {
    ReportWebpExporter.requireWebpTools(probe = { true }) // does not throw
  }

  @Test fun `assembleWebp fails fast when the frames directory has no frames`() {
    val emptyDir = tempFolder.newFolder("empty")
    val ex = assertFailsWith<IllegalStateException> {
      ReportWebpExporter.assembleWebp(emptyDir, File(tempFolder.root, "out.webp"), fps = 5, targetWidthPx = null)
    }
    assertTrue(ex.message!!.contains("no frame"), "must explain the empty-frames cause: ${ex.message}")
  }

  // ---- subprocess smoke tests (skipped without the libwebp tools on PATH) ----

  @Test fun `assembleWebp emits a valid animated WebP from PNG frames`() {
    assumeTrue("the libwebp tools (the `webp` package) must be on PATH", webpToolsOnPath())

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
    // test, a regression in the cwebp/webpmux path (wrong resize arg, dropped frame) would
    // silently produce a file that's the SAME size and `MaxArtifactSize.enforce` would
    // loop until the floor without ever making progress.
    assumeTrue("the libwebp tools (the `webp` package) must be on PATH", webpToolsOnPath())

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
      "Rescaled WebP (${rescaled.length()}B at 64px) is not smaller than full (${fullSize.length()}B at 320px) — resize regressed",
    )
    assertTrue(
      rescaled.readBytes().containsAscii("ANMF"),
      "Rescaled WebP is missing ANMF frames — assembly produced a still",
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

  /**
   * Probe whether the libwebp tools the assembler uses are on PATH and healthy. Mirrors the
   * production preflight ([ReportWebpExporter.requireWebpTools]): each must exit 0 on
   * `-version`. Returns false on any missing/unhealthy tool so the smoke test skips rather
   * than failing in a harder-to-diagnose way mid-encode.
   */
  private fun webpToolsOnPath(): Boolean = listOf("img2webp", "cwebp", "webpmux").all { tool ->
    runCatching {
      val proc = ProcessBuilder(tool, "-version").redirectErrorStream(true).start()
      proc.inputStream.bufferedReader().readText()
      proc.waitFor() == 0
    }.getOrDefault(false)
  }

  /** True if [args] contains [needles] as a contiguous, in-order subsequence. */
  private fun List<String>.containsInOrder(vararg needles: String): Boolean {
    if (needles.isEmpty()) return true
    for (i in 0..this.size - needles.size) {
      if ((needles.indices).all { this[i + it] == needles[it] }) return true
    }
    return false
  }

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
