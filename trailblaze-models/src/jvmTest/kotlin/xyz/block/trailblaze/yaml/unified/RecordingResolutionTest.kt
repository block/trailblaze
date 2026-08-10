package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * [UnifiedTrailAdapter.describeRecordingResolution] exists to tell apart four outcomes that every
 * existing artifact reports identically: an exact-key replay, a replay of another device's tools via
 * a family alias, a matched-empty zero-tool no-op, and a silent fall-through to the LLM.
 *
 * The load-bearing property is the last test here — the description must agree with what the
 * executor actually does, not offer a second opinion about it.
 */
class RecordingResolutionTest {

  /** One trail exercising all four outcomes at once, resolved for `android-phone`. */
  private val fourFaces = UnifiedTrail(
    config = UnifiedTrailConfig(id = "x", target = "y"),
    trail = listOf(
      UnifiedTrailStep(step = "exact", recordings = mapOf("android-phone" to listOf(tool("a"), tool("b")))),
      UnifiedTrailStep(step = "alias", recordings = mapOf("android" to listOf(tool("c")))),
      UnifiedTrailStep(step = "no-op", recordings = mapOf("android-phone" to emptyList())),
      UnifiedTrailStep(step = "unmatched", recordings = mapOf("ios" to listOf(tool("d")))),
      UnifiedTrailStep(step = "never recorded"),
    ),
  )

  /**
   * The same four outcomes plus a trailhead, which lowers through its own path
   * ([TrailheadDefinition.tools], not [DirectionStep.recording]). Its `block_runIf` also makes it the
   * fixture where a conditional is present.
   */
  private val fourFacesWithTrailhead =
    fourFaces.copy(
      trailhead =
        UnifiedTrailStep(step = "boot", recordings = mapOf("android" to listOf(tool("block_runIf"))))
    )

  @Test
  fun `each of the four outcomes is distinguishable`() {
    val r = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone)

    assertEquals("android-phone", r.deviceClassifier)
    // The chain also carries the bare form-factor segment (so a `phone:`-keyed recording would
    // resolve here too — no committed trail uses one, but the chain is the contract, not the
    // corpus) and ends at the universal root, so an `all:`-keyed recording resolves last.
    assertEquals(listOf("android-phone", "android", "phone", "all"), r.resolutionChain)

