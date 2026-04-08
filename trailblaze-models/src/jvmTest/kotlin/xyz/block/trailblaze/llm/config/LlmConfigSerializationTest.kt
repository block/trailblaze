package xyz.block.trailblaze.llm.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmConfigSerializationTest {

  private val yaml = Yaml(
    configuration = YamlConfiguration(strictMode = false),
  )

  @Test
  fun `deserialize model entries by id`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        openai:
          models:
            - id: gpt-4.1
            - id: gpt-4.1-mini
      """.trimIndent(),
    )

    val models = config.providers["openai"]!!.models
    assertEquals(2, models.size)
    assertEquals("gpt-4.1", models[0].id)
    assertNull(models[0].cost)
    assertEquals("gpt-4.1-mini", models[1].id)
  }

  @Test
  fun `deserialize model entry with cost overrides`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        openai:
          models:
            - id: gpt-5-mini
              cost:
                input_per_million: 0.20
                output_per_million: 10.00
      """.trimIndent(),
    )

    val model = config.providers["openai"]!!.models.single()
    assertEquals("gpt-5-mini", model.id)
    assertEquals(0.20, model.cost!!.inputPerMillion)
    assertEquals(10.00, model.cost!!.outputPerMillion)
    assertNull(model.cost!!.cachedInputPerMillion)
  }

  @Test
  fun `deserialize full model definition`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        local_vllm:
          type: openai_compatible
          base_url: "http://localhost:8000/v1"
          auth:
            required: false
          models:
            - id: mistral-large-instruct
              tier: outer
              vision: false
              temperature: 0.7
              context_length: 128000
              max_output_tokens: 8192
              cost:
                input_per_million: 0.0
                output_per_million: 0.0
      """.trimIndent(),
    )

    val provider = config.providers["local_vllm"]!!
    assertEquals(LlmProviderType.OPENAI_COMPATIBLE, provider.type)
    assertEquals("http://localhost:8000/v1", provider.baseUrl)
    assertEquals(false, provider.auth.required)

    val model = provider.models.single()
    assertEquals("mistral-large-instruct", model.id)
    assertEquals("outer", model.tier)
    assertEquals(false, model.vision)
    assertEquals(0.7, model.temperature)
    assertEquals(128000L, model.contextLength)
    assertEquals(8192L, model.maxOutputTokens)
    assertEquals(0.0, model.cost!!.inputPerMillion)
  }

  @Test
  fun `deserialize mixed simple and override entries`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        anthropic:
          models:
            - id: claude-sonnet-4.5
            - id: claude-haiku-4.5
              cost:
                input_per_million: 0.80
      """.trimIndent(),
    )

    val models = config.providers["anthropic"]!!.models
    assertEquals(2, models.size)
    assertEquals("claude-sonnet-4.5", models[0].id)
    assertNull(models[0].cost)
    assertEquals("claude-haiku-4.5", models[1].id)
    assertEquals(0.80, models[1].cost!!.inputPerMillion)
  }

  @Test
  fun `deserialize provider with headers`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        azure_openai:
          type: openai_compatible
          base_url: "https://my-resource.openai.azure.com"
          headers:
            api-version: "2024-02-15-preview"
          auth:
            env_var: AZURE_OPENAI_API_KEY
          models:
            - id: gpt-4.1
              context_length: 1048576
              max_output_tokens: 32768
      """.trimIndent(),
    )

    val provider = config.providers["azure_openai"]!!
    assertEquals("AZURE_OPENAI_API_KEY", provider.auth.envVar)
    assertEquals("2024-02-15-preview", provider.headers["api-version"])

    val model = provider.models.single()
    assertEquals("gpt-4.1", model.id)
    assertEquals(1048576L, model.contextLength)
  }

  @Test
  fun `deserialize defaults`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      defaults:
        model: openai/gpt-4.1
      """.trimIndent(),
    )

    assertEquals("openai/gpt-4.1", config.defaults.model)
  }

  @Test
  fun `deserialize provider with chat_completions_path`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        custom_gateway:
          type: openai_compatible
          base_url: "https://gateway.example.com"
          chat_completions_path: "serving-endpoints/{{model_id}}/invocations"
          auth:
            env_var: GATEWAY_API_TOKEN
          models:
            - id: goose-gpt-4-1
              context_length: 1048576
              max_output_tokens: 32768
      """.trimIndent(),
    )

    val provider = config.providers["custom_gateway"]!!
    assertEquals(
      "serving-endpoints/{{model_id}}/invocations",
      provider.chatCompletionsPath,
    )
  }

  @Test
  fun `deserialize empty config`() {
    val config = yaml.decodeFromString(LlmConfig.serializer(), "{}")
    assertEquals(emptyMap(), config.providers)
    assertNull(config.defaults.model)
  }

  @Test
  fun `serialize and deserialize roundtrip`() {
    val original = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
    )
    val encoded = yaml.encodeToString(LlmConfig.serializer(), original)
    val decoded = yaml.decodeFromString(LlmConfig.serializer(), encoded)
    assertEquals(original, decoded)
  }

  @Test
  fun `deserialize disabled provider`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        openai:
          enabled: false
          models:
            - id: gpt-4.1
      """.trimIndent(),
    )

    assertEquals(false, config.providers["openai"]!!.enabled)
  }

  @Test
  fun `deserialize multiple providers`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        openai:
          models:
            - id: gpt-4.1
        anthropic:
          models:
            - id: claude-sonnet-4.5
        ollama:
          models:
            - id: qwen3-vl:8b
              context_length: 131072
              max_output_tokens: 8192
      """.trimIndent(),
    )

    assertEquals(3, config.providers.size)
    assertEquals("gpt-4.1", config.providers["openai"]!!.models.single().id)
    assertEquals("claude-sonnet-4.5", config.providers["anthropic"]!!.models.single().id)
    assertEquals(131072L, config.providers["ollama"]!!.models.single().contextLength)
  }

  @Test
  fun `deserialize custom provider with explicit model specs`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        custom_gateway:
          type: openai_compatible
          models:
            - id: gateway-gpt-5-2
              context_length: 400000
              max_output_tokens: 128000
            - id: gateway-claude-sonnet
              context_length: 200000
              max_output_tokens: 64000
      """.trimIndent(),
    )

    val models = config.providers["custom_gateway"]!!.models
    assertEquals(2, models.size)
    assertEquals(400000L, models[0].contextLength)
    assertEquals(200000L, models[1].contextLength)
  }

  @Test
  fun `deserialize screenshot config`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      providers:
        custom:
          type: openai_compatible
          models:
            - id: test-model
              screenshot:
                max_dimensions: 1024x768
              context_length: 100000
              max_output_tokens: 4096
      """.trimIndent(),
    )

    val model = config.providers["custom"]!!.models.single()
    assertEquals("1024x768", model.screenshot!!.maxDimensions)
  }

  @Test
  fun `deserialize defaults with screenshot config`() {
    val config = yaml.decodeFromString(
      LlmConfig.serializer(),
      """
      defaults:
        model: openai/gpt-4.1
        screenshot:
          max_dimensions: 768x512
      """.trimIndent(),
    )

    assertEquals("openai/gpt-4.1", config.defaults.model)
    assertEquals("768x512", config.defaults.screenshot!!.maxDimensions)
  }

  @Test
  fun `parseDimensions parses valid format`() {
    val config = LlmScreenshotConfig(maxDimensions = "1536x768")
    val dims = config.parseDimensions()
    assertEquals(1536, dims!!.first)
    assertEquals(768, dims.second)
  }

  @Test
  fun `parseDimensions returns null for null input`() {
    val config = LlmScreenshotConfig(maxDimensions = null)
    assertNull(config.parseDimensions())
  }

  @Test
  fun `parseDimensions handles whitespace`() {
    val config = LlmScreenshotConfig(maxDimensions = " 1536 x 768 ")
    val dims = config.parseDimensions()
    assertEquals(1536, dims!!.first)
    assertEquals(768, dims.second)
  }

  @Test
  fun `parseDimensions returns null for invalid format`() {
    assertNull(LlmScreenshotConfig(maxDimensions = "invalid").parseDimensions())
    assertNull(LlmScreenshotConfig(maxDimensions = "1536").parseDimensions())
    assertNull(LlmScreenshotConfig(maxDimensions = "1536x768x100").parseDimensions())
    assertNull(LlmScreenshotConfig(maxDimensions = "abcxdef").parseDimensions())
  }
}
