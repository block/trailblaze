package xyz.block.trailblaze.decision

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Test

/** The engine against the documented `/v1/systemone` request and response examples. */
class SystemOneDecisionEngineTest {

  private val documentedResponse =
    """
    {
      "model": "jev-1.13.0",
      "answers": {
        "is_urgent": { "type": "noul", "noul": 0.95 },
        "team": {
          "type": "choice",
          "choice": "billing",
          "probabilities": { "billing": 0.88, "technical": 0.12, "sales": 0.0 },
          "confidence": 0.81
        },
        "anger": {
          "type": "score",
          "score": 1.05,
          "legend": { "0": "Calm", "1": "Frustrated", "2": "Very angry" },
          "probabilities": { "0": 0.0, "1": 0.95, "2": 0.05 },
          "confidence": 0.92
        }
      },
      "usage": { "input_tokens": 296, "output_tokens": 20 }
    }
    """
      .trimIndent()

  private val request =
    DecisionRequest(
      state = "Help! My payouts have been failing for 3 days.",
      model = "jev-latest",
      questions =
        linkedMapOf(
          "is_urgent" to DecisionQuestion.noul("Does this convey urgency?"),
          "team" to
            DecisionQuestion.choice(
              "Which team should handle this?",
              linkedMapOf("billing" to "Payments", "technical" to "Bugs", "sales" to "Buying"),
            ),
          "anger" to
            DecisionQuestion.score("How upset is the customer?", listOf("Calm", "Frustrated", "Very angry")),
        ),
    )

  private class Server(private val replies: List<Pair<HttpStatusCode, String>>) {
    val requests = mutableListOf<HttpRequestData>()
    val client =
      HttpClient(
        MockEngine { req ->
          requests += req
          val (status, body) = replies[(requests.size - 1).coerceAtMost(replies.size - 1)]
          respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
      )
  }

  private fun engine(server: Server, apiKey: String? = "k-123") =
    SystemOneDecisionEngine(server.client, "https://decisions.example.com/", apiKey, initialBackoffMs = 0)

  @Test
  fun `posts the contract body to the systemone path with a bearer key`() {
    val server = Server(listOf(HttpStatusCode.OK to documentedResponse))
    runBlocking { engine(server).decide(request) }

    val sent = server.requests.single()
    assertEquals("https://decisions.example.com/v1/systemone", sent.url.toString())
    assertEquals("Bearer k-123", sent.headers[HttpHeaders.Authorization])
    val expected =
      """
      {"state":"Help! My payouts have been failing for 3 days.","model":"jev-latest","questions":{
      "is_urgent":{"type":"noul","instructions":"Does this convey urgency?"},
      "team":{"type":"choice","instructions":"Which team should handle this?","criteria":{"billing":"Payments","technical":"Bugs","sales":"Buying"}},
      "anger":{"type":"score","instructions":"How upset is the customer?","criteria":["Calm","Frustrated","Very angry"]}}}
      """
        .trimIndent()
        .replace("\n", "")
    assertEquals(
      Json.parseToJsonElement(expected),
      Json.parseToJsonElement((sent.body as TextContent).text),
    )
  }

  @Test
  fun `the systemone path goes into the url's path, ahead of any query`() {
    assertEquals("https://host.test/v1/systemone?key=x", SystemOneDecisionEngine.endpointFor("https://host.test/?key=x"))
    assertEquals("https://host.test/base/v1/systemone", SystemOneDecisionEngine.endpointFor(" https://host.test/base/ "))
    assertEquals("https://host.test/v1/systemone#f", SystemOneDecisionEngine.endpointFor("https://host.test#f"))
  }

  @Test
  fun `its name shows the server but never credentials or a query in the url`() {
    assertEquals("https://api.example.test/v1", SystemOneDecisionEngine.loggableUrl("https://user:secret@api.example.test/v1/?key=abc"))
    assertEquals("http://localhost:9", SystemOneDecisionEngine.loggableUrl(" http://localhost:9/ "))
    assertEquals("(unparseable url)", SystemOneDecisionEngine.loggableUrl("http://bad host"))
  }

  @Test
  fun `a server without keys gets no authorization header`() {
    val server = Server(listOf(HttpStatusCode.OK to documentedResponse))
    runBlocking { engine(server, apiKey = null).decide(request) }

    assertNull(server.requests.single().headers[HttpHeaders.Authorization])
  }

  @Test
  fun `decodes every documented answer type`() {
    val server = Server(listOf(HttpStatusCode.OK to documentedResponse))
    val response = runBlocking { engine(server).decide(request) }

    assertEquals("jev-1.13.0", response.model)
    assertEquals(0.95, response.answers.getValue("is_urgent").noul)
    val team = response.answers.getValue("team")
    assertEquals("billing", team.choice)
    assertEquals(0.88, team.probabilities!!.getValue("billing"))
    val anger = response.answers.getValue("anger")
    assertEquals(1.05, anger.score)
    assertEquals("Frustrated", anger.legend!!.getValue("1"))
    assertEquals(DecisionUsage(296, 20), response.usage)
  }

  @Test
  fun `rate limits and overloads are retried`() {
    val server =
      Server(
        listOf(
          HttpStatusCode.TooManyRequests to "{}",
          HttpStatusCode(529, "Overloaded") to "{}",
          HttpStatusCode.OK to documentedResponse,
        )
      )
    val response = runBlocking { engine(server).decide(request) }

    assertEquals(3, server.requests.size)
    assertEquals("jev-1.13.0", response.model)
  }

  @Test
  fun `an invalid request fails at once with its status`() {
    val server = Server(listOf(HttpStatusCode.UnprocessableEntity to """{"detail":"bad"}"""))
    val e = assertFailsWith<DecisionEngineException> { runBlocking { engine(server).decide(request) } }

    assertEquals(422, e.status)
    assertEquals(1, server.requests.size)
  }

  @Test
  fun `an error names the server without the credentials in its url`() {
    val server = Server(listOf(HttpStatusCode.Unauthorized to """{"detail":"bad key"}"""))
    val engine = SystemOneDecisionEngine(server.client, "https://user:secret@decisions.example.com/?key=abc", null)
    val e = assertFailsWith<DecisionEngineException> { runBlocking { engine.decide(request) } }

    assertEquals("HTTP 401 from systemone:https://decisions.example.com: {\"detail\":\"bad key\"}", e.message)
  }

  @Test
  fun `a server that stays overloaded fails after the last attempt`() {
    val server = Server(listOf(HttpStatusCode(529, "Overloaded") to "{}"))
    val e = assertFailsWith<DecisionEngineException> { runBlocking { engine(server).decide(request) } }

    assertEquals(529, e.status)
    assertEquals(4, server.requests.size)
  }
}
