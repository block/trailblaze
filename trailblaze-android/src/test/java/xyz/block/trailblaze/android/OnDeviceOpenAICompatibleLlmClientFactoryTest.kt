package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.config.LlmAuthResolver

/**
 * JVM unit tests for [OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull].
 *
 * Targets the pure decision function rather than [createOrNull] so the tests stay
 * hermetic: no [HttpClient], no Ktor engine, no [OpenAILLMClient] construction, no
 * network — just a fixed `(String) -> String?` map → resolved config, asserted by
 * value equality. Every branch is reachable in microseconds.
 */
class OnDeviceOpenAICompatibleLlmClientFactoryTest {

  private val testProvider = TrailblazeLlmProvider(id = "test_ai", display = "Test AI")
  private val testModel = TrailblazeLlmModel.fallback(
    provider = testProvider,
    modelId = "gpt-4o-mini",
  )

  /** Builds an `argReader` lambda from a fixed map. */
  private fun argsOf(vararg entries: Pair<String, String>): (String) -> String? {
    val map = entries.toMap()
    return { key -> map[key] }
  }

  // ---------------------------------------------------------------------------
  // Bail-out branches
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveOrNull returns null when PROVIDER_TYPE_ARG is missing`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(LlmAuthResolver.BASE_URL_ARG to "https://api.example.com"),
    )
    assertNull(resolved)
  }

  @Test
  fun `resolveOrNull returns null when provider type is not openai_compatible`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "anthropic",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
      ),
    )
    assertNull(resolved)
  }

  @Test
  fun `resolveOrNull returns null when BASE_URL_ARG is missing despite openai_compatible type`() {
    // Asymmetric arg state — host emitted the type but not the URL. The factory bails
    // out cleanly. (A loud host-side validation for this state is a separate concern
    // discussed in PR #2840 lead-dev review #1.)
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible"),
    )
    assertNull(resolved)
  }

  // ---------------------------------------------------------------------------
  // Happy path — verify the resolved values, not just non-null-ness
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveOrNull returns full config when openai_compatible args plus token are present`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
      ),
    )
    assertNotNull(resolved)
    assertEquals("test_ai", resolved.providerId)
    assertEquals("https://api.example.com", resolved.baseUrl)
    assertEquals("real-token", resolved.apiKey)
    assertNull(resolved.chatCompletionsPath)
    assertEquals(emptyMap(), resolved.headers)
  }

  @Test
  fun `resolveOrNull is case-insensitive on PROVIDER_TYPE_ARG value`() {
    // Belt-and-suspenders: the host normalises to lowercase ("openai_compatible") but
    // protect against future drift in either direction.
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "OPENAI_COMPATIBLE",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
      ),
    )
    assertNotNull(resolved)
    assertEquals("real-token", resolved.apiKey)
  }

  // ---------------------------------------------------------------------------
  // Auth handling
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveOrNull throws when auth is required and token is missing`() {
    // Fail-fast contract: without this the on-device side would crash a few frames later
    // inside DefaultDynamicLlmClient.createLlmClient() with the generic "Unsupported
    // provider" error, which is impossible to diagnose without source access.
    val ex = assertFailsWith<IllegalStateException> {
      OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
        trailblazeLlmModel = testModel,
        argReader = argsOf(
          LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
          LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
          // No token arg; AUTH_REQUIRED_ARG also missing so defaults to required=true.
        ),
      )
    }
    // Error message must name the provider id and point at the remediation path.
    assertTrue(ex.message!!.contains("test_ai"), "message: ${ex.message}")
    assertTrue(ex.message!!.contains("auth.env_var"), "message: ${ex.message}")
    assertTrue(ex.message!!.contains("auth.required: false"), "message: ${ex.message}")
  }

  @Test
  fun `resolveOrNull throws when auth is required and token is blank`() {
    val ex = assertFailsWith<IllegalStateException> {
      OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
        trailblazeLlmModel = testModel,
        argReader = argsOf(
          LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
          LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
          LlmAuthResolver.resolve(testProvider) to "   ", // blank
        ),
      )
    }
    assertTrue(ex.message!!.contains("test_ai"))
  }

  @Test
  fun `resolveOrNull returns config with empty token when auth_required is false`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "http://localhost:8080",
        LlmAuthResolver.AUTH_REQUIRED_ARG to "false",
        // intentionally no token arg
      ),
    )
    assertNotNull(resolved)
    assertEquals("", resolved.apiKey)
  }

  @Test
  fun `resolveOrNull treats AUTH_REQUIRED_ARG values other than 'false' as required`() {
    // On-device parses `arg != "false"` to derive the default, so anything other than
    // the literal string "false" (including "true", "True", absent) means auth is
    // required. Pin the contract.
    val ex = assertFailsWith<IllegalStateException> {
      OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
        trailblazeLlmModel = testModel,
        argReader = argsOf(
          LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
          LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
          LlmAuthResolver.AUTH_REQUIRED_ARG to "True", // not literal "false"
        ),
      )
    }
    assertTrue(ex.message!!.contains("test_ai"))
  }

  // ---------------------------------------------------------------------------
  // Headers parsing (silent-fallback-with-log contract)
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveOrNull tolerates malformed HEADERS_ARG JSON and proceeds without headers`() {
    // Regression cover for the AI Lead Dev Review finding: malformed HEADERS_ARG must
    // NOT crash the on-device runner. It logs a warning and proceeds with no static
    // headers — verified here by asserting headers came back empty.
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
        LlmAuthResolver.HEADERS_ARG to "{this is not valid json",
      ),
    )
    assertNotNull(resolved)
    assertEquals(emptyMap(), resolved.headers)
  }

  @Test
  fun `resolveOrNull parses well-formed HEADERS_ARG JSON into the resolved headers map`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
        LlmAuthResolver.HEADERS_ARG to """{"X-Tenant":"acme","X-Route":"default"}""",
      ),
    )
    assertNotNull(resolved)
    assertEquals(mapOf("X-Tenant" to "acme", "X-Route" to "default"), resolved.headers)
  }

  // ---------------------------------------------------------------------------
  // chat_completions_path {{model_id}} substitution
  // ---------------------------------------------------------------------------

  @Test
  fun `resolveOrNull leaves chatCompletionsPath null when CHAT_COMPLETIONS_PATH_ARG omitted`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
      ),
    )
    assertNotNull(resolved)
    assertNull(resolved.chatCompletionsPath)
  }

  @Test
  fun `resolveOrNull passes chat_completions_path through verbatim when no template`() {
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
        LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG to "v1/chat/completions",
      ),
    )
    assertNotNull(resolved)
    assertEquals("v1/chat/completions", resolved.chatCompletionsPath)
  }

  // ---------------------------------------------------------------------------
  // Secret redaction
  // ---------------------------------------------------------------------------

  @Test
  fun `resolved config toString redacts apiKey to prevent accidental token leak via log interpolation`() {
    // Regression cover for the AI Lead Dev Review critical finding: the default
    // Kotlin `data class` toString would interpolate every property, including the
    // raw provider token. A stray `Console.log("$resolved")`, exception message, or
    // IDE inspector would otherwise leak the token into
    // `~/.trailblaze/desktop-logs/trailblaze.log` (which persists).
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = testModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "super-secret-token-XYZ123",
      ),
    )
    assertNotNull(resolved)

    val rendered = resolved.toString()
    // The token MUST NOT appear in the string representation.
    assertEquals(false, rendered.contains("super-secret-token-XYZ123"), "toString leaked the token: $rendered")
    // A redaction marker SHOULD appear so debugging output stays informative.
    assertTrue(rendered.contains("<redacted>"), "toString didn't mark apiKey as redacted: $rendered")
    // The non-secret fields SHOULD still appear so toString remains useful.
    assertTrue(rendered.contains("providerId=test_ai"), rendered)
    assertTrue(rendered.contains("baseUrl=https://api.example.com"), rendered)
  }

  @Test
  fun `resolveOrNull substitutes model_id template into chat_completions_path`() {
    // Pin the `{{model_id}}` substitution contract that mirrors the host-side
    // OpenAICompatibleLlmClientFactory. Some openai_compatible gateways embed the model
    // id in the path (e.g. per-deployment serving endpoints).
    val customModel = TrailblazeLlmModel.fallback(
      provider = testProvider,
      modelId = "my-deployment-name",
    )
    val resolved = OnDeviceOpenAICompatibleLlmClientFactory.resolveOrNull(
      trailblazeLlmModel = customModel,
      argReader = argsOf(
        LlmAuthResolver.PROVIDER_TYPE_ARG to "openai_compatible",
        LlmAuthResolver.BASE_URL_ARG to "https://api.example.com",
        LlmAuthResolver.resolve(testProvider) to "real-token",
        LlmAuthResolver.CHAT_COMPLETIONS_PATH_ARG to "serving-endpoints/{{model_id}}/invocations",
      ),
    )
    assertNotNull(resolved)
    assertEquals("serving-endpoints/my-deployment-name/invocations", resolved.chatCompletionsPath)
  }
}
