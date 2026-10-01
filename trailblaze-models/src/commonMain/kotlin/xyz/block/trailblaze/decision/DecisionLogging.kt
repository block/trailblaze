package xyz.block.trailblaze.decision

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Where decision requests made inside a coroutine are logged. Engines are usually built once per
 * provider, long before any session exists, so they cannot hold a session logger. Whatever owns
 * the session (the LLM-request logger for an agent turn, say) installs this element around the
 * work, and [decideLogged] finds it. With no element installed, decisions are simply not logged.
 */
class DecisionLogContext(
  val session: SessionId,
  val traceId: TraceId?,
  private val emit: (TrailblazeLog) -> Unit,
) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<DecisionLogContext>

  internal fun record(log: TrailblazeLog.TrailblazeDecisionRequestLog) {
    // A logging failure must never fail the decision it describes.
    runCatching { emit(log) }
  }
}

/**
 * [DecisionEngine.decide], plus a [TrailblazeLog.TrailblazeDecisionRequestLog] in the current
 * [DecisionLogContext]. [outcomeOf] turns the answers into the caller's one-line account of what it
 * does with them; it runs only for logging, and a failure in it is swallowed. Engine failures are
 * logged and rethrown. So is cancellation (a caller's timeout, a race the LLM won): the request
 * already reached the engine, so it is logged as cancelled rather than dropped.
 *
 * [loggedRequest] and [loggedResponse] are what the log keeps, when a caller's questions are too big
 * to store on every turn (long option descriptions the session already holds elsewhere, say). The
 * cost is always computed from the real response.
 */
suspend fun DecisionEngine.decideLogged(
  request: DecisionRequest,
  loggedRequest: DecisionRequest = request,
  loggedResponse: (DecisionResponse) -> DecisionResponse = { it },
  outcomeOf: (DecisionResponse) -> String? = { null },
): DecisionResponse {
  val sink = coroutineContext[DecisionLogContext] ?: return decide(request)
  val startedAt = Clock.System.now()
  fun record(response: DecisionResponse?, error: String?, outcome: String?) =
    sink.record(
      TrailblazeLog.TrailblazeDecisionRequestLog(
        engine = name,
        request = loggedRequest,
        response = response?.let { runCatching { loggedResponse(it) }.getOrDefault(it) },
        errorMessage = error,
        outcome = outcome,
        cost = response?.let { runCatching { costOf(it) }.getOrNull() },
        traceId = sink.traceId,
        durationMs = (Clock.System.now() - startedAt).inWholeMilliseconds,
        session = sink.session,
        timestamp = startedAt,
      )
    )
  val response =
    try {
      decide(request)
    } catch (e: CancellationException) {
      record(null, CANCELLED, null)
      throw e
    } catch (e: Exception) {
      record(null, e.message ?: e::class.simpleName, null)
      throw e
    }
  record(response, null, runCatching { outcomeOf(response) }.getOrNull())
  return response
}

/** The [TrailblazeLog.TrailblazeDecisionRequestLog.errorMessage] of a request its caller stopped waiting for. */
internal const val CANCELLED = "cancelled: the caller stopped waiting for the answer"
