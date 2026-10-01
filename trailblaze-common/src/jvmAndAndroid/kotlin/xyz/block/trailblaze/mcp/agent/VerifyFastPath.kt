package xyz.block.trailblaze.mcp.agent

import xyz.block.trailblaze.decision.PLATFORM_SCOPE_WORDS
import xyz.block.trailblaze.decision.ScreenRefs

/**
 * Closes a verify step with no LLM call when its claim is nothing more than "these quoted phrases
 * are visible" and every phrase is already on screen.
 *
 * A verify step usually costs several LLM calls, and a common claim shape — `Verify
 * "Items" and "Settings" are visible` — needs no judgement: find each phrase on the screen, assert
 * it, done. The fast path does exactly that, through the real `assertVisible` tool so the check is
 * logged, recorded, and counts as the step's evidence. Anything it is unsure of falls through to the
 * normal agent loop untouched: a claim with a negation or a condition, a phrase that is not on
 * screen yet, a phrase that only appears inside a larger label.
 *
 * Off by default ([VERIFY_FAST_PATH_ENV]).
 */
internal object VerifyFastPath {

  enum class Mode {
    /** The claim may contain only its quoted phrases and filler words ("verify", "the", "button", …). */
    STRICT,

    /**
     * Any claim with a quoted phrase, no negation or condition, no unquoted number, and no position or
     * state qualifier ("under", "enabled", "selected"). Reaches more claims than
     * [STRICT]; the price is that other unquoted wording ("the confirmation message") is not checked.
     */
    LOOSE,
  }

  /** A quoted phrase and the screen element whose label is exactly that phrase. */
  data class PhraseMatch(val phrase: String, val ref: String)

  private val QUOTE = Regex("""(?<![A-Za-z])['"“‘]([^'"”’]{1,80}?)['"”’](?![A-Za-z])""")

  // Anything that makes a claim more than "these are visible": negation, absence, exclusivity,
  // alternatives, conditions, and the platform or device a claim is scoped to, since the fast path
  // cannot tell which device it is on.
  private val NOT_A_PLAIN_VISIBILITY_CLAIM = Regex(
    """\bnot\b|n't|\bno\b|\bnever\b|\bnone\b|\bwithout\b|\bunless\b|\babsent\b|\bdisappear|\bgone\b|""" +
      """\bhidden\b|\bremoved\b|\bonly\b|\bor\b|\bif\b|\bnor\b|\bneither\b|\beither\b|""" +
      """\bwhen\b|\bwhenever\b|\bwhile\b|\buntil\b|\bonce\b|""" +
      """\b($PLATFORM_SCOPE_WORDS)\b""",
    RegexOption.IGNORE_CASE,
  )

  // Position, state and count qualifiers: a phrase being visible says nothing about where it is,
  // whether its control is on or can be used, or how many times it appears, so [Mode.LOOSE] leaves
  // these to the agent too.
  private val UNCHECKED_QUALIFIER = Regex(
    """\b(under|below|above|beneath|beside|next to|inside|within|between|before|after|left|right|top|""" +
      """bottom|first|last|enabled|disabled|selected|unselected|checked|unchecked|highlighted|greyed|""" +
      """grayed|toggled|active|inactive|focused|expanded|collapsed|clickable|tappable|editable|""" +
      """read-only|readonly|interactive|pressable|two|three|four|five|all|each|every|multiple|several|""" +
      """many)\b""",
    RegexOption.IGNORE_CASE,
  )

  // A leading bracketed tag such as `[ID 39cf45]` is a step label, not part of the claim.
  private val LEADING_TAGS = Regex("""^\s*(\[[^\]]*]\s*)+""")

