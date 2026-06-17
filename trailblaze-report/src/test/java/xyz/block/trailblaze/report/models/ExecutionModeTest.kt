package xyz.block.trailblaze.report.models

import kotlin.test.assertEquals
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * Pins down [ExecutionMode.classify].
 *
 * The historical bug: a recorded trail that made even one LLM call (an LLM-backed `verify`, a
 * `recordable: false` step) was reported as [ExecutionMode.AI_ONLY], because the only signal used
 * was "did this session emit any LLM request log". The fix trusts `hasRecordedSteps` (computed from
 * the trail YAML at session start) as the source of truth for whether the trail carried recordings,
 * and uses the LLM-call signal only to split recorded runs into pure-replay vs recorded-with-AI.
 */
class ExecutionModeTest {
  // A plain terminal status with no self-heal semantics — keeps each case focused on the
  // hasRecordedSteps × made-LLM-calls matrix.
  private val succeeded: SessionStatus = SessionStatus.Ended.Succeeded(durationMs = 1_000)

  // `available == false` → session emitted an LLM request log and did NOT self-heal (see
  // SessionRecordingInfo.fromLogs). These fixtures exercise the non-self-heal LLM-call axis only;
  // self-heal is covered separately below.
  private val noLlmCalls = SessionRecordingInfo(available = true, skipReason = null, usedSelfHeal = false)
  private val madeLlmCalls = SessionRecordingInfo(available = false, skipReason = RecordingSkipReason.NOT_FOUND, usedSelfHeal = false)

  @Test
  fun `recorded trail with no LLM calls is RECORDING_ONLY`() {
    assertEquals(
      ExecutionMode.RECORDING_ONLY,
      ExecutionMode.classify(succeeded, hasRecordedSteps = true, recordingInfo = noLlmCalls),
    )
  }

  @Test
  fun `recorded trail that also made LLM calls is RECORDING_WITH_AI not AI_ONLY`() {
    // This is the case that was previously mislabeled AI_ONLY.
    assertEquals(
      ExecutionMode.RECORDING_WITH_AI,
      ExecutionMode.classify(succeeded, hasRecordedSteps = true, recordingInfo = madeLlmCalls),
    )
  }

  @Test
  fun `non-recorded trail that made LLM calls is AI_ONLY`() {
    assertEquals(
      ExecutionMode.AI_ONLY,
      ExecutionMode.classify(succeeded, hasRecordedSteps = false, recordingInfo = madeLlmCalls),
    )
  }

  @Test
  fun `non-recorded trail with no LLM calls is UNKNOWN`() {
    // e.g. a tool-only trail — neither a recorded replay nor AI-driven, so we don't pretend.
    assertEquals(
      ExecutionMode.UNKNOWN,
      ExecutionMode.classify(succeeded, hasRecordedSteps = false, recordingInfo = noLlmCalls),
    )
  }

  @Test
  fun `self-heal status wins over recorded plus LLM calls`() {
    val selfHealStatus = SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 1_000)
    assertEquals(
      ExecutionMode.SELF_HEAL,
      ExecutionMode.classify(selfHealStatus, hasRecordedSteps = true, recordingInfo = madeLlmCalls),
    )
  }

  @Test
  fun `failed self-heal status is SELF_HEAL`() {
    val failedSelfHeal = SessionStatus.Ended.FailedWithSelfHeal(durationMs = 1_000, exceptionMessage = "boom")
    assertEquals(
      ExecutionMode.SELF_HEAL,
      ExecutionMode.classify(failedSelfHeal, hasRecordedSteps = true, recordingInfo = noLlmCalls),
    )
  }

  @Test
  fun `usedSelfHeal recording info is SELF_HEAL even without a self-heal status`() {
    val selfHealInfo = SessionRecordingInfo(
      available = true,
      skipReason = RecordingSkipReason.EXECUTION_FAILED,
      usedSelfHeal = true,
    )
    assertEquals(
      ExecutionMode.SELF_HEAL,
      ExecutionMode.classify(succeeded, hasRecordedSteps = true, recordingInfo = selfHealInfo),
    )
  }

  @Test
  fun `config-disabled recording is RECORDING_SKIPPED and wins over recorded steps`() {
    val configSkipped = SessionRecordingInfo(
      available = false,
      skipReason = RecordingSkipReason.DISABLED_BY_CONFIG,
      usedSelfHeal = false,
    )
    assertEquals(
      ExecutionMode.RECORDING_SKIPPED,
      ExecutionMode.classify(succeeded, hasRecordedSteps = true, recordingInfo = configSkipped),
    )
  }
}
