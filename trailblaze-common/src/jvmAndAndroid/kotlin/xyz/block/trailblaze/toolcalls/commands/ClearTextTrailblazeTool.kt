package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.EraseTextCommand
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Clears every character from the currently focused text field, regardless of how long the
 * existing content is.
 *
 * Why this needs to be a Kotlin tool rather than a YAML wrapper around `eraseText`:
 * the bare `eraseText` Maestro command, when invoked with no `charactersToErase` value,
 * is clamped to 50 in every driver path — `MaestroAndroidUiAutomatorDriver` (instrumentation)
 * literally `repeat(50) { pressDelete() }`, `MaestroCommandConverter` (accessibility) defaults
 * to 50, the iOS axe converter defaults to 50. Anything past 50 characters in the focused
 * field silently survives the erase, which then concatenates onto the next `inputText`.
 *
 * This tool reads the focused field's current text length from the view hierarchy snapshot
 * and passes that count explicitly. Drivers see "erase exactly N characters" where N matches
 * the field — accessibility clamps it down further as a no-op safety, instrumentation runs
 * exactly N pressDelete calls instead of an arbitrary cap or an unbounded loop.
 *
 * When the focused field can't be located in the snapshot (stale screenState, non-focused
 * dispatch, view hierarchy that doesn't expose `focused = true`), we fall back to
 * [FALLBACK_ERASE_COUNT] — large enough to clear any realistic single-field content,
 * small enough that the instrumentation `repeat(N)` loop stays under a second.
 */
@Serializable
@TrailblazeToolClass("clearText")
@LLMDescription(
  """
Clear all text from the currently focused text field. Use BEFORE `inputText` when you need
to replace whatever's already in a field (search bar, amount field, form input). Takes no
parameters — the tool reads the field's current length from the view hierarchy.

Prefer this over `eraseText` whenever your intent is "wipe the field, then type fresh". Use
`eraseText` only when you genuinely need to remove a specific number of trailing characters
(e.g. backspacing one digit off an amount).
  """,
)
data object ClearTextTrailblazeTool : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "clearText requires a Maestro-backed agent but ran in a Playwright-native context.",
      )

    val focusedLength = toolExecutionContext.screenState?.viewHierarchy?.let(::focusedEditableTextLength)
    val charactersToErase = focusedLength ?: FALLBACK_ERASE_COUNT

    return agent.runMaestroCommands(
      maestroCommands = listOf(EraseTextCommand(charactersToErase = charactersToErase)),
      traceId = toolExecutionContext.traceId,
    )
  }

  /**
   * Returns the length of the currently focused editable node's text content, or null if no
   * focused node carrying readable text was found in the snapshot. Picks the deepest focused
   * node so a focused EditText nested under a focused container (Compose merged-semantics
   * tree) wins over its parent.
   */
  internal fun focusedEditableTextLength(root: ViewHierarchyTreeNode): Int? {
    // aggregate() walks the tree depth-first; the LAST focused node in that order is the
    // innermost one, which is the actual EditText/UITextField the user is typing into rather
    // than a focused ancestor container.
    val focused = root.aggregate().lastOrNull { it.focused && it.text != null } ?: return null
    return focused.text?.length
  }

  /**
   * Used when the view hierarchy doesn't expose a focused field. Bounded so the
   * instrumentation driver's `repeat(N) { pressDelete() }` loop doesn't run unboundedly when
   * the snapshot is unreliable. 500 chars covers every realistic single-field value (passwords,
   * usernames, addresses, amount fields, search queries).
   */
  internal const val FALLBACK_ERASE_COUNT = 500
}
