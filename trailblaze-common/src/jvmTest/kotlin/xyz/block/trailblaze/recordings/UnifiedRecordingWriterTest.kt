package xyz.block.trailblaze.recordings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter

/**
 * Contract tests for the shared save-back writer used by the CLI, MCP, and desktop recording
 * surfaces. Exercises routing (greenfield vs per-classifier-siblings vs shared unified trail), the
 * shadowing-sibling refusal guard, and the unified read-merge-write (fresh create,
 * second-classifier merge, corrupt refusal, multi-tool trailhead) directly against a temp
 * directory — no device, daemon, or CLI needed. The pure merge itself is covered by the
 * `:trailblaze-models` adapter tests.
 */
class UnifiedRecordingWriterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  // ---------------------------------------------------------------------------
  // shouldMergeIntoSharedTrail — routing decision
  // ---------------------------------------------------------------------------

  @Test
  fun `shouldMergeIntoSharedTrail is false for a blank classifier`() {
    val dir = tempFolder.newFolder()
    assertFalse(UnifiedRecordingWriter.shouldMergeIntoSharedTrail(dir, ""))
  }

  @Test
  fun `shouldMergeIntoSharedTrail is true for a greenfield directory`() {
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: do it\n")
    assertTrue(UnifiedRecordingWriter.shouldMergeIntoSharedTrail(dir, "android"))
  }

  @Test
  fun `shouldMergeIntoSharedTrail is true when a unified trail file already exists`() {
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertTrue(UnifiedRecordingWriter.shouldMergeIntoSharedTrail(dir, "android"))
  }

  @Test
  fun `shouldMergeIntoSharedTrail is false for a directory that already holds per-classifier siblings`() {
    val dir = tempFolder.newFolder()
    File(dir, "ios.trail.yaml").writeText(siblingRecordingYaml())
    assertFalse(UnifiedRecordingWriter.shouldMergeIntoSharedTrail(dir, "android"))
  }

  @Test
  fun `shouldMergeIntoSharedTrail is true for a named file whose content is unified`() {
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    File(dir, "payment.trail.yaml").writeText("trail:\n  - step: p\n")
    assertTrue(UnifiedRecordingWriter.shouldMergeIntoSharedTrail(named, "android"))
  }

  // ---------------------------------------------------------------------------
  // unifiedTrailPresent — the gate-off refusal guard
  // ---------------------------------------------------------------------------

  @Test
  fun `unifiedTrailPresent is true when the directory holds a trail file`() {
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertTrue(UnifiedRecordingWriter.unifiedTrailPresent(dir))
  }

  @Test
  fun `unifiedTrailPresent is true for a named unified-content file`() {
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    assertTrue(UnifiedRecordingWriter.unifiedTrailPresent(named))
  }

  @Test
  fun `unifiedTrailPresent is false for a siblings-only directory`() {
    val dir = tempFolder.newFolder()
    File(dir, "android.trail.yaml").writeText(siblingRecordingYaml())
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: s\n")
    assertFalse(UnifiedRecordingWriter.unifiedTrailPresent(dir))
  }

  // ---------------------------------------------------------------------------
  // mergeIntoUnified — the read-merge-write contract
  // ---------------------------------------------------------------------------

  @Test
  fun `mergeIntoUnified creates a fresh unified trail from a first recording`() {
    val dir = tempFolder.newFolder()
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `mergeIntoUnified merges a second classifier without disturbing the first`() {
    val dir = tempFolder.newFolder()
    val yaml = createTrailblazeYaml()
    UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "androidCart"),
      "android",
    )

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = "IOS_HOST", toolName = "iosCart"),
      "ios",
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = yaml.decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot preserved")
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot added")
  }

  @Test
  fun `mergeIntoUnified refuses to overwrite an unreadable existing trail file`() {
    val dir = tempFolder.newFolder()
    val corrupt = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val items = recordingItems(driver = "D", toolName = "tapCart")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt)
    assertEquals(
      "foo: not a unified trail\n",
      corrupt.readText(),
      "an unreadable trail.yaml must be left untouched, not clobbered by the merge",
    )
  }

  @Test
  fun `mergeIntoUnified reports a multi-tool trailhead as unsupported and writes nothing`() {
    val dir = tempFolder.newFolder()
    val items = multiToolTrailheadItems(toolNames = listOf("clearBootstrap", "openBootstrap"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported)
    assertEquals(2, (outcome as UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported).toolCount)
    assertFalse(
      File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(),
      "an un-representable trailhead must not produce a unified trail.yaml — the caller writes a per-classifier sibling",
    )
  }

  @Test
  fun `mergeIntoUnified keeps a single-tool trailhead in the unified trail`() {
    val dir = tempFolder.newFolder()
    val items = multiToolTrailheadItems(toolNames = listOf("openBootstrap"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("openBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
  }

  @Test
  fun `mergeIntoUnified skips and writes nothing when the recording has no steps`() {
    // A degenerate recording (config only, no prompt steps) merges to an empty trail — an empty
    // `trail:` is unparseable, so the write is skipped rather than producing a corrupt file.
    val dir = tempFolder.newFolder()
    val items = listOf<TrailYamlItem>(TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y")))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty)
    assertFalse(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(), "no file written for an empty merge")
  }

  @Test
  fun `mergeIntoUnified returns NoTarget for a parentless orphan path`() {
    // An orphan file with no parent directory resolves to no unified target. Routers never send
    // such a path to UNIFIED, so this is defensive — assert it neither writes nor throws.
    val items = recordingItems(driver = "D", toolName = "tapCart")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(File("orphan.trail.yaml"), items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.NoTarget)
  }

  @Test
  fun `mergeIntoUnified refuses an objective-less capture when the trail already has steps`() {
    // The regression this pins: the merge is replace-per-classifier and aligns positionally, and an
    // objective-less capture is ONE placeholder step. Merging it would bind the whole capture to
    // step 1 and strip this classifier from every step after it. Nothing may be written.
    val dir = tempFolder.newFolder()
    val target = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = target.readText()

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, steplessRecordingItems(), "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail)
    assertEquals(2, (outcome as UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail).existingStepCount)
    assertEquals(before, target.readText(), "the existing trail must be left byte-identical")
    // Both steps keep their android recordings — the loss this refusal prevents.
    val unified = createTrailblazeYaml().decodeUnifiedTrail(target.readText())
    assertEquals(listOf("tapCart"), unified.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("tapPay"), unified.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `mergeIntoUnified accepts an objective-less capture into a greenfield directory`() {
    // The interactive recorder's raw capture is still savable where there is nothing to align to.
    val dir = tempFolder.newFolder()

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, steplessRecordingItems(), "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = createTrailblazeYaml()
      .decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("capturedTap"), unified.trail.single().recordings["android"]?.map { it.name })
  }

  // ---------------------------------------------------------------------------
  // renderStandalone — the per-classifier sibling route
  //
  // Same invariants as mergeIntoUnified, so a recording is refused identically whichever file
  // layout the directory happens to use.
  // ---------------------------------------------------------------------------

  @Test
  fun `renderStandalone keys the recording under the classifier`() {
    val yaml = UnifiedRecordingWriter
      .renderStandalone(recordingItems(driver = "D", toolName = "tapCart"), "ios")
      .getOrThrow()

    val decoded = createTrailblazeYaml().decodeUnifiedTrail(yaml)
    assertEquals(listOf("tapCart"), decoded.trail.single().recordings["ios"]?.map { it.name })
  }

  @Test
  fun `renderStandalone refuses a blank classifier`() {
    // Without a classifier there is no slot to key the tools under, so nothing could replay them.
    val failure = UnifiedRecordingWriter
      .renderStandalone(recordingItems(driver = "D", toolName = "tapCart"), "")
      .exceptionOrNull()

    assertEquals(UnifiedRecordingWriter.BLANK_CLASSIFIER_MESSAGE, failure?.message)
  }

  @Test
  fun `renderStandalone refuses a multi-tool trailhead`() {
    // The unified trailhead holds one tool per classifier — the emitter would throw on encode.
    val failure = UnifiedRecordingWriter
      .renderStandalone(multiToolTrailheadItems(listOf("launchApp", "signIn")), "android")
      .exceptionOrNull()

    assertEquals(UnifiedRecordingWriter.multiToolTrailheadMessage(2), failure?.message)
  }

  @Test
  fun `renderStandalone refuses a recording with no steps`() {
    // An empty `trail:` is unparseable, so writing it would leave an unreadable file behind a
    // success message.
    val configOnly = listOf(TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app")))

    val failure = UnifiedRecordingWriter.renderStandalone(configOnly, "android").exceptionOrNull()

    assertEquals(UnifiedRecordingWriter.EMPTY_MERGE_MESSAGE, failure?.message)
  }

  // --- fixtures ---

  /** The lowered v1 items of a minimal one-config + one-recorded-step recording — the merge input. */
  private fun recordingItems(driver: String, toolName: String): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = driver)),
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
    ),
  )

  /** A two-step recording — the "existing trail already has steps" side of the stepless refusal. */
  private fun twoStepRecordingItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "D")),
    TrailYamlItem.PromptsTrailItem(
      listOf(
        DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart")))),
        DirectionStep(step = "Pay", recording = ToolRecording(tools = listOf(tool("tapPay")))),
      ),
    ),
  )

  /** The interactive recorder's shape: tools captured with no objective window around them. */
  private fun steplessRecordingItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "D")),
    TrailYamlItem.ToolTrailItem(listOf(tool("capturedTap"))),
  )

  /** A minimal per-classifier sibling body — routing only keys off the filename, not the content. */
  private fun siblingRecordingYaml(): String =
    createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = null,
        recordedItems = recordingItems(driver = "D", toolName = "tapCart"),
        classifier = "ios",
      ),
    )

  /**
   * The lowered v1 items whose trailhead (step 0) carries [toolNames] as its `tools:` list, plus one
   * ordinary recorded step. A trailhead with more than one tool has no unified representation.
   */
  private fun multiToolTrailheadItems(toolNames: List<String>): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "D")),
    TrailYamlItem.TrailheadTrailItem(
      TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) }),
    ),
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
    ),
  )

  private fun tool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )
}
