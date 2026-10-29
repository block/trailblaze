package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
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
  isVerification = true,
)
@LLMDescription("Asserts that an element with the provided selector is visible on the screen. Also matches state (checked/enabled/selected/stateDescription).")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class AssertVisibleBySelectorTrailblazeTool(
  val reason: String? = null,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees. Required — the
   * [execute] function enforces non-null. When present, the agent will attempt to use this
   * for richer element matching before falling back to the Maestro command path, which
   * lowers this to a Maestro-shaped selector via [lowerToMaestroSelector].
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /**
   * Maximum time (in milliseconds) to wait for the element to become visible. The driver
   * polls the screen until either the element appears or this timeout elapses, so this
   * doubles as a "wait for selector" knob — set it higher when the screen needs time to
   * settle (e.g. an "Authorizing" overlay clearing) before the target text renders.
   *
   * When `null` the call is unopinionated about timeout and each agent applies its own
   * idle/wait policy (per-driver default). Forwarded to the Maestro fallback path too —
   * without that, a driver that resolves the selector but does not itself poll (the iOS host
   * agent returns null when the element is not on screen yet, by design) silently got
   * Maestro's default budget instead of the one the author asked for.
   */
  val timeoutMs: Long? = null,
  /**
   * Removed: the selector carries the text now. The recorder writes the element's exact text into
   * `textRegex`, so a separate field to compare it again is not needed.
   *
   * Still decoded so a step that sets it FAILS rather than passing on presence alone: trail
   * decoding ignores unknown keys, so deleting the property would silently drop the text check
   * from every trail recorded before the change. Re-record the step: the recorder pins the element's
   * exact text in the driver's own text field. A hand-edited `\Q<text>\E` drops case sensitivity on
   * Maestro and is the wrong field on AXe and web.
   */
  val expectedText: String? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> {
    check(expectedText == null) { REMOVED_EXPECTED_TEXT }
    val maestroSelector = lowerToMaestroSelector(nodeSelector)
      ?: error(
        "AssertVisibleBySelectorTrailblazeTool.toMaestroCommands called with `nodeSelector` " +
          "not set — malformed recording.",
      )
    return listOf(
      AssertConditionCommand(
        condition = Condition(visible = maestroSelector.toMaestroElementSelector()),
        timeout = timeoutMs?.toString(),
      ),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    require(nodeSelector != null) {
      "AssertVisibleBySelectorTrailblazeTool requires `nodeSelector` to be non-null."
    }
    removedFieldError()?.let { return it }
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    val result = when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> super.execute(toolExecutionContext)
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          agent.executeNodeSelectorAssertVisible(
            nodeSelector = nodeSelector,
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
      return TrailblazeToolResult.Success(message = "Verified '${selectorDescription()}' visible")
    }
    return result
  }

  /**
   * The failure for a step that still sets [expectedText], or null. Public for a driver that runs
   * the visibility check on its own backend instead of through [execute], so it fails the same way.
   * The message never repeats the value: it can be a remembered secret.
   */
  fun removedFieldError(): TrailblazeToolResult.Error? =
    if (expectedText == null) null else TrailblazeToolResult.Error.ExceptionThrown(errorMessage = REMOVED_EXPECTED_TEXT)

  /**
   * The nodeSelector's most human-readable handle, for result messages. Ordered by property
   * tier (most → least human-readable), with drivers alphabetized within each tier:
   *   1. Driver-block textRegex (best for log readability)
   *   2. Accessibility / content-description text (still human-readable)
   *   3. Resource ID (last resort — typically opaque)
   */
  private fun selectorDescription(): String = nodeSelector?.androidAccessibility?.textRegex
    ?: nodeSelector?.androidMaestro?.textRegex
    ?: nodeSelector?.androidView?.textRegex
    ?: nodeSelector?.iosMaestro?.textRegex
    // Tier: accessibility / content-description text
    ?: nodeSelector?.androidAccessibility?.contentDescriptionRegex
    ?: nodeSelector?.androidMaestro?.accessibilityTextRegex
    ?: nodeSelector?.androidView?.contentDescriptionRegex
    ?: nodeSelector?.iosMaestro?.accessibilityTextRegex
    // Tier: resource ID
    ?: nodeSelector?.androidAccessibility?.resourceIdRegex
    ?: nodeSelector?.androidMaestro?.resourceIdRegex
    ?: nodeSelector?.androidView?.resourceIdRegex
    ?: nodeSelector?.iosMaestro?.resourceIdRegex
    // Tier: the whole selector, so a structural one (`containsChild`, `childOf`, an index) still
    // names what it matched.
    ?: nodeSelector?.description()?.takeIf { it.isNotBlank() }
    ?: "an empty selector"
}

private const val REMOVED_EXPECTED_TEXT = "assertVisibleBySelector no longer supports `expectedText`. " +
  "Re-record the step, so the selector itself requires the element's text."
