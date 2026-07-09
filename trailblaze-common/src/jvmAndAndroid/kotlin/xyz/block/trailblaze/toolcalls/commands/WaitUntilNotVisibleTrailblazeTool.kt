package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.toolcalls.isSuccess

/**
 * Query tool: waits (up to [timeoutMs]) for the element matching [nodeSelector] to become NOT
 * visible, and returns the verdict as a NON-THROWING structured boolean — `true` when the
 * selector is (or became) not visible within the budget, `false` when it is still visible after
 * the wait.
 *
 * This is the disappearance counterpart to [FindMatchesTrailblazeTool] (which waits for
 * APPEARANCE). It's the scripted-tool equivalent of the Kotlin agent's
 * `SquareVisibilityUtils.isTextNotVisibleAndroid` / `executeNodeSelectorAssertNotVisible`, but it
 * RETURNS a boolean rather than asserting — so "still visible after the timeout" is a normal
 * `false` result the caller branches on (`if (!notVisible) …`), not a thrown verification failure.
 * That's the whole point: it lets a scripted launch step keep its own rich, custom error messages
 * (e.g. "App is still blocked on 2FA", the sign-in-loading-stuck diagnostic) instead of inheriting
 * the generic message a throwing `assertNotVisibleWithText` would emit.
 *
 * Why a dedicated primitive (rather than negating `findMatches`): a positive
 * visibility probe returns true the moment the text is *currently* on screen, so negating it
 * doesn't WAIT for the element to actually go away — right after an action triggers a transition
 * the negation flips to false before the screen leaves the tree. This tool waits for the element
 * to actually disappear, exactly like `isTextNotVisibleAndroid`.
 *
 * ## Driver routing (mirrors `isTextNotVisibleAndroid`)
 *
 * - **Accessibility driver** (`usesAccessibilityDriver`): routes through the driver-native
 *   event-driven [xyz.block.trailblaze.MaestroTrailblazeAgent.executeNodeSelectorAssertNotVisible]
 *   wait against the live accessibility tree.
 * - **Maestro / instrumentation driver**: falls back to a Maestro `extendedWaitUntil`-style
 *   `AssertConditionCommand(notVisible = …)` via [MapsToMaestroCommands]. The [nodeSelector] is
 *   lowered to a Maestro selector by [lowerToMaestroSelector] (text/resourceId predicates map
 *   cleanly; a driver-only selector that can't lower fails loudly there).
 *
 * ## Wire shape
 *
 * Always returns [TrailblazeToolResult.Success] with [TrailblazeToolResult.Success.structuredContent]
 * set to a JSON boolean. The TS SDK's `client.tools.waitUntilNotVisible(...)` proxy unwraps it as
 * the typed `result: boolean` declared in `built-in-tools.ts`.
 *
 * NOT marked [xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool]: waiting for an element to
 * disappear means the live tree changes during the wait, so the per-invocation
 * [xyz.block.trailblaze.toolcalls.SnapshotCache] frame MUST be invalidated afterward — otherwise a
 * follow-up point-in-time `findMatches` in the same batch could read the stale pre-disappearance
 * tree. `isVerification = false` for the same reason (the assertion lives in the caller's branch on
 * the returned boolean, never in this tool).
 */
@Serializable
@TrailblazeToolClass(
  name = "waitUntilNotVisible",
  surfaceToLlm = false,
  isRecordable = false,
  isVerification = false,
)
@LLMDescription(
  "Waits for the element matching the selector to become NOT visible (up to timeoutMs) and " +
    "returns a boolean verdict without throwing: true if it is/became not visible, false if it " +
    "is still visible after the wait. The non-throwing disappearance counterpart to findMatches.",
)
data class WaitUntilNotVisibleTrailblazeTool(
  /** Selector whose disappearance is awaited against the live view hierarchy. */
  val nodeSelector: TrailblazeNodeSelector,
  /**
   * How long (ms) to wait for the element to disappear. `null` lets each driver apply its own
   * idle/wait policy (per-driver default). On the Maestro fallback path this is forwarded as the
   * `AssertConditionCommand` timeout.
   */
  val timeoutMs: Long? = null,
) : MapsToMaestroCommands() {

  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val maestroSelector = lowerToMaestroSelector(nodeSelector = nodeSelector)
      ?: error(
        "waitUntilNotVisible: nodeSelector is required and must lower to a Maestro selector for " +
          "the non-accessibility fallback path.",
      )
    return listOf(
      AssertConditionCommand(
        condition = Condition(notVisible = maestroSelector.toMaestroElementSelector()),
        timeout = timeoutMs?.toString(),
      ),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
    // Mirror SquareVisibilityUtils.isTextNotVisibleAndroid: accessibility driver → native
    // event-driven not-visible wait; otherwise → Maestro AssertConditionCommand fallback
    // (super.execute lowers `toMaestroCommands` through the agent). The accessibility agent
    // returns null when the selector carries a non-accessibility driver branch it can't resolve
    // natively (see AccessibilityTrailblazeAgent.executeNodeSelectorAssertNotVisible); the
    // `?: super.execute(...)` then falls back to the Maestro lowering.
    val verdict: TrailblazeToolResult =
      if (agent != null && agent.usesAccessibilityDriver) {
        agent.executeNodeSelectorAssertNotVisible(
          nodeSelector = nodeSelector,
          timeoutMs = timeoutMs,
          traceId = toolExecutionContext.traceId,
        ) ?: super.execute(toolExecutionContext)
      } else {
        super.execute(toolExecutionContext)
      }

    val notVisible = verdict.isSuccess()
    return TrailblazeToolResult.Success(
      message =
        if (notVisible) {
          "waitUntilNotVisible: selector is not visible"
        } else {
          "waitUntilNotVisible: selector still visible after ${timeoutMs ?: "default"}ms"
        },
      structuredContent = JsonPrimitive(notVisible),
    )
  }
}
