package xyz.block.trailblaze.llm.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LlmConfigMergerTest {

  @Test
  fun `merge adds new provider from overlay`() {
    val base = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("gpt-4.1")))),
    )
    val overlay = LlmConfig(
      providers =
        mapOf("anthropic" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("claude-sonnet-4.5")))),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals(2, result.providers.size)
    assertEquals("gpt-4.1", result.providers["openai"]!!.models.single().id)
    assertEquals("claude-sonnet-4.5", result.providers["anthropic"]!!.models.single().id)
  }

  @Test
  fun `merge appends new models to existing provider`() {
    val base = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("gpt-4.1")))),
    )
    val overlay = LlmConfig(
      providers =
        mapOf("openai" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("gpt-4.1-mini")))),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val models = result.providers["openai"]!!.models
    assertEquals(2, models.size)
    assertEquals(setOf("gpt-4.1", "gpt-4.1-mini"), models.map { it.id }.toSet())
  }

  @Test
  fun `merge overrides model fields by id`() {
    val base = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "gpt-4.1",
              cost = LlmModelCostConfig(inputPerMillion = 2.00, outputPerMillion = 8.00),
            ),
          ),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "gpt-4.1",
              cost = LlmModelCostConfig(inputPerMillion = 1.50),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val model = result.providers["openai"]!!.models.single()
    assertEquals(1.50, model.cost!!.inputPerMillion)
    assertEquals(8.00, model.cost!!.outputPerMillion)
  }

  @Test
  fun `merge overrides provider base_url`() {
    val base = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(baseUrl = "https://api.openai.com/v1")),
    )
    val overlay = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(baseUrl = "https://my-proxy.com/v1")),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("https://my-proxy.com/v1", result.providers["openai"]!!.baseUrl)
  }

  @Test
  fun `merge preserves base provider fields when overlay is null`() {
    val base = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          type = LlmProviderType.OPENAI,
          baseUrl = "https://api.openai.com/v1",
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig()),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val provider = result.providers["openai"]!!
    assertEquals(LlmProviderType.OPENAI, provider.type)
    assertEquals("https://api.openai.com/v1", provider.baseUrl)
  }

  @Test
  fun `merge combines provider headers`() {
    val base = LlmConfig(
      providers = mapOf(
        "azure" to LlmProviderConfig(
          headers = mapOf("api-version" to "2024-01"),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "azure" to LlmProviderConfig(
          headers = mapOf("x-custom" to "value"),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val headers = result.providers["azure"]!!.headers
    assertEquals("2024-01", headers["api-version"])
    assertEquals("value", headers["x-custom"])
  }

  @Test
  fun `merge overrides defaults`() {
    val base = LlmConfig(
      defaults = LlmDefaultsConfig(model = "gpt-4.1"),
    )
    val overlay = LlmConfig(
      defaults = LlmDefaultsConfig(model = "claude-sonnet-4.5"),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("claude-sonnet-4.5", result.defaults.model)
  }

  @Test
  fun `merge empty overlay returns base`() {
    val base = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("gpt-4.1")))),
      defaults = LlmDefaultsConfig(model = "gpt-4.1-mini"),
    )

    val result = LlmConfigMerger.merge(base, LlmConfig())
    assertEquals(base.providers.keys, result.providers.keys)
    assertEquals("gpt-4.1-mini", result.defaults.model)
  }

  @Test
  fun `merge empty base returns overlay`() {
    val overlay = LlmConfig(
      providers = mapOf("openai" to LlmProviderConfig(models = listOf(LlmModelConfigEntry("gpt-4.1")))),
    )

    val result = LlmConfigMerger.merge(LlmConfig(), overlay)
    assertEquals("gpt-4.1", result.providers["openai"]!!.models.single().id)
  }

  @Test
  fun `merge preserves model fields from base when overlay only has cost`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom_gateway" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(id = "gateway-gpt4", contextLength = 1_000_000L),
          ),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom_gateway" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "gateway-gpt4",
              cost = LlmModelCostConfig(inputPerMillion = 0.0),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val model = result.providers["custom_gateway"]!!.models.single()
    assertEquals(1_000_000L, model.contextLength)
    assertEquals(0.0, model.cost!!.inputPerMillion)
  }

  @Test
  fun `merge overrides vision`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a", vision = true)),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a", vision = false)),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals(false, result.providers["custom"]!!.models.single().vision)
  }

  @Test
  fun `merge preserves base vision when overlay is null`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a", vision = false)),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a")),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals(false, result.providers["custom"]!!.models.single().vision)
  }

  @Test
  fun `merge overrides temperature`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a", temperature = 0.5)),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "model-a", temperature = 0.9)),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals(0.9, result.providers["custom"]!!.models.single().temperature)
  }

  @Test
  fun `merge overrides screenshot config`() {
    val base = LlmConfig(
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "1536x768"),
      ),
    )
    val overlay = LlmConfig(
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "768x512"),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("768x512", result.defaults.screenshot!!.maxDimensions)
  }

  @Test
  fun `merge preserves base screenshot when overlay is null`() {
    val base = LlmConfig(
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "1536x768"),
      ),
    )
    val overlay = LlmConfig()

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("1536x768", result.defaults.screenshot!!.maxDimensions)
  }

  @Test
  fun `merge model screenshot overlay wins`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              screenshot = LlmScreenshotConfig(maxDimensions = "1536x768"),
            ),
          ),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              screenshot = LlmScreenshotConfig(maxDimensions = "512x384"),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    val model = result.providers["custom"]!!.models.single()
    assertEquals("512x384", model.screenshot!!.maxDimensions)
  }

  @Test
  fun `merge preserves base description when overlay omits it`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom_gateway" to LlmProviderConfig(
          description = "Custom AI Gateway",
          models = listOf(LlmModelConfigEntry("goose-gpt-4-1")),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom_gateway" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry("goose-gpt-4-1-mini")),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("Custom AI Gateway", result.providers["custom_gateway"]!!.description)
  }

  @Test
  fun `merge overrides description from overlay`() {
    val base = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(description = "Old description"),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(description = "New description"),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals("New description", result.providers["custom"]!!.description)
  }

  @Test
  fun `merge preserves disabled provider when overlay omits enabled`() {
    val base = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          enabled = false,
          models = listOf(LlmModelConfigEntry("gpt-4.1")),
        ),
      ),
    )
    val overlay = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry("gpt-4.1-mini")),
        ),
      ),
    )

    val result = LlmConfigMerger.merge(base, overlay)
    assertEquals(false, result.providers["openai"]!!.enabled)
  }
}
