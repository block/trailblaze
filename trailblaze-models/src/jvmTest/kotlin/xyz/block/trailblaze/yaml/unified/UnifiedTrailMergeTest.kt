package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    assertEquals(mapOf("android" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION")), merged.config.devices)
    assertEquals(2, merged.trail.size)
    assertEquals("Open the cart", merged.trail[0].step)
    assertEquals(listOf("tapCart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("tapPay"), merged.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `merging a new classifier preserves the other classifier untouched`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app", devices = mapOf("ios" to devicePin("IOS_HOST"))),
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
      mapOf("ios" to devicePin("IOS_HOST"), "android" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION")),
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
      config = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("android" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION"))),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
      steps = listOf(directionStep("Open the cart", tool("new-android"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(
      listOf("new-android"),
      merged.trail[0].recordings["android"]?.map { it.name },
      "android slot must be the new recording, not appended to the old",
    )
    assertEquals(listOf("ios-cart"), merged.trail[0].recordings["ios"]?.map { it.name }, "ios slot untouched")
    assertEquals(devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"), merged.config.devices?.get("android"), "driver pin replaced")
  }

  @Test
  fun `configuration-keyed merge writes the leg under the configuration name and leaves config devices untouched`() {
    // The authored cast: an `pos-pair` configuration plus an unrelated single-device entry.
    val posPairCast = TrailblazeDeviceDefinition(
      devices = linkedMapOf(
        "seller" to TrailblazeDeviceDefinition(classifier = "lab-a"),
        "buyer" to TrailblazeDeviceDefinition(classifier = "lab-b"),
      ),
    )
    val existingDevices = linkedMapOf(
      "pos-pair" to posPairCast,
      "android-tablet" to devicePin("ANDROID_ONDEVICE_ACCESSIBILITY"),
    )
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "pos/tip", target = "pos", devices = existingDevices),
      trail = listOf(
        UnifiedTrailStep(step = "The buyer chooses a tip", recordings = mapOf("android-tablet" to listOf(toolNamed("tapTabletTip")))),
      ),
    )
    // The recorded session ran the pos-pair configuration; its v1 config carries the START DEVICE's
    // driver, which must NOT become an `pos-pair:` driver pin (a configuration entry can't hold one —
    // stripping/re-adding would delete the authored cast and write an unparseable entry).
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "pos/tip", target = "pos"),
      steps = listOf(directionStep("The buyer chooses a tip", tool("tapTip"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "pos-pair", selectedDeviceConfiguration = "pos-pair")

    assertEquals(existingDevices, merged.config.devices, "config.devices must be byte-identical: cast preserved, no pos-pair pin")
    assertEquals(listOf("tapTip"), merged.trail[0].recordings["pos-pair"]?.map { it.name })
    assertEquals(listOf("tapTabletTip"), merged.trail[0].recordings["android-tablet"]?.map { it.name }, "other leg untouched")

    // Re-recording the configuration replaces its leg in place and still leaves the cast alone.
    val reRecorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "pos/tip", target = "pos"),
      steps = listOf(directionStep("The buyer chooses a tip", tool("tapTipV2"))),
    )
    val remerged = UnifiedTrailAdapter.mergeRecordedClassifier(merged, reRecorded, "pos-pair", selectedDeviceConfiguration = "pos-pair")
    assertEquals(existingDevices, remerged.config.devices)
    assertEquals(listOf("tapTipV2"), remerged.trail[0].recordings["pos-pair"]?.map { it.name })
  }

  @Test
  fun `a configuration-keyed first write pins no driver on the configuration`() {
    // Greenfield: no existing document, so the base config is seeded from the RECORDING's own v1
    // config — which has no cast to read configuration names off. Only the caller knows the key
    // names a configuration, and without that a driver pin lands on `pos-pair:`, an entry the parser
    // rejects (a configuration's drivers live on its named devices).
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "pos/tip", target = "pos"),
      steps = listOf(directionStep("The buyer chooses a tip", tool("tapTip"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = null,
      recordedItems = recorded,
      classifier = "pos-pair",
      selectedDeviceConfiguration = "pos-pair",
    )

    assertNull(merged.config.devices, "no device entry at all — least of all `pos-pair: {driver: ...}`")
    assertEquals(listOf("tapTip"), merged.trail[0].recordings["pos-pair"]?.map { it.name })
  }

  @Test
  fun `merging under a key that is not the selected configuration fails loud`() {
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "pos/tip", target = "pos"),
      steps = listOf(directionStep("The buyer chooses a tip", tool("tapTip"))),
    )

    // A save site that keys by the launch device while the run bound a configuration is the very
    // bug this keying fixes: refuse rather than write a leg no replay resolves.
    assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = null,
        recordedItems = recorded,
        classifier = "lab-a",
        selectedDeviceConfiguration = "pos-pair",
      )
    }
  }

  @Test
  fun `existing NL wins over a drifted recorded NL`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(UnifiedTrailStep(step = "Open the shopping cart", recordings = mapOf("ios" to listOf(toolNamed("ios"))))),
    )
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
      steps = listOf(directionStep("Tap the cart icon", tool("android"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals("Open the shopping cart", merged.trail[0].step, "canonical NL is not rewritten by a re-record")
    assertEquals(listOf("android"), merged.trail[0].recordings["android"]?.map { it.name })
  }

  @Test
  fun `a recorded step with no tools leaves the classifier absent, not an empty list`() {
    val recorded = recordedItems(
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
      steps = listOf(directionStep("Step 1", tool("a1"))),
      trailhead = TrailheadDefinition(step = null, tools = listOf(toolNamed("launch"))),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")
    assertEquals(TrailheadDefinition.DEFAULT_STEP, merged.trailhead?.step, "null recorded trailhead step → DEFAULT_STEP")
  }

  @Test
  fun `dropping the only driver pin collapses config devices to null`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y", devices = mapOf("android" to devicePin("ANDROID_ONDEVICE_INSTRUMENTATION"))),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
          driver = "ANDROID_ONDEVICE_ACCESSIBILITY",
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
    // priority is a top-level unified field; source is metadata by nature — the first write
    // bridges it into the reserved metadata keys (lowering lifts it back onto the v1 field
    // internal tooling reads).
    assertEquals("P2", merged.config.priority)
    assertEquals("HANDWRITTEN", merged.config.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE))
    assertEquals("authored by hand", merged.config.metadata?.get(UnifiedTrailConfig.METADATA_KEY_SOURCE_REASON))
    // And the carried fields survive the emit → decode round-trip of the saved file.
    val yaml = TrailblazeYaml.Default.encodeUnifiedTrailToString(merged)
    assertEquals(merged, TrailblazeYaml.Default.decodeUnifiedTrail(yaml))
  }

  @Test
  fun `a blank recorded skip is not carried over`() {
    val recorded = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "ANDROID_ONDEVICE_ACCESSIBILITY", skip = "   ")),
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
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "ANDROID_ONDEVICE_ACCESSIBILITY")),
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
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "ANDROID_ONDEVICE_ACCESSIBILITY")),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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
      config = v1Config(driver = "ANDROID_ONDEVICE_ACCESSIBILITY", id = "x", target = "y"),
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

  // ── tools recorded outside an objective window (TrailYamlItem.ToolTrailItem) ────────────────

  /**
   * The regression this pins: step alignment is positional, so tools recorded before the first
   * objective must NOT take an index of their own. If they did, every existing step would be bound
   * to the previous step's tools and the last step's slot would be stripped and never re-added.
   */
  @Test
  fun `tools recorded before the first objective join step 1 instead of shifting the alignment`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("ios" to listOf(tool("iosTapCart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("ios" to listOf(tool("iosTapPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.ToolTrailItem(listOf(tool("launchApp"))),
      TrailYamlItem.PromptsTrailItem(
        listOf(directionStep("Open the cart", tool("tapCart")), directionStep("Pay", tool("tapPay"))),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, classifier = "android")

    assertEquals(listOf("Open the cart", "Pay"), merged.trail.map { it.step })
    assertEquals(listOf("launchApp", "tapCart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("tapPay"), merged.trail[1].recordings["android"]?.map { it.name })
    // The other platform is untouched, including on the last step.
    assertEquals(listOf("iosTapCart"), merged.trail[0].recordings["ios"]?.map { it.name })
    assertEquals(listOf("iosTapPay"), merged.trail[1].recordings["ios"]?.map { it.name })
  }

  /**
   * The placeholder is prose the adapter invented, not prose an author wrote. Existing-NL-wins is
   * the right rule for authored text, but applying it to the placeholder would freeze it into the
   * file: every later re-record carrying real objectives would lose to it forever.
   */
  @Test
  fun `a re-record with real prose replaces the recorded-actions placeholder`() {
    val placeholder = "Recorded actions (no objective captured — replace with a description)"
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(
        UnifiedTrailStep(step = placeholder, recordings = mapOf("android" to listOf(tool("oldTap")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("tapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, classifier = "android")

    assertEquals(listOf("Open the cart"), merged.trail.map { it.step })
    assertEquals(listOf("tapCart"), merged.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `authored prose still wins over a re-record that diverged`() {
    // The placeholder carve-out must not weaken the general rule: real authored NL is canon.
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(UnifiedTrailStep(step = "Open the shopping cart")),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("tap cart icon", tool("tapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, classifier = "android")

    assertEquals(listOf("Open the shopping cart"), merged.trail.map { it.step })
  }

  @Test
  fun `a recording of only step-less tools becomes one placeholder step`() {
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.ToolTrailItem(listOf(tool("tapCart"), tool("tapPay"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing = null, recordedItems = recorded, classifier = "android")

    // The literal is asserted (not referenced) because it is user-visible prose meant to be replaced.
    assertEquals(
      listOf("Recorded actions (no objective captured — replace with a description)"),
      merged.trail.map { it.step },
    )
    assertEquals(listOf("tapCart", "tapPay"), merged.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `step-less tools merged into an existing trail keep the existing NL on step 1`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(UnifiedTrailStep(step = "Open the cart", recordings = mapOf("ios" to listOf(tool("iosTapCart"))))),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.ToolTrailItem(listOf(tool("launchApp"))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, classifier = "android")

    // Existing NL is device-agnostic canon — the placeholder must never overwrite it.
    assertEquals(listOf("Open the cart"), merged.trail.map { it.step })
    assertEquals(listOf("launchApp"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("iosTapCart"), merged.trail[0].recordings["ios"]?.map { it.name })
  }

  // A partial run ("record from step N") records only part of the trail. The window is the only
  // thing that says which steps that recording is allowed to touch - recorded items carry no step
  // provenance, so without it the merge aligns from step 1 and strips the rest.

  @Test
  fun `a windowed recording lands at its offset and leaves steps outside the window untouched`() {
    val existing = threeStepAndroidTrail()
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("newTapPay")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = existing,
      recordedItems = recorded,
      classifier = "android",
      selectedDeviceConfiguration = null,
      stepWindow = 2..2,
    )

    assertEquals(listOf("Open the cart", "Review", "Pay"), merged.trail.map { it.step })
    // Only the windowed step took the new recording.
    assertEquals(listOf("newTapPay"), merged.trail[2].recordings["android"]?.map { it.name })
    // The steps this run never reached keep the recordings an earlier full run left there. Under an
    // unwindowed merge these would both be stripped, which is the corruption the window prevents.
    assertEquals(listOf("oldTapCart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertEquals(listOf("oldTapReview"), merged.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `a windowed recording leaves another classifier's legs alone inside the window too`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("aCart")), "ios" to listOf(tool("iCart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("aPay")), "ios" to listOf(tool("iPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("aPay2")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..1)

    assertEquals(listOf("aPay2"), merged.trail[1].recordings["android"]?.map { it.name })
    assertEquals(listOf("iPay"), merged.trail[1].recordings["ios"]?.map { it.name })
    assertEquals(listOf("iCart"), merged.trail[0].recordings["ios"]?.map { it.name })
  }

  @Test
  fun `a windowed recording keeps the trailhead's recording for the same classifier`() {
    // A partial run skips the trailhead by design (sliceTrail drops it), so the merge must not treat
    // "this recording had no trailhead" as "this device no longer has a trailhead tool".
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trailhead = UnifiedTrailStep(step = "Sign in", recordings = mapOf("android" to listOf(tool("signIn")))),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("oldTapCart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("oldTapPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("newTapPay")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..1)

    assertEquals(listOf("signIn"), merged.trailhead?.recordings?.get("android")?.map { it.name })
  }

  @Test
  fun `an unwindowed merge still strips this classifier from every step`() {
    // The window is opt-in: the ordinary whole-trail save-back keeps replace-per-classifier.
    val existing = threeStepAndroidTrail()
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("newTapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(listOf("newTapCart"), merged.trail[0].recordings["android"]?.map { it.name })
    assertNull(merged.trail[1].recordings["android"])
    assertNull(merged.trail[2].recordings["android"])
  }

  @Test
  fun `a window covering more steps than the recording produced is rejected`() {
    val existing = threeStepAndroidTrail()
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Review", tool("tapReview")))),
    )

    // One recorded step against a two-step window: aligning it would leave step 3 stripped and
    // silently shift what the user sees. Refused instead.
    assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..2)
    }
  }

  @Test
  fun `a window naming steps the trail does not have is rejected`() {
    val existing = threeStepAndroidTrail()
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("tapPay")))),
    )

    assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 3..3)
    }
  }

  @Test
  fun `a window that covers no steps is rejected`() {
    // An inverted window is in bounds and matches a zero-step recording's count, so without its own
    // guard it would merge nothing into the steps and report a successful merge.
    val existing = threeStepAndroidTrail()
    val recorded = listOf(v1Config(driver = null, id = "app/checkout", target = "app"))

    assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..0)
    }
  }

  @Test
  fun `a windowed recording carrying a trailhead is rejected rather than replacing one outside the window`() {
    // The trailhead is outside every window. A window that also overlaid a recorded trailhead could
    // delete the existing one, which is the exact guarantee a window exists to make impossible.
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trailhead = UnifiedTrailStep(step = "Sign in", recordings = mapOf("android" to listOf(tool("signIn")))),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("oldTapCart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("oldTapPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "Sign in again", tools = listOf(tool("newSignIn")))),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("newTapPay")))),
    )

    assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..1)
    }
  }

  @Test
  fun `a windowed recording carrying a multi-tool trailhead is rejected for its trailhead`() {
    // A window's first step is the step it named, not the trail's step 1, so relocating trailhead
    // tools into it would fold a bootstrap into an unrelated step's recording. The trailhead guard
    // already refuses the whole recording — this pins that the narrowing runs behind it, so the
    // caller is told about the trailhead rather than about a step count the narrowing changed.
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trailhead = UnifiedTrailStep(step = "Sign in", recordings = mapOf("android" to listOf(tool("signIn")))),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("oldTapCart")))),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("oldTapPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(step = "Sign in", tools = listOf(tool("launch"), tool("settle"), tool("signIn"))),
      ),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("newTapPay")))),
    )

    val failure = assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..1)
    }
    assertTrue(failure.message!!.contains("recorded trailhead"), "message named the wrong guard: ${failure.message}")
  }

  @Test
  fun `a windowed trailhead-only recording is refused for the steps it did not record`() {
    // The narrowing must not manufacture a step for a window to align: relocating these tools would
    // invent one placeholder step, which MATCHES a one-step window's count and buries the real
    // problem — this run recorded no step at all — under a message about the trailhead.
    val existing = threeStepAndroidTrail()
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(step = "Sign in", tools = listOf(tool("launch"), tool("signIn"))),
      ),
    )

    val failure = assertFailsWith<IllegalArgumentException> {
      UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android", selectedDeviceConfiguration = null, stepWindow = 1..1)
    }
    assertTrue(
      failure.message!!.contains("recorded 0 step(s)"),
      "the window mismatch is what the user has to act on: ${failure.message}",
    )
  }

  @Test
  fun `a multi-tool trailhead keeps only its first tool when step 1 holds no recording`() {
    // recordable:false is "always ask the LLM", and the format makes it mutually exclusive with a
    // recording — so the relocated tools are dropped there along with the step's own. What must NOT
    // happen is the trailhead losing its tool too: keeping the first is what leaves the run with a
    // deterministic step 0 at all, and the rest of the trail with its recordings.
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordable = false),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("oldTapPay")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(step = "Sign in", tools = listOf(tool("launch"), tool("settle"), tool("signIn"))),
      ),
      TrailYamlItem.PromptsTrailItem(
        listOf(directionStep("Open the cart"), directionStep("Pay", tool("newTapPay"))),
      ),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(listOf("launch"), merged.trailhead?.recordings?.get("android")?.map { it.name })
    // The author's always-LLM intent survives: no recording lands on step 1, relocated or not.
    assertNull(merged.trail[0].recordings["android"])
    assertFalse(merged.trail[0].recordable)
    assertEquals(listOf("newTapPay"), merged.trail[1].recordings["android"]?.map { it.name })
  }

  @Test
  fun `a multi-tool trailhead relocates into a recordable step 1 ahead of its own tools`() {
    val existing = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("oldTapCart")))),
      ),
    )
    val recorded = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(step = "Sign in", tools = listOf(tool("launch"), tool("settle"), tool("signIn"))),
      ),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("newTapCart")))),
    )

    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(existing, recorded, "android")

    assertEquals(listOf("launch"), merged.trailhead?.recordings?.get("android")?.map { it.name })
    // Recorded order across the boundary: the trailhead's tool, then the rest, then step 1's own.
    assertEquals(
      listOf("settle", "signIn", "newTapCart"),
      merged.trail[0].recordings["android"]?.map { it.name },
    )
  }

  @Test
  fun `recordedStepCount counts what the merge will align`() {
    val twoSteps = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.PromptsTrailItem(listOf(directionStep("Open the cart", tool("a")), directionStep("Pay", tool("b")))),
    )
    assertEquals(2, UnifiedTrailAdapter.recordedStepCount(twoSteps))

    // Tools recorded outside any objective are folded into the first step, so they never take an
    // index of their own.
    val steplessOnly = listOf(
      v1Config(driver = null, id = "app/checkout", target = "app"),
      TrailYamlItem.ToolTrailItem(listOf(tool("a"), tool("b"))),
    )
    assertEquals(1, UnifiedTrailAdapter.recordedStepCount(steplessOnly))

    val steplessPlusSteps = steplessOnly + TrailYamlItem.PromptsTrailItem(listOf(directionStep("Pay", tool("c"))))
    assertEquals(1, UnifiedTrailAdapter.recordedStepCount(steplessPlusSteps))

    assertEquals(0, UnifiedTrailAdapter.recordedStepCount(listOf(v1Config(null, null, null))))
  }

  @Test
  fun `sliceTrail keeps the selected steps, drops the trailhead and marks the title partial`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app", title = "Checkout"),
      trailhead = UnifiedTrailStep(step = "Sign in", recordings = mapOf("android" to listOf(tool("signIn")))),
      trail = listOf(
        UnifiedTrailStep(step = "Open the cart"),
        UnifiedTrailStep(step = "Review"),
        UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("tapPay")))),
      ),
    )

    val slice = UnifiedTrailAdapter.sliceTrail(unified, 1, 2)

    assertEquals(listOf("Review", "Pay"), slice?.trail?.map { it.step })
    // Dropped on purpose: a partial run starts from whatever is on the device's screen.
    assertNull(slice?.trailhead)
    assertEquals("Partial: Checkout (2-3)", slice?.config?.title)
    // Config is otherwise carried over so the slice resolves the same target and legs.
    assertEquals("app", slice?.config?.target)
    assertEquals(listOf("tapPay"), slice?.trail?.get(1)?.recordings?.get("android")?.map { it.name })

    assertEquals("Partial: Checkout (3)", UnifiedTrailAdapter.sliceTrail(unified, 2, 2)?.config?.title)
  }

  @Test
  fun `sliceTrail refuses a range outside the trail`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
      trail = listOf(UnifiedTrailStep(step = "Open the cart")),
    )

    assertNull(UnifiedTrailAdapter.sliceTrail(unified, 0, 1))
    assertNull(UnifiedTrailAdapter.sliceTrail(unified, -1, 0))
    assertNull(UnifiedTrailAdapter.sliceTrail(unified, 1, 0))
  }

  /** Three steps, all recorded for `android` by an earlier full run. */
  private fun threeStepAndroidTrail() = UnifiedTrail(
    config = UnifiedTrailConfig(id = "app/checkout", target = "app"),
    trail = listOf(
      UnifiedTrailStep(step = "Open the cart", recordings = mapOf("android" to listOf(tool("oldTapCart")))),
      UnifiedTrailStep(step = "Review", recordings = mapOf("android" to listOf(tool("oldTapReview")))),
      UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(tool("oldTapPay")))),
    ),
  )

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

/** The canonical devices-map value for a driver pin, keeping test fixtures terse. */
private fun devicePin(driverName: String): TrailblazeDeviceDefinition =
  TrailblazeDeviceDefinition(driver = TrailblazeDriverType.fromString(driverName)!!)
