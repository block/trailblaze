package xyz.block.trailblaze.host

import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition

/**
 * Pins what does and does not stop a multi-device session from opening.
 *
 * The "does not" half carries most of the weight: an AI-driven step used to be rejected outright,
 * and these assert the rejection is really gone rather than moved. A regression here reads to an
 * author as "multi-device can only replay recordings" — the exact limitation this lifted.
 */
class MultiDeviceSessionPreflightTest {

  private val bound = setOf("seller", "buyer")

  private fun switchDevice(name: String) = TrailblazeToolYamlWrapper(
    name = "switchDevice",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "switchDevice",
      raw = buildJsonObject { put("name", name) },
    ),
  )

  private fun aiStep(prompt: String) = TrailYamlItem.PromptsTrailItem(
    listOf(DirectionStep(step = prompt, recording = null)),
  )

  private fun recordedStep(prompt: String, vararg tools: TrailblazeToolYamlWrapper) =
    TrailYamlItem.PromptsTrailItem(
      listOf(DirectionStep(step = prompt, recording = ToolRecording(tools.toList()))),
    )

  private fun unrecordableStep(prompt: String, vararg tools: TrailblazeToolYamlWrapper) =
    TrailYamlItem.PromptsTrailItem(
      listOf(
        DirectionStep(step = prompt, recordable = false, recording = ToolRecording(tools.toList())),
      ),
    )

  private fun reasonFor(
    trailItems: List<TrailYamlItem>,
    selfHealEnabled: Boolean = false,
    handoverToolAdvertised: Boolean = true,
    useRecordedSteps: Boolean = true,
  ): String? = MultiDeviceSessionPreflight.rejectionReason(
    configurationName = "x2Pair",
    selfHealEnabled = selfHealEnabled,
    handoverToolAdvertised = handoverToolAdvertised,
    useRecordedSteps = useRecordedSteps,
    trailItems = trailItems,
    boundNames = bound,
  )

  @Test
  fun `an AI-driven step no longer stops the session`() {
    // The whole point of the wiring: a step with no recording goes to a brain that is told the
    // roster and can call switchDevice, so it is a supported shape rather than a misfire.
    assertNull(reasonFor(listOf(aiStep("On the buyer display, complete the payment"))))
  }

  @Test
  fun `a trail with no recordings at all is allowed`() {
    // "Recorded steps disabled" and "authored fresh with no legs yet" are the same shape to this
    // check — an agentic multi-device trail starts life as prose only.
    assertNull(
      reasonFor(
        listOf(
          TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "sign in", tools = null)),
          aiStep("Ring up an item on the seller display"),
          aiStep("Tip and sign on the buyer display"),
        ),
      ),
    )
  }

  @Test
  fun `a mix of recorded and AI steps is allowed`() {
    assertNull(
      reasonFor(
        listOf(
          recordedStep("hand over to the buyer", switchDevice("buyer")),
          aiStep("Approve the total however the screen asks"),
        ),
      ),
    )
  }

  @Test
  fun `self-heal is rejected for the save-back defect, not for the AI's reach`() {
    // The reason an author reads governs what they do next, so it has to be true. "The AI can't
    // address the devices" was wrong — AI steps work now, and an author reading that would
    // reasonably retry once they saw one succeed. The real blocker is that healing's save-back
    // misaligns the steps it writes back (#6372), so the reason must point there.
    val reason = reasonFor(listOf(aiStep("do the thing")), selfHealEnabled = true)

    assertTrue(reason != null && "self-heal" in reason, "expected a self-heal rejection, was: $reason")
    assertTrue(
      "misaligns" in reason,
      "the reason must name the save-back defect, not the AI's reach, was: $reason",
    )
    assertTrue(
      "trail source" in reason,
      "the reason must say healing WRITES to the author's trail, was: $reason",
    )
    assertTrue("x2Pair" in reason, "the reason must name the configuration, was: $reason")
  }

  @Test
  fun `a handover to a device the configuration never declared is rejected`() {
    // Permanent, and unrelated to AI support: the name is simply wrong, and catching it here
    // beats failing on that step after every earlier step ran on a real pair.
    val reason = reasonFor(listOf(recordedStep("hand over", switchDevice("kitchen"))))

    assertTrue(reason != null && "kitchen" in reason, "the reason must name the unbound device, was: $reason")
    assertTrue("seller" in reason && "buyer" in reason, "the reason must list bound devices, was: $reason")
  }

  @Test
  fun `a stale handover in a leg this run replays past does not block the re-blaze`() {
    // Re-blazing over the leg is what REPAIRS the stale name, so refusing the run would leave an
    // author with no way to fix it from the CLI. `trailblaze check` still fails the trail, so the
    // mistake is caught — just not by the run that would have overwritten it.
    assertNull(
      reasonFor(
        listOf(recordedStep("hand over", switchDevice("kitchen"))),
        useRecordedSteps = false,
      ),
    )
  }

  @Test
  fun `a stale handover in a non-recordable step's leg does not block either`() {
    // Per-step version of the same thing: `recordable: false` sends this step to the brain on every
    // run, so its leg is dead weight the run never dispatches.
    assertNull(reasonFor(listOf(unrecordableStep("hand over", switchDevice("kitchen")))))
  }

  @Test
  fun `a stale handover in a trailhead is rejected even with recorded steps off`() {
    // Trailheads are the exception: the runner replays their tools whatever the request asked for,
    // so this one really would fail on the pair.
    val reason = reasonFor(
      listOf(
        TrailYamlItem.TrailheadTrailItem(
          TrailheadDefinition(step = "sign in", tools = listOf(switchDevice("kitchen"))),
        ),
      ),
      useRecordedSteps = false,
    )

    assertTrue(
      reason != null && "kitchen" in reason,
      "the reason must name the unbound device, was: $reason",
    )
  }

  @Test
  fun `self-heal is reported before unbound handovers`() {
    // One reason at a time, session-wide first: a flag is one edit, the handover findings are a
    // list to work through, and reporting both would bury the flag.
    val reason = reasonFor(
      listOf(recordedStep("hand over", switchDevice("kitchen"))),
      selfHealEnabled = true,
    )

    assertTrue(reason != null && "self-heal" in reason, "expected the self-heal reason first, was: $reason")
  }

  @Test
  fun `an AI step is rejected when the session never advertises the handover tool`() {
    // The relaxation is conditional on the model actually being able to hand over. A target's
    // `excluded_tools:` can drop switchDevice from the composed repo, and then an AI step is back
    // to the pre-relaxation hazard: it can see the roster and drive only the active device.
    val reason = reasonFor(
      listOf(aiStep("Tip and sign on the buyer display")),
      handoverToolAdvertised = false,
    )

    assertTrue(
      reason != null && "switchDevice" in reason,
      "the reason must name the missing tool, was: $reason",
    )
    assertTrue(
      "excluded_tools" in reason,
      "the reason must point at the setting that caused it, was: $reason",
    )
    assertTrue(
      "Tip and sign on the buyer display" in reason,
      "the reason must name the offending step, was: $reason",
    )
  }

  @Test
  fun `a fully recorded trail opens even without the handover tool advertised`() {
    // A recorded handover dispatches through the runner-util, never through the advertised
    // surface, so replay never needed the tool advertised. Rejecting here would break every
    // existing multi-device trail whose target excludes it.
    assertNull(
      reasonFor(
        listOf(
          recordedStep("start on the seller", switchDevice("seller")),
          recordedStep("hand over to the buyer", switchDevice("buyer")),
        ),
        handoverToolAdvertised = false,
      ),
    )
  }

  @Test
  fun `disabling recorded steps makes a recorded trail AI-driven for this check`() {
    // `--no-use-recorded-steps` sends every step to the brain regardless of its leg, so a trail
    // that looks fully recorded is entirely AI-driven at run time. Reading only `recording != null`
    // would wave it through into the same hazard.
    val reason = reasonFor(
      listOf(recordedStep("hand over to the buyer", switchDevice("buyer"))),
      handoverToolAdvertised = false,
      useRecordedSteps = false,
    )

    assertTrue(
      reason != null && "hand over to the buyer" in reason,
      "expected the un-replayed step to be reported, was: $reason",
    )
  }

  @Test
  fun `disabling recorded steps leaves a recorded trailhead replayed`() {
    // A trailhead reaches one fixed starting state, so the runner replays its tools regardless of
    // the request's flag — `--no-use-recorded-steps` re-blazes the trail's steps, not its way in.
    // Reading the flag here would refuse to open a session over a trailhead the model never sees.
    assertNull(
      reasonFor(
        listOf(
          TrailYamlItem.TrailheadTrailItem(
            TrailheadDefinition(step = "sign in", tools = listOf(switchDevice("seller"))),
          ),
        ),
        handoverToolAdvertised = false,
        useRecordedSteps = false,
      ),
    )
  }

  @Test
  fun `an unrecorded trailhead is AI-driven even with recorded steps on`() {
    // The other half of the same branch: `tools:` absent is the only thing that sends a trailhead
    // to the brain, and there it needs the handover tool like any other AI step.
    val reason = reasonFor(
      listOf(TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "sign in", tools = null))),
      handoverToolAdvertised = false,
    )

    assertTrue(
      reason != null && "sign in" in reason,
      "expected the unrecorded trailhead to be reported, was: $reason",
    )
  }

  @Test
  fun `a fully recorded trail naming only bound devices opens`() {
    assertNull(
      reasonFor(
        listOf(
          recordedStep("start on the seller", switchDevice("seller")),
          recordedStep("hand over to the buyer", switchDevice("buyer")),
        ),
      ),
    )
  }
}
