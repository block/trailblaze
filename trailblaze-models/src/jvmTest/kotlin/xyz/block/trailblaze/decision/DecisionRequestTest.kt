package xyz.block.trailblaze.decision

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId

class DecisionRequestTest {

  private val request =
    DecisionRequest(
      state = "the screen",
      model = "any-model",
      questions =
        linkedMapOf(
          "ref" to DecisionQuestion.choice("Which element?", linkedMapOf("w34" to "Timer", "none" to "None")),
          "done" to DecisionQuestion.noul("Is it done?", yes = "Finished", no = "More to do"),
        ),
    )

  private val response =
    DecisionResponse(
      model = "any-model-1",
      answers =
        mapOf(
          "ref" to
            DecisionAnswer(
              DecisionQuestionType.CHOICE,
              choice = "w34",
              probabilities = mapOf("w34" to 0.9, "none" to 0.1),
              confidence = 0.8,
            ),
          "done" to DecisionAnswer(DecisionQuestionType.NOUL, noul = 0.2),
        ),
      usage = DecisionUsage(100, 2),
    )

  private class FixedEngine(
    private val price: (DecisionResponse) -> DecisionCost? = { null },
    private val answer: () -> DecisionResponse,
  ) : DecisionEngine {
    override val name = "fixed"
    var calls = 0

    override fun costOf(response: DecisionResponse): DecisionCost? = price(response)

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
      calls++
      return answer()
    }
  }

  private val trace = TraceId.generate(TraceId.Companion.TraceOrigin.LLM)

  private fun <T> logged(block: suspend () -> T): Pair<Result<T>, List<TrailblazeLog>> {
    val logs = mutableListOf<TrailblazeLog>()
    val result = runCatching {
      runBlocking { withContext(DecisionLogContext(SessionId("s1"), trace) { logs += it }) { block() } }
    }
    return result to logs
  }

  @Test
  fun `criteria take the contract's per-type shapes`() {
    val json = TrailblazeJson.createTrailblazeJsonInstance(emptyMap()).encodeToJsonElement(DecisionRequest.serializer(), request).jsonObject
    val questions = json.getValue("questions").jsonObject
    assertEquals("choice", questions.getValue("ref").jsonObject.getValue("type").toString().trim('"'))
    assertTrue(questions.getValue("ref").jsonObject.getValue("criteria") is JsonObject)
    assertEquals(
      mapOf("true" to "Finished", "false" to "More to do"),
      request.questions.getValue("done").noulMeanings,
    )
    assertEquals(
      listOf("low", "high"),
      DecisionQuestion.score("How much?", listOf("low", "high")).scoreLevels,
    )
  }

  @Test
  fun `question sizes outside the contract are rejected`() {
    assertFailsWith<IllegalArgumentException> { DecisionQuestion.choice("?", emptyMap()) }
    assertFailsWith<IllegalArgumentException> { DecisionQuestion.score("?", listOf("only")) }
    assertFailsWith<IllegalArgumentException> {
      DecisionQuestion.score("?", (1..11).map { "level $it" })
    }
  }

  @Test
  fun `confidence reads zero for a uniform guess and one for certainty`() {
    assertEquals(0.0, DecisionConfidence.of(listOf(0.25, 0.25, 0.25, 0.25)), 1e-9)
    assertEquals(1.0, DecisionConfidence.of(listOf(1.0, 0.0, 0.0)), 1e-9)
    // (3 * 0.93 - 1) / 2
    assertEquals(0.895, DecisionConfidence.of(listOf(0.93, 0.05, 0.02)), 1e-9)
  }

  @Test
  fun `a decision log survives the session log round trip`() {
    val (_, logs) = logged { FixedEngine { response }.decideLogged(request) { "tapped w34" } }
    val json = TrailblazeJson.createTrailblazeJsonInstance(emptyMap())
    val encoded = json.encodeToString(TrailblazeLog.serializer(), logs.single())
    val decoded = json.decodeFromString(TrailblazeLog.serializer(), encoded)

    assertEquals(logs.single(), decoded)
    assertTrue("TrailblazeDecisionRequestLog" in encoded)
  }

  @Test
  fun `decideLogged logs the request, the answers and the caller's outcome`() {
    val (result, logs) = logged { FixedEngine { response }.decideLogged(request) { "tapped w34" } }

    assertEquals(response, result.getOrThrow())
    val log = logs.single() as TrailblazeLog.TrailblazeDecisionRequestLog
    assertEquals("fixed", log.engine)
    assertEquals(request, log.request)
    assertEquals(response, log.response)
    assertEquals("tapped w34", log.outcome)
    assertEquals(SessionId("s1"), log.session)
    assertEquals(trace, log.traceId)
    assertEquals("ref: w34 (confidence 0.80)\ndone: yes 0.20\noutcome: tapped w34", log.summary())
  }

  @Test
  fun `an engine failure is logged and rethrown`() {
    val (result, logs) =
      logged { FixedEngine { throw IllegalStateException("HTTP 503") }.decideLogged(request) }

    assertEquals("HTTP 503", result.exceptionOrNull()?.message)
    val log = logs.single() as TrailblazeLog.TrailblazeDecisionRequestLog
    assertNull(log.response)
    assertEquals("HTTP 503", log.errorMessage)
  }

  @Test
  fun `a request its caller stops waiting for is logged as cancelled`() {
    val hanging = object : DecisionEngine {
      override val name = "hanging"
      override suspend fun decide(request: DecisionRequest): DecisionResponse = awaitCancellation()
    }
    val (result, logs) = logged { withTimeoutOrNull(10) { hanging.decideLogged(request) } }

    assertNull(result.getOrThrow())
    val log = logs.single() as TrailblazeLog.TrailblazeDecisionRequestLog
    assertNull(log.response)
    assertEquals(CANCELLED, log.errorMessage)
  }

  @Test
  fun `a failing outcome or sink never fails the decision`() {
    val (result, logs) =
      logged { FixedEngine { response }.decideLogged(request) { error("boom") } }
    assertEquals(response, result.getOrThrow())
    assertNull((logs.single() as TrailblazeLog.TrailblazeDecisionRequestLog).outcome)

    val throwingSink = DecisionLogContext(SessionId("s1"), trace) { error("disk full") }
    val answered = runBlocking {
      withContext(throwingSink) { FixedEngine { response }.decideLogged(request) }
    }
    assertEquals(response, answered)
  }

  @Test
  fun `without a log context the engine is simply called`() {
    val engine = FixedEngine { response }
    assertEquals(response, runBlocking { engine.decideLogged(request) })
    assertEquals(1, engine.calls)
  }

  @Test
  fun `an engine that prices its answer has the cost logged beside it`() {
    val priced = FixedEngine(price = { DecisionCost(it.usage!!.inputTokens * 1e-6, it.usage!!.outputTokens * 1e-5) }) { response }
    val (_, logs) = logged { priced.decideLogged(request) }

    val cost = (logs.single() as TrailblazeLog.TrailblazeDecisionRequestLog).cost!!
    assertEquals(100 * 1e-6 + 2 * 1e-5, cost.totalCost, 1e-12)
  }

  @Test
  fun `an unpriced or failing price leaves the cost unknown and the decision intact`() {
    val (_, unpriced) = logged { FixedEngine { response }.decideLogged(request) }
    val (result, failing) = logged { FixedEngine(price = { error("no price list") }) { response }.decideLogged(request) }

    assertEquals(null, (unpriced.single() as TrailblazeLog.TrailblazeDecisionRequestLog).cost)
    assertEquals(null, (failing.single() as TrailblazeLog.TrailblazeDecisionRequestLog).cost)
    assertEquals(response, result.getOrThrow())
  }
}
