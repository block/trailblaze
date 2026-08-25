package xyz.block.trailblaze.usages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Extracts direct tool usage from a parsed [UnifiedTrail] — which tools its recordings invoke,
 * and under which device-classifier keys.
 *
 * This is the model-parsed replacement for grepping trail files for `- <tool>:` lines. Parsing
 * first buys two things a text search can't give:
 *  - **Device scoping.** A recording is keyed by classifier, so "uses the tool" comes with
 *    "on which devices" — an `android:`-only invocation never implicates the trail's iOS legs.
 *  - **No phantom matches.** A tool name mentioned in a step's natural-language text, a comment,
 *    or an argument value is not an invocation; only a recorded tool call is.
 */
object TrailToolUsageScanner {

  private val TOOL_NAME_RX = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

  /**
   * Every tool the trail's recordings invoke, mapped to the steps (and their classifier keys)
   * that invoke it. The trailhead scans as a step with a null index; `trail:` steps carry their
   * 0-based index. Tools nested inside a recorded conditional wrapper count too — see
   * [toolNamesIn].
   */
  fun toolUsages(trail: UnifiedTrail): Map<String, List<TrailStepToolUsage>> {
    val byTool = LinkedHashMap<String, MutableList<TrailStepToolUsage>>()

    fun scanStep(stepIndex: Int?, step: UnifiedTrailStep) {
      val declared = step.recordings.keys.toList()
      val classifiersByTool = LinkedHashMap<String, MutableList<String>>()
      for ((classifier, wrappers) in step.recordings) {
        val names = LinkedHashSet<String>()
        wrappers.forEach { names.addAll(toolNamesIn(it)) }
        for (name in names) {
          classifiersByTool.getOrPut(name) { mutableListOf() }.add(classifier)
        }
      }
      for ((name, classifiers) in classifiersByTool) {
        byTool.getOrPut(name) { mutableListOf() }.add(
          TrailStepToolUsage(
            stepIndex = stepIndex,
            step = step.step,
            classifiers = classifiers,
            declaredClassifiers = declared,
          ),
        )
      }
    }

    trail.trailhead?.let { scanStep(null, it) }
    trail.trail.forEachIndexed { index, step -> scanStep(index, step) }
    return byTool
  }

  /**
   * Argument keys under which a conditional wrapper records nested tool-calls, each holding an
   * ARRAY of single-key `{ <toolName>: <args> }` objects. `then`/`else` are the branch lists of
   * the recorded-conditional contract (`block_runIf`-style: the wrapper records verbatim and
   * re-dispatches its branches at replay, so the inner calls exist ONLY inside these arguments);
   * `tools` is the framework's own encoded-tool-list key, kept for wrappers that nest a plain
   * list. Restricted to these known keys rather than harvesting every single-key-object array,
   * because an ordinary data argument (`items: [{Coffee: {qty: 2}}]`) would otherwise read as a
   * tool invocation.
   */
  private val NESTED_TOOL_LIST_KEYS = setOf("tools", "then", "else")

  /** Argument key holding ONE nested tool-call — a conditional's `condition: { tool: {...} }` predicate. */
  private const val NESTED_TOOL_CALL_KEY = "tool"

  /**
   * The tool names one recorded wrapper invokes: its own name, plus any tools nested inside its
   * arguments.
   *
   * Nesting is real usage, not an edge case to ignore: a recorded conditional wrapper
   * (`block_runIf`-style) carries its predicate under `condition: { tool: {...} }` and its branch
   * actions under `then:` / `else:` arrays, and on a host without the wrapper's class those inner
   * calls ride along as raw JSON inside an [OtherTrailblazeTool]. A scan that stopped at wrapper
   * names would report a tool used only as a guard's predicate or branch action as unused — which
   * would make a zero-usage answer unsafe to delete on. The walk is recursive, so a conditional
   * nested inside another's branch still counts. See [NESTED_TOOL_LIST_KEYS] for the harvested
   * keys and why the set is closed.
   */
  fun toolNamesIn(wrapper: TrailblazeToolYamlWrapper): Set<String> {
    val names = LinkedHashSet<String>()
    names.add(wrapper.name)
    (wrapper.trailblazeTool as? OtherTrailblazeTool)?.let { collectNestedToolNames(it.raw, names) }
    return names
  }

  private fun collectNestedToolNames(element: JsonElement, out: MutableSet<String>) {
    when (element) {
      is JsonObject -> {
        for ((key, value) in element) {
          if (key in NESTED_TOOL_LIST_KEYS && value is JsonArray) {
            for (item in value) harvestToolCall(item, out)
          }
          if (key == NESTED_TOOL_CALL_KEY) harvestToolCall(value, out)
          collectNestedToolNames(value, out)
        }
      }
      is JsonArray -> element.forEach { collectNestedToolNames(it, out) }
      else -> Unit
    }
  }

  /** Record [element]'s tool name when it has the single-key `{ <toolName>: <args> }` call shape. */
  private fun harvestToolCall(element: JsonElement, out: MutableSet<String>) {
    (element as? JsonObject)?.entries?.singleOrNull()?.key
      ?.takeIf(TOOL_NAME_RX::matches)
      ?.let(out::add)
  }
}
