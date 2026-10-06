package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass(
  name = "assertNotVisibleWithText",
  isVerification = true,
)
@LLMDescription(
  """
Assert no element with this text is on screen. Waits for a matching element to disappear (e.g. a
loading spinner) before failing. Text must match an element's whole text, case-insensitively, so
use 'Loading.*' for "Loading...". Only for elements that display text.
""",
)
data class AssertNotVisibleWithTextTrailblazeTool(
  @LLMDescription(REQUIRED_TEXT_DESCRIPTION)
  val text: String,
  @LLMDescription("0-based index among the elements matching the other fields.")
  val index: Int = 0,
  @LLMDescription("Resource id regex, to disambiguate elements with the same text.")
  val id: String? = null,
  val enabled: Boolean? = null,
  val selected: Boolean? = null,
) : MapsToMaestroCommands() {

  companion object {
    /**
     * A negative assertion can't be validated at recording time — the element isn't on screen,
     * so the LLM guesses its casing, and a wrong guess passes vacuously (the assertion succeeds
     * whether the pattern is right or wrong). Neutralize that: match [text] case-insensitively,
     * honoring BOTH its regex reading and its escaped-literal reading, so screen text with regex
     * metacharacters ("$5.00") and deliberate regexes (".*debit 1582.*") both keep working.
     * A leading `(?-i)` in [text] restores case-sensitivity.
     *
     * Whitespace at either end of the element's text is ignored too. The snapshot trims what it
     * prints, so an agent quoting `Name: Jane Doe\nEmail:` off it cannot know the element reads
     * `Name: Jane Doe\nEmail: `, and a whole-text match that counted that space would pass while
     * the text is on screen.
     *
     * Public because it IS this tool's matching semantics: a driver that dispatches the
     * not-visible check on its own backend (the in-process ANDROID_TEST adapter) must build its
     * selector from this same pattern, or the two drivers disagree about what "not visible" means.
     */
    fun toLenientPattern(text: String): String {
      val compilesAsRegex = try {
        Regex(text)
        true
      } catch (_: IllegalArgumentException) {
        false
      }
      val body = if (compilesAsRegex) "(?:${withoutAnchors(text)}|${Regex.escape(text)})" else Regex.escape(text)
      return "(?i)$EDGE_WHITESPACE$body$EDGE_WHITESPACE"
    }

    /**
     * [regex] without a leading `^` (after any leading inline flags) or an unescaped trailing `$`.
     * The match is whole-text already, so they add nothing, and left in they would sit inside the
     * edge whitespace and stop `^Ho$` matching ` Ho `.
     */
    private fun withoutAnchors(regex: String): String {
      val flags = LEADING_INLINE_FLAGS.find(regex)?.value.orEmpty()
      var body = regex.removePrefix(flags).removePrefix("^")
      if (body.endsWith("$") && body.dropLast(1).takeLastWhile { it == '\\' }.length % 2 == 0) {
        body = body.dropLast(1)
      }
      return if (body.isEmpty()) regex else flags + body
    }

    private val LEADING_INLINE_FLAGS = Regex("^\\(\\?[a-zA-Z-]+\\)")

    /** Spans `Zs` as well as `\s`, like the edge whitespace [AssertVisibleBySelectorTrailblazeTool] allows. */
    private const val EDGE_WHITESPACE = "[\\s\\p{Zs}]*"
  }

  override fun toMaestroCommands(): List<Command> = listOf(
    AssertConditionCommand(
      condition = Condition(
        notVisible = ElementSelector(
          // {{var}}/${var} tokens are resolved by the dispatch boundary
          // (interpolateMemoryInTool) before execution, so `text` arrives resolved here.
          textRegex = toLenientPattern(text),
          idRegex = id,
          index = if (index == 0) null else index.toString(),
          enabled = enabled,
          selected = selected,
        ),
      ),
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val mode = toolExecutionContext.nodeSelectorMode
    if (mode == NodeSelectorMode.FORCE_LEGACY) {
      return super.execute(toolExecutionContext)
    }

    val agent = toolExecutionContext.maestroTrailblazeAgent
    if (agent != null) {
      val interpolatedText = toLenientPattern(text)
      val convertedIndex = if (index == 0) null else index
      val platform = toolExecutionContext.trailblazeDeviceInfo.platform
      val driverMatch: DriverNodeMatch = when (platform) {
        TrailblazeDevicePlatform.IOS -> DriverNodeMatch.IosMaestro(
          textRegex = interpolatedText,
          resourceIdRegex = id,
          selected = selected,
        )
        TrailblazeDevicePlatform.ANDROID -> DriverNodeMatch.AndroidAccessibility(
          textRegex = interpolatedText,
          resourceIdRegex = id,
          isEnabled = enabled,
          isSelected = selected,
        )
        TrailblazeDevicePlatform.WEB -> DriverNodeMatch.AndroidAccessibility(
          textRegex = interpolatedText,
          resourceIdRegex = id,
          isEnabled = enabled,
          isSelected = selected,
        )
        TrailblazeDevicePlatform.DESKTOP -> DriverNodeMatch.Compose(
          textRegex = interpolatedText,
          // [DriverNodeMatch.Compose] uses semantic-tag identifiers (`testTag` is an
          // exact-string match, not a regex). The legacy [TrailblazeElementSelector.id]
          // field is regex-shaped, so we drop it on the DESKTOP path rather than
          // pretending it maps cleanly. Users authoring Compose-driver assertions
          // should use textRegex / contentDescriptionRegex instead.
          isEnabled = enabled,
          isSelected = selected,
        )
      }
      val nodeSelector = TrailblazeNodeSelector.withMatch(
        driverMatch,
        index = convertedIndex,
      )
      val result = agent.executeNodeSelectorAssertNotVisible(
        nodeSelector = nodeSelector,
        traceId = toolExecutionContext.traceId,
      )
      if (result != null) {
        if (result.isSuccess()) {
          return TrailblazeToolResult.Success(message = "Verified '$text' not visible")
        }
        return result
      }
    }
    // Fall back to Maestro command path
    val fallbackResult = super.execute(toolExecutionContext)
    if (fallbackResult.isSuccess()) {
      return TrailblazeToolResult.Success(message = "Verified '$text' not visible")
    }
    return fallbackResult
  }
}
