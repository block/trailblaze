package xyz.block.trailblaze.decision

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import java.net.URI
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * A [DecisionEngine] for any server that speaks the `POST /v1/systemone` decision contract: the
 * hosted API that defined it, or a self-hosted server implementing the same contract. The request
 * and response are the [DecisionRequest] and [DecisionResponse] bodies unchanged, so this class
 * only moves bytes.
 *
 * `429` (rate limited), `529` (overloaded) and other `5xx` are retried with exponential backoff, as
 * the contract asks; any other failure (`401` bad key, `422` invalid request) throws at once.
 *
 * @param baseUrl the server root; `/v1/systemone` is appended.
 * @param apiKey sent as a bearer token when non-null. Self-hosted servers often need none.
 */
class SystemOneDecisionEngine(
  private val httpClient: HttpClient,
  baseUrl: String,
  private val apiKey: String? = null,
  private val maxAttempts: Int = 4,
  private val initialBackoffMs: Long = 1_000,
) : DecisionEngine {

  private val endpoint = endpointFor(baseUrl)

  /** The server as logged: scheme, host, port and path only, so no credential in the URL reaches a log. */
  override val name: String = "systemone:" + loggableUrl(baseUrl)

  override suspend fun decide(request: DecisionRequest): DecisionResponse {
    val body = json.encodeToString(DecisionRequest.serializer(), request)
    var backoffMs = initialBackoffMs
    for (attempt in 1..maxAttempts) {
      val response =
        httpClient.post(endpoint) {
          apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
          setBody(TextContent(body, ContentType.Application.Json))
        }
      val status = response.status.value
      val text = response.bodyAsText()
      if (status in 200..299) return json.decodeFromString(DecisionResponse.serializer(), text)
      val retryable = status == 429 || status in 500..599
      if (!retryable || attempt == maxAttempts) {
        throw DecisionEngineException(status, "HTTP $status from $name: ${text.take(300)}")
      }
      delay(backoffMs)
      backoffMs *= 2
    }
    error("unreachable: maxAttempts must be at least 1, was $maxAttempts")
  }

  companion object {
    const val PATH = "/v1/systemone"

    /** [baseUrl] with [PATH] appended to its path, ahead of any query or fragment it carries. */
    internal fun endpointFor(baseUrl: String): String {
      val url = baseUrl.trim()
      val cut = url.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: url.length
      return url.substring(0, cut).trimEnd('/') + PATH + url.substring(cut)
    }

    internal fun loggableUrl(url: String): String =
      runCatching { URI(url.trim()).let { URI(it.scheme, null, it.host, it.port, it.path, null, null).toString() } }
        .getOrDefault("(unparseable url)").trimEnd('/')
    private val json = Json {
      ignoreUnknownKeys = true
      // A noul question without criteria omits the key rather than sending null.
      explicitNulls = false
    }
  }
}

/** A decision engine answered with a non-success HTTP status. */
class DecisionEngineException(val status: Int, message: String) : RuntimeException(message)
