package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import xyz.block.trailblaze.agent.trail.toJsonArgs
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

  /**
   * A recorded conditional wrapper in the verbatim shape `block_runIf` records: its branch actions
   * ride inside its own args as `{ <toolName>: <args> }` entries under `then:`.
   */
  private fun runIf(then: List<TrailblazeToolYamlWrapper>) = TrailblazeToolYamlWrapper(
    name = "block_runIf",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "block_runIf",
      raw = buildJsonObject {
        put(
          "then",
          buildJsonArray {
            then.forEach { inner -> add(buildJsonObject { put(inner.name, inner.toJsonArgs()) }) }
          },
        )
      },
    ),
  )

  /** The same wrapper carrying its `switchDevice` as the `condition: { tool: ... }` PREDICATE. */
  private fun runIfPredicate(name: String) = TrailblazeToolYamlWrapper(
    name = "block_runIf",
    trailblazeTool = OtherTrailblazeTool(
      toolName = "block_runIf",
      raw = buildJsonObject {
        put(
          "condition",
          buildJsonObject {
            put("tool", buildJsonObject { put("switchDevice", buildJsonObject { put("name", name) }) })
          },
        )
      },
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
  fun `a handover nested in a conditional's branch is reported`() {
    // `block_runIf` records verbatim: the inner calls live in its own args as
    // `{ <toolName>: <args> }`. The branch dispatches for real, so an unbound name there fails the
    // run exactly like a top-level one — mid-run, after the pair has already done work.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(runIf(then = listOf(switchDevice("kitchen"), tap()))))),
      bound,
    )
    assertEquals(listOf("step 1" to "kitchen"), unbound)
  }

  @Test
  fun `a nested handover naming a bound device is not reported`() {
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(runIf(then = listOf(switchDevice("buyer")))))),
      bound,
    )
    assertTrue(unbound.isEmpty(), "expected no findings, got $unbound")
  }

  @Test
  fun `a handover nested two conditionals deep is still reported`() {
    // The walk is recursive, so a conditional inside another's branch is reached — the shape a
    // recorded guard-within-a-guard produces.
    val unbound = MultiDeviceHandoverGuard.unboundTargets(
      listOf(steps(listOf(runIf(then = listOf(runIf(then = listOf(switchDevice("kitchen")))))))),
      bound,
    )
    assertEquals(listOf("step 1" to "kitchen"), unbound)
  }

  @Test
  fun `an ordinary data argument named switchDevice is not a handover`() {
    // The gate is FATAL, so a walk that harvested any object keyed `switchDevice` at any depth
    // would fail `trailblaze check` on a trail that runs perfectly well. Only the recorded
    // call shape — a single-key `{ toolName: args }` object in a nested-call slot — counts.
    val dataCarrier = TrailblazeToolYamlWrapper(
      name = "recordAnalytics",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "recordAnalytics",
        raw = buildJsonObject {
          put("payload", buildJsonObject { put("switchDevice", buildJsonObject { put("name", "kitchen") }) })
        },
      ),
    )

    val unbound = MultiDeviceHandoverGuard.unboundTargets(listOf(steps(listOf(dataCarrier))), bound)

    assertTrue(unbound.isEmpty(), "a data field must not read as a handover, got $unbound")
    assertFalse(MultiDeviceHandoverGuard.containsHandover(dataCarrier))
  }

  @Test
  fun `a nested handover counts structurally even when its name is unreadable`() {
    // `handoverTargets` is empty for an interpolated name by design — it isn't knowable here. But
    // the branch still MOVES the session at replay, so a caller asking "did the active device
    // change?" has to get a yes, or it keeps attributing later work to a stale member.
    val interpolated = runIf(then = listOf(switchDevice("{{nextDevice}}")))

    assertEquals(emptyList(), MultiDeviceHandoverGuard.handoverTargets(interpolated))
    assertTrue(
      MultiDeviceHandoverGuard.containsHandover(interpolated),
      "an interpolated nested handover is still a handover",
    )
  }

  @Test
  fun `a nested handover with no args at all still counts structurally`() {
    val argless = TrailblazeToolYamlWrapper(
      name = "block_runIf",
      trailblazeTool = OtherTrailblazeTool(
        toolName = "block_runIf",
        raw = buildJsonObject {
          put("then", buildJsonArray { add(buildJsonObject { put("switchDevice", JsonNull) }) })
        },
      ),
    )

    assertTrue(MultiDeviceHandoverGuard.containsHandover(argless))
    assertTrue(
      MultiDeviceHandoverGuard.unboundTargets(listOf(steps(listOf(argless))), bound).isEmpty(),
      "no readable target means nothing to report — run time owns that error",
    )
  }

  @Test
  fun `a handover in a conditional's predicate is NOT reported, but does count as a handover`() {
    // `block_runIf` dispatches its predicate inside a try/catch — "a throw is a `false` verdict,
    // not an error" — so an unbound name here does not fail the trail: the condition is false and
    // the else branch runs. Reporting it would fail `trailblaze check` on a trail that runs, which
    // is the one failure mode a fatal gate cannot have.
    //
    // The active device is a different question: a predicate switch that SUCCEEDS moves the
    // session before the branch executes, so the dialect replay still has to stop.
    val predicate = runIfPredicate("kitchen")

    assertTrue(
      MultiDeviceHandoverGuard.unboundTargets(listOf(steps(listOf(predicate))), bound).isEmpty(),
      "a caught predicate failure is survivable, so it is not a fatal finding",
    )
    assertTrue(
      MultiDeviceHandoverGuard.containsHandover(predicate),
      "a predicate switch that succeeds still moves the session",
    )
    assertFalse(
      MultiDeviceHandoverGuard.canMoveSession(predicate, bound),
      "but `kitchen` binds to nothing, so this one can only throw — caught into a `false` verdict " +
        "on the same device, which leaves the active member exactly where it was",
    )
  }

  @Test
  fun `a predicate switch to a bound name can move the session`() {
    // The contrast case for `canMoveSession`: `buyer` binds, so the predicate really does hand the
    // session over — before either branch runs — and a caller tracking the active device loses it.
    val predicate = runIfPredicate("buyer")
    assertTrue(MultiDeviceHandoverGuard.canMoveSession(predicate, bound))
    assertTrue(
      MultiDeviceHandoverGuard.unboundTargets(listOf(steps(listOf(predicate))), bound).isEmpty(),
      "and a bound name is not a finding either way",
    )
  }

  @Test
  fun `a predicate switch to an interpolated name can move the session`() {
    // `{{nextDevice}}` resolves from memory at dispatch, so whether it binds is not knowable here.
    // Unknown has to read as "may move" — the alternative attributes the rest of a leg to a member
    // the predicate may already have left.
    assertTrue(MultiDeviceHandoverGuard.canMoveSession(runIfPredicate("{{nextDevice}}"), bound))
  }

  @Test
  fun `a BRANCH switch can move the session whether or not its name binds`() {
    // No bindability shortcut applies in a branch: a bound name switches for real, and an unbound
    // one fails the run rather than being caught. Either way the leg is not worth replaying past.
    assertTrue(MultiDeviceHandoverGuard.canMoveSession(runIf(then = listOf(switchDevice("buyer"))), bound))
    assertTrue(MultiDeviceHandoverGuard.canMoveSession(runIf(then = listOf(switchDevice("kitchen"))), bound))
  }

  @Test
  fun `a handover in a conditional's BRANCH is still reported`() {
    // The contrast case for the predicate rule above: `then:` has no catch around it, so an
    // unbound name there fails the run outright and must stay a finding.
    assertEquals(
      listOf("step 1" to "kitchen"),
      MultiDeviceHandoverGuard.unboundTargets(
        listOf(steps(listOf(runIf(then = listOf(switchDevice("kitchen")))))),
        bound,
      ),
    )
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
