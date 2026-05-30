package xyz.block.trailblaze.agent.blaze

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [StaleRefTracker], [parseStaleRefFromError], and
 * [buildStaleRefRecoveryMessage].
 *
 * The recovery loop fires after [STALE_REF_THRESHOLD] consecutive
 * `Element ref 'X' not found on current screen` errors targeting the same ref. A
 * different ref or a non-stale-ref outcome breaks the streak; the tracker fires at
 * most once per ref per run.
 */
class StaleRefRecoveryTest {

  // --- parseStaleRefFromError ---

  @Test
  fun `extracts ref from tap stale-ref error`() {
    val ref = parseStaleRefFromError(
      "tap: Element ref '000' not found on current screen. The screen has changed…",
    )
    assertEquals("000", ref)
  }

  @Test
  fun `extracts ref from assertVisible stale-ref error`() {
    val ref = parseStaleRefFromError(
      "assertVisible: Element ref 'y778' not found on current screen. The screen has changed…",
    )
    assertEquals("y778", ref)
  }

  @Test
  fun `does not match found-but-no-bounds error`() {
    // This is a DIFFERENT failure mode — the ref resolves, the element just has no geometry.
    // Recovery for stale-ref hallucination should not fire here.
    assertNull(
      parseStaleRefFromError(
        "tap: Element ref '000' found but has no bounds.",
      ),
    )
  }

  @Test
  fun `does not match unrelated tool errors`() {
    assertNull(parseStaleRefFromError("tap: No screen state available"))
    assertNull(parseStaleRefFromError("Tool execution failed"))
    assertNull(parseStaleRefFromError(null))
    assertNull(parseStaleRefFromError(""))
  }

  // --- StaleRefTracker ---

  @Test
  fun `does not fire below threshold`() {
    val t = StaleRefTracker()
    repeat(STALE_REF_THRESHOLD - 1) {
      assertFalse(t.recordStaleRef("000"))
    }
    assertEquals(STALE_REF_THRESHOLD - 1, t.currentCount)
    assertEquals("000", t.currentRef)
  }

  @Test
  fun `fires exactly once at threshold for the same ref`() {
    val t = StaleRefTracker()
    var fireCount = 0
    repeat(STALE_REF_THRESHOLD + 5) {
      if (t.recordStaleRef("000")) fireCount++
    }
    // Single fire per ref keeps the recovery message from re-injecting on every
    // iteration once we're past the threshold (the LLM would see the same recovery
    // header three calls in a row, which is noisy and the message gets stale).
    assertEquals(1, fireCount, "Recovery should fire exactly once per ref")
  }

  @Test
  fun `different ref restarts streak`() {
    val t = StaleRefTracker()
    assertFalse(t.recordStaleRef("000"))
    assertFalse(t.recordStaleRef("000"))
    // Switch to a different ref before crossing threshold.
    assertFalse(t.recordStaleRef("y778"))
    // First hit on the new ref — streak is back to 1.
    assertEquals(1, t.currentCount)
    assertEquals("y778", t.currentRef)
  }

  @Test
  fun `resetStreak clears counter and ref`() {
    val t = StaleRefTracker()
    t.recordStaleRef("000")
    t.recordStaleRef("000")
    t.resetStreak()
    assertEquals(0, t.currentCount)
    assertNull(t.currentRef)
    // After reset, the original ref needs the full threshold again.
    repeat(STALE_REF_THRESHOLD - 1) {
      assertFalse(t.recordStaleRef("000"))
    }
    assertTrue(t.recordStaleRef("000"))
  }

  @Test
  fun `fires again for a different ref after previous fire`() {
    val t = StaleRefTracker()
    // First ref crosses the threshold.
    repeat(STALE_REF_THRESHOLD - 1) { t.recordStaleRef("000") }
    assertTrue(t.recordStaleRef("000"))
    // Different ref now hallucinated — this is a new failure mode that deserves its own
    // recovery message naming the new dead ref. Fire again.
    repeat(STALE_REF_THRESHOLD - 1) { t.recordStaleRef("y778") }
    assertTrue(t.recordStaleRef("y778"))
  }

