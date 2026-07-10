package xyz.block.trailblaze.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Pins the `trailhead:` root element: a first-class optional sibling of `config:` and the steps
 * that declares the trail's deterministic step 0. Covers the three V1 authoring forms (bare-string
 * shorthand, `{ step, tools }`, NL-only), the position/cardinality rules, and the unified-format
 * top-level `trailhead:` key (parse → lower → emit).
 */
class TrailheadTrailItemTest {

  private val yaml = TrailblazeYaml.Default

  @Test
  fun `v1 bare-string shorthand decodes to a single bootstrap tool with no step`() {
    val items = yaml.decodeTrail(
      """
      - config:
          id: x
          target: myapp
      - trailhead: myapp_android_freshInstall
      - prompts:
          - step: Tap Pay
      """.trimIndent(),
    )
    val th = items.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertNull(th.step)
    assertEquals(1, th.tools?.size)
    assertEquals("myapp_android_freshInstall", th.tools?.single()?.name)
  }

  @Test
  fun `v1 step-plus-tools form decodes both the NL step and the tools`() {
    val items = yaml.decodeTrail(
      """
      - config:
          id: x
          target: myapp
      - trailhead:
          step: Sign in fresh and land on the payment pad
          tools:
            - myapp_android_signInViaUI: {}
            - myapp_launchClientRoute:
                route: /dl/view/my-money
      - prompts:
          - step: Tap Pay
      """.trimIndent(),
    )
    val th = items.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertEquals("Sign in fresh and land on the payment pad", th.step)
    assertEquals(listOf("myapp_android_signInViaUI", "myapp_launchClientRoute"), th.tools?.map { it.name })
  }

  @Test
  fun `v1 NL-only trailhead decodes with a step and no tools declared`() {
    val items = yaml.decodeTrail(
      """
      - config:
          id: x
          target: myapp
      - trailhead:
          step: Sign in as the standard account
      - prompts:
          - step: Tap Pay
      """.trimIndent(),
    )
    val th = items.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertEquals("Sign in as the standard account", th.step)
    // No `tools:` key at all → null (blaze via AI), distinct from an explicit `tools: []`
    // (a deterministic no-op) — see ToolRecording's 3-state doc.
    assertNull(th.tools)
  }

