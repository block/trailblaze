package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the end-of-trail turbo check reaches the report.
 *
 * The property under test is not "does it throw" — it is WHICH result the session is ended with.
 * The report's PASSED/FAILED comes from the session-end log, so a turbo failure that only reached
 * JUnit would leave a trail that replayed entirely at heuristic speed reporting green, which is the
 * silent pass `turboRequired` exists to eliminate.
 */
class TurboVerdictTest {

  @Test
  fun `a turbo failure on a passing trail becomes the session's result`() {
    val turboFailure = IllegalStateException("turbo was armed but no launch ever attached")

    val verdict = turboVerdict(Result.success(null)) { throw turboFailure }

    // THE regression. Ending the session with the original success here is what made the report and
    // the exit status disagree.
    assertTrue(verdict.sessionResult.isFailure, "ended the session as passed despite losing turbo")
    assertSame(turboFailure, verdict.sessionResult.exceptionOrNull())
    // Carried out separately as well, because JUnit only learns of it by being thrown at.
    assertSame(turboFailure, verdict.failure)
  }

  @Test
  fun `a passing trail that kept turbo is left exactly as it was`() {
    val passed = Result.success<Nothing?>(null)

    val verdict = turboVerdict(passed) { /* the check found nothing wrong */ }

    assertEquals(passed, verdict.sessionResult)
    assertNull(verdict.failure, "invented a failure for a trail that kept its turbo")
  }

  @Test
  fun `a trail that already failed keeps its own cause and is never asked about turbo`() {
    // Asserted with an exploding check rather than a flag: on a failed trail there is no answer
    // worth having. The trail stopped early, so "no launch ever attached the detector" is a
    // consequence of the failure, and reporting it would replace the real cause with a symptom.
    val realCause = IllegalStateException("tapOn(Sign in) found no matching node")

    val verdict = turboVerdict(Result.failure(realCause)) {
      error("asked whether turbo held on a trail that never finished")
    }

    assertSame(realCause, verdict.sessionResult.exceptionOrNull())
    assertNull(verdict.failure, "would rethrow a turbo failure over the real one")
  }

  @Test
  fun `the thrown failure and the reported failure are the same object`() {
    // They are read by different consumers — the report reads the session result, JUnit catches the
    // throw — and a run whose two verdicts name different causes cannot be triaged from either one.
    val turboFailure = IllegalStateException("turbo lost")

    val verdict = turboVerdict(Result.success(null)) { throw turboFailure }

    assertSame(verdict.failure, verdict.sessionResult.exceptionOrNull())
    assertFalse(verdict.sessionResult.isSuccess)
  }
}
