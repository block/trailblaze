package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * On-demand "full screen" inspection for the in-process Koog agent loop.
 *
 * The screen view the agent receives after every action is the **compact** element list, which by
 * design lists only the *interactable / content-bearing* elements (each tagged with a `[ref]`). That
 * keeps the per-turn context small, but it means non-interactable nodes — static labels, headings,
 * decorative text — aren't shown. When the agent needs the fuller picture (an element it expects
 * isn't in the compact list, or it needs surrounding static text for context), this tool re-renders
 * the **same `[ref]`-annotated representation with the "meaningful" filter disabled**
 * ([SnapshotDetail.ALL_ELEMENTS]) so every visible element is listed.
 *
 * Crucially this is still the **ref-based** representation: elements keep the same `[ref]` ids the
 * agent already taps with (refs are content-hashed, stable across captures and detail levels — see
 * [xyz.block.trailblaze.api.ElementRef]). So the agent reads the full list and then taps by `ref`
 * exactly as it does from the compact view — no coordinate fallback.
 *
 * Read-only ([ReadOnlyTrailblazeTool]) and non-recordable: it never mutates device state, so it is
 * not part of any recording and does not invalidate the snapshot cache. Mirrors the higher-fidelity
 * `viewHierarchy(verbosity=FULL)` capability the MCP `newtools` observation toolset exposes to the
 * other agent, but as a self-contained tool that reads the live screen state in-process.
 */
@Serializable
@TrailblazeToolClass(
  name = "requestDetailedViewHierarchy",
  // Agent-loop-internal perception aid: the in-process Koog agent calls it mid-reasoning to widen
  // its view of the screen. It is NOT a user-facing action — like `objectiveStatus` it's injected
  // into the agent's tool registry (via koogInspectionTools), not the user catalog, so it never
  // appears in `toolbox` and can't be invoked as a standalone `trailblaze tool …` command.
  // isRecordable = false keeps it out of trail recordings; surfaceToScriptedTools = false keeps it
  // out of scripted-tool `client.d.ts` authoring (it's an internal dispatcher detail, not a
  // building block a `.ts` tool author should call).
  isRecordable = false,
  surfaceToScriptedTools = false,
)
@LLMDescription(
  "List the FULL view hierarchy: every element, including non-interactable ones (static labels, " +
    "headings, text) AND elements scrolled off-screen — all of which are omitted from the compact " +
    "screen view you normally see. Each element is shown with its [ref] id; off-screen ones are " +
    "marked `(offscreen)` (scroll them into view before tapping). Tap any on-screen element with " +
    "the `tap` tool by ref, exactly as from the normal screen view. Call this when an element you " +
    "expect isn't in the current screen view, when you need surrounding static text for context, " +
    "or to discover what's reachable by scrolling — BEFORE blindly scrolling or guessing.",
)
class RequestDetailedViewHierarchyTrailblazeTool(
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // Prefer the screenState the context already carries — the dispatcher captures it fresh and
    // passes it in (`runTrailblazeTools(screenState = screenStateProvider(), …)`), so reusing it
    // avoids a redundant, expensive recapture. Only fall back to the provider if no screenState
    // was supplied (e.g. a direct invocation that didn't pre-capture).
    val screenState = toolExecutionContext.screenState
      ?: toolExecutionContext.screenStateProvider?.invoke()
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "requestDetailedViewHierarchy: no screen state available. Is a device connected?",
      )

    // Re-render the compact, ref-annotated element list with BOTH the "meaningful" filter disabled
    // ([SnapshotDetail.ALL_ELEMENTS] — surfaces non-interactable nodes) AND off-screen elements
    // included ([SnapshotDetail.OFFSCREEN] — surfaces scroll-reachable nodes, marked "(offscreen)").
    // Together these are the genuine "full hierarchy" the compact view trims away. Drivers that
    // build their text representation eagerly override the detail-aware overload (e.g. the Maestro
    // host driver); others fall back to the default compact text.
    val fullList = screenState.viewHierarchyTextRepresentation(
      setOf(SnapshotDetail.ALL_ELEMENTS, SnapshotDetail.OFFSCREEN),
    )
      ?: screenState.viewHierarchyTextRepresentation
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "requestDetailedViewHierarchy: current driver does not expose a view hierarchy " +
          "(platform=${screenState.trailblazeDevicePlatform.name}).",
      )

    return TrailblazeToolResult.Success(
      message = "Full view hierarchy (all elements, including non-interactable and off-screen ones). " +
        "Tap any on-screen element by its [ref] with the `tap` tool; scroll \"(offscreen)\" elements " +
        "into view before tapping.\n\n$fullList",
    )
  }
}