  @Test
  fun `v1 trailhead with explicit empty tools round-trips as a deterministic no-op`() {
    val original = listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "myapp")),
      TrailYamlItem.TrailheadTrailItem(
        TrailheadDefinition(step = "Sign in fresh", tools = emptyList()),
      ),
      TrailYamlItem.PromptsTrailItem(listOf(DirectionStep(step = "Tap Pay"))),
    )
    val decoded = yaml.decodeTrail(yaml.encodeToString(original))
    val th = decoded.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertEquals("Sign in fresh", th.step)
    // The explicit `tools = emptyList()` must round-trip as declared-empty, not collapse to null.
    assertNotNull(th.tools)
    assertTrue(th.tools!!.isEmpty())
  }

  @Test
  fun `only one trailhead is allowed`() {
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeTrail(
        """
        - trailhead: a_freshInstall
        - trailhead: b_freshInstall
        - prompts:
            - step: hi
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `trailhead must come before any prompts step`() {
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeTrail(
        """
        - prompts:
            - step: hi
        - trailhead: a_freshInstall
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `an empty trailhead is rejected at construction`() {
    assertFailsWith<IllegalArgumentException> {
      TrailheadDefinition(step = null, tools = emptyList())
    }
  }

  @Test
  fun `trailhead before config is rejected at decode`() {
    // Position-rule mirror of the after-prompts test: the trailhead is step 0, so it must sit
    // AFTER the config item, never before it.
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeTrail(
        """
        - trailhead: a_freshInstall
        - config:
            id: x
            target: myapp
        - prompts:
            - step: hi
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `an empty trailhead object is rejected at decode`() {
    // The construction guard must also fire through the decode path: an empty `trailhead: {}`
    // (no step, no tools) is meaningless and should fail at parse, not lower to a no-op step 0.
    assertFailsWith<IllegalArgumentException> {
      yaml.decodeTrail(
        """
        - config:
            id: x
            target: myapp
        - trailhead: {}
        - prompts:
            - step: hi
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `toPromptStep lowers tools to a replayable recording`() {
    // recordable=true so the recording is eligible to replay — runners invoke it with
    // useRecordedSteps=true so the bootstrap tools always run deterministically.
    val th = TrailheadDefinition(
      step = "Sign in",
      tools = listOf(
        TrailblazeToolYamlWrapper(
          name = "myapp_freshInstall",
          trailblazeTool = OtherTrailblazeTool(
            toolName = "myapp_freshInstall",
            raw = JsonObject(emptyMap()),
          ),
        ),
      ),
    )
    val step = th.toPromptStep() as DirectionStep
    assertEquals("Sign in", step.step)
    assertEquals(true, step.recordable)
    assertEquals(1, step.recording?.tools?.size)
  }

  @Test
  fun `toPromptStep on an NL-only trailhead has no recording and uses the step text`() {
    val step = TrailheadDefinition(step = "Sign in").toPromptStep() as DirectionStep
    assertEquals("Sign in", step.step)
    assertNull(step.recording)
  }

  @Test
  fun `unified top-level trailhead lowers to a TrailheadTrailItem between config and prompts`() {
    val items = yaml.decodeTrail(
      """
      config:
        id: x
        target: myapp
      trailhead:
        step: Sign in fresh
        recording:
          android-phone:
            myapp_android_signInViaUI: {}
          ios:
            myapp_ios_signInViaUI: {}
      trail:
        - step: Tap Pay
          recording:
            android-phone: []
      """.trimIndent(),
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    assertTrue(items[0] is TrailYamlItem.ConfigTrailItem)
    val th = (items[1] as TrailYamlItem.TrailheadTrailItem).trailhead
    assertEquals("Sign in fresh", th.step)
    assertEquals(listOf("myapp_android_signInViaUI"), th.tools?.map { it.name })
    assertTrue(items[2] is TrailYamlItem.PromptsTrailItem)
  }

  @Test
  fun `unified trailhead can declare a real bootstrap on one platform and an explicit no-op on another`() {
    // Motivating scenario: android needs a trailhead to reach its starting state; ios is already
    // there and should explicitly do nothing — NOT fall through to AI to figure it out.
    val items = yaml.decodeTrail(
      """
      config:
        id: x
        target: myapp
      trailhead:
        step: Reach the starting state
        recording:
          android-phone:
            myapp_android_signInViaUI: {}
          ios: {}
      trail:
        - step: Tap Pay
      """.trimIndent(),
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    val androidTh = (items[1] as TrailYamlItem.TrailheadTrailItem).trailhead
    assertEquals(listOf("myapp_android_signInViaUI"), androidTh.tools?.map { it.name })
    val androidStep0 = androidTh.toPromptStep() as DirectionStep
    assertEquals(1, androidStep0.recording?.tools?.size)

    val iosItems = yaml.decodeTrail(
      """
      config:
        id: x
        target: myapp
      trailhead:
        step: Reach the starting state
        recording:
          android-phone:
            myapp_android_signInViaUI: {}
          ios: {}
      trail:
        - step: Tap Pay
      """.trimIndent(),
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone")),
    )
    val iosTh = (iosItems[1] as TrailYamlItem.TrailheadTrailItem).trailhead
    // Declared-empty (not absent) — an explicit no-op, distinct from "no classifier matched."
    assertNotNull(iosTh.tools)
    assertTrue(iosTh.tools!!.isEmpty())
    val iosStep0 = iosTh.toPromptStep() as DirectionStep
    // A non-null, zero-tool recording: replays zero tools and succeeds without calling AI.
    assertNotNull(iosStep0.recording)
    assertTrue(iosStep0.recording!!.tools.isEmpty())
  }

  @Test
  fun `unified trailhead rejects a list of tools — one tool per platform`() {
    // A trailhead is a single tool per platform, so each classifier is a tool MAP, not a list. A
    // multi-step bootstrap composes inside the trailhead tool's own definition, not as a list here.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          target: myapp
        trailhead:
          step: Sign in fresh
          recording:
            android-phone:
              - myapp_android_signInViaUI: {}
        trail:
          - step: Tap Pay
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message!!.contains("single tool call") || ex.message!!.contains("one tool per platform"),
      "expected a one-tool-per-platform message, got: ${ex.message}",
    )
  }

  @Test
  fun `unified single-tool-map trailhead parses to exactly one tool per classifier`() {
    val trail = yaml.decodeUnifiedTrail(
      """
      config:
        target: myapp
      trailhead:
        step: Sign in fresh
        recording:
          android-phone:
            myapp_android_signInViaUI: { email: sam@example.com }
      trail:
        - step: Tap Pay
      """.trimIndent(),
    )
    val tools = trail.trailhead?.recordings?.get("android-phone")
    assertEquals(listOf("myapp_android_signInViaUI"), tools?.map { it.name })
  }

  @Test
  fun `unified trailhead rejects a classifier map with more than one tool`() {
    // The single-tool-map is exactly-one: a two-key map would otherwise silently decode only the
    // first tool (the wrapper serializer reads entries.first()), dropping the rest.
    val ex = assertFailsWith<IllegalArgumentException> {
      yaml.decodeUnifiedTrail(
        """
        config:
          target: myapp
        trailhead:
          step: Sign in fresh
          recording:
            android-phone:
              myapp_android_signInViaUI: {}
              myapp_android_openMoney: {}
        trail:
          - step: Tap Pay
        """.trimIndent(),
      )
    }
    assertTrue(
      ex.message!!.contains("exactly ONE tool") || ex.message!!.contains("one tool per platform"),
      "expected an exactly-one-tool message, got: ${ex.message}",
    )
  }

  @Test
  fun `unified trailhead recordings without classifiers throws rather than silently dropping`() {
    // Guard parity with regular step recordings: a unified trail whose ONLY recordings live under
    // `trailhead:` must still fail loudly when lowered with no device classifiers, instead of
    // resolving the trailhead against an empty chain and dropping its bootstrap tools.
    assertFailsWith<IllegalStateException> {
      yaml.decodeTrail(
        """
        config:
          id: x
          target: myapp
        trailhead:
          step: Sign in
          recording:
            android-phone:
              myapp_android_signInViaUI: {}
        trail:
          - step: Tap Pay
        """.trimIndent(),
      )
    }
  }

  @Test
  fun `unified trailhead maxRetries is carried through lowering and toPromptStep`() {
    val items = yaml.decodeTrail(
      """
      config:
        id: x
        target: myapp
      trailhead:
        step: Sign in
        maxRetries: 7
        recording:
          android-phone:
            myapp_android_signInViaUI: {}
      trail:
        - step: Tap Pay
          recording:
            android-phone: []
      """.trimIndent(),
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    val th = (items[1] as TrailYamlItem.TrailheadTrailItem).trailhead
    assertEquals(7, th.maxRetries)
    assertEquals(7, (th.toPromptStep() as DirectionStep).maxRetries)
  }

  @Test
  fun `unified trailhead round-trips through encode then decode`() {
    val original = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "myapp"),
      trailhead = UnifiedTrailStep(
        step = "Sign in fresh",
        recordings = linkedMapOf(
          "android-phone" to listOf(
            TrailblazeToolYamlWrapper(
              name = "myapp_android_signInViaUI",
              trailblazeTool = OtherTrailblazeTool(
                toolName = "myapp_android_signInViaUI",
                raw = JsonObject(emptyMap()),
              ),
            ),
          ),
        ),
      ),
      trail = listOf(UnifiedTrailStep(step = "Tap Pay")),
    )
    val encoded = yaml.encodeUnifiedTrailToString(original)
    val decoded = yaml.decodeUnifiedTrail(encoded)
    assertEquals("Sign in fresh", decoded.trailhead?.step)
    assertEquals(
      listOf("myapp_android_signInViaUI"),
      decoded.trailhead?.recordings?.get("android-phone")?.map { it.name },
    )
  }

  @Test
  fun `unified NL-only trailhead round-trips (no classifier tools)`() {
    val original = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "myapp"),
      trailhead = UnifiedTrailStep(step = "Sign in as the standard account"),
      trail = listOf(UnifiedTrailStep(step = "Tap Pay")),
    )
    val decoded = yaml.decodeUnifiedTrail(yaml.encodeUnifiedTrailToString(original))
    assertEquals("Sign in as the standard account", decoded.trailhead?.step)
    assertTrue(decoded.trailhead?.recordings?.isEmpty() == true)
  }

  @Test
  fun `emitted unified trailhead is valid YAML that decodeTrail lowers to step 0`() {
    // End-to-end through the RUNTIME entry point: emit a unified trail with a trailhead, then run the
    // emitted string back through decodeTrail (closest-wins for an android-phone) and confirm the
    // trailhead lands as step 0 with the right per-classifier tool. Catches emitter indentation bugs a
    // decodeUnifiedTrail-only round-trip would miss.
    val original = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "myapp"),
      trailhead = UnifiedTrailStep(
        step = "Sign in fresh",
        recordings = linkedMapOf(
          "android-phone" to listOf(toolWrapper("myapp_android_signInViaUI")),
          "ios" to listOf(toolWrapper("myapp_ios_signInViaUI")),
        ),
      ),
      trail = listOf(UnifiedTrailStep(step = "Tap Pay")),
    )
    val emitted = yaml.encodeUnifiedTrailToString(original)
    val items = yaml.decodeTrail(
      emitted,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    assertTrue(items[0] is TrailYamlItem.ConfigTrailItem)
    val th = (items[1] as TrailYamlItem.TrailheadTrailItem).trailhead
    assertEquals("Sign in fresh", th.step)
    assertEquals(listOf("myapp_android_signInViaUI"), th.tools?.map { it.name })
    assertTrue(items[2] is TrailYamlItem.PromptsTrailItem)
    // The lowered step 0 is a replayable recording — what the runners actually execute first.
    val step0 = th.toPromptStep() as DirectionStep
    assertEquals(true, step0.recordable)
    assertEquals(1, step0.recording?.tools?.size)
  }

  private fun toolWrapper(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(emptyMap())),
  )
}
