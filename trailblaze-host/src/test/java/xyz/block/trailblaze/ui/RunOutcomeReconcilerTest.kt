package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * Pins the daemon-delegated pass/fail reconciliation: the on-disk [SessionStatus] is the source
 * of truth, so a session that ended Succeeded must NOT be demoted by a post-run connect/teardown
 * error (the V1 on-device-RPC dead-server case), and a genuinely-failed session must still fail.
 */
class RunOutcomeReconcilerTest {

  private val connectFailure = "Error occurred during instrumentation process."
  private val sessionDesc = "session-123"

  @Test
  fun `succeeded on disk plus connect-teardown error reconciles to success`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = false,
      latchError = connectFailure,
      diskStatus = SessionStatus.Ended.Succeeded(durationMs = 1000),
      sessionDescription = sessionDesc,
    )
    assertTrue(outcome.success, "A Succeeded session must not be demoted by a connect/teardown error")
    assertNull(outcome.error)
  }

  @Test
  fun `succeeded-with-self-heal on disk plus connect-teardown error reconciles to success`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = false,
      latchError = connectFailure,
      diskStatus = SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 1000),
      sessionDescription = sessionDesc,
    )
    assertTrue(outcome.success)
    assertNull(outcome.error)
  }

  @Test
  fun `failed on disk stays failed (negative control)`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = false,
      latchError = "Assertion failed",
      diskStatus = SessionStatus.Ended.Failed(durationMs = 1000, exceptionMessage = "boom"),
      sessionDescription = sessionDesc,
    )
    assertFalse(outcome.success, "A genuinely failed session must not be promoted")
    assertEquals("Assertion failed", outcome.error)
  }

  @Test
  fun `latch success but session ended failed is demoted`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = true,
      latchError = null,
      diskStatus = SessionStatus.Ended.TimeoutReached(durationMs = 1000, message = "timed out"),
      sessionDescription = sessionDesc,
    )
    assertFalse(outcome.success)
    assertEquals("Session $sessionDesc ended with status: TimeoutReached", outcome.error)
  }

  @Test
  fun `latch success with succeeded disk status stays success`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = true,
      latchError = null,
      diskStatus = SessionStatus.Ended.Succeeded(durationMs = 1000),
      sessionDescription = sessionDesc,
    )
    assertTrue(outcome.success)
    assertNull(outcome.error)
  }

  @Test
  fun `non-terminal disk status keeps the in-memory failure`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = false,
      latchError = connectFailure,
      diskStatus = SessionStatus.Unknown,
      sessionDescription = sessionDesc,
    )
    assertFalse(outcome.success, "An unterminated session must not be promoted off a connect error")
    assertEquals(connectFailure, outcome.error)
  }

  @Test
  fun `null disk status keeps the in-memory failure`() {
    val outcome = reconcileRunOutcome(
      latchSuccess = false,
      latchError = connectFailure,
      diskStatus = null,
      sessionDescription = sessionDesc,
    )
    assertFalse(outcome.success)
    assertEquals(connectFailure, outcome.error)
  }
}
