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

  @Test
  fun `each of the four outcomes is distinguishable`() {
    val r = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, androidPhone)

    assertEquals("android-phone", r.deviceClassifier)
    // The chain also carries the bare form-factor segment, so a `phone:`-keyed recording would
    // resolve here too. No committed trail uses one — but the chain is the contract, not the corpus.
    assertEquals(listOf("android-phone", "android", "phone"), r.resolutionChain)

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
    assertTrue(summary.contains("2 exact"), summary)
    assertTrue(summary.contains("1 via family alias 'android'"), summary)
    assertTrue(summary.contains("1 zero-tool no-op"), summary)
    assertTrue(summary.contains("1 unmatched"), summary)
    assertTrue(summary.contains("1 never recorded"), summary)
  }

  @Test
  fun `the description agrees with what the executor actually does`() {
    // The property that makes this reportable: for every step, "described as matched" must equal
    // "lowered with a non-null recording", and any disagreement with hasRecordingForDevice would
    // mean the report and the runtime had two different opinions about the same trail. This is the
    // parallel-implementation failure mode, asserted away.
    for (device in listOf(androidPhone, androidTablet, iosIphone)) {
      val described = UnifiedTrailAdapter.describeRecordingResolution(fourFaces, device)
      val lowered = UnifiedTrailAdapter.lowerToTrailItems(fourFaces, device)
        .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
        .single().promptSteps.map { (it as DirectionStep).recording }

      assertEquals(
        lowered.map { it != null },
        described.steps.map { it.resolvedClassifier != null },
        "described match must equal lowered recording presence for $device",
      )
      assertEquals(
        lowered.map { it?.tools?.size },
        described.steps.map { it.toolCount },
        "described toolCount must equal the tools actually lowered for $device",
      )
      assertEquals(
        described.steps.any { it.resolvedClassifier != null },
        UnifiedTrailAdapter.hasRecordingForDevice(fourFaces, device),
        "description and the requireRecordings gate must not disagree for $device",
      )
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
