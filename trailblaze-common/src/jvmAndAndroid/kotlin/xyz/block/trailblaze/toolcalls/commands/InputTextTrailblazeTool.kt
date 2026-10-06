package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
// Hidden from the LLM, which types with `type` and picks the field by ref; this is what `type` records.
@TrailblazeToolClass("inputText", surfaceToLlm = false)
@LLMDescription(
  "Type text into the focused text field, or tap `selector` first and type into that field. " +
    "To replace existing text, pass clearFirst: true.",
)
data class InputTextTrailblazeTool(
  @param:LLMDescription("Text to type.") val text: String,
  override val reasoning: String? = null,
  /**
   * Whether to dismiss the soft keyboard after typing. Default `true`: each `inputText` step
   * leaves the device ready for the next one, with no keyboard covering the next tap's target.
   * Recorded trails omit this field, so they replay with the dismissal.
   *
   * The wasm `/devices` viewer passes `false` on its per-keystroke flush, because the user is
   * still typing.
   */
  @param:LLMDescription("Close the soft keyboard after typing. Default true.")
  val hideKeyboardAfter: Boolean = true,
  /**
   * The field to type into. When set, the field is tapped first with the same selector-resolved tap
   * as `tapOnElementBySelector`, so a field that reacts to a click still gets one; the Android
   * accessibility driver then also focuses it directly when it is an editable field, so the text
   * can't land in another one. A selector naming the field's label, or a hint that focusing hides,
   * types into the field the tap focused. When null, types into the focused field, as recorded
   * trails always have.
   *
   * The LLM never sets this: it picks a field by ref with [TypeTrailblazeTool], which records as
   * this tool with the selector filled in.
   */
  @param:LLMDescription("Tap this field first, then type into it. Omit to type into the focused field.")
  val selector: TrailblazeNodeSelector? = null,
  /**
   * Empty the field before typing, so it ends up holding only [text] — one step in place of a
   * recorded `eraseText`/`clearText` + `inputText` pair. With a [selector], the field emptied is
   * the one the text lands in: the field the driver focused, or the one the tap focused. Default
   * `false`: typing adds to what the field holds, as recorded trails always have.
   */
  @param:LLMDescription("Empty the field before typing, replacing what it holds. Default false.")
  val clearFirst: Boolean = false,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so `text` arrives resolved here.
    if (selector != null) {
      // Tap first, as the tapOnElementBySelector step this replaces did.
      val tapResult = TapOnByElementSelector(reason = reasoning, nodeSelector = selector)
        .execute(toolExecutionContext)
      if (!tapResult.isSuccess()) return tapResult
      // A driver that can focus the field directly types there; the rest type into what the tap focused.
      toolExecutionContext.maestroTrailblazeAgent
        ?.executeNodeSelectorInputText(selector, text, hideKeyboardAfter, clearFirst, toolExecutionContext.traceId)
        ?.let { return it }
    }
    if (clearFirst) {
      val cleared = clearFocusedField(toolExecutionContext)
      if (!cleared.isSuccess()) return cleared
    }
    val maestroCommands = if (hideKeyboardAfter) {
      listOf(InputTextCommand(text)) +
        HideKeyboardTrailblazeTool.hideKeyboardCommands(toolExecutionContext.trailblazeDeviceInfo)
    } else {
      listOf(InputTextCommand(text))
    }
    val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
    if (result.isSuccess()) return TrailblazeToolResult.Success(message = "Typed '$text'")
    return result
  }

  /**
   * Empties the focused field: natively where the driver can, otherwise by erasing
   * [charactersToClear] characters. A [selector] tap just moved focus, so its count comes from a
   * fresh capture — the step's own screen state predates the tap.
   */
  private suspend fun clearFocusedField(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    toolExecutionContext.maestroTrailblazeAgent
      ?.clearFocusedTextField(toolExecutionContext.traceId)
      ?.let { return it }
    val screenState = if (selector != null) {
      toolExecutionContext.screenStateProvider?.invoke() ?: toolExecutionContext.screenState
    } else {
      toolExecutionContext.screenState
    }
    val charactersToErase = charactersToClear(screenState, selector)
    // The focused field is already empty.
    if (charactersToErase == 0) return TrailblazeToolResult.Success()
    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = listOf(EraseTextCommand(charactersToErase = charactersToErase)),
      traceId = toolExecutionContext.traceId,
    )
  }

  companion object {
    /** Class names of the views a key press types into, on the Maestro-lowered drivers. */
    private val TEXT_INPUT_CLASS = Regex("(EditText|TextField|SearchField)$")

    /**
     * How many characters to erase to empty the field, when the driver has no clear of its own.
     * Erasing past the start of a field is harmless; stopping short leaves old text in front of
     * the new, so every uncertain case rounds up.
     *
     * - The focused field's length, when the hierarchy marks one focused.
     * - Otherwise, the length of what [selector] resolves to, when every match is a text input
     *   (it has a placeholder, is a password field, or has a text-input class): an empty one
     *   erases nothing. iOS through Maestro marks no field focused, yet reports a field's value
     *   (masked, for a secure field) as its text.
     * - Otherwise [ClearTextTrailblazeTool.FALLBACK_ERASE_COUNT]: a selector naming a label
     *   beside the field says nothing about how much the field holds.
     *
     * Best-effort, like the recorded tap + `eraseText` it replaces: keys erase from the caret, and
     * the count trusts the capture taken after the tap, so a field whose caret or focus isn't
     * where the tap should leave it can keep text. The native clears (Android accessibility, AXe,
     * in-process Android) check the field instead.
     */
    internal fun charactersToClear(screenState: ScreenState?, selector: TrailblazeNodeSelector?): Int {
      screenState?.viewHierarchy?.let(ClearTextTrailblazeTool::focusedEditableTextLength)?.let { return it }
      val root = screenState?.trailblazeNodeTree
      if (root != null && selector != null) {
        val matches = when (val result = TrailblazeNodeSelectorResolver.resolve(root, selector)) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
        }
        val values = matches.map { it.driverDetail.textInputValue() }
        if (values.isNotEmpty() && values.all { it != null }) return values.maxOf { it!!.length }
      }
      return ClearTextTrailblazeTool.FALLBACK_ERASE_COUNT
    }

    /**
     * What a text input on a Maestro-lowered driver holds — its text, not its placeholder, and ""
     * when empty — or null when the node isn't identifiably a text input.
     */
    private fun DriverNodeDetail.textInputValue(): String? = when (this) {
      is DriverNodeDetail.IosMaestro -> text.orEmpty().takeIf { isTextInput(hintText, password, className) }
      is DriverNodeDetail.AndroidMaestro -> text.orEmpty().takeIf { isTextInput(hintText, password, className) }
      else -> null
    }

    private fun isTextInput(hintText: String?, password: Boolean, className: String?): Boolean =
      hintText != null || password || className?.let(TEXT_INPUT_CLASS::containsMatchIn) == true
  }
}
