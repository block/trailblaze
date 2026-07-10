package xyz.block.trailblaze.android

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMProvider
import xyz.block.trailblaze.android.openai.OpenAiInstrumentationArgUtil
import xyz.block.trailblaze.http.DefaultDynamicLlmClient
import xyz.block.trailblaze.http.NoOpLlmClient
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import xyz.block.trailblaze.llm.config.BuiltInLlmModelRegistry
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.LlmAuthResolver
import xyz.block.trailblaze.llm.config.TrailblazeProjectYamlConfig
import xyz.block.trailblaze.util.Console

/**
 * Auto-resolves [TrailblazeLlmModel] and [LLMClient] from on-device configuration,
 * eliminating the need for manual wiring in [AndroidTrailblazeRule] subclasses.
 *
 * Model resolution order:
 * 1. `llm.defaults.model` from `trails/config/trailblaze.yaml` classpath resource
 * 2. `trailblaze.llm.default_model` instrumentation arg (set by the desktop host app)
 * 3. Auto-detect from the first provider whose auth token is present
 *
 * Client construction:
 * - Builds the appropriate [LLMClient] for the resolved model's provider using tokens
 *   from instrumentation args.
 */
object AndroidLlmClientResolver {

  private const val CONFIG_RESOURCE_PATH = TrailblazeConfigPaths.CONFIG_RESOURCE_PATH

