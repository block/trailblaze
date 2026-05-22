// The `selector` field on this tool is @Deprecated to focus the deprecation signal on
// *external* construction sites that still pass legacy selectors. The class's own
// internal logic (toMaestroCommands lowering, the desc fallback, dispatch in execute)
// must continue to read `selector` until the migration completes — those internal
// references are the legitimate handling for legacy recordings already on disk, not
// new tech debt. Suppress at file scope so the warning signal stays focused.
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
  name = "assertVisibleBySelector",
  surfaceToLlm = false,
  surfaceToScriptedTools = false,
  isVerification = true,
)
@LLMDescription("Asserts that an element with the provided selector is visible on the screen.")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class AssertVisibleBySelectorTrailblazeTool(
  val reason: String? = null,
  /**
   * Legacy Maestro-shape selector.
   *
   * **Deprecated** — new tool construction should use [nodeSelector] exclusively. This
   * field remains nullable + serializable so older trail YAMLs (which carry a `selector:`
   * block alongside `nodeSelector:` or solo) continue to load unchanged and the runtime
   * dispatch logic in [execute] picks the right path per recording. The field will be
   * removed once the remaining flat-selector inventory in committed trails reaches zero
   * (tracked in the selector→nodeSelector migration workstream). At least one of
   * [selector] and [nodeSelector] must be non-null at runtime — the [execute]
   * function enforces this.
   */
  @Deprecated(
    message = "Prefer `nodeSelector` for new construction; this field exists only to load " +
      "legacy YAML recordings until the migration completes.",
    level = DeprecationLevel.WARNING,
  )
  val selector: TrailblazeElementSelector? = null,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees.
   * When present, the agent will attempt to use this for richer element matching
   * before falling back to the legacy Maestro command path via [selector].
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /**
   * Maximum time (in milliseconds) to wait for the element to become visible. The driver
   * polls the screen until either the element appears or this timeout elapses, so this
   * doubles as a "wait for selector" knob — set it higher when the screen needs time to
   * settle (e.g. an "Authorizing" overlay clearing) before the target text renders.
   *
   * When `null` the call is unopinionated about timeout and each agent applies its own
   * idle/wait policy (per-driver default). The Maestro fallback path ignores this field
   * entirely — Maestro's own assert timeout is always used there.
   */
  val timeoutMs: Long? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val maestroSelector = lowerToMaestroSelector(selector, nodeSelector)
      ?: error(
        "AssertVisibleBySelectorTrailblazeTool.toMaestroCommands called with neither " +
          "`selector` nor `nodeSelector` set — malformed recording.",
      )
    return listOf(
      AssertConditionCommand(condition = Condition(visible = maestroSelector.toMaestroElementSelector())),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    require(selector != null || nodeSelector != null) {
      "AssertVisibleBySelectorTrailblazeTool requires at least one of `selector` or " +
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
          agent.executeNodeSelectorAssertVisible(
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
          agent.executeNodeSelectorAssertVisible(
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
      // Prefer the original selector's text for a friendly message, but fall back to the
      // nodeSelector's driver-specific text for post-migration recordings where the legacy
      // `selector:` block is gone. Ordered by property tier (most → least human-readable),
      // with drivers alphabetized within each tier:
      //   1. Legacy `selector` (most direct authoring intent: textRegex → idRegex)
      //   2. Driver-block textRegex (best for log readability)
      //   3. Accessibility / content-description text (still human-readable)
      //   4. Resource ID (last resort — typically opaque)
      val desc = selector?.textRegex
        ?: selector?.idRegex
        // Tier: textRegex across all driver blocks
        ?: nodeSelector?.androidAccessibility?.textRegex
        ?: nodeSelector?.androidMaestro?.textRegex
        ?: nodeSelector?.iosMaestro?.textRegex
        // Tier: accessibility / content-description text
        ?: nodeSelector?.androidAccessibility?.contentDescriptionRegex
        ?: nodeSelector?.androidMaestro?.accessibilityTextRegex
        ?: nodeSelector?.iosMaestro?.accessibilityTextRegex
        // Tier: resource ID
        ?: nodeSelector?.androidAccessibility?.resourceIdRegex
        ?: nodeSelector?.androidMaestro?.resourceIdRegex
        ?: nodeSelector?.iosMaestro?.resourceIdRegex
        ?: "element"
      return TrailblazeToolResult.Success(message = "Verified '$desc' visible")
    }
    return result
  }
}
