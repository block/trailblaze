package xyz.block.trailblaze.agent.blaze

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [detectActionCycleHint].
 *
 * Verifies the cycle detector handles single-action repeats (length 1), alternating
 * loops (length 2), and three-step cycles (length 3). Lengths beyond 3 are
 * intentionally not flagged.
 */
class ProgressTrackingTest {

  // --- Length 1: consecutive identical actions ---

  @Test
  fun `no hint for empty history`() {
    assertNull(detectActionCycleHint(emptyList()))
  }

  @Test
  fun `no hint for single action`() {
    assertNull(detectActionCycleHint(listOf("tap(a)")))
  }

  @Test
  fun `length-1 warning at two repeats`() {
    val hint = detectActionCycleHint(listOf("tap(a)", "tap(a)"))
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("'tap'"))
    assertTrue(hint.contains("2 times"))
  }

  @Test
  fun `length-1 stays WARNING below the critical threshold`() {
    // Three identical actions is *frequently* legitimate — the trail step might
    // literally say "add the same item three times" or "tap N to enter PIN digit by
    // digit." The detector should hint to the LLM but not hard-fail the run yet.
    val hint = detectActionCycleHint(listOf("tap(a)", "tap(a)", "tap(a)"))
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("3 times"))
  }

  @Test
  fun `length-1 still WARNING at five repeats`() {
    // Pre-2026-05 this was CRITICAL; the bar is intentionally higher now so legitimate
    // multi-step repetition (PIN entry, "add the same item N times" prompts, scrolling
    // a long list with a fixed direction) doesn't trip stuck-detection.
    val hint = detectActionCycleHint(List(5) { "tap(a)" })
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("5 times"))
  }

  @Test
  fun `length-1 escalates to CRITICAL at the configured threshold`() {
    val hint = detectActionCycleHint(List(LENGTH_1_CRITICAL_REPEATS) { "tap(a)" })
    assertNotNull(hint)
    assertTrue(hint.startsWith("CRITICAL:"))
    assertTrue(hint.contains("$LENGTH_1_CRITICAL_REPEATS times"))
  }

  @Test
  fun `length-1 does not fire when last action differs`() {
    // The streak ended on the last action, so no consecutive repeat to warn about.
    assertNull(detectActionCycleHint(listOf("tap(a)", "tap(a)", "tap(a)", "scroll(down)")))
  }

  // --- Length 2: alternating cycles ---

  @Test
  fun `length-2 warning at two full cycles`() {
    // A, B, A, B → 2 full A-B cycles
    val hint = detectActionCycleHint(listOf("tap(items)", "tap(back)", "tap(items)", "tap(back)"))
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("cycle of 2 actions"))
    assertTrue(hint.contains("2 times"))
  }

  @Test
  fun `length-2 stays WARNING below the critical threshold`() {
    val hint = detectActionCycleHint(
      listOf(
        "tap(items)", "tap(back)",
        "tap(items)", "tap(back)",
        "tap(items)", "tap(back)",
      ),
    )
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("cycle of 2 actions"))
    assertTrue(hint.contains("3 times"))
  }

  @Test
  fun `length-2 escalates to CRITICAL at the configured threshold`() {
    val pingPong = List(LENGTH_2_CRITICAL_REPEATS) { listOf("tap(items)", "tap(back)") }.flatten()
    val hint = detectActionCycleHint(pingPong)
    assertNotNull(hint)
    assertTrue(hint.startsWith("CRITICAL:"))
    assertTrue(hint.contains("$LENGTH_2_CRITICAL_REPEATS times"))
  }

  @Test
  fun `length-2 reproduces the case_4839652 25-cycle loop`() {
    // The bug that motivated this work: AI alternated tap(Items) ↔ tap(Back) 25 times
    // before exhausting the LLM call budget. Detector must fire long before that.
    val pingPong = List(25) { listOf("tap(items)", "tap(back)") }.flatten()
    val hint = detectActionCycleHint(pingPong)
    assertNotNull(hint)
    assertTrue(hint.startsWith("CRITICAL:"))
    assertTrue(hint.contains("25 times"))
  }

  @Test
  fun `length-2 does not fire on a single A-B pair`() {
    // Two distinct actions are not a cycle — could be legitimate progress.
    assertNull(detectActionCycleHint(listOf("tap(a)", "scroll(down)")))
  }

  @Test
  fun `length-2 does not fire on truncated A-B-A`() {
    // 1.5 cycles isn't enough — wait until at least 2 full cycles before alarming.
    assertNull(detectActionCycleHint(listOf("tap(a)", "tap(b)", "tap(a)")))
  }

  @Test
  fun `length-2 fires regardless of leading actions`() {
    // Earlier unrelated actions don't prevent detection in the suffix.
    val hint = detectActionCycleHint(
      listOf("scroll(down)", "tap(items)", "tap(back)", "tap(items)", "tap(back)"),
    )
    assertNotNull(hint)
    assertTrue(hint.contains("cycle of 2 actions"))
  }

  // --- Length 3: three-step cycles ---

  @Test
  fun `length-3 warning at two full cycles`() {
    val hint = detectActionCycleHint(
      listOf("tap(a)", "tap(b)", "tap(c)", "tap(a)", "tap(b)", "tap(c)"),
    )
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("cycle of 3 actions"))
  }

  @Test
  fun `length-3 stays WARNING below the critical threshold`() {
    val hint = detectActionCycleHint(
      listOf(
        "tap(a)", "tap(b)", "tap(c)",
        "tap(a)", "tap(b)", "tap(c)",
        "tap(a)", "tap(b)", "tap(c)",
      ),
    )
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
  }

  @Test
  fun `length-3 escalates to CRITICAL at the configured threshold`() {
    val triple = List(LENGTH_3_CRITICAL_REPEATS) { listOf("tap(a)", "tap(b)", "tap(c)") }.flatten()
    val hint = detectActionCycleHint(triple)
    assertNotNull(hint)
    assertTrue(hint.startsWith("CRITICAL:"))
    assertTrue(hint.contains("$LENGTH_3_CRITICAL_REPEATS times"))
  }

  // --- Negative cases ---

  @Test
  fun `no hint for unrelated actions in tail`() {
    assertNull(
      detectActionCycleHint(
        listOf("tap(a)", "tap(b)", "scroll(down)", "tap(c)", "type(hello)"),
      ),
    )
  }

  @Test
  fun `length-4 cycles are NOT flagged by design`() {
    // Cycles longer than 3 are out of scope: in practice they more often represent
    // legitimate exploration than a stuck loop.
    val signatures = listOf(
      "tap(a)", "tap(b)", "tap(c)", "tap(d)",
      "tap(a)", "tap(b)", "tap(c)", "tap(d)",
    )
    assertNull(detectActionCycleHint(signatures))
  }

  // --- Shortest match wins ---

  @Test
  fun `pure A-A-A-A reports as length-1, not length-2`() {
    // Same action 4× could match length 1 (4 repeats) or length 2 (AA = AA, 2 cycles).
    // Length 1 is the more specific and useful diagnosis, so it should win.
    val hint = detectActionCycleHint(List(4) { "tap(a)" })
    assertNotNull(hint)
    assertTrue(hint.contains("with the same arguments"))
    assertTrue(!hint.contains("cycle of 2 actions"))
  }

  // --- Dominant-action detector ---

  @Test
  fun `dominant-action detector fires WARNING when 70 percent of last 15 actions are the same`() {
    // 13 of 15 are swipe(UP) (~87%), with the tail being a non-dominant action so the
    // strict-tail-run guard doesn't suppress.
    val signatures = listOf(
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "scrollUntilTextIsVisible(Pizza)",
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "swipe(UP)",
      "scrollUntilTextIsVisible(Pizza)",
    )
    val hint = detectDominantActionHint(signatures)
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("'swipe'"))
  }

  @Test
  fun `dominant-action detector returns null below threshold`() {
    val signatures = (0 until 15).map { if (it % 2 == 0) "tap(a)" else "tap(b)" } // 50/50
    assertNull(detectDominantActionHint(signatures))
  }

  @Test
  fun `dominant-action detector returns null when window not yet full`() {
    val signatures = List(10) { "swipe(UP)" } // only 10, need 15
    assertNull(detectDominantActionHint(signatures))
  }

  @Test
  fun `dominant-action detector defers to strict detector for tail-run length-1 cycles`() {
    // 11 of 15 are swipe(UP), AND the last 11 are strictly consecutive — the strict
    // detector handles this. Dominant-action returns null to avoid double-warning.
    val signatures = listOf("tap(a)", "tap(b)", "tap(c)", "tap(d)") + List(11) { "swipe(UP)" }
    assertNull(detectDominantActionHint(signatures))
  }

  @Test
  fun `verifySelfHealFailsGracefully build 4805 trace fires WARNING via dominant detector`() {
    // Reproduction of the actual stuck pattern: swipe(UP) interleaved with scrollFail.
    // The 15-action window must end on a non-dominant action so the strict-tail-run
    // guard doesn't suppress. 13/15 swipe = 87% > 70% threshold.
    val window15 = listOf(
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "scrollUntilTextIsVisible(Pizza, UP)",
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "swipe(UP)", "swipe(UP)", "swipe(UP)", "swipe(UP)",
      "swipe(UP)",
      "scrollUntilTextIsVisible(Pizza, UP)", // tail breaks consecutive
    )
    val hint = detectDominantActionHint(window15)
    assertNotNull(hint, "Build 4805's stuck pattern should fire WARNING via dominant detector")
    assertTrue(hint.startsWith("WARNING:"))
  }

  @Test
  fun `dominant-action detector extracts bare tool name from runner colon-separated fingerprints`() {
    // TrailblazeRunner builds fingerprints as "tool:args" (not "tool(args)"). The hint
    // must surface just the tool name, otherwise the LLM gets the full JSON-serialized
    // fingerprint dumped into its prompt — defeats the point of an actionable warning.
    val args = """{"reasoning":"scrolling for Pizza","direction":"UP"}"""
    val signatures = List(13) { "swipe:$args" } + listOf(
      "scrollUntilTextIsVisible:$args",
      "tap:{}",
    )
    val hint = detectDominantActionHint(signatures)
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("'swipe'"), "Expected bare tool name 'swipe' but got: $hint")
    // The JSON args must NOT leak into the warning.
    assertTrue(!hint.contains("reasoning"), "JSON args leaked into warning: $hint")
  }

  @Test
  fun `strict cycle detector extracts bare tool name from runner colon-separated fingerprints`() {
    // Same colon-format issue as the dominant-action detector test above. The strict
    // length-1 detector calls formatCycleHint, which previously did substringBefore('(')
    // and dumped the full "swipe:{...}" fingerprint into the WARNING text. Build 4884's
    // verifySelfHealFailsGracefully run showed exactly this: the LLM saw
    // 'swipe:{"direction":"UP"}' inside the WARNING instead of just 'swipe' — counts as
    // noise the LLM had to parse around. Fix routes through extractToolNameFromFingerprint
    // (shared with detectDominantActionHint).
    val args = """{"reasoning":"scrolling for Pizza","direction":"UP"}"""
    val signatures = List(3) { "swipe:$args" }
    val hint = detectActionCycleHint(signatures)
    assertNotNull(hint)
    assertTrue(hint.startsWith("WARNING:"))
    assertTrue(hint.contains("'swipe'"), "Expected bare tool name 'swipe' but got: $hint")
    assertTrue(!hint.contains("reasoning"), "JSON args leaked into warning: $hint")
  }

  @Test
  fun `length-2 cycle detector extracts bare tool names from runner colon-separated fingerprints`() {
    // Same as above but for the length-2 path (the else branch in formatCycleHint).
    val args = """{"reasoning":"x"}"""
    val signatures = listOf(
      "tap:$args", "scrollUntilTextIsVisible:$args",
      "tap:$args", "scrollUntilTextIsVisible:$args",
    )
    val hint = detectActionCycleHint(signatures)
    assertNotNull(hint)
    assertTrue(hint.contains("'tap'"), "Expected bare 'tap' in: $hint")
    assertTrue(hint.contains("'scrollUntilTextIsVisible'"), "Expected bare 'scrollUntilTextIsVisible' in: $hint")
    assertTrue(!hint.contains("reasoning"), "JSON args leaked into warning: $hint")
  }
}
