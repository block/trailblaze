package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Resolves authentication tokens for all configured LLM providers.
 *
 * Token resolution priority (per provider):
 * 1. **Environment variable** (always wins — critical for CI where SSO isn't possible)
 * 2. **Custom token provider** (e.g., Databricks OAuth, keychains)
 * 3. **Built-in env var mapping** (fallback for standard providers without YAML config)
 *
 * Produces [ResolvedProviderAuth] instances consumed by:
 * - **Path A (Reverse Proxy):** URL prefix matching + auth header injection
 * - **Path B (Instrumentation Args):** Tokens passed to device as `trailblaze.llm.auth.token.<provider_id>`
 *
 * Note: Built-in model data (pricing, context lengths, capabilities) is already compiled into
 * the Android test APK via trailblaze-models. Custom models that use `inherits` inherit specs
 * from built-in models. Only the auth token and provider endpoint info need to be passed at
 * runtime — the model specs are already on-device.
 */
object LlmAuthResolver {

  /**
   * Prefix for dynamic per-provider auth token instrumentation args.
   * Format: `trailblaze.llm.auth.token.<provider_id>` = `<token>`
   */
  private const val AUTH_TOKEN_PREFIX = "trailblaze.llm.auth.token."

  /** Returns the instrumentation arg key for a provider's auth token. */
  fun resolve(provider: TrailblazeLlmProvider): String = "$AUTH_TOKEN_PREFIX${provider.id}"

  /** Returns the instrumentation arg key for a provider's auth token by ID. */
  fun resolve(providerId: String): String = "$AUTH_TOKEN_PREFIX$providerId"

  /** Instrumentation arg for the selected provider's base URL (for openai_compatible on device). */
  const val BASE_URL_ARG = "trailblaze.llm.provider.base_url"

  /** Instrumentation arg for the selected provider's type. */
  const val PROVIDER_TYPE_ARG = "trailblaze.llm.provider.type"

  /** Instrumentation arg for a custom chat completions path. */
  const val CHAT_COMPLETIONS_PATH_ARG = "trailblaze.llm.provider.chat_completions_path"

  /** Instrumentation arg for the default model key (e.g., "openai/gpt-4.1"). */
  const val DEFAULT_MODEL_ARG = "trailblaze.llm.default_model"

  /**
   * Resolves auth for all providers in the config.
   *
   * @param config The loaded LLM config
   * @param customTokenProviders Map of provider ID to token-resolving lambda (e.g., Databricks OAuth).
   *   These are checked AFTER env vars, so env vars always win.
   */
  fun resolveAll(
    config: LlmConfig,
    customTokenProviders: Map<String, () -> String?> = emptyMap(),
  ): Map<String, ResolvedProviderAuth> {
    if (config.providers.isEmpty()) {
      return resolveFromBuiltInProviders(customTokenProviders)
    }

    return config.providers
      .filter { (_, providerConfig) -> providerConfig.enabled != false }
      .map { (key, providerConfig) ->
        key to resolveProvider(key, providerConfig, customTokenProviders[key])
      }
      .toMap()
  }

  /**
   * Converts resolved auths to instrumentation arguments for Android.
   *
   * Produces flat key-value pairs using a dynamic convention keyed by provider ID:
   * - `trailblaze.llm.auth.token.<provider_id>` → `{token}` for each provider with a resolved token
   * - `trailblaze.llm.provider.type` → provider type (e.g., "openai_compatible")
   * - `trailblaze.llm.provider.base_url` → custom endpoint URL (for openai_compatible providers)
   * - `trailblaze.llm.provider.chat_completions_path` → custom path (if set)
   *
   * @param auths The resolved provider auths
   * @param selectedProviderId The currently selected provider ID (endpoint info is passed for it)
   */
  fun toInstrumentationArgs(
    auths: Map<String, ResolvedProviderAuth>,
    selectedProviderId: String? = null,
    defaultModel: String? = null,
  ): Map<String, String> = buildMap {
    // Pass each provider's token keyed by provider ID
    for ((providerId, auth) in auths) {
      val token = auth.token ?: continue
      put(resolve(providerId), token)
    }

    // For the selected provider, pass endpoint info as flat args (for standalone device execution)
    if (selectedProviderId != null) {
      val selectedAuth = auths[selectedProviderId]
      val config = selectedAuth?.providerConfig
      if (config != null) {
        config.type?.let { put(PROVIDER_TYPE_ARG, it.name.lowercase()) }
        config.baseUrl?.let { put(BASE_URL_ARG, it) }
        config.chatCompletionsPath?.let { put(CHAT_COMPLETIONS_PATH_ARG, it) }
      }
    }

    // Pass the default model so the device can auto-resolve without config files
    defaultModel?.let { put(DEFAULT_MODEL_ARG, it) }
  }

  private fun resolveProvider(
    key: String,
    config: LlmProviderConfig,
    customTokenProvider: (() -> String?)?,
  ): ResolvedProviderAuth {
    val envVarKey = config.auth.envVar
      ?: LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(
        TrailblazeLlmProvider.ALL_PROVIDERS.firstOrNull { it.id == key }
          ?: TrailblazeLlmProvider(id = key, display = key),
      )

    // Priority: env var > custom provider > null
    val token = envVarKey?.let { System.getenv(it) }
      ?: customTokenProvider?.invoke()

    return ResolvedProviderAuth(
      providerId = key,
      token = token,
      envVarKey = envVarKey,
      headers = config.headers,
      baseUrl = config.baseUrl,
      providerConfig = config,
    )
  }

  /**
   * Fallback: when no YAML config exists, resolve tokens for all built-in providers
   * using the hardcoded env var mapping and built-in provider YAML configs.
   */
  private fun resolveFromBuiltInProviders(
    customTokenProviders: Map<String, () -> String?>,
  ): Map<String, ResolvedProviderAuth> {
    return TrailblazeLlmProvider.ALL_PROVIDERS
      .filter { it != TrailblazeLlmProvider.MCP_SAMPLING }
      .associate { provider ->
        val builtIn = BuiltInLlmModelRegistry.configForProvider(provider)
        val envVarKey = LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(provider)
        val token = envVarKey?.let { System.getenv(it) }
          ?: customTokenProviders[provider.id]?.invoke()
        val providerConfig = builtIn?.let {
          LlmProviderConfig(
            type = it.type,
            description = it.description,
            baseUrl = it.baseUrl,
            chatCompletionsPath = it.chatCompletionsPath,
            headers = it.headers,
            auth = it.auth,
            models = it.models,
          )
        }
        provider.id to ResolvedProviderAuth(
          providerId = provider.id,
          token = token,
          envVarKey = envVarKey,
          headers = builtIn?.headers ?: emptyMap(),
          baseUrl = builtIn?.baseUrl,
          providerConfig = providerConfig,
        )
      }
  }
}
