package xyz.block.trailblaze.llm.config

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Hermetic integration test for the full host-side openai_compatible pipeline:
 *
 * 1. A workspace `trails/config/trailblaze.yaml` declaring a custom `openai_compatible`
 *    provider is written to a temp directory.
 * 2. [LlmConfigLoader.load] discovers and parses it (no network — yaml is local).
 * 3. [LlmAuthResolver.resolveAll] resolves the provider's auth (using a fake env var so
 *    the test is deterministic even on dev machines without `OPENAI_API_KEY` set).
 * 4. [LlmAuthResolver.toInstrumentationArgs] produces the final flat map that the host
 *    passes to `am instrument -e` for on-device delivery.
 *
 * The assertion targets the exact keys the on-device APK reads back in
 * `AndroidStandaloneServerTest.getDynamicLlmClient` — `PROVIDER_TYPE_ARG`, `BASE_URL_ARG`,
 * `CHAT_COMPLETIONS_PATH_ARG`, `HEADERS_ARG`, `AUTH_REQUIRED_ARG`, the per-provider
 * auth-token key, and `DEFAULT_MODEL_ARG`. If any of these stop being emitted by the host
 * (or change key name), this test breaks before a real device run would surface the
 * regression as "Unsupported provider".
 *
 * No HTTP calls, no daemon, no LLM API contact. Runs in milliseconds.
 */
class OpenAiCompatibleWorkspaceConfigTest {

  private lateinit var tempProjectDir: File
  private lateinit var tempUserHomeDir: File

  @Before
  fun setUp() {
    tempProjectDir = Files.createTempDirectory("trailblaze-cfg-test-project").toFile()
    tempUserHomeDir = Files.createTempDirectory("trailblaze-cfg-test-home").toFile()
    val configDir = File(tempProjectDir, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText(
      """
      llm:
        providers:
          test_ai:
            type: openai_compatible
            base_url: "https://api.example.com"
            chat_completions_path: "v1/chat/completions"
            headers:
              X-Tenant: "acme"
            auth:
              env_var: TEST_AI_FAKE_ENV_VAR_DOES_NOT_EXIST
              required: true
            models:
              - id: gpt-4o-mini
                inherits: openai/gpt-4o-mini
        defaults:
          model: test_ai/gpt-4o-mini
      """.trimIndent(),
    )
  }

  @After
  fun tearDown() {
    tempProjectDir.deleteRecursively()
    tempUserHomeDir.deleteRecursively()
  }

  @Test
  fun `workspace yaml flows end-to-end into openai_compatible instrumentation args`() {
    val loadedConfig = LlmConfigLoader.load(
      userHomeDir = tempUserHomeDir,
      projectDir = tempProjectDir,
    )

    val testAiConfig = loadedConfig.providers["test_ai"]
    assertNotNull("workspace yaml didn't load — discovery walk-up may be broken", testAiConfig)
    assertEquals(LlmProviderType.OPENAI_COMPATIBLE, testAiConfig!!.type)
    assertEquals("https://api.example.com", testAiConfig.baseUrl)

    // Stub the env-var resolution via the customTokenProviders seam — the real-prod path
    // reads System.getenv. We use a bogus env var name in the yaml so the test stays
    // hermetic on any dev machine (a real OPENAI_API_KEY won't bleed in), then inject a
    // deterministic token through customTokenProviders so we can assert it appears in the
    // emitted args map.
    val auths = LlmAuthResolver.resolveAll(
      loadedConfig,
      customTokenProviders = mapOf("test_ai" to { "fake-resolved-token-not-real" }),
    )
    val testAiAuth = auths["test_ai"]
    assertNotNull("LlmAuthResolver.resolveAll dropped the test_ai provider", testAiAuth)

    // Simulate the host's "user selected test_ai/gpt-4o-mini" decision via the pure helper.
    val selected = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "test_ai",
      persistedModelId = "gpt-4o-mini",
    )
    assertEquals("test_ai", selected.selectedProviderId)
    assertEquals("test_ai/gpt-4o-mini", selected.defaultModel)

    val args = LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = selected.selectedProviderId,
      defaultModel = selected.defaultModel,
    )

    // These are the exact keys AndroidStandaloneServerTest.getDynamicLlmClient reads back.
    assertEquals("openai_compatible", args[LlmAuthResolver.PROVIDER_TYPE_ARG])
    assertEquals("https://api.example.com", args[LlmAuthResolver.BASE_URL_ARG])
    assertEquals("v1/chat/completions", args[LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG])
    assertEquals("true", args[LlmAuthResolver.AUTH_REQUIRED_ARG])
    assertEquals("test_ai/gpt-4o-mini", args[LlmAuthResolver.DEFAULT_MODEL_ARG])

    val headersJson = args[LlmAuthResolver.HEADERS_ARG]
    assertNotNull("HEADERS_ARG should be emitted when provider declares static headers", headersJson)
    assertTrue(
      "HEADERS_ARG JSON missing the configured X-Tenant entry: $headersJson",
      headersJson!!.contains("X-Tenant") && headersJson.contains("acme"),
    )

    // Pin the per-provider auth-token arg too. Without this, a regression in
    // [LlmAuthResolver.toInstrumentationArgs]'s `for ((providerId, auth) in auths)` loop
    // (an accidental refactor, a guard added that skips the put, etc.) would leave this
    // integration test passing while every on-device custom provider then fails with
    // "auth token missing" or "Unsupported provider" — the exact bug class this PR was
    // built to eliminate.
    assertEquals(
      "auth token arg must be emitted for the selected provider — otherwise the on-device " +
        "factory cannot construct an OpenAILLMClient",
      "fake-resolved-token-not-real",
      args[LlmAuthResolver.resolve("test_ai")],
    )
  }

  @Test
  fun `NONE sentinel from settings does not emit any provider metadata`() {
    // Regression cover for the OSS default `llm = none` scenario: even with a fully
    // populated workspace yaml, if the persisted selection is the NONE sentinel we must
    // emit no `default_model` and no provider-scoped args.
    val loadedConfig = LlmConfigLoader.load(
      userHomeDir = tempUserHomeDir,
      projectDir = tempProjectDir,
    )
    val auths = LlmAuthResolver.resolveAll(loadedConfig)

    val selected = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "none",
      persistedModelId = "none",
    )

    val args = LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = selected.selectedProviderId,
      defaultModel = selected.defaultModel,
    )

    // No selected-provider metadata.
    assertEquals(null, args[LlmAuthResolver.PROVIDER_TYPE_ARG])
    assertEquals(null, args[LlmAuthResolver.BASE_URL_ARG])
    assertEquals(null, args[LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG])
    assertEquals(null, args[LlmAuthResolver.HEADERS_ARG])
    assertEquals(null, args[LlmAuthResolver.AUTH_REQUIRED_ARG])
    assertEquals(null, args[LlmAuthResolver.DEFAULT_MODEL_ARG])
  }
}
