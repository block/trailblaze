package xyz.block.trailblaze.android

import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.LlmProviderType
import xyz.block.trailblaze.util.Console

/**
 * Builds an on-device [OpenAILLMClient] for a custom `openai_compatible` provider that
 * crossed the host→device boundary via instrumentation args. The on-device mirror of the
 * host-side `OpenAICompatibleLlmClientFactory` in `trailblaze-host`.
 *
 * The host emits these instrumentation args for the currently selected provider (see
 * [LlmAuthResolver.toInstrumentationArgs]):
 *
 * - [LlmAuthResolver.PROVIDER_TYPE_ARG] — must equal `openai_compatible` for this factory to fire
 * - [LlmAuthResolver.BASE_URL_ARG] — the gateway endpoint
 * - [LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG] — optional custom path; supports `{{model_id}}` substitution
 * - [LlmAuthResolver.HEADERS_ARG] — optional JSON-encoded `Map<String, String>` of static request headers
 * - [LlmAuthResolver.AUTH_REQUIRED_ARG] — `"true"` (default) or `"false"`
 * - `trailblaze.llm.auth.token.<provider_id>` — the auth token (required unless `auth.required: false`)
 *
 * Tokens cross the boundary through the same instrumentation-arg channel that built-in
 * OpenAI tokens use, so [InstrumentationArgUtil]'s masking-on-log applies uniformly to
 * custom-provider tokens just as it does to built-in ones.
 */
object OnDeviceOpenAICompatibleLlmClientFactory {

  /**
   * Returns an [OpenAILLMClient] configured for the active provider when the host emitted
   * `openai_compatible` metadata for it, or `null` when no such config arrived (caller
   * falls back to its built-in NONE/Ollama/OpenAI registry).
   *
   * Throws [IllegalStateException] when the provider is `openai_compatible` AND auth is
   * required AND no token instrumentation arg arrived — surfacing the cause clearly,
   * before `DefaultDynamicLlmClient.createLlmClient()` would otherwise crash several
   * frames later with the generic "Unsupported provider".
   *
   * Thin wrapper over [resolveOrNull] — the pure decision lives in that function so unit
   * tests can exercise every branch hermetically without a Ktor [HttpClient].
   *
   * @param trailblazeLlmModel the active model (its provider id is used to look up the
   *   per-provider auth-token instrumentation arg and to substitute `{{model_id}}` in
   *   the optional `chat_completions_path`)
   * @param baseHttpClient the cached HTTP client; the returned client wraps it via
   *   [HttpClient.config] with the parsed static headers, so this baseline client is
   *   shared (no per-call HTTP client allocation when there are no static headers)
   */
  fun createOrNull(
    trailblazeLlmModel: TrailblazeLlmModel,
    baseHttpClient: HttpClient,
    argReader: (String) -> String? = InstrumentationArgUtil::getInstrumentationArg,
  ): OpenAILLMClient? {
    val resolved = resolveOrNull(trailblazeLlmModel, argReader) ?: return null
    val configuredClient = if (resolved.headers.isNotEmpty()) {
      baseHttpClient.config {
        defaultRequest {
          resolved.headers.forEach { (k, v) -> header(k, v) }
        }
      }
    } else {
      baseHttpClient
    }
    val settings = if (resolved.chatCompletionsPath != null) {
      OpenAIClientSettings(baseUrl = resolved.baseUrl, chatCompletionsPath = resolved.chatCompletionsPath)
    } else {
      OpenAIClientSettings(baseUrl = resolved.baseUrl)
    }
    Console.log("Registered on-device openai_compatible client for provider '${resolved.providerId}'")
    return OpenAILLMClient(
      apiKey = resolved.apiKey,
      settings = settings,
      baseClient = configuredClient,
    )
  }

