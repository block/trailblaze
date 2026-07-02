package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the two pure helpers in `CliOutputFormatters.kt` introduced
 * alongside the OOBE-sweep PR: [extractStatusHeader] (lifts the daemon's
 * `**verb** — message` block out of markdown) and [screenSummaryDuplicatesAnswer]
 * (decides whether `ask`'s `**Screen:**` block restates `**Answer:**` closely
 * enough to suppress).
 *
 * The other formatter functions (`formatBlazeResultAgent`, `formatAskResultAgent`,
 * `formatVerifyResultAgent`) write to `Console` and are awkward to assert against
 * in a unit test — they're exercised via the higher-level CLI command tests.
 * These tests cover the pure-function helpers that drive their decisions.
 */
class CliOutputFormattersHelpersTest {

  // ─────────────────────────────────────────────────────────────────────────
  // extractStatusHeader
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `extracts status verb and message from a typical step result prefix`() {
    val prefix = "**✓ Executed** — Tapped Checkout"
    assertEquals("→ Executed — Tapped Checkout", extractStatusHeader(prefix))
  }

  @Test
  fun `extracts arrow-prefixed analyzed verb`() {
    val prefix = "**→ Analyzed** — Found nothing actionable"
    assertEquals("→ Analyzed — Found nothing actionable", extractStatusHeader(prefix))
  }

  @Test
  fun `extracts done verb without a trailing message`() {
    // `snapshot` emits **✅ Done** with no message tail.
    val prefix = "**✅ Done**"
    assertEquals("→ Done", extractStatusHeader(prefix))
  }

  @Test
  fun `returns null for text that does not lead with a markdown bold block`() {
    assertNull(extractStatusHeader("just a plain line"))
  }

  @Test
  fun `returns null for empty and whitespace-only input`() {
    assertNull(extractStatusHeader(""))
    assertNull(extractStatusHeader("    "))
    assertNull(extractStatusHeader("\n\n"))
  }

  @Test
  fun `returns null for an unclosed bold block`() {
    // `**Bold but no closing` would otherwise dangle into the screen section.
    assertNull(extractStatusHeader("**Bold but no closing"))
  }

  @Test
  fun `strips the U+FE0F variation selector that follows compound emoji`() {
    // ⚠️ is two codepoints: U+26A0 WARNING SIGN + U+FE0F VARIATION SELECTOR-16.
    // An earlier regex class consumed only the warning sign, leaving the
    // variation selector to render as a phantom leading glyph in the verb.
    val prefix = "**⚠️ Needs input** — provide a step value"
    assertEquals("→ Needs input — provide a step value", extractStatusHeader(prefix))
  }

  @Test
  fun `matchStatusHeader reports the end index for callers that want to resume`() {
    // Verifies the contract `formatBlazeResultAgent` relies on for printing
    // the `**Suggestion:** …` / `**Hint:** …` middle slice that lives between
    // the status header and the `**Screen:**` marker.
    val text = "**✓ Executed** — Tapped Checkout\n\n**Suggestion:** retry"
    val match = matchStatusHeader(text)
    assertEquals("→ Executed — Tapped Checkout", match?.formatted)
    // End index should point just past the closing `**` of the header — the
    // body that follows starts with the `\n\n` separator.
    val tail = match?.let { text.substring(it.endIdx) }
    assertEquals("\n\n**Suggestion:** retry", tail)
  }

  @Test
  fun `matchStatusHeader exposes the cleaned verb and inline message`() {
    val match = matchStatusHeader("**✅ Done** — Executed tapOnPoint")
    assertEquals("Done", match?.verb)
    assertEquals("Executed tapOnPoint", match?.message)
  }

  @Test
  fun `matchStatusHeader reports a null message for a verb-only header`() {
    val match = matchStatusHeader("**✅ Done**")
    assertEquals("Done", match?.verb)
    assertNull(match?.message)
  }

  // ─────────────────────────────────────────────────────────────────────────
  // layoutStatusBody — inline vs. detached rendering of the result body
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `single-line message stays inline on the breadcrumb`() {
    val match = StatusHeaderMatch("→ Executed — Tapped Checkout", 0, "Executed", "Tapped Checkout")
    // No continuation before the screen marker.
    val layout = layoutStatusBody(match, bodyAfterHeader = "\n\n")
    assertEquals("→ Executed — Tapped Checkout", layout.breadcrumb)
    assertNull(layout.detachedBody)
  }

