package xyz.block.trailblaze.agent.trail

import org.junit.Test
import xyz.block.trailblaze.agent.blaze.detectActionCycleHint
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [stripActionOutcomeSuffix], which removes the terminal `→ SUCCESS` /
 * `→ FAILED[: ...]` outcome suffix from Trail action-history entries before they
 * are fed into the cycle detector.
 *
 * The strip must be anchored at the end of the entry so a `→` embedded in a tool
 * argument (e.g. a breadcrumb label like `"Menu → Settings"`) does not collapse
 * distinct actions to the same signature and trigger spurious loop warnings.
 */
class TrailGoalPlannerSignatureTest {

  @Test
  fun `strips trailing SUCCESS suffix`() {
    assertEquals(
      "tap(ref=u82, \"Items\")",
      stripActionOutcomeSuffix("tap(ref=u82, \"Items\") → SUCCESS"),
    )
  }

  @Test
  fun `strips trailing FAILED suffix without error message`() {
    assertEquals(
      "tap(ref=u82, \"Items\")",
      stripActionOutcomeSuffix("tap(ref=u82, \"Items\") → FAILED"),
    )
  }

  @Test
  fun `strips trailing FAILED suffix with single-line error message`() {
    assertEquals(
      "tap(ref=u82, \"Items\")",
      stripActionOutcomeSuffix("tap(ref=u82, \"Items\") → FAILED: element not found"),
    )
  }

  @Test
  fun `strips trailing FAILED suffix with multi-line error message`() {
    val multilineError = "tap(ref=u82, \"Items\") → FAILED: timeout\n  at Foo.bar(Foo.kt:42)"
    assertEquals(
      "tap(ref=u82, \"Items\")",
      stripActionOutcomeSuffix(multilineError),
    )
  }

  @Test
  fun `preserves arrow embedded inside tool arguments`() {
    val entry = "type({\"text\":\"Menu → Settings\"}) → SUCCESS"
    assertEquals(
      "type({\"text\":\"Menu → Settings\"})",
      stripActionOutcomeSuffix(entry),
    )
  }

  @Test
  fun `preserves arrow inside breadcrumb-style label without outcome suffix`() {
    val entry = "tap(\"Account → Settings\")"
    assertEquals(entry, stripActionOutcomeSuffix(entry))
  }

  @Test
  fun `passes through entry without outcome suffix unchanged`() {
    assertEquals("tap(ref=u82)", stripActionOutcomeSuffix("tap(ref=u82)"))
  }

  @Test
  fun `does not strip non-outcome word after arrow`() {
    // "PENDING" is not a recognized outcome — the suffix must be left intact so the
    // detector sees it as part of the signature rather than silently dropping it.
    val entry = "tap(items) → PENDING"
    assertEquals(entry, stripActionOutcomeSuffix(entry))
  }

  @Test
  fun `coalesces SUCCESS and FAILED variants of the same action through the cycle detector`() {
    // Reproduces the case_4839652 path through the strip helper: the same logical
    // action (tap Items → tap Back) ping-pongs, with mixed outcomes from the
    // recoverable-failure retry path. After stripping, the detector should see a
    // length-2 cycle and emit a CRITICAL hint.
    val rawHistory = listOf(
      "tap(ref=w199, \"Items\") → SUCCESS",
      "tap(ref=u82, \"Back\") → FAILED: transient",
      "tap(ref=w199, \"Items\") → SUCCESS",
      "tap(ref=u82, \"Back\") → SUCCESS",
      "tap(ref=w199, \"Items\") → SUCCESS",
      "tap(ref=u82, \"Back\") → SUCCESS",
    )
    val signatures = rawHistory.map(::stripActionOutcomeSuffix)
    val hint = detectActionCycleHint(signatures)
    assertNotNull(hint, "alternating tap-pair across mixed outcomes should trigger a hint")
    assertEquals(true, hint.startsWith("CRITICAL"))
    assertEquals(true, hint.contains("cycle of 2 actions"))
  }

  @Test
  fun `does not collide on embedded arrow with otherwise-distinct arguments`() {
    // Two type calls with arguments that happen to share a "Menu → " prefix but
    // differ after the arrow. The pre-fix `substringBefore(" → ")` would truncate
    // both to `type("Menu` and falsely report a length-1 repeat.
    val rawHistory = listOf(
      "type(\"Menu → Settings\") → SUCCESS",
      "type(\"Menu → Profile\") → SUCCESS",
    )
    val signatures = rawHistory.map(::stripActionOutcomeSuffix)
    assertNull(
      detectActionCycleHint(signatures),
      "distinct args sharing a 'Menu → ' prefix must not collapse to the same signature",
    )
  }
}
