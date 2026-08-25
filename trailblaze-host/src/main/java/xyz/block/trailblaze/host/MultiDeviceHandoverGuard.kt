package xyz.block.trailblaze.host

import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.trail.toJsonArgs
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
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
   */
  fun unboundTargets(
    trailItems: List<TrailYamlItem>,
    boundNames: Set<String>,
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
              step.recording?.tools.orEmpty().forEach { add("step $stepNumber" to it) }
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
    return located.mapNotNull { (where, tool) ->
      if (tool.name != SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME) return@mapNotNull null
      val target = readTargetName(tool) ?: return@mapNotNull null
      if (target in boundNames) null else where to target
    }
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
  internal fun readTargetName(tool: TrailblazeToolYamlWrapper): String? {
    val name = (tool.toJsonArgs()["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (name.isNullOrBlank()) return null
    return name.takeIf { !INTERPOLATION_TOKEN.containsMatchIn(it) }
  }
}
