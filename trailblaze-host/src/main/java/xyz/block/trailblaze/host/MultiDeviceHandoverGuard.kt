package xyz.block.trailblaze.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Checks the device names a fully-recorded multi-device trail hands off to against the names the
 * session actually bound.
 *
 * A handover rides INSIDE a step's leg — the step's prose names the party ("On the buyer display,
 * …") and its recorded leg opens with the `switchDevice` the session performed — so a wrong name
 * never appears as its own step to whoever reads the trail. Left to run time, `switchDevice` fails
 * on that step, after every earlier step already ran on a real device pair.
 */
internal object MultiDeviceHandoverGuard {

  /**
   * `${var}` / `{{var}}`, the two spellings [xyz.block.trailblaze.AgentMemory] interpolates.
   * Interpolation happens at the tool-dispatch boundary, long after this guard runs, so a name
   * carrying one is not knowable here.
   */
  private val INTERPOLATION_TOKEN = Regex("""\$\{[^}]+}|\{\{[^}]+}}""")

  /**
   * Where each handover sits ("trailhead" / "step N" / "tools item N") → the unbound name it
   * targets, in order.
   *
   * @param inspectStepRecording whether a prompt step's recorded leg is in scope. Static callers
   * (`trailblaze check`, the lints) judge every leg a trail holds; a caller judging one RUN passes
   * that run's replay predicate, because a leg it will re-blaze past cannot fail on the name.
   * Trailheads and literal `tools:` items are always in scope — both dispatch regardless of the
   * run's recorded-steps setting. Step numbering counts every step either way, so the reported
   * position matches what a reader sees in the file.
   */
  fun unboundTargets(
    trailItems: List<TrailYamlItem>,
    boundNames: Set<String>,
    inspectStepRecording: (PromptStep) -> Boolean = { true },
  ): List<Pair<String, String>> {
    var stepNumber = 0
    var toolsItemNumber = 0
    val located = buildList<Pair<String, TrailblazeToolYamlWrapper>> {
      trailItems.forEach { item ->
        when (item) {
          is TrailYamlItem.TrailheadTrailItem ->
            item.trailhead.tools.orEmpty().forEach { add("trailhead" to it) }

          is TrailYamlItem.PromptsTrailItem ->
            item.promptSteps.forEach { step ->
              stepNumber++
              if (inspectStepRecording(step)) {
                step.recording?.tools.orEmpty().forEach { add("step $stepNumber" to it) }
              }
            }

          // A bare `tools:` item dispatches its calls the same way a step's leg does, so a
          // handover written there is just as real. Numbered on its own so the count keeps
          // matching what a reader sees in the `trail:` list.
          is TrailYamlItem.ToolTrailItem -> {
            toolsItemNumber++
            item.tools.forEach { add("tools item $toolsItemNumber" to it) }
          }

          // Enumerated rather than `else`, so a new item type that can carry tools has to be
          // considered here instead of silently going unchecked.
          is TrailYamlItem.ConfigTrailItem -> Unit
        }
      }
    }
    return located.flatMap { (where, tool) ->
      handoverTargets(tool).filter { it !in boundNames }.map { where to it }
    }
  }

  /**
   * Every device name this recorded call hands off to: its own when it IS a `switchDevice`, plus
   * each `switchDevice` nested in its arguments.
   *
   * Nesting is real dispatch, not decoration. A wrapper tool records VERBATIM — `block_runIf` keeps
   * its `condition.tool` predicate and its `then:` / `else:` branch actions in its own args as
   * `{ <toolName>: <args> }` — and a branch that runs dispatches the inner `switchDevice` for real,
   * where a name the session never bound fails the run. A gate that read only the top-level name
   * would pass a trail that breaks the moment its guarded branch is taken.
   *
   * A nested BRANCH name is judged exactly like a top-level one: whether the branch runs is a
   * runtime question, but whether the name CAN bind is not — an undeclared one resolves to nothing
   * on every path, so reporting it costs no false positives.
   *
   * A nested PREDICATE name is not reported, because there the same failure is survivable — see
   * [collectNestedHandovers]. Use [containsHandover] when the question is whether the active device
   * may have moved rather than whether the trail is broken.
   */
  fun handoverTargets(tool: TrailblazeToolYamlWrapper): List<String> {
    val args = tool.toJsonArgs()
    return buildList {
      if (tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME) {
        readTargetName(args)?.let(::add)
      }
      // Predicates excluded: an unbound name there is caught and turned into a `false` verdict,
      // so it is not the fatal misconfiguration this list reports.
      collectNestedHandovers(args)
        .filter { (slot, _) -> slot == HandoverSlot.BRANCH }
        .forEach { (_, nested) -> readTargetName(nested)?.let(::add) }
    }
  }

  /**
   * Argument keys under which a recorded wrapper nests tool-calls: `then` / `else` are
   * `block_runIf`'s branch arrays, `tools` the framework's own encoded-tool-list key, and `tool`
   * a conditional's single `condition: { tool: {...} }` predicate.
   *
   * Closed on purpose, mirroring `TrailToolUsageScanner`'s set and for the same reason: a walk that
   * harvested any object keyed `switchDevice` at any depth would read an ordinary data argument
   * (`payload: { switchDevice: { name: "kitchen" } }`) as a real invocation, and this gate is
   * FATAL — a false finding fails `trailblaze check` on a trail that runs perfectly well.
   */
  private val NESTED_TOOL_LIST_KEYS = setOf("tools", "then", "else")
  private const val NESTED_TOOL_CALL_KEY = "tool"

  /**
   * Which slot a nested handover sits in, because the two have different replay semantics.
   *
   * `block_runIf` dispatches its `condition: { tool: ... }` [PREDICATE] inside a try/catch — "a
   * throw is a `false` verdict, not an error" — so a `switchDevice` there that cannot bind does NOT
   * fail the trail and does NOT move the session: the condition is false and the else branch runs
   * on the same device. A [BRANCH] handover has no such catch: it fails the run outright when the
   * name is unbindable, and moves the session when it is not.
   */
  private enum class HandoverSlot { BRANCH, PREDICATE }

  /**
   * Every `switchDevice` nested inside [element]'s recorded tool-call slots, each paired with the
   * slot it was found in.
   *
   * The walk recurses through the whole document, so a conditional inside another conditional's
   * branch is reached, and a wrapper this host has no class for — riding along as raw JSON in an
   * `OtherTrailblazeTool` — is covered like a typed one. What it does NOT do is treat a bare
   * `switchDevice` key as a call: only the single-key `{ <toolName>: <args> }` shape sitting in one
   * of [NESTED_TOOL_LIST_KEYS] or under [NESTED_TOOL_CALL_KEY] counts.
   *
   * A nested call whose args are absent or not an object still yields an entry: it IS a handover
   * structurally, and [readTargetName] independently decides that its target is unreadable.
   */
  private fun collectNestedHandovers(element: JsonElement): List<Pair<HandoverSlot, JsonObject>> = buildList {
    fun harvest(slot: HandoverSlot, candidate: JsonElement) {
      val call = candidate as? JsonObject ?: return
      val (name, args) = call.entries.singleOrNull()?.toPair() ?: return
      if (name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME) {
        add(slot to (args as? JsonObject ?: JsonObject(emptyMap())))
      }
    }
    fun walk(node: JsonElement) {
      when (node) {
        is JsonObject -> node.forEach { (key, value) ->
          if (key in NESTED_TOOL_LIST_KEYS && value is JsonArray) {
            value.forEach { harvest(HandoverSlot.BRANCH, it) }
          }
          if (key == NESTED_TOOL_CALL_KEY) harvest(HandoverSlot.PREDICATE, value)
          walk(value)
        }

        is JsonArray -> node.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(element)
  }

  /**
   * Whether this recorded call hands off at all — structurally, regardless of whether any target
   * name is readable.
   *
   * Deliberately NOT `handoverTargets(tool).isNotEmpty()`. That list is empty for an interpolated
   * or malformed name by design, and a caller asking "did the active device change here?" needs a
   * yes for exactly those cases: `then: [{ switchDevice: { name: "{{nextDevice}}" } }]` moves the
   * session at replay just as surely as a literal name does. [SelectorDialectLint] uses this to
   * decide when to stop attributing selectors to a member it can no longer track — reading it off
   * the target list would let an interpolated handover replay on with a stale active device.
   */
  fun containsHandover(tool: TrailblazeToolYamlWrapper): Boolean =
    tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME ||
      containsHandover(tool.toJsonArgs())

  /**
   * As above for one argument SUBTREE — for a caller judging part of a tool's args against the
   * member active before the tool ran, which is only sound while that subtree cannot itself move
   * the session.
   */
  fun containsHandover(args: JsonElement): Boolean =
    collectNestedHandovers(args).isNotEmpty()

  /**
   * Whether this recorded call can leave a DIFFERENT device active than the one it started on,
   * given the names this trail is able to bind.
   *
   * Stricter than [containsHandover] in exactly one case, and the case matters: a PREDICATE
   * handover naming a device that is not in [bindableNames] can only ever throw, and `block_runIf`
   * turns that throw into a `false` verdict on the same device. The session provably does not move,
   * so a caller that tracks the active device can keep tracking it. Treating that as a move instead
   * costs coverage rather than correctness — [SelectorDialectLint] would abandon the rest of the
   * leg, and every invalid selector after the conditional would go unchecked on a trail whose
   * active device never changed.
   *
   * Everything else is a possible move: a bindable predicate name switches for real before either
   * branch runs, an unreadable one is not knowable here, and any branch handover either moves the
   * session or fails the run — which [handoverTargets] reports separately.
   */
  fun canMoveSession(tool: TrailblazeToolYamlWrapper, bindableNames: Set<String>): Boolean =
    tool.name == SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME ||
      canMoveSession(tool.toJsonArgs(), bindableNames)

  /** As above for one argument SUBTREE. */
  fun canMoveSession(args: JsonElement, bindableNames: Set<String>): Boolean =
    collectNestedHandovers(args).any { (slot, nested) ->
      slot == HandoverSlot.BRANCH ||
        readTargetName(nested).let { target -> target == null || target in bindableNames }
    }

  /**
   * The literal device name this handover targets, or null when this check can't judge it:
   *
   *  - a missing / non-string / blank `name:` is a decode problem, not a naming problem — run time
   *    owns the error, and blanket-rejecting would turn `JsonNull` or a number into a fabricated
   *    device name in the message;
   *  - a name carrying an interpolation token resolves from memory at dispatch, after the session
   *    seeds `initialMemorySeeds` / `initialArgs`, so it is genuinely unknown here.
   *
   * Shared with [SelectorDialectLint], which needs the same "is this name knowable statically?"
   * answer before it decides whether an unmatched handover is a real finding.
   */
  internal fun readTargetName(tool: TrailblazeToolYamlWrapper): String? = readTargetName(tool.toJsonArgs())

  /** As above, from an already-extracted args object — the shape a nested call arrives in. */
  private fun readTargetName(args: JsonObject): String? {
    val name = (args["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (name.isNullOrBlank()) return null
    return name.takeIf { !INTERPOLATION_TOKEN.containsMatchIn(it) }
  }
}
