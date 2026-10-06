package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Types text, into a field picked by its snapshot ref or into the focused field: the LLM's only
 * typing tool, as `tap` is its only tapping tool.
 *
 * The LLM picks elements by ref, never by selector. With a ref, this resolves it exactly as
 * [TapTrailblazeTool] does and records `inputText` with that selector, so a recorded trail never
 * holds a ref. When `tap` resolves the ref to something other than a selector tap (a coordinate
 * tap), the recording keeps that tap as its own step followed by a plain `inputText`. With no ref,
 * this records a plain `inputText` into the focused field.
 */
@Serializable
@TrailblazeToolClass(name = "type", isRecordable = false)
@LLMDescription(
  "Type text into a text field. Pass the field's snapshot ref (e.g. y778 from [y778] \"Email\") " +
    "to tap it first; omit ref to type into the focused field. To replace existing text, pass " +
    "clearFirst: true. On a number pad, PIN pad or button grid there is no field: tap each digit " +
    "instead.",
)
data class TypeTrailblazeTool(
  @param:LLMDescription("Text to type.")
  val text: String,
  @param:LLMDescription("Text field ref from the snapshot, e.g. 'y778'. Omit to type into the focused field.")
  val ref: String? = null,
  override val reasoning: String? = null,
  @param:LLMDescription("Close the soft keyboard after typing. Default true.")
  val hideKeyboardAfter: Boolean = true,
  @param:LLMDescription("Empty the field before typing, replacing what it holds. Default false.")
  val clearFirst: Boolean = false,
) : DelegatingTrailblazeTool, ReasoningTrailblazeTool {

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    if (ref == null) {
      return listOf(
        InputTextTrailblazeTool(text = text, reasoning = reasoning, hideKeyboardAfter = hideKeyboardAfter, clearFirst = clearFirst),
      )
    }
    val tapSteps = TapTrailblazeTool(ref = ref, reasoning = reasoning)
      .toExecutableTrailblazeTools(executionContext)
    val selector = (tapSteps.singleOrNull() as? TapOnByElementSelector)?.nodeSelector
    return if (selector != null) {
      listOf(InputTextTrailblazeTool(text = text, reasoning = reasoning, hideKeyboardAfter = hideKeyboardAfter, selector = selector, clearFirst = clearFirst))
    } else {
      tapSteps + InputTextTrailblazeTool(text = text, reasoning = reasoning, hideKeyboardAfter = hideKeyboardAfter, clearFirst = clearFirst)
    }
  }
}
