package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
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
 * Pins which recorded handovers [MultiDeviceHandoverGuard] reports as unbound.
 *
 * A prose-addressed step ("On the buyer display, …") carries its `switchDevice` as the first tool
 * of its own leg, so a name the session never bound is invisible in the trail's step list. The
 * guard exists to surface it before any step runs on a real pair; these assert the exact set it
 * reports and, just as importantly, what it stays quiet about.
 */
class MultiDeviceHandoverGuardTest {

  private fun switchDevice(name: String?) = TrailblazeToolYamlWrapper(
    name = "switchDevice",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "switchDevice",
      raw = buildJsonObject { if (name != null) put("name", name) },
    ),
  )

  private fun tap() = TrailblazeToolYamlWrapper(
    name = "tapOnElementBySelector",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "tapOnElementBySelector",
      raw = buildJsonObject { put("reason", "tap") },
    ),
  )

  private fun steps(vararg legs: List<TrailblazeToolYamlWrapper>) = TrailYamlItem.PromptsTrailItem(
    legs.mapIndexed { index, tools ->
      DirectionStep(
        step = "step ${index + 1}",
        recording = ToolRecording(tools),
      )
    },
  )

  private fun trailhead(tools: List<TrailblazeToolYamlWrapper>) =
    TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "bootstrap", tools = tools))

  private val bound = setOf("seller", "buyer")

  @Test
  fun `handovers naming bound devices are not reported`() {
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(tap()), listOf(switchDevice("buyer"), tap()), listOf(switchDevice("seller"), tap()))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }

  @Test
  fun `a handover to an unbound device is reported with the step it rides in`() {
    // The step's own prose says "buyer display"; only the recorded leg names `android-tablet`,
    // which is the classifier rather than the configuration's device name.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(tap()), listOf(switchDevice("android-tablet"), tap()))),
      bound,
    )
    assertEquals(listOf("step 2" to "android-tablet"), unbound)
  }

  @Test
  fun `step numbering runs across prompt items, and a trailhead handover is labelled as one`() {
    // A trail's steps can be split across several `- prompts:` items; numbering per item would
    // point a reader at the wrong step.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(
        trailhead(listOf(switchDevice("kitchen"))),
        steps(listOf(tap()), listOf(tap())),
        steps(listOf(switchDevice("register"), tap())),
      ),
      bound,
    )
    assertEquals(listOf("trailhead" to "kitchen", "step 3" to "register"), unbound)
  }

  @Test
  fun `every unbound handover is reported, not just the first`() {
    // The point of checking at session start is to hand back the whole list; reporting one at a
    // time would mean one device-pair boot per typo.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(switchDevice("kitchen")), listOf(switchDevice("buyer")), listOf(switchDevice("register")))),
      bound,
    )
    assertEquals(listOf("step 1" to "kitchen", "step 3" to "register"), unbound)
  }

  @Test
  fun `a handover with no readable name arg is left to run time`() {
    // Nothing here is a naming mistake to report — it's a decode problem the guard can't judge,
    // and blocking the session on it would turn a malformed arg into a misleading error.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(switchDevice(null), tap()))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }

  @Test
  fun `a non-string or blank name arg is left to run time`() {
    // Stringifying JsonNull / a number would put a device name that appears nowhere in the trail
    // ("null", "7") into the error, and a blank one names nothing at all.
    val nonString = TrailblazeToolYamlWrapper(
      name = "switchDevice",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "switchDevice",
        raw = buildJsonObject { put("name", JsonNull) },
      ),
    )
    val numeric = TrailblazeToolYamlWrapper(
      name = "switchDevice",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "switchDevice",
        raw = buildJsonObject { put("name", 7) },
      ),
    )
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(nonString), listOf(numeric), listOf(switchDevice("   ")))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }

  @Test
  fun `a memory-interpolated name is left to run time`() {
    // Interpolation happens at the tool-dispatch boundary, after the session seeds memory — long
    // after this guard runs — so the literal token is not a device name to reject.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(switchDevice("{{buyerDevice}}")), listOf(switchDevice("\${buyerDevice}")))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }

  @Test
  fun `a bare tools item's handover is reported`() {
    // `- tools:` dispatches its calls exactly like a step's leg, so a handover written there is
    // just as real — and it carries no prose to make the wrong name visible to a reader.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(
        TrailYamlItem.ToolTrailItem(listOf(tap(), switchDevice("kitchen"))),
        steps(listOf(switchDevice("buyer"))),
        TrailYamlItem.ToolTrailItem(listOf(switchDevice("register"))),
      ),
      bound,
    )
    assertEquals(listOf("tools item 1" to "kitchen", "tools item 2" to "register"), unbound)
  }

  @Test
  fun `an unrecorded step contributes nothing`() {
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(TrailYamlItem.PromptsTrailItem(listOf(DirectionStep(step = "blaze this", recording = null)))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }
}
