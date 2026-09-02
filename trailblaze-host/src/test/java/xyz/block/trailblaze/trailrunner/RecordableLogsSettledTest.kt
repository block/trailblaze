package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId

/**
 * A recording is built from the selector-keyed tool logs an on-device run flushes late, so merging
 * before they land writes a recording that is missing steps. [recordableLogsSettled] is the gate. It
 * has to get right the two ways a delegated trace ends - every tool arrived, or the batch stopped
 * early and the rest never will - and it has to hold a run whose tools were all executed directly,
 * which advertises no expected count at all.
 */
class RecordableLogsSettledTest {

  private val session = SessionId("tb-settled-test")

  private fun ended(vararg logs: TrailblazeLog): List<TrailblazeLog> = logs.toList() + statusLog(
    SessionStatus.Ended.Succeeded(durationMs = 1_000),
  )

  private fun statusLog(status: SessionStatus) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = status,
    session = session,
    timestamp = Clock.System.now(),
  )

  private fun delegating(trace: TraceId, childCount: Int) = TrailblazeLog.DelegatingTrailblazeToolLog(
    toolName = "verifyThing",
    trailblazeTool = OtherTrailblazeTool("verifyThing"),
    session = session,
    timestamp = Clock.System.now(),
    traceId = trace,
    executableTools = List(childCount) { OtherTrailblazeTool("tapOn$it") },
  )

  private fun recorded(
    trace: TraceId,
    name: String,
    successful: Boolean = true,
    isRecordable: Boolean = true,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = OtherTrailblazeTool(name),
    toolName = name,
    successful = successful,
    traceId = trace,
    durationMs = 10,
    session = session,
    timestamp = Clock.System.now(),
    isRecordable = isRecordable,
  )

  @Test
  fun `a delegated batch with one of its tools still in flight is not settled`() {
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertFalse(
      ended(delegating(trace, childCount = 2), recorded(trace, "tapOn0"))
        .recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `a delegated batch is settled once every tool it expanded into has arrived`() {
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertTrue(
      ended(
        delegating(trace, childCount = 2),
        recorded(trace, "tapOn0"),
        recorded(trace, "tapOn1"),
      ).recordableLogsSettled(previousRecordableCount = 2),
    )
  }

  @Test
  fun `a batch that stopped at a failing tool is settled, because the rest will never run`() {
    // Execution abandons the expansion at the first failure, so waiting for the advertised count
    // would wait out the whole budget and then refuse a save-back the run had earned.
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertTrue(
      ended(
        delegating(trace, childCount = 3),
        recorded(trace, "tapOn0", successful = false),
      ).recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `one trace being complete says nothing about another`() {
    val done = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    val pending = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertFalse(
      ended(
        delegating(done, childCount = 1),
        recorded(done, "tapOn0"),
        delegating(pending, childCount = 1),
      ).recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `a run that has not ended is never settled, however complete its tool logs look`() {
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertFalse(
      listOf(delegating(trace, childCount = 1), recorded(trace, "tapOn0"))
        .recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `a run whose tools all executed directly is not settled while its logs are still arriving`() {
    // No delegating parent means no advertised count, so the per-trace rule has nothing to wait for.
    // A count that moved since the last look is the device still flushing.
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertFalse(
      ended(recorded(trace, "inputText")).recordableLogsSettled(previousRecordableCount = 0),
    )
  }

  @Test
  fun `a run whose tools all executed directly is settled once its logs stop arriving`() {
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertTrue(
      ended(recorded(trace, "inputText")).recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `a run that logged no tool calls at all is never settled`() {
    assertFalse(ended().recordableLogsSettled(previousRecordableCount = 0))
  }

  @Test
  fun `a delegated batch holding a non-recordable tool is settled once all of them arrive`() {
    // The advertised count is every tool the parent delegated to, recordable or not, so per-trace
    // completeness has to count the same population - otherwise this waits out the whole budget and
    // refuses a save-back the run earned.
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertTrue(
      ended(
        delegating(trace, childCount = 2),
        recorded(trace, "tapOn0"),
        recorded(trace, "assertVisible", isRecordable = false),
      ).recordableLogsSettled(previousRecordableCount = 1),
    )
  }

  @Test
  fun `a run whose only tool calls are non-recordable is never settled`() {
    // The recording generator drops non-recordable tools, so such a run produces no tools to save.
    // Settling on them would hand the writer an empty recording for steps that already have one.
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)
    assertFalse(
      ended(recorded(trace, "assertVisible", isRecordable = false))
        .recordableLogsSettled(previousRecordableCount = 1),
    )
  }
}
