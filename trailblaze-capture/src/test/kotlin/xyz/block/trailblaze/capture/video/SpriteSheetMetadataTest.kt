package xyz.block.trailblaze.capture.video

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared sprite-metadata rules (moved here from the former
 * WasmReportDegenerateSpriteTest when the rules were consolidated out of WasmReport). The
 * cross-language parse/acceptance contract is additionally locked by
 * `sprite-metadata-parity-fixtures.json` (see SpriteMetadataParityFixturesTest in
 * :trailblaze-report and `run-report-sprites.test.ts`).
 */
class SpriteSheetMetadataTest {

  @Test
  fun `legacy sheet with null unique count is never degenerate`() {
    assertFalse(
      SpriteSheetMetadata.isSpriteDegenerate(
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
      SpriteSheetMetadata.isSpriteDegenerate(
        uniqueFrameCount = 3,
        totalFrameCount = 234,
        stepScreenshotCount = 2,
      ),
    )
  }

  @Test
  fun `sparse native sprite with far fewer unique frames than steps is degenerate`() {
    assertTrue(
      SpriteSheetMetadata.isSpriteDegenerate(
        uniqueFrameCount = 4,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  @Test
  fun `healthy farm sprite with many unique frames is not degenerate`() {
    assertFalse(
      SpriteSheetMetadata.isSpriteDegenerate(
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
      SpriteSheetMetadata.isSpriteDegenerate(
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
      SpriteSheetMetadata.isSpriteDegenerate(
        uniqueFrameCount = 4,
        totalFrameCount = 30,
        stepScreenshotCount = 0,
      ),
    )
  }

  @Test
  fun `unique frames at the floor are not degenerate`() {
    assertFalse(
      SpriteSheetMetadata.isSpriteDegenerate(
        uniqueFrameCount = 8,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  @Test
  fun `unique frames just below the floor with high total is degenerate`() {
    assertTrue(
      SpriteSheetMetadata.isSpriteDegenerate(
        uniqueFrameCount = 7,
        totalFrameCount = 234,
        stepScreenshotCount = 30,
      ),
    )
  }

  // --- isRestampedSpriteDominatedBySingleFrame (#3 safety net) ---

  /** frameMap mirroring a real broken-screenrecord CI failure: one physical frame owns ~43% of the map. */
  private fun t3LikeFrameMap(): List<Int> =
    buildList {
      addAll(listOf(0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 8, 9, 10)) // 16 varied leading frames
      repeat(44) { add(11) } // one frame frozen across 44 of 102 logical frames (~43%)
      addAll(List(42) { 12 + (it % 3) }) // trailing motion to reach 102 total
    }

  @Test
  fun `restamped sprite dominated by one frame is degenerate`() {
    val frameMap = t3LikeFrameMap()
    assertTrue(
      SpriteSheetMetadata.isRestampedSpriteDominatedBySingleFrame(
        restamped = true,
        frameMap = frameMap,
        totalFrameCount = frameMap.size,
      ),
    )
  }

  @Test
  fun `same dominated frameMap is NOT degenerate when timing was trustworthy (not restamped)`() {
    // Identical distribution, but restamped=false means the frames carry real wall-clock PTS —
    // a genuinely long static period, not a guessed one. The video timeline is honest; keep it.
    val frameMap = t3LikeFrameMap()
    assertFalse(
      SpriteSheetMetadata.isRestampedSpriteDominatedBySingleFrame(
        restamped = false,
        frameMap = frameMap,
        totalFrameCount = frameMap.size,
      ),
    )
  }

  @Test
  fun `restamped sprite with evenly distributed frames is not degenerate`() {
    // 102 logical frames spread across many physical frames — no single frame dominates.
    val frameMap = List(102) { it / 2 } // each physical frame owns at most 2 logical slots
    assertFalse(
      SpriteSheetMetadata.isRestampedSpriteDominatedBySingleFrame(
        restamped = true,
        frameMap = frameMap,
        totalFrameCount = frameMap.size,
      ),
    )
  }

  @Test
  fun `short restamped clip dominated by one frame is not degenerate (below aliasing floor)`() {
    // A brief clip where one frame naturally owns most slots — too few total frames to be sure
    // it's broken rather than genuinely low-motion, so we don't strip it.
    val frameMap = List(30) { if (it < 25) 0 else it }
    assertFalse(
      SpriteSheetMetadata.isRestampedSpriteDominatedBySingleFrame(
        restamped = true,
        frameMap = frameMap,
        totalFrameCount = frameMap.size,
      ),
    )
  }

  @Test
  fun `restamped sprite with no frameMap is not degenerate`() {
    assertFalse(
      SpriteSheetMetadata.isRestampedSpriteDominatedBySingleFrame(
        restamped = true,
        frameMap = null,
        totalFrameCount = 234,
      ),
    )
  }

  // --- parse ---

  @Test
  fun `parses a full modern metadata file`() {
    val meta = SpriteSheetMetadata.parse(
      """
      fps=2
      frames=6
      height=720
      frameWidth=328
      columns=2
      rows=3
      uniqueFrames=4
      sheets=1
      frameMap=0,0,1,2,2,3
      restamped=false
      """.trimIndent(),
    )
    assertNotNull(meta)
    assertEquals(2, meta.fps)
    assertEquals(6, meta.frames)
    assertEquals(720, meta.height)
    assertEquals(328, meta.frameWidth)
    assertEquals(2, meta.columns)
    assertEquals(3, meta.rows)
    assertEquals(4, meta.uniqueFrames)
    assertEquals(1, meta.sheets)
    assertEquals(listOf(0, 0, 1, 2, 2, 3), meta.frameMap)
    assertFalse(meta.restamped)
    assertEquals(2, meta.physicalFrame(3))
  }

  @Test
  fun `missing required frames or height fails the parse`() {
    assertNull(SpriteSheetMetadata.parse("fps=2\nheight=720"))
    assertNull(SpriteSheetMetadata.parse("fps=2\nframes=10"))
    assertNull(SpriteSheetMetadata.parse("fps=2\nframes=0\nheight=720"))
  }

  @Test
  fun `legacy file defaults optional keys`() {
    val meta = SpriteSheetMetadata.parse("fps=2\nframes=40\nheight=600")
    assertNotNull(meta)
    assertEquals(1, meta.columns)
    assertEquals(40, meta.rows) // uniqueFrames absent → rows defaults to frames
    assertNull(meta.uniqueFrames)
    assertEquals(1, meta.sheets)
    assertNull(meta.frameMap)
    assertFalse(meta.restamped)
    assertNull(meta.frameWidth)
    assertEquals(7, meta.physicalFrame(7)) // identity without a frameMap
  }

  @Test
  fun `malformed frameMap entries fall back to identity`() {
    val meta = SpriteSheetMetadata.parse("frames=4\nheight=600\nframeMap=0,1,x,3")
    assertNotNull(meta)
    assertNull(meta.frameMap)
    assertEquals(2, meta.physicalFrame(2))
  }

  @Test
  fun `frameMap whose length does not match frames falls back to identity wholesale`() {
    val meta = SpriteSheetMetadata.parse("frames=6\nheight=600\nframeMap=0,1,2")
    assertNotNull(meta)
    assertNull(meta.frameMap)
    assertEquals(4, meta.physicalFrame(4))
  }

  // --- rejectionReason ---

  private fun meta(
    frames: Int,
    uniqueFrames: Int? = null,
    sheets: Int = 1,
    frameMap: List<Int>? = null,
    restamped: Boolean = false,
    columns: Int = 1,
    rows: Int? = null,
  ) = SpriteSheetMetadata(
    fps = 2,
    frames = frames,
    height = 600,
    columns = columns,
    rows = rows ?: uniqueFrames ?: frames,
    uniqueFrames = uniqueFrames,
    sheets = sheets,
    frameMap = frameMap,
    restamped = restamped,
    frameWidth = null,
  )

  @Test
  fun `healthy sprite is accepted`() {
    assertNull(SpriteSheetMetadata.rejectionReason(meta(frames = 120, uniqueFrames = 100), stepScreenshotCount = 10))
  }

  @Test
  fun `sheetRows is the full grid except on the final partial sheet`() {
    // 10 unique frames over 3 sheets of 2x2: sheets 0 and 1 are full, sheet 2 holds 2 frames = 1 row.
    val meta = meta(frames = 12, uniqueFrames = 10, sheets = 3, columns = 2, rows = 2)
    assertEquals(2, meta.sheetRows(0))
    assertEquals(2, meta.sheetRows(1))
    assertEquals(1, meta.sheetRows(2))
  }

  @Test
  fun `multi-sheet sprite is accepted only by a consumer that declares support`() {
    val multi = meta(frames = 120, uniqueFrames = 100, sheets = 2)
    assertEquals(
      SpriteSheetMetadata.SpriteRejection.MULTI_SHEET,
      SpriteSheetMetadata.rejectionReason(multi, stepScreenshotCount = 10),
    )
    assertNull(SpriteSheetMetadata.rejectionReason(multi, stepScreenshotCount = 10, supportsMultiSheet = true))
    // Without uniqueFrames the last sheet's geometry is underivable — reject even when capable.
    val noUnique = meta(frames = 120, uniqueFrames = null, sheets = 2)
    assertEquals(
      SpriteSheetMetadata.SpriteRejection.MULTI_SHEET,
      SpriteSheetMetadata.rejectionReason(noUnique, stepScreenshotCount = 10, supportsMultiSheet = true),
    )
  }

  @Test
  fun `restamped dominated sprite is rejected with the restamped reason`() {
    val frameMap = t3LikeFrameMap()
    assertEquals(
      SpriteSheetMetadata.SpriteRejection.RESTAMPED_DOMINATED,
      SpriteSheetMetadata.rejectionReason(
        meta(frames = frameMap.size, uniqueFrames = 15, frameMap = frameMap, restamped = true),
        stepScreenshotCount = 2,
      ),
    )
  }
}
