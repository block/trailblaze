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
}
