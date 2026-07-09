package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.TrailSourceType
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.VerificationStep

/**
 * Pins [UnifiedTrailAdapter.mergeRecordedClassifier] — the recorder's write-back primitive that
 * folds one device's freshly-recorded v1 items into a unified trail's per-classifier slots.
 *
 * The contract under test: a recording contributes ONLY its own classifier (driver pin + per-step
 * recordings + trailhead tool); every other classifier already on disk is preserved; re-recording
 * the same device replaces its slot rather than appending; and the shared NL is never rewritten by
 * a re-record.
 */
class UnifiedTrailMergeTest {

  @Test
  fun `first write with no existing file builds a fresh single-classifier unified trail`() {
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", id = "app/checkout", target = "app"),
      steps = listOf(
        directionStep("Open the cart", tool("tapCart")),
        directionStep("Pay", tool("tapPay")),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    assertEquals("app/checkout", merged.config.id)
    assertEquals("app", merged.config.target)
    assertEquals(mapOf("android" to "ANDROID_ONDEVICE_INSTRUMENTATION"), merged.config.devices)
    assertEquals(2, merged.trail.size)
    assertEquals("Open the cart", merged.trail[0].step)
    assertEquals(listOf("tapCart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("tapPay"), merged.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `merging a new classifier preserves the other classifier untouched`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app", devices = mapOf("ios" to "IOS_HOST")),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("ios" to listOf(toolNamed("ios-cart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("ios" to listOf(toolNamed("ios-pay")))),
      ),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", id = "app/checkout", target = "app"),
      steps = listOf(
        directionStep("Open the cart", tool("android-cart")),
        directionStep("Pay", tool("android-pay")),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    // Both platforms pinned; neither overwrites the other.
    assertEquals(
      mapOf("ios" to "IOS_HOST", "android" to "ANDROID_ONDEVICE_INSTRUMENTATION"),
      merged.config.devices,
    )
    assertEquals(listOf("ios-cart"), merged.trail[0].recordings["ios"]?.map { it.name })
    assertEquals(listOf("android-cart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("ios-pay"), merged.trail[1].recordings["ios"]?.map { it.name })
    assertEquals(listOf("android-pay"), merged.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `re-recording the same classifier replaces its slot rather than appending`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("android" to "OLD_DRIVER")),
      trail = listOf(
        UnifiedTrailStep(
          step = "Open the cart",
          recordings = linkedMapOf(
            "android" to listOf(toolNamed("old-android")),
            "ios" to listOf(toolNamed("ios-cart")),
          ),
        ),
      ),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "NEW_DRIVER", id = "x", target = "y"),
      steps = listOf(directionStep("Open the cart", tool("new-android"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(
      listOf("new-android"),
      merged.trail[0].recordings["android"]?.map { it.name },
      "android slot must be the new recording, not appended to the old",
    )
    assertEquals(listOf("ios-cart"), merged.trail[0].recordings["ios"]?.map { it.name }, "ios slot untouched")
    assertEquals("NEW_DRIVER", merged.config.devices?.get("android"), "driver pin replaced")
  }

  @Test
  fun `existing NL wins over a drifted recorded NL`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(UnifiedTrailStep(step = "Open the shopping cart", recordings = mapOf("ios" to listOf(toolNamed("ios"))))),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Tap the cart icon", tool("android"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals("Open the shopping cart", merged.trail[0].step, "canonical NL is not rewritten by a re-record")
    assertEquals(listOf("android"), merged.trail[0].recordings["android"]?.map { it.name })
  }

  @Test
  fun `a recorded step with no tools leaves the classifier absent, not an empty list`() {
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(DirectionStep(step = "LLM-only step", recording = null)),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    assertFalse(
      "android" in merged.trail[0].recordings,
      "no recorded tools → classifier absent (LLM mode), never `android: []` (a deliberate no-op)",
    )
  }

  @Test
  fun `a recording longer than the existing trail appends the extra steps`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(UnifiedTrailStep(step = "Step 1", recordings = mapOf("ios" to listOf(toolNamed("ios1"))))),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(
        directionStep("Step 1", tool("a1")),
        directionStep("Step 2", tool("a2")),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(2, merged.trail.size)
    assertEquals("Step 2", merged.trail[1].step)
    assertEquals(listOf("a2"), merged.trail[1].recordings["android"]?.map { it.name })
    assertNull(merged.trail[1].recordings["ios"], "the appended step has no ios recording")
  }

  @Test
  fun `a recording shorter than the existing trail strips this classifier from the trailing steps`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Step 1",
          recordings = linkedMapOf("ios" to listOf(toolNamed("ios1")), "android" to listOf(toolNamed("oldA1"))),
        ),
        UnifiedTrailStep(
          step = "Step 2",
          recordings = linkedMapOf("ios" to listOf(toolNamed("ios2")), "android" to listOf(toolNamed("oldA2"))),
        ),
      ),
    )
    // Re-record android with only ONE step (the device didn't reach step 2 this time).
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("newA1"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(2, merged.trail.size)
    assertEquals(listOf("newA1"), merged.trail[0].recordings["android"]?.map { it.name })
    assertNull(merged.trail[1].recordings["android"], "trailing step's stale android slot is stripped, not kept")
    assertEquals(listOf("ios2"), merged.trail[1].recordings["ios"]?.map { it.name }, "other classifier survives")
  }

  @Test
  fun `a recording with no trailhead strips this classifier from an existing trailhead`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(
        step = "Sign in",
        recordings = linkedMapOf("ios" to listOf(toolNamed("ios-launch")), "android" to listOf(toolNamed("old-android-launch"))),
      ),
      trail = listOf(UnifiedTrailStep(step = "Step 1", recordings = mapOf("ios" to listOf(toolNamed("ios1"))))),
    )
    // Re-record android, but this recording has no trailhead at all.
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    val trailhead = merged.trailhead!!
    assertNull(trailhead.recordings["android"], "android's trailhead slot is stripped when the re-record has no trailhead")
    assertEquals(listOf("ios-launch"), trailhead.recordings["ios"]?.map { it.name }, "ios trailhead preserved")
  }

  @Test
  fun `a first-write trailhead takes the recorded step text`() {
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1"))),
      trailhead = TrailheadDefinition(step = "Sign in first", tools = listOf(toolNamed("launch"))),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")
    assertEquals("Sign in first", merged.trailhead?.step)
    assertEquals(listOf("launch"), merged.trailhead?.recordings?.get("android")?.map { it.name })
  }

  @Test
  fun `a trailhead recorded with no step text falls back to the default trailhead step`() {
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1"))),
      trailhead = TrailheadDefinition(step = null, tools = listOf(toolNamed("launch"))),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")
    assertEquals(TrailheadDefinition.DEFAULT_STEP, merged.trailhead?.step, "null recorded trailhead step → DEFAULT_STEP")
  }

  @Test
  fun `dropping the only driver pin collapses config devices to null`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("android" to "OLD")),
      trail = listOf(UnifiedTrailStep(step = "Step 1", recordings = mapOf("android" to listOf(toolNamed("a1"))))),
    )
    // A recording with no driver in its config (e.g. LLM-driven session with no driver marker).
    val recorded = recordedItems(
      config = v1Config(driver = null, id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1new"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertNull(merged.config.devices, "no recorded driver + no other pins → devices drops to null")
  }

  @Test
  fun `trailhead recording merges into the classifier slot and keeps the other platform`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(
        step = "Sign in",
        recordings = mapOf("ios" to listOf(toolNamed("ios-launch"))),
      ),
      trail = listOf(UnifiedTrailStep(step = "Step 1", recordings = mapOf("ios" to listOf(toolNamed("ios1"))))),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1"))),
      trailhead = TrailheadDefinition(step = "Sign in", tools = listOf(toolNamed("android-launch"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    val trailhead = merged.trailhead!!
    assertEquals("Sign in", trailhead.step)
    assertEquals(listOf("ios-launch"), trailhead.recordings["ios"]?.map { it.name }, "ios trailhead untouched")
    assertEquals(listOf("android-launch"), trailhead.recordings["android"]?.map { it.name })
  }

  @Test
  fun `first write carries the recorded config tags and lifts the scalar skip into the classifier slot`() {
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(
        TrailConfig(
          id = "app/x",
          target = "app",
          driver = "D",
          tags = listOf("smoke", "flaky"),
          skip = "blocked on #123",
        ),
      ),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("tapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    assertEquals(listOf("smoke", "flaky"), merged.config.tags, "trail-level tags must survive the first write")
    assertEquals(
      mapOf("android" to "blocked on #123"),
      merged.config.skip,
      "the v1 scalar skip must lift into this classifier's slot",
    )
  }

  @Test
  fun `first write carries title priority and source from the recorded config`() {
    // Metadata scalars a save-back must not lose: title feeds report names, priority feeds
    // priority filters, source records hand-edited provenance.
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(
        TrailConfig(
          id = "app/x",
          target = "app",
          title = "Checkout with a saved card",
          priority = "P2",
          source = TrailSource(type = TrailSourceType.HANDWRITTEN, reason = "authored by hand"),
        ),
      ),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("tapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    assertEquals("Checkout with a saved card", merged.config.title)
    // priority/source are metadata by nature: the first write bridges them into the reserved
    // metadata keys (lowering lifts them back onto the v1 fields internal tooling reads).
    assertEquals("P2", merged.config.metadata?.get(UnifiedTrailConfig.METADATA_KEY_PRIORITY))
    assertEquals("HANDWRITTEN", merged.config.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE))
    assertEquals("authored by hand", merged.config.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON))
    // And the carried fields survive the emit → decode round-trip of the saved file.
    val yaml = TrailblazeYaml.Default.encodeUnifiedTrailToString(merged)
    assertEquals(merged, TrailblazeYaml.Default.decodeUnifiedTrail(yaml))
  }

  @Test
  fun `a blank recorded skip is not carried over`() {
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "D", skip = "   ")),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("s", tool("t")))),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")
    assertNull(merged.config.skip, "a blank skip reason is not a skip (v1 semantics)")
  }

