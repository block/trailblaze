package xyz.block.trailblaze.agent.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifyAssertionLedgerTest {

  @Test
  fun `parser extracts single-quoted target per bullet line`() {
    val verifyText = """
      - "Gift Cards" is visible
      - "Loyalty" is visible
      - "Estimates" is visible
      - "Increase your sales" is visible
    """.trimIndent()

    val targets = VerifyTextParser.parse(verifyText)

    assertEquals(4, targets.size)
    assertTrue(targets.any { it.normalized == "gift cards" })
    assertTrue(targets.any { it.normalized == "loyalty" })
    assertTrue(targets.any { it.normalized == "estimates" })
    assertTrue(targets.any { it.normalized == "increase your sales" })
  }

  @Test
  fun `parser exempts single-bullet verify text from auto-termination`() {
    val verifyText = "\"Gift Cards\" is visible"

    val targets = VerifyTextParser.parse(verifyText)

    assertTrue(
      targets.isEmpty(),
      "single-bullet verify text should return empty required set — no auto-termination",
    )
  }

  @Test
  fun `parser exempts single bullet wrapped onto continuation line from auto-termination`() {
    // Common YAML block-text shape — one logical bullet, two physical lines. Must NOT enable
    // auto-termination just because lines.size >= 2.
    val verifyText = """
      - "Gift Cards" appears under
        "Increase your sales"
    """.trimIndent()

    val targets = VerifyTextParser.parse(verifyText)

    assertTrue(
      targets.isEmpty(),
      "single bullet with a wrapped continuation line should NOT enable auto-termination",
    )
  }

  @Test
  fun `parser exempts unbulleted multi-line verify text from auto-termination`() {
    // Prose verify text without bullet markers — behavioral fallback handles this path; parser
    // path must not contribute targets, otherwise a single matching assertion could falsely
    // satisfy a multi-sentence verify.
    val verifyText = """
      "Gift Cards" is visible.
      "Loyalty" is visible.
    """.trimIndent()

    val targets = VerifyTextParser.parse(verifyText)

    assertTrue(
      targets.isEmpty(),
      "unbulleted verify text must NOT enable parser-path auto-termination",
    )
  }

  @Test
  fun `parser ignores lines with multiple quoted phrases`() {
    val verifyText = """
      - "Gift Cards" appears under "Increase your sales"
      - "Loyalty" is visible
    """.trimIndent()

    val targets = VerifyTextParser.parse(verifyText)

    assertEquals(
      setOf("loyalty"),
      targets.map { it.normalized }.toSet(),
      "ambiguous multi-quoted lines should NOT contribute targets",
    )
  }

  @Test
  fun `parser handles bullet markers, dashes, and bullets`() {
    val verifyText = """
      - "Cards"
      * "Loyalty"
      • "Estimates"
        "Sales"
    """.trimIndent()

    val targets = VerifyTextParser.parse(verifyText)

    assertEquals(
      setOf("cards", "loyalty", "estimates", "sales"),
      targets.map { it.normalized }.toSet(),
    )
  }

  @Test
  fun `normalizer collapses whitespace and lowercases`() {
    val a = NormalizedAssertionTarget("Gift  Cards")
    val b = NormalizedAssertionTarget("gift cards")
    assertEquals(a.normalized, b.normalized)
    assertTrue(a.matches(b))
  }

  @Test
  fun `ledger marks targets satisfied via assertVisibleBySelector textRegex`() {
    val verifyText = """
      - "Gift Cards" is visible
      - "Loyalty" is visible
    """.trimIndent()
    val ledger = VerifyAssertionLedger(verifyText)

    assertFalse(ledger.allSatisfied(), "fresh ledger with required targets must be unsatisfied")

    val argsGiftCards = buildJsonObject {
      put("selector", buildJsonObject { put("textRegex", "Gift Cards") })
    }
    ledger.recordSuccessfulAssertion("assertVisibleBySelector", argsGiftCards, isSuccess = true)
    assertFalse(ledger.allSatisfied(), "1 of 2 targets satisfied is not all")

    val argsLoyalty = buildJsonObject {
      put("selector", buildJsonObject { put("textRegex", "Loyalty") })
    }
    ledger.recordSuccessfulAssertion("assertVisibleBySelector", argsLoyalty, isSuccess = true)
    assertTrue(ledger.allSatisfied(), "both targets satisfied — auto-termination should fire")
  }

  @Test
  fun `ledger ignores failed assertions even when target matches`() {
    val ledger = VerifyAssertionLedger("""
      - "Gift Cards" is visible
      - "Loyalty" is visible
    """.trimIndent())

    val args = buildJsonObject {
      put("selector", buildJsonObject { put("textRegex", "Gift Cards") })
    }
    val recorded = ledger.recordSuccessfulAssertion(
      toolName = "assertVisibleBySelector",
      toolArgs = args,
      isSuccess = false,
    )

    assertFalse(recorded, "failed assertions must not credit the ledger")
    assertFalse(ledger.allSatisfied())
  }

  @Test
  fun `ledger ignores tools that are not verification assertions`() {
    val ledger = VerifyAssertionLedger("""
      - "Sign In" is visible
      - "Login" is visible
    """.trimIndent())

    val args = buildJsonObject {
      put("text", "Sign In")
    }
    // tapByText is not a verification tool — should be ignored
    val recorded = ledger.recordSuccessfulAssertion(
      toolName = "tapByText",
      toolArgs = args,
      isSuccess = true,
    )

    assertFalse(recorded)
    assertFalse(ledger.allSatisfied())
  }

  @Test
  fun `ledger tolerates whitespace and case differences in asserted text`() {
    val ledger = VerifyAssertionLedger("""
      - "Gift Cards" is visible
      - "Loyalty" is visible
    """.trimIndent())

    val args = buildJsonObject {
      put("selector", buildJsonObject { put("textRegex", "  gift  cards  ") })
    }
    val recorded = ledger.recordSuccessfulAssertion(
      toolName = "assertVisibleBySelector",
      toolArgs = args,
      isSuccess = true,
    )

    assertTrue(recorded, "normalization should match 'gift cards' against 'Gift Cards'")
  }

  @Test
  fun `extractor returns null for unknown tool name`() {
    val args = buildJsonObject { put("selector", buildJsonObject { put("textRegex", "X") }) }
    assertNull(AssertionTargetExtractor.extract("someUnknownTool", args))
  }

  @Test
  fun `extractor reads assertVisibleWithText text field`() {
    val args = buildJsonObject { put("text", "Gift Cards") }
    val target = AssertionTargetExtractor.extract("assertVisibleWithText", args)
    assertEquals("gift cards", target?.normalized)
  }

  @Test
  fun `build 4151 reproduction — rotating 4-selector loop auto-terminates after 4 calls`() {
    // The exact scenario from the regression that motivated this fix: agent rotates through 4 selectors.
    // Without the ledger this loop ran 40+ times until MAX_CALLS_REACHED. With the ledger,
    // the 4th successful assertion (in any order) must satisfy the step.
    val verifyText = """
      - "Gift Cards" is visible
      - "Loyalty" is visible
      - "Estimates" is visible
      - "Increase your sales" is visible
    """.trimIndent()
    val ledger = VerifyAssertionLedger(verifyText)

    val rotatingSelectors = listOf("Gift Cards", "Loyalty", "Estimates", "Increase your sales")

    var callCount = 0
    for (selector in rotatingSelectors) {
      callCount++
      ledger.recordSuccessfulAssertion(
        toolName = "assertVisibleBySelector",
        toolArgs = buildJsonObject {
          put("selector", buildJsonObject { put("textRegex", selector) })
        },
        isSuccess = true,
      )
      if (ledger.allSatisfied()) break
    }

    assertEquals(4, callCount, "ledger must satisfy on exactly the 4th unique assertion")
    assertTrue(ledger.allSatisfied())
    assertTrue(ledger.shouldAutoTerminate())
  }

  @Test
  fun `behavioral fallback — rotating loop on UNQUOTED comma-separated verify text auto-terminates on first repeat`() {
    // The verify text the actual trail had: "Verify that Gift Cards, Loyalty, and Estimates
    // are in the Increase your sales section". No quoted bullets — parser extracts ZERO
    // required targets. Behavioral fallback must catch the rotating loop anyway.
    val verifyText = "Verify that Gift Cards, Loyalty, and Estimates are in the Increase your sales section"
    val ledger = VerifyAssertionLedger(verifyText)
    assertTrue(ledger.requiredTargets.isEmpty(), "parser should NOT extract from unquoted text")

    val selectors = listOf("Gift Cards", "Loyalty", "Estimates", "Increase your sales", "Gift Cards")
    var callCount = 0
    for (selector in selectors) {
      callCount++
      ledger.recordSuccessfulAssertion(
        toolName = "assertVisibleBySelector",
        toolArgs = buildJsonObject {
          put("selector", buildJsonObject { put("textRegex", selector) })
        },
        isSuccess = true,
      )
      if (ledger.shouldAutoTerminate()) break
    }

    assertEquals(
      5, callCount,
      "behavioral fallback should fire on the 5th call when the LLM repeats 'Gift Cards' " +
        "after asserting on 4 unique targets",
    )
    assertFalse(ledger.allSatisfied(), "parser path is empty — only behavioral path can fire")
    assertTrue(ledger.isLikelyRotatingLoop())
    assertTrue(ledger.shouldAutoTerminate())
  }

  @Test
  fun `behavioral fallback does not fire on a single re-assertion (only 1 unique target seen)`() {
    val ledger = VerifyAssertionLedger("Verify the user can see Sign In")

    val args = buildJsonObject {
      put("selector", buildJsonObject { put("textRegex", "Sign In") })
    }
    // Two successful assertions on the same target — should NOT auto-terminate. With only 1
    // unique target, a re-assertion is plausibly intentional ("verify before tap, verify after").
    ledger.recordSuccessfulAssertion("assertVisibleBySelector", args, isSuccess = true)
    ledger.recordSuccessfulAssertion("assertVisibleBySelector", args, isSuccess = true)

    assertEquals(1, ledger.seenSnapshot.size)
    assertEquals(1, ledger.repeatCount)
    assertFalse(ledger.isLikelyRotatingLoop(), "needs ≥2 unique targets to fire behaviorally")
    assertFalse(ledger.shouldAutoTerminate())
  }

  @Test
  fun `behavioral fallback fires once threshold is met then stays true on subsequent calls`() {
    val ledger = VerifyAssertionLedger("Verify multiple things visible")

    fun assertOn(text: String) = ledger.recordSuccessfulAssertion(
      "assertVisibleBySelector",
      buildJsonObject { put("selector", buildJsonObject { put("textRegex", text) }) },
      isSuccess = true,
    )

    assertOn("A")
    assertOn("B")
    assertFalse(ledger.shouldAutoTerminate(), "two unique, no repeat yet")
    assertOn("A")
    assertTrue(ledger.shouldAutoTerminate(), "repeat after ≥2 unique → terminate")
    assertOn("C")
    assertTrue(ledger.shouldAutoTerminate(), "monotonic — once true, stays true")
  }
}
