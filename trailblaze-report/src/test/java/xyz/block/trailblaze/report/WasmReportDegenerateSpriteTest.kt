package xyz.block.trailblaze.report

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class WasmReportDegenerateSpriteTest {

  @Test
  fun `legacy sheet with null unique count is never degenerate`() {
    assertFalse(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = null,
        totalFrameCount = 234,
        stepScreenshotCount = 50,
      ),
    )
  }

  @Test
  fun `real-world broken screenrecord is degenerate regardless of step count`() {
    // A near-static recorded run: 3 unique frames stretched across 234 logical frames in a
    // replay-mode run where only a couple of steps carried screenshots (stepScreenshotCount ~2).
    // The old `unique < steps` guard let this through (3 < 2 is false); the total-frame rule
    // catches it.
    assertTrue(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 3,
        totalFrameCount = 234,
        stepScreenshotCount = 2,
      ),
    )
  }

  @Test
  fun `sparse native sprite with far fewer unique frames than steps is degenerate`() {
    assertTrue(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 4,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  @Test
  fun `healthy farm sprite with many unique frames is not degenerate`() {
    assertFalse(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 200,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  @Test
  fun `genuinely short healthy clip with little dedup is not degenerate`() {
    // A short test: 5 unique frames over 6 logical frames is real motion, not aliasing.
    assertFalse(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 5,
        totalFrameCount = 6,
        stepScreenshotCount = 3,
      ),
    )
  }

  @Test
  fun `short low-motion clip below the aliasing total is not degenerate`() {
    // ~15s spinner at 2fps: few unique frames but the total is below the aliasing floor and
    // there are no step screenshots to undercut it, so it still renders.
    assertFalse(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 4,
        totalFrameCount = 30,
        stepScreenshotCount = 0,
      ),
    )
  }

  @Test
  fun `unique frames at the floor are not degenerate`() {
    assertFalse(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 8,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  @Test
  fun `unique frames just below the floor with high total is degenerate`() {
    assertTrue(
      WasmReport.isSpriteDegenerate(
        uniqueFrameCount = 7,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }
}