  private val FILLER = setOf(
    "verify", "verifies", "confirm", "check", "ensure", "assert", "expect", "that", "the", "a", "an",
    "text", "texts", "label", "labels", "title", "titles", "heading", "header", "button", "buttons",
    "tab", "tabs", "option", "options", "item", "items", "field", "message", "messages", "link",
    "links", "icon", "is", "are", "be", "both", "and", "also", "as", "well", "with", "on", "in", "at",
    "of", "screen", "page", "view", "displayed", "display", "displays", "shown", "show", "shows",
    "visible", "appear", "appears", "appeared", "present", "can", "you", "see", "there", "it", "its",
    "this", "following", "section", "sheet", "dialog", "modal", "menu", "row", "list",
  )

  private val BOTH = Regex("""\bboth\b""", RegexOption.IGNORE_CASE)
  private val WORD = Regex("[a-z]+")
  private val DIGIT = Regex("[0-9]")

  // A rendered screen line with a ref: `[a12] Button "Save" [disabled]`. Lines without a ref (a
  // child label shown under its parent) are not assertable by ref and are skipped.
  private val QUOTED_LABEL = Regex(""""((?:[^"\\]|\\.)*)"""")
  private val SPACES = Regex("\\s+")

  /**
   * The quoted phrases of [claim] when the claim asks for nothing but their visibility under [mode],
   * else null.
   */
  fun phrasesOf(rawClaim: String, mode: Mode): List<String>? {
    val claim = LEADING_TAGS.replace(rawClaim, "")
    val phrases = QUOTE.findAll(claim).map { it.groupValues[1].fold() }.filter { it.isNotEmpty() }.toList()
    if (phrases.isEmpty()) return null
    val unquoted = QUOTE.replace(claim, " ")
    if (NOT_A_PLAIN_VISIBILITY_CLAIM.containsMatchIn(unquoted)) return null
    // "both" claims a count, which holds only when the claim names exactly two phrases.
    if (BOTH.containsMatchIn(unquoted) && phrases.distinct().size != 2) return null
    val acceptsRest = when (mode) {
      Mode.STRICT -> WORD.findAll(unquoted.lowercase()).all { it.value in FILLER } && !DIGIT.containsMatchIn(unquoted)
      Mode.LOOSE -> !DIGIT.containsMatchIn(unquoted) && '$' !in unquoted && !UNCHECKED_QUALIFIER.containsMatchIn(unquoted)
    }
    return phrases.distinct().takeIf { acceptsRest }
  }

  /**
   * Maps every phrase to the first ref-bearing line of [screenText] with a quoted label EXACTLY
   * equal to it (trimmed, inner whitespace folded). Null if any phrase has no such line: a phrase
   * that is only a substring of a label is left to the agent, which can judge "close enough".
   */
  fun matchOnScreen(phrases: List<String>, screenText: String): List<PhraseMatch>? {
    val labelledRefs = ScreenRefs.parse(screenText).map { line ->
      line.ref to QUOTED_LABEL.findAll(line.label).map { it.groupValues[1].unescape().fold() }.toList()
    }
    return phrases.map { phrase ->
      val ref = labelledRefs.firstOrNull { (_, labels) -> phrase in labels }?.first ?: return null
      PhraseMatch(phrase, ref)
    }
  }

  fun modeFromEnv(value: String? = System.getenv(VERIFY_FAST_PATH_ENV)): Mode? = when (value?.trim()?.lowercase()) {
    "1", "true", "strict" -> Mode.STRICT
    "loose" -> Mode.LOOSE
    else -> null
  }

  private fun String.fold(): String = trim().replace(SPACES, " ")
  private fun String.unescape(): String = replace("\\\"", "\"").replace("\\\\", "\\")
}

/**
 * Opt-in: close a verify step with no LLM call when its claim only asks for quoted phrases to be
 * visible and every phrase is on screen (see [VerifyFastPath]). `1`/`true`/`strict` for the strict
 * claim gate, `loose` for the wider one; anything else (or unset) leaves it off.
 */
const val VERIFY_FAST_PATH_ENV = "TRAILBLAZE_KOOG_VERIFY_FAST_PATH"