  @Test
  fun `a recordable-false step never receives a recording and the result round-trips`() {
    // A recordable:false step is always-LLM; recordings and recordable:false are mutually exclusive
    // and the parser rejects the combination. Even if the recorder captured tools for such a step,
    // the merge must not write them — otherwise the saved trail.yaml is unreadable on the next run.
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "D")),
      TrailYamlItem.PromptsTrailItem(
        listOf(
          DirectionStep(step = "Always-LLM step", recordable = false, recording = ToolRecording(tools = listOf(tool("sneaky")))),
        ),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    val step = merged.trail.single()
    assertFalse(step.recordable, "recordable:false must be preserved")
    assertTrue(step.recordings.isEmpty(), "no recording may be attached to a recordable:false step")
    // The invariant is what makes the file readable — prove it survives an emit → decode round-trip.
    val yaml = TrailblazeYaml.Default.encodeUnifiedTrailToString(merged)
    assertEquals(merged, TrailblazeYaml.Default.decodeUnifiedTrail(yaml))
  }

  @Test
  fun `recording a device onto an existing recordable-false step does not corrupt it`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(UnifiedTrailStep(step = "Always-LLM step", recordable = false)),
    )
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "D")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Always-LLM step", recording = ToolRecording(tools = listOf(tool("t"))))),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    val step = merged.trail.single()
    assertFalse(step.recordable)
    assertTrue(step.recordings.isEmpty(), "the existing always-LLM step keeps no recordings")
  }

  @Test
  fun `an appended recorded verification step becomes a unified verify step`() {
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(
        directionStep("Open the cart", tool("tapCart")),
        VerificationStep(verify = "The cart shows 2 items", recording = ToolRecording(tools = listOf(tool("assertItems")))),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    assertFalse(merged.trail[0].verify, "a recorded DirectionStep appends as a plain step")
    assertTrue(merged.trail[1].verify, "a recorded VerificationStep appends as a verify step")
    assertEquals("The cart shows 2 items", merged.trail[1].step)
    assertEquals(listOf("assertItems"), merged.trail[1].recordings["android"]?.map { it.name })
    // The kind survives the on-disk round-trip too.
    val yaml = TrailblazeYaml.Default.encodeUnifiedTrailToString(merged)
    assertEquals(merged, TrailblazeYaml.Default.decodeUnifiedTrail(yaml))
  }

  @Test
  fun `re-recording with a different step kind keeps the existing kind`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "The cart shows 2 items", verify = true, recordings = mapOf("ios" to listOf(toolNamed("ios-assert")))),
      ),
    )
    // The re-record captured the same step as a plain direction step (kind drift).
    val recorded = recordedItems(
      config = v1Config(driver = "D", id = "x", target = "y"),
      steps = listOf(directionStep("The cart shows 2 items", tool("android-assert"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertTrue(merged.trail[0].verify, "the existing kind is device-agnostic canon and wins on merge")
    assertEquals(listOf("android-assert"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("ios-assert"), merged.trail[0].recordings["ios"]?.map { it.name })
  }

  @Test
  fun `merge output round-trips through the unified emitter and lowers back for the device`() {
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", id = "app/x", target = "app"),
      steps = listOf(
        directionStep("Open the cart", tool("tapCart")),
        directionStep("Pay", tool("tapPay")),
      ),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    val yaml = TrailblazeYaml.Default.encodeUnifiedTrailToString(merged)
    val decoded = TrailblazeYaml.Default.decodeUnifiedTrail(yaml)
    assertEquals(merged, decoded, "merge output must survive an emit → decode round-trip byte-stably")

    // And it lowers to a runnable v1 recording for an android device.
    val lowered = UnifiedTrailAdapter.lowerToTrailItems(
      decoded,
      classifiers = listOf(classifier("android"), classifier("phone")),
    )
    val steps = lowered.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    assertEquals(2, steps.size)
    assertEquals(listOf("tapCart"), (steps[0] as DirectionStep).recording?.tools?.map { it.name })
    assertTrue(
      lowered.filterIsInstance<TrailYamlItem.ConfigTrailItem>().single().config.driver == "ANDROID_ONDEVICE_INSTRUMENTATION",
    )
  }

  // --- fixtures ---

  private fun classifier(value: String) = TrailblazeDeviceClassifier(value)

  private fun v1Config(driver: String?, id: String?, target: String?) =
    TrailYamlItem.ConfigTrailItem(TrailConfig(id = id, target = target, driver = driver))

  private fun directionStep(nl: String, vararg tools: TrailblazeToolYamlWrapper) =
    DirectionStep(step = nl, recording = if (tools.isEmpty()) null else ToolRecording(tools = tools.toList()))

  private fun recordedItems(
    config: TrailYamlItem.ConfigTrailItem,
    steps: List<PromptStep>,
    trailhead: TrailheadDefinition? = null,
  ): List<TrailYamlItem> = buildList {
    add(config)
    trailhead?.let { add(TrailYamlItem.TrailheadTrailItem(it)) }
    add(TrailYamlItem.PromptsTrailItem(steps))
  }

  private fun tool(name: String) = toolNamed(name)

  private fun toolNamed(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(
      toolName = name,
      raw = JsonObject(mapOf("marker" to JsonPrimitive(name))),
    ),
  )
}
