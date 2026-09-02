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
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

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
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android")?.driver?.name)
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
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt)
    assertEquals(
      "foo: not a unified trail\n",
      corrupt.readText(),
      "an unreadable trail.yaml must be left untouched, not clobbered by the merge",
    )
  }

  @Test
  fun `mergeIntoUnified refuses to merge a classifier leg into a multi-device trail`() {
    val dir = tempFolder.newFolder()
    val multiDeviceYaml =
      """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android
              buyer:
                classifier: android
      trail:
        - step: do the thing
          recording:
            pos-pair:
              - tapCart: {}
      """.trimIndent() + "\n"
    val existing = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(multiDeviceYaml) }
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SkippedMultiDeviceTrail)
    assertEquals(setOf("pos-pair"), outcome.configurationNames)
    assertEquals(
      multiDeviceYaml,
      existing.readText(),
      "a multi-device trail's legs are keyed by configuration name; a classifier merge must not touch it",
    )
  }

  @Test
  fun `mergeIntoUnified merges a declared single-device leg on a trail that also declares a configuration`() {
    val dir = tempFolder.newFolder()
    val mixedYaml =
      """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android
              buyer:
                classifier: android
          android: {}
      trail:
        - step: do the thing
          recording:
            pos-pair:
              - tapCart: {}
      """.trimIndent() + "\n"
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(mixedYaml)
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

    // `android-tablet` is not a literal declared key, but it resolves to the declared `android:`
    // single-device entry through the same lineage every classifier lookup uses — this is an
    // ordinary single-device re-record of a mixed trail, not a configuration replay.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android-tablet")

    assertTrue(
      outcome is UnifiedRecordingWriter.MergeOutcome.Merged,
      "a mixed trail (configuration + single-device entries) must still accept single-device " +
        "classifier merges; got $outcome",
    )
    val yaml = createTrailblazeYaml()
    val unified = yaml.decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("tapCart"), step.recordings["pos-pair"]?.map { it.name }, "configuration leg preserved")
    assertEquals(listOf("tapCart"), step.recordings["android-tablet"]?.map { it.name }, "classifier leg merged")
  }

  @Test
  fun `mergeIntoUnified still refuses a classifier that resolves to no declared single-device entry`() {
    val dir = tempFolder.newFolder()
    val mixedYaml =
      """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android
              buyer:
                classifier: android
          android: {}
      trail:
        - step: do the thing
          recording:
            pos-pair:
              - tapCart: {}
      """.trimIndent() + "\n"
    val existing = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(mixedYaml) }
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

    // `ios` resolves to neither the configuration name nor a declared single-device entry, so the
    // configuration gate still refuses it rather than writing a leg the trail never declared.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "ios")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SkippedMultiDeviceTrail)
    assertEquals(mixedYaml, existing.readText(), "a refused merge must leave the trail untouched")
  }

  @Test
  fun `mergeIntoUnified re-records an existing classifier leg on a configuration-only trail`() {
    val dir = tempFolder.newFolder()
    // Declares the configuration and nothing else in `config.devices`, but carries a classifier leg
    // from before the cast was added — the shape a trail lands in when a single-device trail grows a
    // configuration.
    val configurationOnlyYaml =
      """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: lab-a
              buyer:
                classifier: lab-b
      trail:
        - step: do the thing
          recording:
            pos-pair:
              - tapCart: {}
            android-tablet:
              - tapCart: {}
      """.trimIndent() + "\n"
    val existing = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(configurationOnlyYaml) }
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapTip")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android-tablet")

    assertTrue(
      outcome is UnifiedRecordingWriter.MergeOutcome.Merged,
      "a leg the trail already declares must stay re-recordable even when no single-device " +
        "`config.devices` entry backs it; got $outcome",
    )
    val unified = createTrailblazeYaml().decodeUnifiedTrail(existing.readText())
    val step = unified.trail.single()
    assertEquals(listOf("tapTip"), step.recordings["android-tablet"]?.map { it.name }, "declared leg re-recorded")
    assertEquals(listOf("tapCart"), step.recordings["pos-pair"]?.map { it.name }, "configuration leg preserved")

    // A cast MEMBER classifier is still refused: its steps live under the configuration's leg, so
    // merging one under its own classifier would duplicate that leg.
    val memberOutcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "lab-a")
    assertTrue(
      memberOutcome is UnifiedRecordingWriter.MergeOutcome.SkippedMultiDeviceTrail,
      "a configuration member's classifier is not a declared single-device slot; got $memberOutcome",
    )
  }

  @Test
  fun `a configuration-name-keyed merge keeps the configuration entry it is keyed by`() {
    val dir = tempFolder.newFolder()
    val multiDeviceYaml =
      """
      config:
        devices:
          pos-pair:
            devices:
              seller:
                classifier: android
              buyer:
                classifier: android
      trail:
        - step: do the thing
          recording:
            pos-pair:
              - tapCart: {}
      """.trimIndent() + "\n"
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(multiDeviceYaml)

    // The multi-device save-back keys its legs by the CONFIGURATION name. Merging under that key
    // must not delete the cast of devices the trail is built around — without that, one save-back
    // would leave a trail whose steps reference a configuration the config no longer declares.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapCheckout"),
      "pos-pair",
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val unified = createTrailblazeYaml()
      .decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(
      setOf("pos-pair"),
      unified.config.multiDeviceConfigurationNames,
      "the configuration entry must survive a merge keyed by its own name",
    )
    assertEquals(
      listOf("seller", "buyer"),
      unified.config.devices?.getValue("pos-pair")?.devices?.keys?.toList(),
      "the configuration's member devices must survive too",
    )
    assertEquals(
      listOf("tapCheckout"),
      unified.trail.single().recordings["pos-pair"]?.map { it.name },
      "the merge still replaces the configuration's recorded leg",
    )
  }

  @Test
  fun `mergeIntoUnified saves a multi-tool trailhead by moving the extra tools into the first step`() {
    val dir = tempFolder.newFolder()
    val items = multiToolTrailheadItems(toolNames = listOf("clearBootstrap", "openBootstrap"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(
      listOf("clearBootstrap"),
      unified.trailhead?.recordings?.get("android")?.map { it.name },
      "the trailhead keeps the first recorded tool",
    )
    assertEquals(
      listOf("openBootstrap", "tapCart"),
      unified.trail.single().recordings["android"]?.map { it.name },
      "the extra trailhead tool replays ahead of the step's own tools",
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
    val items = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart")

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
  fun `mergeIntoUnified refuses a trailhead-only multi-tool session when the trail already has steps`() {
    // A session that only ran the trailhead recorded no objective of its own, so its relocated tools
    // become the one placeholder step — which would align against the existing step 1 and replace a
    // real recording with a leftover bootstrap tool. The stepless refusal covers this shape (it
    // reads the recorded prompt steps, not the relocation), and this pins that it still does now
    // that such a recording renders at all instead of being refused upstream.
    val dir = tempFolder.newFolder()
    val target = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = target.readText()

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, trailheadOnlyMultiToolItems(), "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail, "got $outcome")
    assertEquals(before, target.readText(), "the existing trail must be left byte-identical")
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
      .renderStandalone(recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"), "ios")
      .getOrThrow()

    val decoded = createTrailblazeYaml().decodeUnifiedTrail(yaml)
    assertEquals(listOf("tapCart"), decoded.trail.single().recordings["ios"]?.map { it.name })
  }

  @Test
  fun `renderStandalone refuses a blank classifier`() {
    // Without a classifier there is no slot to key the tools under, so nothing could replay them.
    val failure = UnifiedRecordingWriter
      .renderStandalone(recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"), "")
      .exceptionOrNull()

    assertEquals(UnifiedRecordingWriter.BLANK_CLASSIFIER_MESSAGE, failure?.message)
  }

  @Test
  fun `renderStandalone renders a multi-tool trailhead the same way the merge does`() {
    // Both file layouts must map the recording identically — a sibling that refused what the shared
    // trail accepts would lose a recording purely because of where it was being written.
    val yaml = UnifiedRecordingWriter
      .renderStandalone(multiToolTrailheadItems(listOf("clearBootstrap", "openBootstrap")), "android")
      .getOrThrow()

    val decoded = createTrailblazeYaml().decodeUnifiedTrail(yaml)
    assertEquals(listOf("clearBootstrap"), decoded.trailhead?.recordings?.get("android")?.map { it.name })
    assertEquals(listOf("openBootstrap", "tapCart"), decoded.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `renderStandalone refuses a recording with no steps`() {
    // An empty `trail:` is unparseable, so writing it would leave an unreadable file behind a
    // success message.
    val configOnly = listOf(TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app")))

    val failure = UnifiedRecordingWriter.renderStandalone(configOnly, "android").exceptionOrNull()

    assertEquals(UnifiedRecordingWriter.EMPTY_MERGE_MESSAGE, failure?.message)
  }

  // ---------------------------------------------------------------------------
  // mergeIntoUnified with a step window - the partial-recording save-back
  // ---------------------------------------------------------------------------

  @Test
  fun `a windowed merge replaces only the windowed step and preserves the rest on disk`() {
    val dir = tempFolder.newFolder()
    // Seed a full two-step android recording, then re-record only step 2.
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")

    val rerecordedStep2 = listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Pay", recording = ToolRecording(tools = listOf(tool("tapPayV2"))))),
      ),
    )
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, rerecordedStep2, "android", stepWindow = 1..1)

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val unified = createTrailblazeYaml()
      .decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("Open the cart", "Pay"), unified.trail.map { it.step })
    assertEquals(listOf("tapPayV2"), unified.trail[1].recordings["android"]?.map { it.name })
    // Step 1 was outside the window, so its recording from the seed run survives the round trip.
    assertEquals(listOf("tapCart"), unified.trail[0].recordings["android"]?.map { it.name })
  }

  @Test
  fun `a windowed merge whose recording has a different step count is refused and writes nothing`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText()

    // One recorded step against a two-step window: a run that self-healed a step into existence (or
    // out of it) leaves no way to say which recorded step is which, so alignment would shift.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapSomething"),
      "android",
      stepWindow = 0..1,
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.StepWindowMismatch, "got $outcome")
    outcome as UnifiedRecordingWriter.MergeOutcome.StepWindowMismatch
    assertEquals(2, outcome.expectedStepCount)
    assertEquals(1, outcome.recordedStepCount)
    assertEquals(before, File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
  }

  @Test
  fun `a window naming steps the target no longer has is refused and writes nothing`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText()

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapSomething"),
      "android",
      stepWindow = 5..5,
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.StepWindowOutOfRange, "got $outcome")
    assertEquals(2, (outcome as UnifiedRecordingWriter.MergeOutcome.StepWindowOutOfRange).existingStepCount)
    assertEquals(before, File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
  }

  @Test
  fun `a windowed merge into a trail that does not exist yet is refused`() {
    val dir = tempFolder.newFolder()

    // A window is a claim about steps already on disk. With no file there is nothing to window.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapSomething"),
      "android",
      stepWindow = 0..0,
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.StepWindowOutOfRange, "got $outcome")
    assertFalse(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists())
  }

  @Test
  fun `a merge whose covered step was rewritten while the run ran is refused and writes nothing`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText()

    // The run covered "Pay". Someone rewrote that step in place while it ran, so every count still
    // lines up and only the prose says the recording no longer belongs there.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay with a gift card")),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun, "got $outcome")
    assertEquals(before, File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
  }

  @Test
  fun `a merge whose covered steps are untouched still writes`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")

    // A step edited OUTSIDE the window is none of this run's business, so only "Pay" is compared.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay")),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
  }

  @Test
  fun `a covered step turned into a verify while the run ran is refused`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")

    // Same prose, different kind: `verify:` asserts, doesn't self-heal, and offers a different tool
    // surface, so tools recorded against a direction step don't belong to it.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay", verify = true)),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun, "got $outcome")
  }

  @Test
  fun `a merge into a trail retargeted at another app while the run ran is refused`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText()

    // Same steps, word for word, but the trail now drives a different application - and selectors
    // captured in one app describe nothing in another.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay"), target = "some-other-app"),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun, "got $outcome")
    assertEquals(before, File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
  }

  @Test
  fun `a merge whose target app is unchanged still writes`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay")),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
  }

  @Test
  fun `a merge into a trail that gained a target while the run ran is refused`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")
    val before = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText()

    // Absent at dispatch, present now. The comparison is symmetric on purpose: a run that drove
    // whatever target was selected can't have its selectors filed under a trail that now names one.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      recordingItems(driver = null, toolName = "tapPayV2"),
      "android",
      stepWindow = 1..1,
      expectedDispatched = dispatched(UnifiedTrailStep(step = "Pay"), target = null),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun, "got $outcome")
    assertEquals(before, File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
  }

  @Test
  fun `a whole-trail merge compares the trailhead it ran too`() {
    val dir = tempFolder.newFolder()
    UnifiedRecordingWriter.mergeIntoUnified(dir, twoStepRecordingItems(), "android")

    // No trailhead on disk, so a run claiming to have covered one ran against a different document.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      twoStepRecordingItems(),
      "android",
      expectedDispatched = dispatched(
        UnifiedTrailStep(step = "Open the cart"),
        UnifiedTrailStep(step = "Pay"),
        trailhead = UnifiedTrailStep(step = "Launch the app"),
      ),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun, "got $outcome")
  }

  @Test
  fun `a first write is not compared against the file it is about to create`() {
    val dir = tempFolder.newFolder()

    // Nothing on disk yet, so there is nothing this run could have drifted from. Comparing anyway
    // would read the absent file's empty step list as "the steps it covered were edited" and refuse
    // every greenfield save-back that carries an expectation.
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      twoStepRecordingItems(),
      "android",
      expectedDispatched = dispatched(
        UnifiedTrailStep(step = "Open the cart"),
        UnifiedTrailStep(step = "Pay"),
      ),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    assertTrue(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists())
  }

  // --- fixtures ---

  /**
   * The document a run was dispatched with, as the save-back hands it to the merge: the steps it
   * executed plus the config it ran under. [target] defaults to the fixtures' own app.
   */
  private fun dispatched(
    vararg steps: UnifiedTrailStep,
    trailhead: UnifiedTrailStep? = null,
    target: String? = "app",
  ): UnifiedTrail = UnifiedTrail(
    config = UnifiedTrailConfig(id = "app/x", target = target),
    trailhead = trailhead,
    trail = steps.toList(),
  )

  /** The lowered v1 items of a minimal one-config + one-recorded-step recording — the merge input. */
  private fun recordingItems(driver: String?, toolName: String): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = driver)),
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
    ),
  )

  /** A two-step recording — the "existing trail already has steps" side of the stepless refusal. */
  private fun twoStepRecordingItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
    TrailYamlItem.PromptsTrailItem(
      listOf(
        DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart")))),
        DirectionStep(step = "Pay", recording = ToolRecording(tools = listOf(tool("tapPay")))),
      ),
    ),
  )

  /** The interactive recorder's shape: tools captured with no objective window around them. */
  private fun steplessRecordingItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
    TrailYamlItem.ToolTrailItem(listOf(tool("capturedTap"))),
  )

  /** A minimal per-classifier sibling body — routing only keys off the filename, not the content. */
  private fun siblingRecordingYaml(): String =
    createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = null,
        recordedItems = recordingItems(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"),
        classifier = "ios",
      ),
    )

  /**
   * The lowered v1 items whose trailhead (step 0) carries [toolNames] as its `tools:` list, plus one
   * ordinary recorded step. A unified trailhead slot holds one tool, so more than one is mapped onto
   * the format: the first stays, the rest move to the front of the step's recording.
   */
  private fun multiToolTrailheadItems(toolNames: List<String>): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
    TrailYamlItem.TrailheadTrailItem(
      TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) }),
    ),
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
    ),
  )

  /** A run that satisfied only the trailhead: several tools on step 0 and no objective of its own. */
  private fun trailheadOnlyMultiToolItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
    TrailYamlItem.TrailheadTrailItem(
      TrailheadDefinition(step = "Bootstrap", tools = listOf(tool("clearBootstrap"), tool("openBootstrap"))),
    ),
  )

  private fun tool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )
}
