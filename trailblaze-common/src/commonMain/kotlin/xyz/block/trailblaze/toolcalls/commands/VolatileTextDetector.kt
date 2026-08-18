package xyz.block.trailblaze.toolcalls.commands

/**
 * Rewrites a captured assertVisible [expectedText] so volatile run-to-run state (starting with
 * live item counts) is matched by shape instead of pinned as a strict EXACT literal that fails
 * replay. The stable text around the volatile span is kept verbatim (regex-escaped) and the
 * volatile span is made optional — so the assertion still requires the stable text and rejects
 * unrelated text in that position, but tolerates the value changing OR disappearing entirely
 * (e.g. a captured "Review sale\n3 items" later showing "2 items" or just "Review sale").
 *
 * Only fires when there is stable text to anchor on. A capture that is *only* a volatile token
 * (e.g. "1 item") is left EXACT, so a trail that deliberately pins a count still catches a wrong
 * count. Extensible by adding more [VolatileToken] entries (e.g. currency, timestamps) — kept
 * deliberately minimal here.
 */
object VolatileTextDetector {

  data class ResolvedExpectedText(
    val expectedText: String?,
    val mode: TextMatchMode,
  )

  /**
   * A volatile span to tolerate at replay. [locator] matches the span (anchored to a trailing
   * subtitle position, including the line separator) so the whole thing can be dropped;
   * [optionalGroup] is the regex fragment (already wrapped to be optional) substituted for it.
   */
  private data class VolatileToken(val locator: Regex, val optionalGroup: String)

  /**
   * Volatile tokens to tolerate. Only a trailing item-count *subtitle* for now: a count on its
   * own line at the very end (e.g. "Review sale\n3 items"). Deliberately NOT any inline
   * "<n> items" — that keeps stable copy like "Buy 2 items get 1 free" or a deliberately-pinned
   * count an exact assertion. Add currency / timestamp tokens here when the corpus shows them
   * failing.
   */
  private val volatileTokens: List<VolatileToken> = listOf(
    VolatileToken(
      locator = Regex("\\n\\s*\\d+\\s+items?\\s*$", RegexOption.IGNORE_CASE),
      optionalGroup = "(?:\\n\\s*\\d+\\s+items?\\s*)?",
    ),
  )

  fun resolve(expectedText: String?): ResolvedExpectedText {
    if (expectedText == null) return ResolvedExpectedText(null, TextMatchMode.EXACT)
    for (token in volatileTokens) {
      val match = token.locator.find(expectedText) ?: continue
      val head = expectedText.substring(0, match.range.first)
      // Need stable text to anchor on; a bare count line stays an exact assertion.
      if (head.isBlank()) continue
      val pattern = Regex.escape(head) + token.optionalGroup
      return ResolvedExpectedText(pattern, TextMatchMode.REGEX)
    }
    return ResolvedExpectedText(expectedText, TextMatchMode.EXACT)
  }
}
