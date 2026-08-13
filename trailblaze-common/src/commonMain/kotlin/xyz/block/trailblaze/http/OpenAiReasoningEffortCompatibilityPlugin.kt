package xyz.block.trailblaze.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * OpenAI's `/v1/chat/completions` rejects any GPT-5.6-family request that carries function tools
 * alongside an active `reasoning_effort`: "Function tools with reasoning_effort are not supported…
 * set reasoning_effort to 'none'". Koog injects `reasoning_effort` itself, so no Trailblaze-side
 * option can prevent it — the only place to satisfy the constraint is the wire.
 *
 * Do not drop this when syncing: without it every function-tool call against a GPT-5.6-family model
 * 400s, which takes the whole agent loop down.
 *
 * Keyed on the request body's `model` because that is where the OpenAI provider carries it; a plugin
 * that keys on the endpoint path only fires for providers that route the model through the URL.
 */
object OpenAiReasoningEffortCompatibilityPlugin :
  HttpClientPlugin<Unit, OpenAiReasoningEffortCompatibilityPlugin> {

  override val key: AttributeKey<OpenAiReasoningEffortCompatibilityPlugin> =
    AttributeKey("OpenAiReasoningEffortCompatibility")

  private const val REASONING_EFFORT = "reasoning_effort"
  private const val NONE = "none"

  // Both spellings occur: the OpenAI catalog names models `gpt-5.6-*`, deployment ids use `gpt-5-6`.
  private val REASONING_EFFORT_INCOMPATIBLE_MODEL = Regex("gpt-5[.\\-]6", RegexOption.IGNORE_CASE)

  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  override fun prepare(block: Unit.() -> Unit): OpenAiReasoningEffortCompatibilityPlugin = this

  override fun install(
    plugin: OpenAiReasoningEffortCompatibilityPlugin,
    scope: HttpClient,
  ) {
    scope.plugin(HttpSend).intercept { request ->
      val body = request.body
      if (body is TextContent) {
        pinReasoningEffortForFunctionTools(body.text)?.let { pinnedBody ->
          request.setBody(TextContent(pinnedBody, ContentType.Application.Json))
        }
      }
      execute(request)
    }
  }

  /**
   * Returns the request body with `reasoning_effort` pinned to `none`, or null when the request must
   * go out byte-identical — anything that is not a GPT-5.6-family chat completion carrying function
   * tools, including a body that is already pinned.
   */
  internal fun pinReasoningEffortForFunctionTools(requestBody: String): String? {
    val body =
      try {
        json.parseToJsonElement(requestBody) as? JsonObject
      } catch (_: Exception) {
        null
      } ?: return null

    val model = (body["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (!REASONING_EFFORT_INCOMPATIBLE_MODEL.containsMatchIn(model)) return null

    val tools = body["tools"] as? JsonArray
    if (tools.isNullOrEmpty()) return null

    val current = body[REASONING_EFFORT] as? JsonPrimitive
    if (current?.isString == true && current.content == NONE) return null

    return buildJsonObject {
        body.forEach { (key, value) -> if (key != REASONING_EFFORT) put(key, value) }
        put(REASONING_EFFORT, JsonPrimitive(NONE))
      }
      .toString()
  }
}