  /**
   * Pure decision: given the active model and a reader for instrumentation args, return
   * the resolved openai_compatible config — or `null` when this factory should bow out
   * (no `openai_compatible` type emitted, or no `base_url`). Throws [IllegalStateException]
   * when the provider is openai_compatible but a required auth token is missing.
   *
   * No I/O, no `HttpClient`, no `OpenAILLMClient` construction. Every branch is reachable
   * from a JVM unit test by passing a fixed `(String) -> String?` map.
   */
  internal fun resolveOrNull(
    trailblazeLlmModel: TrailblazeLlmModel,
    argReader: (String) -> String?,
  ): ResolvedOpenAICompatibleConfig? {
    val providerType = argReader(LlmAuthResolver.PROVIDER_TYPE_ARG)
    val providerBaseUrl = argReader(LlmAuthResolver.BASE_URL_ARG)
    if (
      providerType?.equals(LlmProviderType.OPENAI_COMPATIBLE.name, ignoreCase = true) != true ||
      providerBaseUrl == null
    ) {
      return null
    }

    val activeProvider = trailblazeLlmModel.trailblazeLlmProvider
    val token = argReader(LlmAuthResolver.resolve(activeProvider))
    val authRequired = argReader(LlmAuthResolver.AUTH_REQUIRED_ARG) != "false"
    if (authRequired && token.isNullOrBlank()) {
      error(
        "Custom openai_compatible provider '${activeProvider.id}' requires an auth token, " +
          "but no token instrumentation arg was received on-device. " +
          "Set the env var named in `auth.env_var` before launching the daemon, " +
          "or declare `auth.required: false` in trailblaze.yaml if the provider does not require auth.",
      )
    }
    val resolvedKey = if (authRequired) token!! else token.orEmpty()

    // `{{model_id}}` substitution mirrors the host's OpenAICompatibleLlmClientFactory.
    val chatPath = argReader(LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG)
      ?.replace("{{model_id}}", trailblazeLlmModel.modelId)
    val parsedHeaders = parseHeaders(activeProvider.id, argReader)
    return ResolvedOpenAICompatibleConfig(
      providerId = activeProvider.id,
      baseUrl = providerBaseUrl,
      chatCompletionsPath = chatPath,
      apiKey = resolvedKey,
      headers = parsedHeaders,
    )
  }

  /**
   * Pure-data outcome of [resolveOrNull]. Carries everything [createOrNull] needs to
   * build an [OpenAILLMClient]; carries nothing else. Equality + property access lets
   * unit tests assert resolved values directly without instantiating a Ktor client.
   *
   * [toString] is overridden to redact [apiKey] — without this override, a stray
   * `Console.log("$resolved")` (or an exception that interpolates the receiver, or an
   * IDE debug inspector) would leak the raw provider token into the daemon log file at
   * `~/.trailblaze/desktop-logs/trailblaze.log`, which persists. Built-in tokens that
   * cross the same boundary get masked via [InstrumentationArgUtil]'s log machinery;
   * this data class lives one careless interpolation away from bypassing that
   * protection, so we redact at the source.
   */
  internal data class ResolvedOpenAICompatibleConfig(
    val providerId: String,
    val baseUrl: String,
    val chatCompletionsPath: String?,
    val apiKey: String,
    val headers: Map<String, String>,
  ) {
    override fun toString(): String =
      "ResolvedOpenAICompatibleConfig(" +
        "providerId=$providerId, " +
        "baseUrl=$baseUrl, " +
        "chatCompletionsPath=$chatCompletionsPath, " +
        "apiKey=<redacted>, " +
        "headers=$headers" +
        ")"
  }

  private fun parseHeaders(providerId: String, argReader: (String) -> String?): Map<String, String> =
    argReader(LlmAuthResolver.HEADERS_ARG)
      ?.let { headersJson ->
        runCatching { Json.decodeFromString<Map<String, String>>(headersJson) }
          .onFailure { e ->
            Console.log(
              "Failed to parse openai_compatible HEADERS_ARG JSON for provider " +
                "'$providerId': ${e.message}. Proceeding with no static headers.",
            )
          }
          .getOrNull()
      }
      ?: emptyMap()
}
