package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit-level coverage for [FfmpegRescaleSupport.requireEncoder] — the ffmpeg
 * encoder-availability probe used by [ReportWebpExporter.requireLibwebpAnim].
 *
 * The caller exercises its own `requireXxx` only via integration. This file pins the
 * shared helper directly so the contract — empty-input rejection, error-message shape,
 * missing-hint surfacing — can't drift unnoticed.
 *
 * **Not exercised here:** the happy-path "encoder is available" branch, which would
 * require either ffmpeg installed (CI-environment-dependent) or process-builder mocking
 * (overkill for the value). The production caller indirectly asserts it via its
 * integration test.
 */
class FfmpegRescaleSupportTest {

  @Test
  fun `requireEncoder throws on empty encoderName before reaching the subprocess`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.requireEncoder(encoderName = "", missingHint = "install ffmpeg")
    }
    assertTrue(
      ex.message!!.contains("non-blank"),
      "Error must come from the encoderName.isNotBlank() require: '${ex.message}'",
    )
  }

  @Test
  fun `requireEncoder throws on blank missingHint`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      FfmpegRescaleSupport.requireEncoder(encoderName = "libwebp", missingHint = "   ")
    }
    assertTrue(
      ex.message!!.contains("install advice"),
      "Error must call out the hint-required contract: '${ex.message}'",
    )
  }

  @Test
  fun `requireEncoder error message includes the missingHint when the encoder is absent`() {
    val hint = "Try brew install ffmpeg-tobor7q9 (a deliberately non-existent variant)."
    val ex = assertFailsWith<IllegalStateException> {
      // `zzz_nonexistent_encoder_xyz` is guaranteed not to exist in any ffmpeg build, so
      // the subprocess probe will not find it (or the subprocess itself may not exist if
      // ffmpeg is missing from the CI agent — either path produces the same throw). The
      // assertion is that the hint propagates into the error, not which path triggered.
      FfmpegRescaleSupport.requireEncoder(
        encoderName = "zzz_nonexistent_encoder_xyz",
        missingHint = hint,
      )
    }
    assertTrue(
      ex.message!!.contains(hint),
      "Missing hint must appear in the error so the caller's install advice reaches the user: '${ex.message}'",
    )
    assertTrue(
      ex.message!!.contains("zzz_nonexistent_encoder_xyz"),
      "Error must name the requested encoder so the user knows what to install: '${ex.message}'",
    )
  }
}
