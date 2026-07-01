// The `selector` field on this tool is @Deprecated to focus the deprecation signal on
// *external* construction sites that still pass legacy selectors. The class's own internal
// logic (toMaestroCommands lowering, the desc fallback, dispatch in execute) must continue
// to read `selector` until the migration completes — those internal references are the
// legitimate handling for legacy recordings already on disk, not new tech debt.
@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass(
  name = "assertNotVisibleBySelector",
  surfaceToLlm = false,
  isVerification = true,
)
@LLMDescription("Asserts that an element with the provided selector is NOT visible on the screen.")
/**
 *  ----- DO NOT GIVE THIS TOOL TO THE LLM -----
 *
 * The not-visible counterpart to [AssertVisibleBySelectorTrailblazeTool]; delegate to it,
 * don't register it to the LLM. It lets recorded trails assert the *absence* of a
 * selector-resolved element while keeping the selector's spatial / structural scoping
 * (`below:`, `containsChild:`, …) — expressiveness the text-only
 * [AssertNotVisibleWithTextTrailblazeTool] cannot offer.
 *
 * Dispatch mirrors the visible variant: by default the Maestro path emits an
 * `AssertConditionCommand(notVisible = …)`; in node-selector modes it delegates to
 * [xyz.block.trailblaze.MaestroTrailblazeAgent.executeNodeSelectorAssertNotVisible] and
 * falls back to the Maestro path when the driver returns null.
 */
data class AssertNotVisibleBySelectorTrailblazeTool(
  val reason: String? = null,
  /**
   * Legacy Maestro-shape selector. **Deprecated** — new construction should use
   * [nodeSelector]. Retained nullable + serializable so older trail YAMLs (which carry a
   * `selector:` block) keep loading unchanged; [execute] picks the path per recording. At
   * least one of [selector] / [nodeSelector] must be non-null at runtime.
   */
  @Deprecated(
    message = "Prefer `nodeSelector` for new construction; this field exists only to load " +
      "legacy YAML recordings until the migration completes.",
    level = DeprecationLevel.WARNING,
  )
  val selector: TrailblazeElementSelector? = null,
  /** Rich driver-native selector generated from [xyz.block.trailblaze.api.TrailblazeNode] trees. Preferred over [selector] when present. */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /**
   * Maximum time (in milliseconds) to wait for the element to DISAPPEAR. The driver polls
   * the screen until either the element is gone or this timeout elapses. When `null` the
   * call is unopinionated and each agent applies its own idle/wait policy. The Maestro
   * fallback path ignores this field (Maestro's own assert timeout is used there).
   */
  val timeoutMs: Long? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val maestroSelector = lowerToMaestroSelector(selector, nodeSelector)
      ?: error(
        "AssertNotVisibleBySelectorTrailblazeTool.toMaestroCommands called with neither " +
          "`selector` nor `nodeSelector` set — malformed recording.",
      )
    return listOf(
      AssertConditionCommand(condition = Condition(notVisible = maestroSelector.toMaestroElementSelector())),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    require(selector != null || nodeSelector != null) {
      "AssertNotVisibleBySelectorTrailblazeTool requires at least one of `selector` or " +
        "`nodeSelector` to be non-null."
    }
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    val result = when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> super.execute(toolExecutionContext)
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          val effectiveNodeSelector = nodeSelector
            ?: selector?.toTrailblazeNodeSelector(toolExecutionContext.trailblazeDeviceInfo.platform)
            ?: error("FORCE_NODE_SELECTOR with neither nodeSelector nor selector")
          agent.executeNodeSelectorAssertNotVisible(
            nodeSelector = effectiveNodeSelector,
            timeoutMs = timeoutMs,
            traceId = toolExecutionContext.traceId,
          ) ?: super.execute(toolExecutionContext)
        } else {
          super.execute(toolExecutionContext)
        }
      }
      NodeSelectorMode.PREFER_NODE_SELECTOR -> {
        if (nodeSelector != null && agent != null) {
          agent.executeNodeSelectorAssertNotVisible(
            nodeSelector = nodeSelector,
            timeoutMs = timeoutMs,
            traceId = toolExecutionContext.traceId,
          ) ?: super.execute(toolExecutionContext)
        } else {
          super.execute(toolExecutionContext)
        }
      }
    }
    if (result.isSuccess()) {
      // Prefer the most human-readable identifier for the success message, mirroring the
      // tier order in [AssertVisibleBySelectorTrailblazeTool]: legacy text/id → driver-block
      // textRegex → accessibility/content-description → resource id.
      val desc = selector?.textRegex
        ?: selector?.idRegex
        ?: nodeSelector?.androidAccessibility?.textRegex
        ?: nodeSelector?.androidMaestro?.textRegex
        ?: nodeSelector?.iosMaestro?.textRegex
        ?: nodeSelector?.androidAccessibility?.contentDescriptionRegex
        ?: nodeSelector?.androidMaestro?.accessibilityTextRegex
        ?: nodeSelector?.iosMaestro?.accessibilityTextRegex
        ?: nodeSelector?.androidAccessibility?.resourceIdRegex
        ?: nodeSelector?.androidMaestro?.resourceIdRegex
        ?: nodeSelector?.iosMaestro?.resourceIdRegex
        ?: "element"
      return TrailblazeToolResult.Success(message = "Verified '$desc' not visible")
    }
    return result
  }
}