  @Test
  fun `resetStreak clears lastFiredRef so same-ref drift after success re-fires`() {
    // Regression guard: previously [resetStreak] only cleared [lastRef] and
    // [consecutiveCount], leaving [lastFiredRef] set. That meant once recovery fired for
    // a ref like "000", a subsequent success (which resets the streak) followed by the
    // LLM drifting BACK to "000" — common because refs like "000"/"001" get reused across
    // screens — would silently never re-fire for the rest of the step, even though the
    // original recovery message had long rolled out of the chat-history window.
    val t = StaleRefTracker()
    // First loop on "000" crosses the threshold and fires.
    repeat(STALE_REF_THRESHOLD - 1) { t.recordStaleRef("000") }
    assertTrue(t.recordStaleRef("000"))
    // The LLM makes real progress (some other tool result lands non-stale-ref).
    t.resetStreak()
    // The LLM later drifts back to the same dead ref. This is a fresh loop deserving a
    // fresh recovery message — verify recovery fires again.
    repeat(STALE_REF_THRESHOLD - 1) { assertFalse(t.recordStaleRef("000")) }
    assertTrue(t.recordStaleRef("000"))
  }

  @Test
  fun `tracker is order-sensitive — chronological refs fire correctly`() {
    // The state machine fires on consecutive same-ref hits. Within one batched LLM
    // response (e.g. MultipleToolStrategy), the order of recordStaleRef calls must match
    // the order the LLM actually emitted them. The runner's history-walk helper
    // (`summarizeStaleRefsFromLastIteration`) reverses the chat history newest-first to
    // window the last N tool results, then re-reverses to chronological order before
    // feeding the tracker — without that final reverse, the order is inverted and the
    // fire semantics change. This test documents the chronological-order contract.
    val t = StaleRefTracker()
    // Prior iteration leaves count=1 on "000".
    t.recordStaleRef("000")
    assertEquals(1, t.currentCount)

    // This iteration's batch in chronological order: [000, 000, y778]. The second "000"
    // hits threshold and fires; then the ref switch to "y778" resets the streak.
    assertFalse(t.recordStaleRef("000"))
    assertTrue(t.recordStaleRef("000"))
    assertFalse(t.recordStaleRef("y778"))
    assertEquals(1, t.currentCount)
  }

  @Test
  fun `reversed iteration order would miss the fire (regression guard)`() {
    // Demonstrates *why* the runner's `summarizeStaleRefsFromLastIteration` must restore
    // chronological order via `.reversed()` after windowing newest-first. The same batch
    // played back in reverse order produces a different (wrong) tracker outcome.
    val t = StaleRefTracker()
    t.recordStaleRef("000") // prior iteration: count = 1 on "000"

    // REVERSED batch (what the unfixed code path would have produced): [y778, 000, 000]
    assertFalse(t.recordStaleRef("y778")) // ref switch, count = 1
    assertFalse(t.recordStaleRef("000")) // ref switch, count = 1
    assertFalse(t.recordStaleRef("000")) // count = 2
    // Counter ends at 2 — we miss the fire that the chronological order would have caught.
    // This is the buggy behavior; the test asserts what the bug LOOKED like so a regression
    // would be caught by the chronological-order test above flipping to expected.
    assertEquals(2, t.currentCount)
  }

  @Test
  fun `A-B-A re-fire is suppressed when no non-stale reset intervenes`() {
    // Regression guard: previously the tracker stored only the most-recently-fired ref
    // in `lastFiredRef`. After fire("A") → fire("B"), the slot held "B"; a fresh
    // 3-streak on "A" with no non-stale reset would then RE-FIRE for "A" because the
    // guard compared only against the latest fired ref. That re-fire violated the
    // documented "fire at most once per ref per step" property and meant the LLM
    // received another STALE-REF RECOVERY for a ref it had already been told to drop.
    // Track all fired refs within the current streak (only cleared by `resetStreak`)
    // so any ref that has fired in this window stays suppressed.
    val t = StaleRefTracker()
    // First ref fires.
    repeat(STALE_REF_THRESHOLD) { t.recordStaleRef("A") }
    // Second ref fires — note no non-stale reset between the two streaks (just a
    // ref switch), which alone should NOT clear the fired set.
    repeat(STALE_REF_THRESHOLD) { t.recordStaleRef("B") }
    // Now back to "A" with another 3-streak. The fired set still contains "A" — must
    // NOT re-fire.
    var fired = false
    repeat(STALE_REF_THRESHOLD + 5) { if (t.recordStaleRef("A")) fired = true }
    assertFalse(fired, "Re-firing for a ref already in firedRefs without a resetStreak()")

    // Confirm a genuine non-stale reset clears the set and "A" can fire again.
    t.resetStreak()
    repeat(STALE_REF_THRESHOLD - 1) { assertFalse(t.recordStaleRef("A")) }
    assertTrue(t.recordStaleRef("A"))
  }