  @Test
  fun `multi-line message detaches under a bare verb breadcrumb, preserving its shape`() {
    // The regression this fixes: a JSON payload from `trailblaze tool <read-tool>` used to
    // jam its first line (`{`) into the breadcrumb (`→ Done — {`). It now detaches whole.
    val json = "{\n    \"appIds\": [\n        \"android\",\n        \"com.example.app\"\n    ]\n}"
    val text = "**✅ Done** — $json\n\n**Screen:** Home | [button] Settings"
    val screenIdx = text.indexOf("**Screen:** ")
    val match = matchStatusHeader(text.substring(0, screenIdx))!!
    val bodyAfterHeader = text.substring(match.endIdx, screenIdx)

    val layout = layoutStatusBody(match, bodyAfterHeader)

    assertEquals("→ Done", layout.breadcrumb)
    assertEquals(json, layout.detachedBody)
  }

  @Test
  fun `suggestion or hint body stays inline for the caller's middle slice`() {
    // A `**Suggestion:**` marker after the message is NOT part of the message, so the
    // breadcrumb keeps its inline single-line message and the marker falls to the middle slice.
    val match = StatusHeaderMatch("→ Needs input — provide a step", 0, "Needs input", "provide a step")
    val layout = layoutStatusBody(match, bodyAfterHeader = "\n\n**Suggestion:** retry")
    assertEquals("→ Needs input — provide a step", layout.breadcrumb)
    assertNull(layout.detachedBody)
  }

  @Test
  fun `verb-only header has no detached body`() {
    val match = StatusHeaderMatch("→ Done", 0, "Done", null)
    val layout = layoutStatusBody(match, bodyAfterHeader = "")
    assertEquals("→ Done", layout.breadcrumb)
    assertNull(layout.detachedBody)
  }

  // ─────────────────────────────────────────────────────────────────────────
  // isMisuseResult — MISUSE markers only count inside an error result
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `misuse fires on a daemon error whose message carries a marker`() {
    assertTrue(isMisuseResult("**❌ Error** — Unknown tool: tap_on_text. Use toolbox() to see available tools."))
    assertTrue(isMisuseResult("**❌ Error** — Tool not valid for the current device/target: openUrl."))
    assertTrue(isMisuseResult("""{"error":"Unknown tool: foo"}"""))
  }

  @Test
  fun `misuse does NOT fire when a SUCCESS payload merely contains a marker phrase`() {
    // Regression guard for the read-tool payload path: a successful tool's output that happens
    // to mention a marker phrase must not be misreported as EXIT=3.
    assertFalse(isMisuseResult("**✅ Done** — {\n    \"note\": \"Unknown tool handling is documented\"\n}"))
    assertFalse(isMisuseResult("**✓ Executed** — output mentioning not valid for the current device/target"))
  }

  @Test
  fun `misuse does NOT fire on a generic error without a marker`() {
    assertFalse(isMisuseResult("**❌ Error** — Action failed: device disconnected"))
  }

  // ─────────────────────────────────────────────────────────────────────────
  // screenSummaryDuplicatesAnswer
  // ─────────────────────────────────────────────────────────────────────────

  @Test
  fun `dedup fires on exact match`() {
    val s = "Square app splash screen with Sign in and Create account."
    assertTrue(screenSummaryDuplicatesAnswer(s, s))
  }

  @Test
  fun `dedup tolerates trailing whitespace and stray newlines`() {
    val answer = "Login screen."
    val screen = "Login screen.   \n"
    assertTrue(screenSummaryDuplicatesAnswer(answer, screen))
  }

  @Test
  fun `dedup tolerates internal whitespace collapse`() {
    val answer = "Settings screen"
    val screen = "Settings\n\nscreen"
    assertTrue(screenSummaryDuplicatesAnswer(answer, screen))
  }

  @Test
  fun `dedup does NOT fire when screen adds detail the answer is missing`() {
    // Regression guard: an earlier substring-based check would have suppressed
    // the screen here, hiding the parenthetical the user actually wants.
    val answer = "Square splash screen with Sign in and Create account."
    val screen = "Square splash screen with Sign in and Create account ('One point of sale, wherever you grow')."
    assertFalse(screenSummaryDuplicatesAnswer(answer, screen))
  }

  @Test
  fun `dedup does NOT fire when answer is a common short substring of screen`() {
    // Regression guard: "Search" must not suppress "Search results found for…".
    assertFalse(screenSummaryDuplicatesAnswer("Search", "Search results found for shoes"))
  }

  @Test
  fun `dedup does NOT fire when either side is blank or null`() {
    // Empty string contains every string in Kotlin — without the blank guard,
    // a vacuous answer would have suppressed any screen.
    assertFalse(screenSummaryDuplicatesAnswer("", "Real screen content"))
    assertFalse(screenSummaryDuplicatesAnswer("   ", "Real screen content"))
    assertFalse(screenSummaryDuplicatesAnswer("Real answer", ""))
    assertFalse(screenSummaryDuplicatesAnswer(null, "Real screen content"))
    assertFalse(screenSummaryDuplicatesAnswer("Real answer", null))
    assertFalse(screenSummaryDuplicatesAnswer(null, null))
  }
}
