package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.android.accessibility.AbsenceConfirmation.Observation.ABSENT_IN_COMPLETE_CAPTURE
import xyz.block.trailblaze.android.accessibility.AbsenceConfirmation.Observation.ABSENT_IN_PARTIAL_CAPTURE
import xyz.block.trailblaze.android.accessibility.AbsenceConfirmation.Observation.NO_TREE
import xyz.block.trailblaze.android.accessibility.AbsenceConfirmation.Observation.PRESENT

/**
 * The policy behind `assertNotVisible` on the accessibility driver: when may a poll loop say an
 * element is gone? The defect this pins down: a capture taken while the app's main thread was
 * blocked came back PARTIAL (node fetches returned null), the element was not in it, and the
 * old loop released on that single no-match — with the element still on screen.
 */
class AbsenceConfirmationTest {

  @Test
  fun `a partial capture without the element never confirms absence on its own`() {
    val policy = AbsenceConfirmation()

    // The exact failure: every capture during the app's stall is partial and lacks the element.
    repeat(20) {
      assertFalse(policy.observe(ABSENT_IN_PARTIAL_CAPTURE), "partial capture #$it must not confirm absence")
    }
    assertFalse(policy.awaitingConfirmation)
  }

  @Test
  fun `absence needs two consecutive complete captures`() {
    val policy = AbsenceConfirmation()

    assertFalse(policy.observe(ABSENT_IN_COMPLETE_CAPTURE), "first complete no-match is not enough")
    assertTrue(policy.awaitingConfirmation, "one more complete capture is owed")
    assertTrue(policy.observe(ABSENT_IN_COMPLETE_CAPTURE), "second consecutive complete no-match confirms")
  }

  @Test
  fun `a partial capture between two complete ones breaks the streak`() {
    val policy = AbsenceConfirmation()

    assertFalse(policy.observe(ABSENT_IN_COMPLETE_CAPTURE))
    assertFalse(policy.observe(ABSENT_IN_PARTIAL_CAPTURE), "partial capture proves nothing")
    assertFalse(policy.awaitingConfirmation, "the streak restarted")
    assertFalse(policy.observe(ABSENT_IN_COMPLETE_CAPTURE), "streak is back to one")
    assertTrue(policy.observe(ABSENT_IN_COMPLETE_CAPTURE))
  }

  @Test
  fun `seeing the element resets the streak`() {
    val policy = AbsenceConfirmation()

    assertFalse(policy.observe(ABSENT_IN_COMPLETE_CAPTURE))
    assertFalse(policy.observe(PRESENT), "the element is there")
    assertFalse(policy.observe(ABSENT_IN_COMPLETE_CAPTURE), "one complete no-match after presence is not enough")
    assertTrue(policy.observe(ABSENT_IN_COMPLETE_CAPTURE))
  }

  @Test
  fun `no tree at all is inconclusive, not absence`() {
    val policy = AbsenceConfirmation()

    repeat(5) { assertFalse(policy.observe(NO_TREE)) }
    assertEquals(5, policy.inconclusiveCount)
    assertEquals(0, policy.completeAbsentCount)
  }

  @Test
  fun `failure message blames the capture when no complete tree was ever seen`() {
    val policy = AbsenceConfirmation()
    repeat(3) { policy.observe(ABSENT_IN_PARTIAL_CAPTURE) }

    val message = policy.describeFailure()
    assertTrue(message.contains("no complete accessibility tree"), message)
    assertTrue(message.contains("3 poll(s)"), message)
  }

  @Test
  fun `failure message says still present when the element was seen`() {
    val policy = AbsenceConfirmation()
    policy.observe(PRESENT)
    policy.observe(ABSENT_IN_PARTIAL_CAPTURE)
    policy.observe(PRESENT)

    val message = policy.describeFailure()
    assertTrue(message.startsWith("still present"), message)
    assertTrue(message.contains("2 poll(s)"), message)
  }

  @Test
  fun `a single-confirmation policy releases on the first complete no-match`() {
    val policy = AbsenceConfirmation(confirmationsRequired = 1)

    assertFalse(policy.observe(ABSENT_IN_PARTIAL_CAPTURE), "even with one confirmation, partial never counts")
    assertTrue(policy.observe(ABSENT_IN_COMPLETE_CAPTURE))
    assertFalse(policy.awaitingConfirmation)
  }
}
