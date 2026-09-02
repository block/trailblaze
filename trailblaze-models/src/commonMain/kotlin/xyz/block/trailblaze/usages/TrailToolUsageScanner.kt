package xyz.block.trailblaze.usages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets

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
   * Which of the trail's declared device classifiers actually reach the tool, given the [steps] a
   * [toolUsages] scan attributed to it.
   *
   * This is the question a consumer selecting lanes to run has to answer, and it is NOT
   * `steps.flatMap { it.classifiers }`: a recording is chosen at replay by **closest-wins**
   * resolution, so a device whose step declares a more specific non-invoking key never reaches an
   * `all:` invocation. A trail keying `all:` and `ios-iphone:` in one step, with the tool only under
   * `all:`, does not invoke it on an iPhone. Answering that requires the lineage, which is why it
   * lives here rather than in the KDoc as homework for every caller.
   *
   * The candidate set is [UnifiedTrailTargets.declaredClassifiers] — the framework's existing answer
   * to "what does this trail carry direction for", so this cannot drift from device selection or the
   * desktop Trails browser. It may include `all`, which is a real coverage token in this vocabulary
   * ("the leg nothing more specific claims") and not filtered out; a trail that keys only `all:`
   * legitimately answers `[all]`.
   *
   * MULTI-DEVICE CONFIGURATIONS are resolved the way selection resolves them, not skipped. A
   * configuration's legs are keyed by the configuration NAME (`pos-pair:`), which no member device's
   * lineage contains — so a chain walk alone answers "reaches nothing" for every member of a trail
   * that records under its configuration, which is every such trail. Selection is what makes those
   * legs reachable: `UnifiedTrailAdapter.lowerToTrailItems` puts the selected configuration's name
   * at the HEAD of the chain and excludes only the OTHER configurations' names, so each attempt
   * below mirrors one runnable session. A trail that declares a configuration has NO
   * configuration-free session — `MultiDeviceConfigurationResolver.resolve` always selects the
   * sole declared configuration, and rejects a trail declaring more than one — so the plain
   * single-device chain is attempted only when the trail declares none. In a configured trail a
   * member resolves with its configuration at the head of its chain, where it can shadow a broader
   * leg (`all:`); a classifier no configuration casts never runs the trail at all, so it has no
   * sessions and reaches nothing. A classifier reaches the tool when any of its sessions does.
   */
  fun invokingClassifiers(trail: UnifiedTrail, steps: List<TrailStepToolUsage>): Set<String> {
    if (steps.isEmpty()) return emptySet()
    val configurationNames = trail.config.multiDeviceConfigurationNames
    val configurationsByMember = configurationMembership(trail)
    // Hoisted out of the per-classifier walk below: the same step sets are re-read for every
    // candidate classifier, and `classifiers` is a List whose membership test is linear.
    val resolvableSteps = steps.map { step ->
      ResolvableStep(
        declaredKeys = step.declaredClassifiers.toSet(),
        invokingKeys = step.classifiers.toSet(),
      )
    }
    return UnifiedTrailTargets.declaredClassifiers(trail).filterTo(LinkedHashSet()) { classifier ->
      val chain = TrailblazeClassifierLineage
        .chainFor(TrailblazeDeviceClassifier(classifier))
        .map { it.classifier }
      // Each entry is one session this classifier can run in. `null` — select nothing, exclude
      // every configuration name — exists only when the trail declares no configuration: a trail
      // that declares one always replays with it selected. A member's sessions are the
      // configurations casting it; a classifier no configuration casts never runs a configured
      // trail at all, so it gets no sessions and reaches nothing.
      val sessions: List<String?> = when {
        configurationNames.isEmpty() -> listOf(null)
        else -> configurationsByMember[classifier].orEmpty()
      }
      sessions.any { selectedConfiguration ->
        val resolutionChain = listOfNotNull(selectedConfiguration) + chain
        val excludedKeys = configurationNames - setOfNotNull(selectedConfiguration)
        resolvableSteps.any { step ->
          TrailblazeClassifierLineage.closestDeclaredKey(
            declaredKeys = step.declaredKeys,
            resolutionChain = resolutionChain,
            excludedKeys = excludedKeys,
          ) in step.invokingKeys
        }
      }
    }
  }

  /** One step's keys as sets, so the per-classifier walk re-reads them without rebuilding them. */
  private class ResolvableStep(val declaredKeys: Set<String>, val invokingKeys: Set<String>)

  /**
   * Member classifier → the multi-device configurations that cast it. A device can appear in more
   * than one configuration, and each is a separately selectable session with its own recordings, so
   * every one of them is a way that member might reach the tool.
   */
  private fun configurationMembership(trail: UnifiedTrail): Map<String, List<String>> {
    val byMember = LinkedHashMap<String, MutableList<String>>()
    trail.config.devices?.forEach { (name, definition) ->
      definition.devices?.values?.forEach { member ->
        member.classifier?.let { byMember.getOrPut(it) { mutableListOf() }.add(name) }
      }
    }
    return byMember
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
