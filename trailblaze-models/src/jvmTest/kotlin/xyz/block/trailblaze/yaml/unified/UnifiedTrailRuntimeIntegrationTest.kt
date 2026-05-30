package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Integration tests pinning the runtime-execution path for the unified format trails. These
 * exercise the contract a real executor sees: feed a the unified format YAML string +
 * device-classifier list into [TrailblazeYaml.decodeTrail] and assert the
 * lowered v1-shape result is what the executor will run.
 *
 * Separate from [UnifiedTrailAdapterTest] (which tests the adapter's pure
 * functions) and [UnifiedTrailParserTest] (which tests the parser shape) — this
 * file ties the layers together to prove the end-to-end pipeline works.
 */
class UnifiedTrailRuntimeIntegrationTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `unified YAML decodes through version-aware decodeTrail with classifier lowering`() {
    val unifiedYaml = """
      config:
        id: myapp/checkout
        target: myapp
        devices:
          - android-phone
          - ios
      trail:
        - step: Open the app
          android-phone:
            - openApp-android:
                appId: com.example
          ios:
            - openApp-ios:
                appId: com.example.ios
        - step: Tap continue
          android:
            - tap-shared:
                x: 1
                y: 1
          ios:
            - tap-ios:
                x: 2
                y: 2
    """.trimIndent()

    // Run as an iPhone — classifier list `[ios-iphone, ios]`. Step 1: no
    // `ios-iphone` key → fall through to `ios` → openApp-ios. Step 2: same
    // — `ios` matches before `android`.
    val iosItems = yaml.decodeTrail(
      unifiedYaml,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios-iphone"), TrailblazeDeviceClassifier("ios")),
    )
    val iosPrompts = iosItems.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    assertEquals(2, iosPrompts.size)
    val iosStep1 = iosPrompts[0] as DirectionStep
    assertEquals("openApp-ios", iosStep1.recording?.tools?.single()?.name)
    val iosStep2 = iosPrompts[1] as DirectionStep
    assertEquals("tap-ios", iosStep2.recording?.tools?.single()?.name)

