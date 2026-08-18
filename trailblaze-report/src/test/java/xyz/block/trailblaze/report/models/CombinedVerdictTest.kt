package xyz.block.trailblaze.report.models

import xyz.block.trailblaze.exception.TrailheadException
import kotlin.test.Test
import kotlin.test.assertEquals

class CombinedVerdictTest {

  private fun pass() = AttemptSignal(Outcome.PASSED)
  private fun fail() = AttemptSignal(Outcome.FAILED)
  private fun trailhead() = AttemptSignal(Outcome.FAILED, TrailheadException.KIND)
  private fun timeout() = AttemptSignal(Outcome.TIMEOUT)
  private fun error() = AttemptSignal(Outcome.ERROR)
  private fun maxCalls() = AttemptSignal(Outcome.MAX_CALLS_REACHED)
  private fun skipped() = AttemptSignal(Outcome.SKIPPED)

  /**
   * Held alongside [skipped] because the two classify the same but only this one is reachable from
   * the report generator: `mapStatusToOutcome` can emit CANCELLED and never emits SKIPPED. Testing
   * only the unreachable member would leave the one a nightly can actually produce uncovered.
   */
  private fun cancelled() = AttemptSignal(Outcome.CANCELLED)

  /**
   * The whole taxonomy in one table, so a change to any rule shows up as a diff against every
   * other rule rather than against one case someone happened to write a test for.
   */
  private val cases = listOf(
    Triple("a single clean run", listOf(pass()), CombinedVerdict.PASSED),
    Triple("a rerun where nothing failed", listOf(pass(), pass()), CombinedVerdict.PASSED),
    Triple("failed then passed", listOf(fail(), pass()), CombinedVerdict.RESCUED),
    Triple("timed out then passed", listOf(timeout(), pass()), CombinedVerdict.RESCUED),
    Triple("passed then failed on a rerun", listOf(pass(), fail()), CombinedVerdict.RESCUED),
    Triple("failed twice running", listOf(fail(), fail()), CombinedVerdict.REPRODUCED),
    Triple("failed three times running", listOf(fail(), fail(), fail()), CombinedVerdict.REPRODUCED),
    Triple("failed once, never retried", listOf(fail()), CombinedVerdict.FAILED_UNRETRIED),
    Triple("setup broke, once", listOf(trailhead()), CombinedVerdict.SETUP_FAILED),
    Triple("setup broke, twice", listOf(trailhead(), trailhead()), CombinedVerdict.SETUP_FAILED),
    Triple("setup broke, then the case itself failed", listOf(trailhead(), fail()), CombinedVerdict.REPRODUCED),
    Triple("failed then timed out", listOf(fail(), timeout()), CombinedVerdict.VERDICT_LOST),
    Triple("timed out then failed", listOf(timeout(), fail()), CombinedVerdict.VERDICT_LOST),
    Triple("failed then errored", listOf(fail(), error()), CombinedVerdict.VERDICT_LOST),
    Triple("timed out once", listOf(timeout()), CombinedVerdict.NO_VERDICT),
    Triple("timed out twice", listOf(timeout(), timeout()), CombinedVerdict.NO_VERDICT),
    Triple("errored then timed out", listOf(error(), timeout()), CombinedVerdict.NO_VERDICT),
    Triple("ran out of call budget", listOf(maxCalls()), CombinedVerdict.AMBIGUOUS),
    Triple("failed then ran out of budget", listOf(fail(), maxCalls()), CombinedVerdict.AMBIGUOUS),
    Triple("ran out of budget then passed", listOf(maxCalls(), pass()), CombinedVerdict.RESCUED),
    Triple("never ran", listOf(skipped()), CombinedVerdict.UNCLASSIFIED),
    Triple("skipped after a failure", listOf(fail(), skipped()), CombinedVerdict.UNCLASSIFIED),
    // A run that was cancelled reached no conclusion and never finished attempting one, so it
    // lands in the residue rather than in a failure state. Named here so the residue is a stated
    // outcome of the taxonomy rather than somewhere a reachable outcome quietly falls.
    Triple("cancelled once, never retried", listOf(cancelled()), CombinedVerdict.UNCLASSIFIED),
    Triple("cancelled after a failure", listOf(fail(), cancelled()), CombinedVerdict.UNCLASSIFIED),
    Triple("no attempts at all", emptyList(), CombinedVerdict.UNCLASSIFIED),
  )

  @Test
  fun `every modelled attempt sequence lands on its stated verdict`() {
    cases.forEach { (description, attempts, expected) ->
      assertEquals(expected, combinedVerdictOf(attempts), description)
    }
  }

  @Test
  fun `a pass anywhere in the run outranks every failure signal`() {
    // The rule that keeps a nightly red honest in the other direction: whatever else happened,
    // a test that passed is not evidence the product regressed. Asserted across every non-pass
    // signal so a new outcome cannot quietly acquire the power to outrank a pass.
    listOf(fail(), trailhead(), timeout(), error(), maxCalls(), skipped(), cancelled()).forEach { signal ->
      assertEquals(
        CombinedVerdict.RESCUED,
        combinedVerdictOf(listOf(signal, pass())),
        "${signal.outcome} followed by a pass is a rescue",
      )
    }
  }

  @Test
  fun `only a repeated verdict about the case itself reads as reproduced`() {
    // REPRODUCED is the state a leg's red is meant to mean, so it is the one that must not
    // over-claim. Nothing that failed to reach a verdict, and nothing that failed before
    // reaching the case, may land here however many times it repeats.
    val neverReproduced = listOf(
      listOf(timeout(), timeout()),
      listOf(error(), error()),
      listOf(maxCalls(), maxCalls()),
      listOf(trailhead(), trailhead()),
      listOf(fail(), timeout()),
      listOf(skipped(), skipped()),
    )
    neverReproduced.forEach { attempts ->
      val verdict = combinedVerdictOf(attempts)
      assertEquals(
        false,
        verdict == CombinedVerdict.REPRODUCED,
        "${attempts.map { it.outcome }} must not read as a reproduced product failure, got $verdict",
      )
    }
  }

  @Test
  fun `no run without a passing attempt may ever read as passed or rescued`() {
    // The single assertion protecting "green means there isn't a regression". Every other rule
    // here decides which KIND of red a red is, and getting one of those wrong produces a
    // mislabelled red. Getting this one wrong turns a red into a green, which is the only error
    // this layer exists to make impossible.
    //
    // Enumerated over the signals rather than written out as cases, so a combination nobody
    // thought to list is still covered, and a rule edit that never touches the table above is
    // still caught here.
    val nonPassing = listOf(fail(), trailhead(), timeout(), error(), maxCalls(), skipped(), cancelled())
    val sequences = nonPassing.map { listOf(it) } +
      nonPassing.flatMap { first -> nonPassing.map { second -> listOf(first, second) } }

    sequences.forEach { attempts ->
      val verdict = combinedVerdictOf(attempts)
      assertEquals(
        false,
        verdict == CombinedVerdict.PASSED || verdict == CombinedVerdict.RESCUED,
        "${attempts.map { it.outcome }} contains no passing attempt but read as $verdict",
      )
    }
  }

  @Test
  fun `one failure and two failures are different claims`() {
    // Whether a single un-retried failure should colour a leg red is a policy call, so the two
    // populations stay separately countable rather than being merged behind one label.
    assertEquals(CombinedVerdict.FAILED_UNRETRIED, combinedVerdictOf(listOf(fail())))
    assertEquals(CombinedVerdict.REPRODUCED, combinedVerdictOf(listOf(fail(), fail())))
  }
}