    val (exact, alias, noOp, unmatched, never) = r.steps
    assertEquals("android-phone" to 2, exact.resolvedClassifier to exact.toolCount)
    assertEquals("android" to 1, alias.resolvedClassifier to alias.toolCount, "family alias must name the ancestor that won")
    assertEquals("android-phone" to 0, noOp.resolvedClassifier to noOp.toolCount, "a matched empty list is a MATCH carrying zero tools")
    assertNull(unmatched.resolvedClassifier, "an ios-only recording must not resolve for android-phone")
    assertNull(unmatched.toolCount, "toolCount is null iff nothing matched")
    assertNull(never.resolvedClassifier)
  }

  @Test
  fun `a zero-tool no-op is not reported as an LLM fall-through`() {
    // The distinction ToolRecording's 3-state model turns on: `android-phone: []` replays zero tools
    // deterministically, while an unmatched chain hands the step to the LLM. Collapsing them is the
    // reporting bug this type exists to prevent.
    val r = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone)

    assertEquals(listOf("no-op"), r.deterministicNoOps.map { fourFaces.trail[it.stepIndex!!].step })
    assertEquals(listOf("unmatched"), r.unresolvedDeclared.map { fourFaces.trail[it.stepIndex!!].step })
  }

  @Test
  fun `a never-recorded step is not counted as a resolution failure`() {
    // Authored intent (a prompt-only step) must not inflate the unmatched count, or every
    // prompt-driven trail reads as a coverage hole.
    val r = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone)

    assertTrue(r.unresolvedDeclared.none { it.declaredClassifiers.isEmpty() })
  }

  @Test
  fun `an unresolved verify is distinguishable from an unresolved action step`() {
    // An unresolved verify becomes an LLM-backed assertion, which the ExecutionMode kdoc calls a
    // by-design reason for an LLM call. An unresolved action step is a coverage hole. A count that
    // merges them can't support a threshold.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "act", recordings = mapOf("ios" to listOf(tool("a")))),
        UnifiedTrailStep(step = "check", verify = true, recordings = mapOf("ios" to listOf(tool("b")))),
      ),
    )

    val unresolved = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone).unresolvedDeclared

    assertEquals(listOf(false, true), unresolved.map { it.isVerify })
  }

  @Test
  fun `the trailhead is described alongside the steps and carries no index`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(step = "boot", recordings = mapOf("android" to listOf(tool("a")))),
      trail = listOf(UnifiedTrailStep(step = "tap", recordings = mapOf("android-phone" to listOf(tool("b"))))),
    )

    val r = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone)

    assertNull(r.steps.first().stepIndex, "the trailhead is step 0 but has no trail: index")
    assertEquals(0, r.steps[1].stepIndex)
  }

  @Test
  fun `the summary names every outcome present`() {
    val summary = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone).summarize()

    // Counts, not wording — the wording is a log line, the counts are the contract.
    assertTrue(summary.contains("5 step"), summary)
    assertTrue(summary.contains("1 exact"), summary)
    assertTrue(summary.contains("1 via family alias 'android'"), summary)
    assertTrue(summary.contains("1 zero-tool no-op"), summary)
    assertTrue(summary.contains("1 unmatched"), summary)
    assertTrue(summary.contains("1 never recorded"), summary)
  }

  @Test
  fun `the summary's outcome counts partition the steps`() {
    // The census is only readable if the buckets sum to what the line says it counted. They didn't:
    // a matched-empty exact key counted as both `exact` and `zero-tool no-op`, so this 5-step trail
    // printed 6 labels. Summing the numbers out of the rendered line is what a reader does, so the
    // test does the same rather than re-deriving from the model.
    // Both fixtures matter: the trailhead one also carries a conditional, which must NOT read as a
    // sixth bucket.
    for (unified in listOf(fourFaces, fourFacesWithTrailhead)) {
      val summary = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone).summarize()

      val trailSteps = Regex("""^(\d+) step""").find(summary)!!.groupValues[1].toInt()
      val counted = trailSteps + if (summary.contains("+ trailhead")) 1 else 0
      // Buckets are comma-separated; the `conditional` sub-count is parenthesised precisely so it
      // isn't one of them.
      val buckets = Regex(""", (\d+) """).findAll(summary).map { it.groupValues[1].toInt() }.toList()

      assertEquals(5, trailSteps, summary)
      assertEquals(counted, buckets.sum(), "outcome buckets must sum to what the line counted: $summary")
    }
  }

  @Test
  fun `the summary counts trail steps and names the trailhead separately`() {
    // steps.size includes the trailhead, but every other artifact numbers steps from `trail:` — a
    // trail with 5 steps plus a trailhead reading "6 step(s)" makes the log disagree with the
    // numbering a reader is holding.
    val withTrailhead =
      UnifiedTrailAdapter.describeRecordingResolution(fourFacesWithTrailhead, androidPhone)
        .summarize()
    val without =
      UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone).summarize()

    assertTrue(withTrailhead.startsWith("5 step(s) + trailhead"), withTrailhead)
    assertTrue(without.startsWith("5 step(s),"), without)
    assertTrue(withTrailhead.contains("(1 conditional)"), withTrailhead)
  }

  @Test
  fun `the description agrees with what the executor actually does`() {
    // The property that makes this reportable: for every step, "described as matched" must equal
    // "lowered with a non-null recording", and any disagreement with hasRecordingForDevice would
    // mean the report and the runtime had two different opinions about the same trail. This is the
    // parallel-implementation failure mode, asserted away.
    //
    // Run over the trailhead-bearing fixture too. The trailhead lowers through TrailheadDefinition
    // .tools rather than DirectionStep.recording, so it is a second implementation of the same
    // null-vs-empty decision — and it is the step whose silent guard loss the class doc calls the
    // worst case. They share resolveClosestKey today; an `.orEmpty()` on either path would break the
    // agreement for exactly one of them.
    for (unified in listOf(fourFaces, fourFacesWithTrailhead)) {
      for (device in listOf(androidPhone, androidTablet, iosIphone)) {
        val described = UnifiedTrailAdapter.describeRecordingResolution(unified, device)
        val lowered = UnifiedTrailAdapter.lowerToTrailItems(unified, device)
        // Trailhead first, then the prompt steps — the same order describeRecordingResolution emits.
        val loweredTools =
          lowered.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().map { it.trailhead.tools } +
            lowered
              .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
              .single()
              .promptSteps
              .map { (it as DirectionStep).recording?.tools }

        assertEquals(
          loweredTools.map { it != null },
          described.steps.map { it.resolvedClassifier != null },
          "described match must equal lowered recording presence for $device",
        )
        assertEquals(
          loweredTools.map { it?.size },
          described.steps.map { it.toolCount },
          "described toolCount must equal the tools actually lowered for $device",
        )
        assertEquals(
          described.steps.any { it.resolvedClassifier != null },
          UnifiedTrailAdapter.hasRecordingForDevice(unified, device),
          "description and the requireRecordings gate must not disagree for $device",
        )
      }
    }
  }

  @Test
  fun `a conditional guard in the recording is visible per step`() {
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Dismiss the popup if it is visible",
          recordings = mapOf("android-phone" to listOf(tool("block_runIf"))),
        ),
        UnifiedTrailStep(step = "Tap Charge", recordings = mapOf("android-phone" to listOf(tool("tapOnElementBySelector")))),
      ),
    )

    val r = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone)

    assertEquals(listOf(true, false), r.steps.map { it.isConditionallyGuarded })
    assertEquals(1, r.conditionallyGuarded.size)
  }

  @Test
  fun `a step guarded on a sibling device but not this one is reported as a lost guard`() {
    // The one conditional-coverage signal that needs no prose: another device's recording proves the
    // step is conditional, so an unguarded replay here lost the guard rather than never needing one.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Dismiss the Updates to Orders pop up if it is visible",
          recordings = mapOf(
            "android-tablet" to listOf(tool("block_runIf")),
            "android-phone" to listOf(tool("tapOnElementBySelector")),
          ),
        ),
      ),
    )
    val tablet = UnifiedTrailAdapter.describeRecordingResolution(unified, androidTablet)
    val phone = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone)

    assertEquals(listOf(0), phone.lostGuardsVersus(listOf(tablet)).map { it.stepIndex })
    assertTrue(tablet.lostGuardsVersus(listOf(phone)).isEmpty(), "the guarded device has lost nothing")
  }

  @Test
  fun `a matched-empty step is not reported as a lost guard`() {
    // A zero-tool no-op replays nothing, so there is no guard for it to have lost. Found by
    // reconciling two independent corpus counts: including these inflated the corpus figure from 50
    // device cells to 53, and the whole residual disagreement between the two counts WAS these three
    // cells — the null/empty conflation this file exists to prevent, one level up.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Dismiss the popup if it is visible",
          recordings = mapOf(
            "android-tablet" to listOf(tool("block_runIf")),
            "android-phone" to emptyList(),
          ),
        ),
      ),
    )
    val tablet = UnifiedTrailAdapter.describeRecordingResolution(unified, androidTablet)
    val phone = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone)

    assertEquals("android-phone", phone.steps.single().resolvedClassifier, "it did match, with zero tools")
    assertTrue(phone.lostGuardsVersus(listOf(tablet)).isEmpty(), "a no-op has no guard to lose")
  }

  @Test
  fun `a self-guarding tool counts as conditional, not as a lost guard`() {
    // block_dismissIfPresent probes for its dialog and no-ops when absent, so a device recorded with
    // it never fires blind — it just spells the condition differently than the block_runIf wrapper.
    // Treating only wrappers as conditional would report this device as having lost a guard it has.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(
          step = "Dismiss the Updates to Orders pop up if it is visible",
          recordings = mapOf(
            "android-tablet" to listOf(tool("block_runIf")),
            "android-phone" to listOf(tool("block_dismissIfPresent")),
          ),
        ),
      ),
    )
    val tablet = UnifiedTrailAdapter.describeRecordingResolution(unified, androidTablet)
    val phone = UnifiedTrailAdapter.describeRecordingResolution(unified, androidPhone)

    assertTrue(phone.steps.single().isConditionallyGuarded, "it re-evaluates at replay")
    assertTrue(phone.lostGuardsVersus(listOf(tablet)).isEmpty())
  }

  @Test
  fun `an unmatched step is not reported as a lost guard`() {
    // A step with no recording on this device runs via the LLM, which branches natively — it has no
    // guard to lose, and reporting it here would double-count the AI fall-through finding.
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trail = listOf(
        UnifiedTrailStep(step = "conditional", recordings = mapOf("android-tablet" to listOf(tool("block_runIf")))),
      ),
    )
    val tablet = UnifiedTrailAdapter.describeRecordingResolution(unified, androidTablet)
    val iphone = UnifiedTrailAdapter.describeRecordingResolution(unified, iosIphone)

    assertTrue(iphone.lostGuardsVersus(listOf(tablet)).isEmpty())
  }

  private val androidPhone = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone"))
  private val androidTablet = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("tablet"))
  private val iosIphone = listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone"))

  private companion object {
    fun tool(name: String) = TrailblazeToolYamlWrapper(
      name = name,
      trailblazeTool = OtherTrailblazeTool(
        toolName = name,
        raw = JsonObject(mapOf("marker" to JsonPrimitive(name))),
      ),
    )
  }
}
