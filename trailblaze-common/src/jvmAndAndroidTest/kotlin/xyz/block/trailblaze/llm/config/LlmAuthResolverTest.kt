package xyz.block.trailblaze.llm.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Verifies the host→on-device instrumentation-arg contract for openai_compatible
 * providers. The on-device APK reads these keys back via [LlmAuthResolver] constants
 * to construct an [OpenAILLMClient] for any custom provider declared in the workspace
 * `trailblaze.yaml` — see `AndroidStandaloneServerTest.getDynamicLlmClient`. If a key
 * changes here without a matching change on-device, custom providers fail at runtime
 * with "Unsupported provider".
 */
class LlmAuthResolverTest {

  @Test
  fun `toInstrumentationArgs emits full openai_compatible metadata for the selected provider`() {
    val providerConfig = LlmProviderConfig(
      type = LlmProviderType.OPENAI_COMPATIBLE,
      baseUrl = "https://api.example.com/v1",
      chatCompletionsPath = "chat/completions",
      headers = mapOf("X-Route" to "default", "X-Tenant" to "acme"),
      auth = LlmAuthConfig(envVar = "EXAMPLE_TOKEN", required = true),
    )
    val auths = mapOf(
      "example_provider" to ResolvedProviderAuth(
        providerId = "example_provider",
        token = "secret-token",
        envVarKey = "EXAMPLE_TOKEN",
        headers = providerConfig.headers,
        baseUrl = providerConfig.baseUrl,
        providerConfig = providerConfig,
      ),
    )

    val args = LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = "example_provider",
      defaultModel = "example_provider/some-model",
    )

    assertEquals("secret-token", args["trailblaze.llm.auth.token.example_provider"])
    assertEquals("openai_compatible", args[LlmAuthResolver.PROVIDER_TYPE_ARG])
    assertEquals("https://api.example.com/v1", args[LlmAuthResolver.BASE_URL_ARG])
    assertEquals("chat/completions", args[LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG])
    assertEquals("example_provider/some-model", args[LlmAuthResolver.DEFAULT_MODEL_ARG])
    assertEquals("true", args[LlmAuthResolver.AUTH_REQUIRED_ARG])

    val headersJson = args[LlmAuthResolver.HEADERS_ARG]
    assertTrue("HEADERS_ARG should be emitted when provider has static headers", headersJson != null)
    val decoded = Json.decodeFromString<Map<String, String>>(headersJson!!)
    assertEquals(mapOf("X-Route" to "default", "X-Tenant" to "acme"), decoded)
  }

  @Test
  fun `toInstrumentationArgs omits HEADERS_ARG when provider declares no headers`() {
    val providerConfig = LlmProviderConfig(
      type = LlmProviderType.OPENAI_COMPATIBLE,
      baseUrl = "https://api.example.com/v1",
      headers = emptyMap(),
      auth = LlmAuthConfig(envVar = "EXAMPLE_TOKEN"),
    )
    val auths = mapOf(
      "example_provider" to ResolvedProviderAuth(
        providerId = "example_provider",
        token = "t",
        envVarKey = "EXAMPLE_TOKEN",
        headers = emptyMap(),
        baseUrl = providerConfig.baseUrl,
        providerConfig = providerConfig,
      ),
    )

    val args = LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = "example_provider",
    )

    assertFalse(args.containsKey(LlmAuthResolver.HEADERS_ARG))
    // `auth.required` is left null in this provider, so we must not ship the literal
    // string "null" — the on-device side reads back `arg != "false"` to derive the
    // default, so anything other than the explicit string "false" would short-circuit
    // the absent-token guard.
    assertFalse(args.containsKey(LlmAuthResolver.AUTH_REQUIRED_ARG))
  }

  @Test
  fun `toInstrumentationArgs encodes auth_required=false for providers that opt out of auth`() {
    val providerConfig = LlmProviderConfig(
      type = LlmProviderType.OPENAI_COMPATIBLE,
      baseUrl = "http://localhost:8080",
      auth = LlmAuthConfig(required = false),
    )
    val auths = mapOf(
      "local_dev" to ResolvedProviderAuth(
        providerId = "local_dev",
        token = null,
        envVarKey = null,
        headers = emptyMap(),
        baseUrl = providerConfig.baseUrl,
        providerConfig = providerConfig,
      ),
    )

    val args = LlmAuthResolver.toInstrumentationArgs(
      auths = auths,
      selectedProviderId = "local_dev",
    )

    assertEquals("false", args[LlmAuthResolver.AUTH_REQUIRED_ARG])
    // No token so the auth-token key shouldn't be emitted.
    assertNull(args["trailblaze.llm.auth.token.local_dev"])
  }

  // ---------------------------------------------------------------------------
  // selectedLlmInstrumentationArgs — pure decision tests
  //
  // These tests intentionally hit ONLY the pure decision logic — no I/O, no
  // settings repo, no LlmConfigLoader, no HTTP client. The purpose is to pin
  // the contract between the persisted (provider, model) selection and what
  // gets shipped to the device, deterministically, in milliseconds.
  // ---------------------------------------------------------------------------

  @Test
  fun `selectedLlmInstrumentationArgs returns provider and combined model key when both set`() {
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "example_provider",
      persistedModelId = "some-model",
    )

    assertEquals("example_provider", result.selectedProviderId)
    assertEquals("example_provider/some-model", result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs returns null when provider is the NONE sentinel`() {
    // The OSS default is `llmProvider = "none"`; without this check we'd ship the
    // literal string "none/none" to the device. Regression cover for the AI Lead
    // Dev Review finding on PR #2840.
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = TrailblazeLlmProvider.NONE.id,
      persistedModelId = TrailblazeLlmProvider.NONE.id,
    )

    assertNull(result.selectedProviderId)
    assertNull(result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs returns null when provider is the NONE sentinel even with a real model`() {
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = TrailblazeLlmProvider.NONE.id,
      persistedModelId = "gpt-4o-mini",
    )

    assertNull(result.selectedProviderId)
    assertNull(result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs returns null when provider is blank`() {
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "",
      persistedModelId = "gpt-4o-mini",
    )

    assertNull(result.selectedProviderId)
    assertNull(result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs returns null when model is blank`() {
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "example_provider",
      persistedModelId = "",
    )

    assertNull(result.selectedProviderId)
    assertNull(result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs returns null when both halves are blank`() {
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "",
      persistedModelId = "",
    )

    assertNull(result.selectedProviderId)
    assertNull(result.defaultModel)
  }

  @Test
  fun `selectedLlmInstrumentationArgs treats custom provider id matching a TEST_AI shape correctly`() {
    // The exact scenario PR #2840 targets: a workspace yaml declares an openai_compatible
    // provider with a custom id (e.g. `test_ai`) and a model inheriting from a built-in.
    // The host must pass the *custom* provider id through so the on-device APK can match
    // it back to the same id in its LLM client registry.
    val result = LlmAuthResolver.selectedLlmInstrumentationArgs(
      persistedProviderId = "test_ai",
      persistedModelId = "gpt-4o-mini",
    )

    assertEquals("test_ai", result.selectedProviderId)
    assertEquals("test_ai/gpt-4o-mini", result.defaultModel)
  }
}
