package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.exception.TrailheadException

/**
 * What a test's attempts, taken together, say about the product.
 *
 * A nightly leg's red is only worth waking up for if it means the product regressed. Every
 * attempt of a test carries an [Outcome], but an outcome describes how one run ENDED, not whether
 * the thing under test is broken — a run that timed out reached no conclusion at all, and a run
 * whose setup never completed never tested the case. Reading a leg's colour off the raw outcomes
 * therefore mixes "this regressed" with "we failed to measure it", and the two need opposite
 * responses.
 *
 * Each attempt is classified on its own and the results are combined ([combinedVerdictOf]). The
 * pair is never classified directly: a retry can end differently from the attempt it replaced,
 * and collapsing them first destroys exactly the disagreement worth reporting.
 *
 * Nothing here suppresses anything. Every state is reported; the point is to say which kind of
 * red a red is, not to decide which reds to show.
 */
@Serializable
enum class CombinedVerdict {
  /** Every attempt passed. */
  PASSED,

  /**
   * At least one attempt passed and at least one did not — the test was rescued by a retry.
   * Deliberately the same population the retry headline counts, so the two can never disagree.
   */
  RESCUED,

  /**
   * Two or more attempts, none passed, and every attempt reached a negative verdict about the
   * case itself. This is the state a nightly red is supposed to mean: it went red, it went red
   * again, and both times the test actually ran and disagreed with the product.
   */
  REPRODUCED,

  /**
   * No attempt passed and every failure was a trailhead (setup) failure, so no attempt ever
   * reached the behaviour under test. Never a product regression however many times it repeats —
   * the fixture broke, not the app. Separate from [REPRODUCED] because the two want different
   * people looking at them.
   */
  SETUP_FAILED,

  /**
   * A single attempt reached a negative verdict and no retry followed.
   *
   * Held apart from [REPRODUCED] because "it failed once" and "it failed twice running" are
   * different claims, and only the second is evidence on its own. Whether this alone should
   * colour a leg red is a policy question for the humans reading the board, so this state names
   * the population without deciding it.
   */
  FAILED_UNRETRIED,

  /**
   * No attempt passed; some reached a verdict and others did not. The retry did not confirm the
   * failure and did not clear it — it replaced a real verdict with a non-answer, so the run now
   * holds less information than its first attempt did.
   */
  VERDICT_LOST,

  /**
   * No attempt passed and no attempt reached a verdict at all. Says nothing about the product;
   * it is a report that the measurement failed.
   */
  NO_VERDICT,

  /**
   * No attempt passed and an attempt exhausted its call budget. Genuinely undecidable from this
   * data: a budget exhaustion looks the same whether the product grew a step the flow must now
   * get through or the agent simply failed to drive it. Named rather than folded into a
   * neighbouring state, so the gap is countable and a discriminator can be aimed at it later.
   */
  AMBIGUOUS,

  /**
   * The attempts do not fit any rule above. Exists so an unmodelled combination is visible and
   * countable instead of being absorbed into whichever state is nearest — a residue that reports
   * itself can be examined, one that has been rounded off cannot.
   */
  UNCLASSIFIED,
}

/**
 * One attempt's signals, as [combinedVerdictOf] reads them.
 *
 * Deliberately not a [SessionResult]: the classification depends on these two fields and nothing
 * else, and taking the whole result would let it quietly start depending on more.
 */
data class AttemptSignal(val outcome: Outcome, val failureKind: String? = null)

/**
 * Layer 1 — what one attempt, on its own, says about the product.
 *
 * Keyed on [Outcome] rather than on an exception type. The outcome is a closed vocabulary the
 * report already commits to, while exception types are open-ended and mostly absent; keying on
 * them would make the taxonomy drift every time a new exception appeared. The one exception type
 * consulted is the trailhead kind, and only to separate "never got to the test" from "tested it
 * and it failed" — a distinction the outcome alone cannot carry.
 */
private enum class AttemptClass {
  PASS,

  /** Reached a conclusion about the case, and the conclusion was negative. */
  VERDICT_FAIL,

  /** Ran, but reached no conclusion — the measurement failed rather than the product. */
  NO_VERDICT,

  /** Ran out of call budget. Undecidable without a discriminator this data does not carry. */
  AMBIGUOUS,

  /** Never ran. Kept distinct from [NO_VERDICT]: not attempting is not the same as failing to conclude. */
  NOT_RUN,
}

private fun AttemptSignal.classify(): AttemptClass = when (outcome) {
  Outcome.PASSED -> AttemptClass.PASS
  Outcome.FAILED -> AttemptClass.VERDICT_FAIL
  Outcome.TIMEOUT, Outcome.ERROR -> AttemptClass.NO_VERDICT
  Outcome.MAX_CALLS_REACHED -> AttemptClass.AMBIGUOUS
  Outcome.SKIPPED, Outcome.CANCELLED -> AttemptClass.NOT_RUN
}

/**
 * Layer 3 — combine each attempt's classification into one verdict for the test.
 *
 * Order matters and is the policy. A pass anywhere outranks everything, because a test that
 * passed is not evidence of a regression whatever else happened. Ambiguity outranks the failure
 * states, because a run that may simply have run out of budget cannot be offered as proof the
 * product broke. Only then do the failure states separate, cause first.
 *
 * @return [CombinedVerdict.UNCLASSIFIED] for an empty or unmodelled sequence — never a guess.
 */
fun combinedVerdictOf(attempts: List<AttemptSignal>): CombinedVerdict {
  if (attempts.isEmpty()) return CombinedVerdict.UNCLASSIFIED
  val classes = attempts.map { it.classify() }

  if (classes.all { it == AttemptClass.PASS }) return CombinedVerdict.PASSED
  if (classes.any { it == AttemptClass.PASS }) return CombinedVerdict.RESCUED
  if (classes.any { it == AttemptClass.AMBIGUOUS }) return CombinedVerdict.AMBIGUOUS

  val failures = attempts.filter { it.classify() == AttemptClass.VERDICT_FAIL }
  if (failures.isNotEmpty() && failures.all { it.failureKind == TrailheadException.KIND }) {
    // Every attempt that concluded, concluded that the setup broke. Retrying a broken fixture
    // reproduces the broken fixture, so attempt count carries no extra meaning here.
    return CombinedVerdict.SETUP_FAILED
  }

  if (classes.all { it == AttemptClass.VERDICT_FAIL }) {
    return if (classes.size >= 2) CombinedVerdict.REPRODUCED else CombinedVerdict.FAILED_UNRETRIED
  }
  if (classes.all { it == AttemptClass.NO_VERDICT }) return CombinedVerdict.NO_VERDICT
  if (classes.any { it == AttemptClass.VERDICT_FAIL } && classes.any { it == AttemptClass.NO_VERDICT }) {
    return CombinedVerdict.VERDICT_LOST
  }
  return CombinedVerdict.UNCLASSIFIED
}
