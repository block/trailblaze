package xyz.block.trailblaze.report.utils

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.capture.video.SpriteSheetMetadata

/**
 * Cross-language behavioral contract for `video_sprites.txt` sprite metadata.
 *
 * `src/main/resources/xyz/block/trailblaze/report/sprite-metadata-parity-fixtures.json` is the
 * single source of truth for how sprite metadata parses and whether a sprite may play as a video
 * timeline, consumed by BOTH this test (driving the real [SpriteSheetMetadata], used by
 * WasmReport) and the TS mirror's `run-report-sprites.test.ts` (driving the real
 * `run-report-sprites.ts`, used by run-report-cli.ts). A semantic drift in either implementation
 * fails that side's suite.
 *
 * To change either rule: update both implementations AND the fixture in the same change. Never
 * encode new semantics in only one language's tests. (Same fixture-parity pattern as
 * `SessionEventsParityFixturesTest` and `MatcherParityFixturesTest`.)
 */
class SpriteMetadataParityFixturesTest {

  private companion object {
    /** Sentinel default marking `rejectionMultiSheetCapable` as absent from the fixture case. */
    val ABSENT = JsonPrimitive("__absent__")
  }

  @Serializable
  private data class FrameMapSample(val logical: Int, val physical: Int)

  @Serializable
  private data class SheetRowsSample(val sheet: Int, val rows: Int)

  @Serializable
  private data class ParsedExpectation(
    val fps: Int,
    val frames: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val uniqueFrames: Int? = null,
    val sheets: Int,
    val restamped: Boolean,
    val frameWidth: Int? = null,
    val frameMapExplicit: Boolean,
    val frameMapLength: Int,
    val frameMapSamples: List<FrameMapSample>,
    val sheetRowsSamples: List<SheetRowsSample> = emptyList(),
  )

  /** `parsed == null` means the file must fail to parse (no verdict to check). */
  @Serializable
  private data class FixtureCase(
    val name: String,
    val txt: String,
    val stepScreenshotCount: Int,
    val parsed: ParsedExpectation? = null,
    val rejection: String? = null,
    /**
     * Verdict for a consumer that declared multi-sheet support. Typed as non-nullable
     * [JsonElement] to keep the fixture's three-way semantics: key absent (the [ABSENT] sentinel
     * default — a nullable field would collapse "absent" and "explicit null" into Kotlin null) →
     * same as [rejection]; explicit JSON null ([JsonNull]) → accepted when capable; a string →
     * that rejection.
     */
    val rejectionMultiSheetCapable: JsonElement = ABSENT,
  )

  /** The acceptance thresholds the fixture pins; each suite asserts its implementation matches. */
  @Serializable
  private data class FixtureConstants(
    @SerialName("MIN_USEFUL_UNIQUE_FRAMES") val minUsefulUniqueFrames: Int,
    @SerialName("MIN_ALIASING_TOTAL_FRAMES") val minAliasingTotalFrames: Int,
    @SerialName("RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC") val restampedSingleFrameDominanceFrac: Double,
  )

  @Serializable
  private data class ParityFixtures(val constants: FixtureConstants, val cases: List<FixtureCase>)

  private val fixtures: ParityFixtures by lazy {
    // Repo-root-relative walk-up, same as SessionEventsParityFixturesTest.
    val file = locate("trailblaze-report/src/main/resources/xyz/block/trailblaze/report/sprite-metadata-parity-fixtures.json")
    Json { ignoreUnknownKeys = true }.decodeFromString<ParityFixtures>(file.readText())
  }

  @Test
  fun `acceptance constants match the shared parity fixture`() {
    assertEquals(
      fixtures.constants.minUsefulUniqueFrames,
      SpriteSheetMetadata.MIN_USEFUL_UNIQUE_FRAMES,
      "MIN_USEFUL_UNIQUE_FRAMES",
    )
    assertEquals(
      fixtures.constants.minAliasingTotalFrames,
      SpriteSheetMetadata.MIN_ALIASING_TOTAL_FRAMES,
      "MIN_ALIASING_TOTAL_FRAMES",
    )
    assertEquals(
      fixtures.constants.restampedSingleFrameDominanceFrac,
      SpriteSheetMetadata.RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC,
      0.0,
      "RESTAMPED_SINGLE_FRAME_DOMINANCE_FRAC",
    )
  }

  @Test
  fun `parse and acceptance agree with the shared parity fixtures`() {
    check(fixtures.cases.isNotEmpty())
    fixtures.cases.forEach { case ->
      val meta = SpriteSheetMetadata.parse(case.txt)
      val expected = case.parsed
      if (expected == null) {
        assertNull(meta, "case='${case.name}': expected an unparseable file")
        return@forEach
      }
      assertNotNull(meta, "case='${case.name}': expected the file to parse")
      assertEquals(expected.fps, meta.fps, "case='${case.name}': fps")
      assertEquals(expected.frames, meta.frames, "case='${case.name}': frames")
      assertEquals(expected.height, meta.height, "case='${case.name}': height")
      assertEquals(expected.columns, meta.columns, "case='${case.name}': columns")
      assertEquals(expected.rows, meta.rows, "case='${case.name}': rows")
      assertEquals(expected.uniqueFrames, meta.uniqueFrames, "case='${case.name}': uniqueFrames")
      assertEquals(expected.sheets, meta.sheets, "case='${case.name}': sheets")
      assertEquals(expected.restamped, meta.restamped, "case='${case.name}': restamped")
      assertEquals(expected.frameWidth, meta.frameWidth, "case='${case.name}': frameWidth")
      assertEquals(expected.frameMapExplicit, meta.frameMap != null, "case='${case.name}': frameMapExplicit")
      val resolvedLength = meta.frameMap?.size ?: meta.frames
      assertEquals(expected.frameMapLength, resolvedLength, "case='${case.name}': frameMapLength")
      expected.frameMapSamples.forEach { sample ->
        assertEquals(
          sample.physical,
          meta.physicalFrame(sample.logical),
          "case='${case.name}': physicalFrame(${sample.logical})",
        )
      }
      expected.sheetRowsSamples.forEach { sample ->
        assertEquals(
          sample.rows,
          meta.sheetRows(sample.sheet),
          "case='${case.name}': sheetRows(${sample.sheet})",
        )
      }
      assertEquals(
        case.rejection,
        SpriteSheetMetadata.rejectionReason(meta, case.stepScreenshotCount)?.wireName,
        "case='${case.name}': rejection verdict",
      )
      val expectedCapableVerdict = when (val e = case.rejectionMultiSheetCapable) {
        ABSENT -> case.rejection
        is JsonNull -> null
        else -> e.jsonPrimitive.content
      }
      assertEquals(
        expectedCapableVerdict,
        SpriteSheetMetadata.rejectionReason(meta, case.stepScreenshotCount, supportsMultiSheet = true)?.wireName,
        "case='${case.name}': rejection verdict (multi-sheet-capable consumer)",
      )
    }
  }

  /**
   * Walk up from the JVM working dir to find the repo-root-anchored fixture. Same anchor pattern
   * as `SessionEventsParityFixturesTest.locate`.
   */
  private fun locate(repoRelativePath: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