    // Run as Android phone — classifier list `[android-phone, android]`. Step 1
    // matches `android-phone:` directly. Step 2 falls through to `android:`.
    val androidItems = yaml.decodeTrail(
      unifiedYaml,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android-phone"), TrailblazeDeviceClassifier("android")),
    )
    val androidPrompts = androidItems.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    val androidStep1 = androidPrompts[0] as DirectionStep
    assertEquals("openApp-android", androidStep1.recording?.tools?.single()?.name)
    val androidStep2 = androidPrompts[1] as DirectionStep
    assertEquals("tap-shared", androidStep2.recording?.tools?.single()?.name)
  }

  @Test
  fun `v1 YAML still passes through decodeTrail unchanged regardless of classifiers`() {
    val v1Yaml = """
      - config:
          id: x
          target: y
          platform: android
      - prompts:
          - step: Step 1
            recording:
              tools:
                - tap:
                    x: 1
                    y: 1
    """.trimIndent()
    val items = yaml.decodeTrail(
      v1Yaml,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("anything")),
    )
    // v1 input: classifiers are ignored, recording survives intact.
    val prompts = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    val step = prompts.single() as DirectionStep
    assertEquals("tap", step.recording?.tools?.single()?.name)
  }

  @Test
  fun `extractTrailConfig works on both v1 and unified without device classifiers`() {
    val unified = yaml.extractTrailConfig(
      """
      config:
        id: unified-trail
        target: myapp
        context: this is context
      trail:
        - step: anything
          android-phone: []
      """.trimIndent(),
    )
    assertNotNull(unified)
    assertEquals("unified-trail", unified.id)
    assertEquals("myapp", unified.target)
    assertEquals("this is context", unified.context)

    val v1 = yaml.extractTrailConfig(
      """
      - config:
          id: v1-trail
          target: myapp
      - prompts:
          - step: anything
      """.trimIndent(),
    )
    assertNotNull(v1)
    assertEquals("v1-trail", v1.id)
  }

  @Test
  fun `recordable false unified-format step lowers to LLM-mode DirectionStep for the executor`() {
    val unifiedYaml = """
      config:
        id: x
        target: y
      trail:
        - step: This step is always LLM-handled
          recordable: false
    """.trimIndent()
    val items = yaml.decodeTrail(
      unifiedYaml,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android-phone"), TrailblazeDeviceClassifier("android")),
    )
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    assertFalse(step.recordable, "recordable: false must propagate so the executor doesn't try to replay")
    assertNull(step.recording)
  }

  @Test
  fun `hasRecordedSteps returns true for a the unified format trail with any classifier recording`() {
    val unifiedYaml = """
      config:
        id: x
        target: y
      trail:
        - step: hi
          android-phone:
            - tap:
                x: 1
                y: 1
    """.trimIndent()
    assertTrue(
      yaml.hasRecordedSteps(unifiedYaml),
      "unified trail with any non-empty classifier list should report recordings present",
    )
  }

  @Test
  fun `hasRecordedSteps returns false for a the unified format trail with only recordable false steps`() {
    val unifiedYaml = """
      config:
        id: x
        target: y
      trail:
        - step: LLM-only step
          recordable: false
    """.trimIndent()
    assertFalse(
      yaml.hasRecordedSteps(unifiedYaml),
      "unified trail with no classifier recordings should not claim recordings present",
    )
  }

  @Test
  fun `trail dir resolver includes trail-yaml in the candidate list after platform recordings`() {
    val candidates = TrailRecordings.computePossibleFileNamesForDeviceClassifiers(
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone")),
    )
    val trailYamlIdx = candidates.indexOf("trail.yaml")
    val blazeYamlIdx = candidates.indexOf("blaze.yaml")
    val iosIphoneIdx = candidates.indexOf("ios-iphone.trail.yaml")
    val iosIdx = candidates.indexOf("ios.trail.yaml")
    assertTrue(trailYamlIdx >= 0, "trail.yaml should be in the candidate list")
    assertTrue(iosIphoneIdx in 0 until trailYamlIdx, "ios-iphone.trail.yaml should come BEFORE trail.yaml")
    assertTrue(iosIdx in 0 until trailYamlIdx, "ios.trail.yaml should come BEFORE trail.yaml")
    assertTrue(blazeYamlIdx > trailYamlIdx, "trail.yaml should come BEFORE blaze.yaml")
  }

  @Test
  fun `isRecordingFile distinguishes trail-yaml from per-platform recordings`() {
    assertTrue(TrailRecordings.isRecordingFile("android-phone.trail.yaml"))
    assertTrue(TrailRecordings.isRecordingFile("ios-iphone.trail.yaml"))
    assertFalse(
      TrailRecordings.isRecordingFile("trail.yaml"),
      "trail.yaml is the the unified format unified file, not a per-platform recording",
    )
    assertFalse(TrailRecordings.isRecordingFile("blaze.yaml"))
  }

  // ── Guard against silent LLM-mode fallback ─────────────────────────────
  //
  // decodeTrail throws when a the unified format trail with recordings is lowered without
  // device classifiers — the alternative is to silently drop every recording
  // and execute every step in LLM mode without the caller noticing. These
  // tests pin that the guard fires for the dangerous case and stays out of
  // the way for the safe ones.

  @Test
  fun `guard fires when the unified format trail with recordings is decoded with no classifiers`() {
    val unifiedWithRecordings = """
      config:
        id: x
        target: y
      trail:
        - step: Tap
          android-phone:
            - tap:
                x: 1
                y: 1
    """.trimIndent()
    val ex = kotlin.test.assertFailsWith<IllegalStateException> {
      yaml.decodeTrail(unifiedWithRecordings)
    }
    assertTrue(
      ex.message?.contains("no device classifiers") == true,
      "expected guard message, got: ${ex.message}",
    )
    assertTrue(
      ex.message?.contains("extractTrailConfig") == true,
      "guard error should point users at extractTrailConfig as an alternative, got: ${ex.message}",
    )
  }

  @Test
  fun `guard does NOT fire when the unified format trail has only recordable false steps`() {
    // No recordings exist to silently drop, so the empty-classifiers path is
    // a legitimate "config + intent only" decode — guard stays out of the way.
    val unifiedNoRecordings = """
      config:
        id: x
        target: y
      trail:
        - step: LLM-only step
          recordable: false
    """.trimIndent()
    val items = yaml.decodeTrail(unifiedNoRecordings)
    val prompts = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single().promptSteps
    assertEquals(1, prompts.size)
    assertFalse((prompts.single() as DirectionStep).recordable)
  }

  @Test
  fun `guard does NOT fire when the unified format trail has only explicit empty classifier no-ops`() {
    // Explicit `<classifier>: []` is still "no recording to drop" — the
    // empty list lowers to a null recording for any classifier whether we
    // have classifiers or not. Decoding is safe.
    val unifiedOnlyEmptyClassifiers = """
      config:
        id: x
        target: y
      trail:
        - step: Skip everywhere
          android-phone: []
          ios-iphone: []
    """.trimIndent()
    val items = yaml.decodeTrail(unifiedOnlyEmptyClassifiers)
    assertNotNull(items)
  }

  @Test
  fun `guard does NOT fire for v1 input regardless of classifiers`() {
    val v1 = """
      - config:
          id: x
          target: y
      - prompts:
        - step: Tap
          recording:
            tools:
            - tap: { x: 1, y: 1 }
    """.trimIndent()
    // v1 ignores classifiers. The guard is unified-only.
    val items = yaml.decodeTrail(v1)
    val step = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single() as DirectionStep
    assertNotNull(step.recording, "v1 recording must survive zero-classifier decode")
  }

  @Test
  fun `extractTrailConfig bypasses the guard — works on the unified format with recordings and no classifiers`() {
    // extractTrailConfig is the safe entry point for callers that just need
    // static config (device picker, dir resolver, etc.). It routes through
    // decodeTrailDocument directly, NOT decodeTrail, so the guard can't fire.
    val unifiedWithRecordings = """
      config:
        id: needs-extracting
        target: myapp
      trail:
        - step: Tap
          android-phone:
            - tap:
                x: 1
                y: 1
    """.trimIndent()
    val config = yaml.extractTrailConfig(unifiedWithRecordings)
    assertEquals("needs-extracting", config?.id)
    assertEquals("myapp", config?.target)
  }

  @Test
  fun `decodeTrailDocument also bypasses the guard — the unified format with recordings parses cleanly`() {
    val unifiedWithRecordings = """
      config:
        id: x
        target: y
      trail:
        - step: Tap
          android-phone:
            - tap:
                x: 1
                y: 1
    """.trimIndent()
    val doc = yaml.decodeTrailDocument(unifiedWithRecordings)
    assertTrue(doc is TrailDocument.Unified, "decodeTrailDocument should return format-native shape")
  }
}
