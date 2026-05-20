package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.rules.TemporaryFolder

/**
 * Unit-test coverage for the standalone bits of [FfmpegRescaleSupport] — the input
 * validations on `runFfmpegToTemp` and the `scaleFilter` helper. The full ffmpeg path
 * is exercised end-to-end by [ReportGifExporterTest] and [ReportWebpExporterTest];
 * this file is for the lightweight contract checks that don't need an ffmpeg
 * subprocess.
 */
class FfmpegRescaleSupportTest {

  @get:org.junit.Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  @Test fun `scaleFilter returns null for a null width so callers can no-op`() {
    assertNull(FfmpegRescaleSupport.scaleFilter(null, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO))
    assertNull(FfmpegRescaleSupport.scaleFilter(null, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN))
  }

  @Test fun `scaleFilter emits -1 for LANCZOS_AUTO (any height, GIF-friendly)`() {
    assertEquals(
      "scale=720:-1:flags=lanczos",
      FfmpegRescaleSupport.scaleFilter(720, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO),
    )
  }

  @Test fun `scaleFilter emits -2 for LANCZOS_EVEN (even height, libx264-required)`() {
    assertEquals(
      "scale=720:-2:flags=lanczos",
      FfmpegRescaleSupport.scaleFilter(720, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN),
    )
  }

  @Test fun `scaleFilter rejects zero or negative widths so the failure surfaces before ffmpeg`() {
    // ffmpeg would otherwise reject `scale=0:...` or `scale=-N:...` with an opaque
    // codec error 30+ seconds into a subprocess; surface the programmer error here
    // instead. Tested via both EvenHeight modes because the require is in the shared
    // entry path, but a regression that gates the require behind one mode would still
    // be caught.
    assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.scaleFilter(0, FfmpegRescaleSupport.EvenHeight.LANCZOS_AUTO)
    }
    assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.scaleFilter(-100, FfmpegRescaleSupport.EvenHeight.LANCZOS_EVEN)
    }
  }

  @Test fun `runFfmpegToTemp rejects a tempSuffix without a leading dot`() {
    val dest = File(tempFolder.root, "out.mp4")
    assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.runFfmpegToTemp(
        tag = "Test",
        dest = dest,
        tempSuffix = "mp4",
        errorContext = "test",
      ) { _ -> emptyList() }
    }
  }

  @Test fun `runFfmpegToTemp rejects a tag with path-unsafe characters`() {
    // The lowercased tag participates in the temp filename; a slash would land us in a
    // sibling dir of /tmp at best, or off the real filesystem at worst. Pin the
    // whitelist explicitly so a future caller can't accidentally bypass it.
    val dest = File(tempFolder.root, "out.mp4")
    for (badTag in listOf("Report/Bad", "../escape", "with space", "a:b")) {
      assertFailsWith<IllegalArgumentException>("tag '$badTag' should be rejected") {
        FfmpegRescaleSupport.runFfmpegToTemp(
          tag = badTag,
          dest = dest,
          tempSuffix = ".mp4",
          errorContext = "test",
        ) { _ -> emptyList() }
      }
    }
  }

  @Test fun `runFfmpegToTemp rejects a dest whose parent directory does not exist`() {
    // Defensive contract: caller is responsible for `parentFile?.mkdirs()`. Without
    // this check, a stale-path dest would surface as a generic rename failure 30+
    // seconds into the ffmpeg subprocess; the require short-circuits with the actual
    // problem.
    val missingParent = File(tempFolder.root, "does/not/exist/out.mp4")
    assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.runFfmpegToTemp(
        tag = "Test",
        dest = missingParent,
        tempSuffix = ".mp4",
        errorContext = "test",
      ) { _ -> emptyList() }
    }
  }
}