  private val httpClient by lazy {
    TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
      timeoutInSeconds = 120,
      reverseProxyUrl = InstrumentationArgUtil.reverseProxyEndpoint(),
    )
  }

  /**
   * Priority order for auto-detecting the provider when no default model is specified.
   * The first provider whose auth token is present in instrumentation args wins.
   */
  private val PROVIDER_PRIORITY = listOf(
    TrailblazeLlmProvider.OPENAI,
    TrailblazeLlmProvider.OPEN_ROUTER,
    TrailblazeLlmProvider.ANTHROPIC,
    TrailblazeLlmProvider.GOOGLE,
    TrailblazeLlmProvider.OLLAMA,
  )

  /**
   * Resolves the first available model from the given [candidates], falling back to
   * [resolveModel] if none match.
   *
   * Each candidate is a model key like `"open_router/openai/gpt-4.1"` or `"openai/gpt-4.1"`.
   * The provider is inferred from the key and checked for an available auth token.
   *
   * Example:
   * ```
   * AndroidLlmClientResolver.resolveModel(
   *   "open_router/openai/gpt-4.1",  // prefer free tier
   *   "openai/gpt-4.1",              // fall back to paid
   * )
   * ```
   */
  fun resolveModel(vararg candidates: String): TrailblazeLlmModel {
    for (candidate in candidates) {
      val model = BuiltInLlmModelRegistry.find(candidate) ?: continue
      val tokenKey = LlmAuthResolver.resolve(model.trailblazeLlmProvider)
      if (model.trailblazeLlmProvider == TrailblazeLlmProvider.NONE ||
        model.trailblazeLlmProvider == TrailblazeLlmProvider.OLLAMA ||
        InstrumentationArgUtil.getInstrumentationArg(tokenKey) != null
      ) {
        Console.log("AndroidLlmClientResolver: Resolved model from candidate: $candidate")
        return model
      }
    }
    // Fall back to default resolution
    return resolveModel()
  }

  /**
   * Resolves the default [TrailblazeLlmModel].
   *
   * Resolution order:
   * 1. `llm.defaults.model` from `trails/config/trailblaze.yaml` classpath resource
   * 2. Explicit `trailblaze.llm.default_model` instrumentation arg (e.g., "openai/gpt-4.1")
   * 3. Auto-detect: tries [PROVIDER_PRIORITY] in order (OpenAI → OpenRouter → Anthropic →
   *    Google → Ollama) and returns the first model whose provider has an auth token available.
   */
  fun resolveModel(): TrailblazeLlmModel {
    // 1. On-device config file (assets/trailblaze/trailblaze.yaml)
    loadDefaultModelFromConfig()?.let { modelKey ->
      Console.log("AndroidLlmClientResolver: Resolved model from config resource: $modelKey")
      return findOrFallback(modelKey)
    }

    // 2. Instrumentation arg from host (e.g., "openai/gpt-4.1")
    val defaultModelArg =
      InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.DEFAULT_MODEL_ARG)
    if (defaultModelArg != null) {
      Console.log("AndroidLlmClientResolver: Resolved model from instrumentation arg: $defaultModelArg")
      return findOrFallback(defaultModelArg)
    }

    // 3. Auto-detect from available tokens
    for (provider in PROVIDER_PRIORITY) {
      val tokenKey = LlmAuthResolver.resolve(provider)
      if (InstrumentationArgUtil.getInstrumentationArg(tokenKey) != null) {
        // Prefer the provider's declared default_model, then fall back to first entry
        val defaultModelId = BuiltInLlmModelRegistry.defaultModelForProvider(provider)
        val model = if (defaultModelId != null) {
          BuiltInLlmModelRegistry.find("${provider.id}/$defaultModelId")
        } else {
          null
        } ?: BuiltInLlmModelRegistry.modelListForProvider(provider)?.entries?.firstOrNull()
        if (model != null) {
          Console.log(
            "AndroidLlmClientResolver: Auto-detected model ${model.modelId} " +
              "from available ${provider.id} token"
          )
          return model
        }
      }
    }

    error(
      buildString {
        appendLine("Could not resolve LLM model.")
        appendLine("Either:")
        appendLine("  - Add src/androidTest/resources/trails/config/trailblaze.yaml with llm.defaults.model set")
        appendLine("  - Pass an API key as instrumentation arg (e.g., trailblaze.llm.auth.token.openai)")
      }
    )
  }

  /**
   * Creates an [LLMClient] for the given [model] by looking up the provider's auth token
   * from instrumentation args and constructing the appropriate client.
   */
  fun createClient(model: TrailblazeLlmModel): LLMClient {
    // aiEnabled=false (recordings-only): never build a live client, so a no-recording step
    // fails loudly instead of silently falling back to AI.
    if (!InstrumentationArgUtil.isAiEnabled()) return NoOpLlmClient()
    if (model.trailblazeLlmProvider == TrailblazeLlmProvider.NONE) return NoOpLlmClient()
    // Koog 1.0.0: wrap the on-device Ktor HttpClient (with TLS / reverse-proxy plugin
    // for the instrumentation reverse-proxy endpoint) in a KoogHttpClient.Factory so
    // each LLM client gets a KoogHttpClient built from our configured engine.
    val httpClientFactory = KtorKoogHttpClient.Factory(baseClient = httpClient)
    val llmClients = buildMap<LLMProvider, LLMClient> {
      // Ollama (no token needed)
      val ollamaBaseUrl =
        InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.BASE_URL_ARG)
      put(
        LLMProvider.Ollama,
        OllamaClient(
          baseUrl = ollamaBaseUrl ?: "http://localhost:11434",
          httpClientFactory = httpClientFactory,
        ),
      )

      // OpenAI
      getToken(TrailblazeLlmProvider.OPENAI)?.let { key ->
        put(
          LLMProvider.OpenAI,
          OpenAILLMClient(
            apiKey = key,
            settings =
              OpenAIClientSettings(
                baseUrl = OpenAiInstrumentationArgUtil.getBaseUrlFromInstrumentationArg(),
              ),
            httpClientFactory = httpClientFactory,
          ),
        )
      }

      // Anthropic
      getToken(TrailblazeLlmProvider.ANTHROPIC)?.let { key ->
        put(
          LLMProvider.Anthropic,
          AnthropicLLMClient(
            apiKey = key,
            settings = AnthropicClientSettings(
              // Include the runtime-resolved model: it may be a findOrFallback() construction
              // (built-in YAML unreadable on-device → base map empty) or carry yaml overrides,
              // either of which would miss the built-in map's LLModel keys.
              modelVersionsMap = BuiltInLlmModelRegistry
                .koogModelVersionsMap(TrailblazeLlmProvider.ANTHROPIC, extraModels = listOf(model)),
            ),
            httpClientFactory = httpClientFactory,
          ),
        )
      }

      // OpenRouter
      getToken(TrailblazeLlmProvider.OPEN_ROUTER)?.let { key ->
        put(
          LLMProvider.OpenRouter,
          OpenRouterLLMClient(apiKey = key, httpClientFactory = httpClientFactory),
        )
      }

      // Custom providers with a base_url but no explicit `type:` in trailblaze.yaml
      // (PROVIDER_TYPE_ARG absent, so the openai_compatible factory below bows out).
      val koogProvider = model.trailblazeLlmProvider.toKoogLlmProvider()
      if (!containsKey(koogProvider)) {
        getToken(model.trailblazeLlmProvider)?.let { key ->
          val baseUrl =
            InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.BASE_URL_ARG)
          if (baseUrl != null) {
            put(
              koogProvider,
              OpenAILLMClient(
                apiKey = key,
                settings = OpenAIClientSettings(baseUrl = baseUrl),
                httpClientFactory = httpClientFactory,
              ),
            )
          }
        }
      }

      // Custom openai_compatible providers from the workspace `trailblaze.yaml` arrive via
      // instrumentation args and are rebuilt on-device by [OnDeviceOpenAICompatibleLlmClientFactory].
      // The host only emits PROVIDER_TYPE_ARG=openai_compatible for the *currently selected*
      // provider, so reaching a non-null result here means the user explicitly configured this
      // provider as openai_compatible — register/replace under the active provider id even when
      // it collides with a built-in (e.g. redefining `providers.anthropic` with a custom base_url).
      OnDeviceOpenAICompatibleLlmClientFactory
        .createOrNull(model, httpClient)
        ?.let { customClient -> put(koogProvider, customClient) }
    }

    return DefaultDynamicLlmClient(
      trailblazeLlmModel = model,
      llmClients = llmClients,
    ).createLlmClient()
  }

  private fun getToken(provider: TrailblazeLlmProvider): String? =
    InstrumentationArgUtil.getInstrumentationArg(LlmAuthResolver.resolve(provider))

  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
  )

  /** Reads `llm.defaults.model` from a classpath resource at `trails/config/trailblaze.yaml`. */
  private fun loadDefaultModelFromConfig(): String? = try {
    val content = (Thread.currentThread().contextClassLoader?.getResource(CONFIG_RESOURCE_PATH)
      ?: AndroidLlmClientResolver::class.java.classLoader?.getResource(CONFIG_RESOURCE_PATH))
      ?.readText() ?: return null
    val config = yaml.decodeFromString(TrailblazeProjectYamlConfig.serializer(), content)
    config.llm?.defaults?.model
  } catch (_: Exception) {
    null
  }

  /**
   * Finds a model by key from the built-in registry, falling back to a minimal model
   * with default capabilities if the registry YAML can't be loaded from the classpath.
   */
  private fun findOrFallback(modelKey: String): TrailblazeLlmModel {
    BuiltInLlmModelRegistry.find(modelKey)?.let { return it }

    // Built-in YAML not accessible — construct from the key
    val slashIndex = modelKey.indexOf('/')
    if (slashIndex > 0) {
      val providerId = modelKey.substring(0, slashIndex)
      val modelId = modelKey.substring(slashIndex + 1)
      val provider = TrailblazeLlmProvider.ALL_PROVIDERS
        .firstOrNull { it.id == providerId }
        ?: TrailblazeLlmProvider(id = providerId, display = providerId)
      Console.log(
        "AndroidLlmClientResolver: Built-in YAML not accessible, using fallback for $modelKey"
      )
      return TrailblazeLlmModel.fallback(provider, modelId)
    }

    error("Model '$modelKey' not found and cannot infer provider (use 'provider/model' format)")
  }
}