  @Test
  fun `rotating refs do not fire same-ref detector by design`() {
    // Documentation-by-test: the rotating-refs anti-pattern ("000" → "001" → "y778" →
    // "000" → …) is intentionally NOT caught by this detector — that pattern is the
    // domain of [detectActionCycleHint] / [detectDominantActionHint]. Verify that even
    // many rotations never advance the consecutive counter past 1.
    val t = StaleRefTracker()
    val refs = listOf("000", "001", "y778")
    repeat(10) { refs.forEach { ref -> assertFalse(t.recordStaleRef(ref)) } }
    // Each call switched to a different ref, so the counter resets to 1 each time.
    assertEquals(1, t.currentCount)
  }

  // --- summarizeIterationStaleRefs (Bug-1 regression guard) ---

  @Test
  fun `summarize empty input returns empty summary`() {
    val summary = summarizeIterationStaleRefs(emptyList())
    assertTrue(summary.staleRefs.isEmpty())
    assertFalse(summary.hadNonStaleRefResult)
  }

  @Test
  fun `summarize pure stale-ref iteration marks no progress`() {
    val summary = summarizeIterationStaleRefs(
      listOf(
        "tap: Element ref '000' not found on current screen.",
        "tap: Element ref '000' not found on current screen.",
      ),
    )
    assertEquals(listOf("000", "000"), summary.staleRefs)
    assertFalse(summary.hadNonStaleRefResult)
  }

  @Test
  fun `summarize pure success iteration marks progress`() {
    val summary = summarizeIterationStaleRefs(
      listOf(
        "Successfully tapped element",
        "Successfully asserted visible",
      ),
    )
    assertTrue(summary.staleRefs.isEmpty())
    assertTrue(summary.hadNonStaleRefResult)
  }

  @Test
  fun `summarize mixed iteration reports both signals`() {
    // The verification-step case the bots flagged: a single LLM response containing both
    // a successful assertion AND a stale-ref error. The caller must reset the streak
    // (because the LLM made progress) before recording the stale-ref hit.
    val summary = summarizeIterationStaleRefs(
      listOf(
        "Successfully asserted visible",
        "assertVisible: Element ref '000' not found on current screen.",
      ),
    )
    assertEquals(listOf("000"), summary.staleRefs)
    assertTrue(summary.hadNonStaleRefResult)
  }

  @Test
  fun `mixed iterations never accumulate stale streak when caller resets on progress`() {
    // Integration-level regression for Bug 1: simulate the runner's reset-then-record
    // sequence across many mixed iterations and verify the counter never crosses the
    // threshold — which would have triggered a false-positive recovery injection.
    val t = StaleRefTracker()
    repeat(STALE_REF_THRESHOLD * 5) {
      val summary = summarizeIterationStaleRefs(
        listOf(
          "Successfully asserted visible",
          "assertVisible: Element ref '000' not found on current screen.",
        ),
      )
      if (summary.hadNonStaleRefResult) t.resetStreak()
      for (ref in summary.staleRefs) {
        // Recovery should NEVER fire here — every iteration has a success that resets first.
        assertFalse(t.recordStaleRef(ref))
      }
    }
    assertEquals(1, t.currentCount)
  }

  // --- buildStaleRefRecoveryMessage ---

  @Test
  fun `recovery message names the dead ref and repeat count`() {
    val msg = buildStaleRefRecoveryMessage(ref = "000", repeatCount = STALE_REF_THRESHOLD)
    assertTrue(msg.contains("STALE-REF RECOVERY"))
    assertTrue(msg.contains("'000'"))
    assertTrue(msg.contains("$STALE_REF_THRESHOLD"))
  }

  @Test
  fun `recovery message instructs to re-read the live hierarchy`() {
    val msg = buildStaleRefRecoveryMessage(ref = "y778", repeatCount = 4)
    assertTrue(msg.contains("view hierarchy"), "Must point the LLM at the appended hierarchy")
    // The LLM should also know it can bail out with FAILED when nothing matches —
    // otherwise it might loop trying to invent yet another ref.
    assertTrue(msg.contains("objectiveStatus(FAILED)"))
  }
}
