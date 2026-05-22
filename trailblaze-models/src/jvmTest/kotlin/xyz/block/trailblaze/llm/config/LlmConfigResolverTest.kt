package xyz.block.trailblaze.llm.config

import ai.koog.prompt.llm.LLMCapability
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

class LlmConfigResolverTest {

  @AfterTest
  fun resetEffectiveDefault() {
    // Several tests below set `EffectiveScreenshotScalingConfig.effective` to pin
    // determinism invariants. Clear after each test so the singleton doesn't leak into
    // the next test class in this Gradle test JVM.
    EffectiveScreenshotScalingConfig.clearForTests()
  }

  @Test
  fun `empty config returns built-in model lists`() {
    val result = LlmConfigResolver.resolve(LlmConfig())
    assertTrue(result.modelLists.isNotEmpty())
    // Should include all built-in providers
    val providerIds = result.modelLists.map { it.provider.id }.toSet()
    assertTrue("openai" in providerIds)
    assertTrue("anthropic" in providerIds)
    assertTrue("google" in providerIds)
  }

  @Test
  fun `model id resolves from built-in registry`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val openaiList = result.modelLists.single()
    assertEquals(TrailblazeLlmProvider.OPENAI, openaiList.provider)
    val model = openaiList.entries.single()
    assertEquals("gpt-4.1", model.modelId)
    assertEquals(2.00, model.inputCostPerOneMillionTokens)
    assertEquals(8.00, model.outputCostPerOneMillionTokens)
    assertTrue(model.contextLength > 0)
    assertTrue(model.maxOutputTokens > 0)
  }

  @Test
  fun `override pricing on built-in model`() {
    val config = LlmConfig(
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

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(1.50, model.inputCostPerOneMillionTokens)
    // Output cost preserved from built-in
    assertEquals(8.00, model.outputCostPerOneMillionTokens)
  }

  @Test
  fun `full definition creates new model`() {
    val config = LlmConfig(
      providers = mapOf(
        "local_vllm" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "my-custom-model",
              contextLength = 128000,
              maxOutputTokens = 16384,
              cost = LlmModelCostConfig(inputPerMillion = 0.0, outputPerMillion = 0.0),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals("my-custom-model", model.modelId)
    assertEquals("local_vllm", model.trailblazeLlmProvider.id)
    assertEquals(0.0, model.inputCostPerOneMillionTokens)
    assertEquals(128000L, model.contextLength)
    assertEquals(16384L, model.maxOutputTokens)
    assertTrue(model.capabilityIds.contains(LLMCapability.Vision.Image.id))
    assertTrue(model.capabilityIds.contains(LLMCapability.Tools.id))
  }

  @Test
  fun `full definition without context_length uses defaults`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "some-model",
              maxOutputTokens = 8192,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals("some-model", model.modelId)
    // Default context length when not specified
    assertEquals(131_072L, model.contextLength)
    assertEquals(8192L, model.maxOutputTokens)
  }

  @Test
  fun `ollama model not in registry uses defaults`() {
    val config = LlmConfig(
      providers = mapOf(
        "ollama" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(id = "my-custom-model:7b"),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals("my-custom-model:7b", model.modelId)
    assertEquals(TrailblazeLlmProvider.OLLAMA, model.trailblazeLlmProvider)
    assertEquals(0.0, model.inputCostPerOneMillionTokens)
    assertTrue(model.contextLength > 0)
    assertTrue(model.maxOutputTokens > 0)
  }

  @Test
  fun `disabled provider is excluded`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          enabled = false,
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
        "anthropic" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "claude-sonnet-4-6")),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    assertEquals(1, result.modelLists.size)
    assertEquals(TrailblazeLlmProvider.ANTHROPIC, result.modelLists.single().provider)
  }

  @Test
  fun `standard provider key infers type`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    assertEquals(TrailblazeLlmProvider.OPENAI, result.modelLists.single().provider)
  }

  @Test
  fun `unknown provider key creates custom provider`() {
    val config = LlmConfig(
      providers = mapOf(
        "my_corp_gateway" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "corp-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val provider = result.modelLists.single().provider
    assertEquals("my_corp_gateway", provider.id)
    assertEquals("My corp gateway", provider.display)
  }

  @Test
  fun `defaults are preserved in resolved config`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
      defaults = LlmDefaultsConfig(model = "openai/gpt-4.1"),
    )

    val result = LlmConfigResolver.resolve(config)
    assertEquals("openai/gpt-4.1", result.defaults.model)
  }

  @Test
  fun `custom model gets default capabilities`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val capIds = result.modelLists.single().entries.single().capabilityIds
    assertTrue(LLMCapability.Vision.Image.id in capIds)
    assertTrue(LLMCapability.Tools.id in capIds)
    assertTrue(LLMCapability.Schema.JSON.Standard.id in capIds)
    assertTrue(LLMCapability.Temperature.id in capIds)
    assertTrue(LLMCapability.ToolChoice.id in capIds)
  }

  @Test
  fun `vision false removes vision capability`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "text-only-model",
              vision = false,
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val capIds = result.modelLists.single().entries.single().capabilityIds
    assertTrue(LLMCapability.Vision.Image.id !in capIds)
    assertTrue(LLMCapability.Tools.id in capIds)
  }

  @Test
  fun `temperature default is preserved through resolution`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "creative-model",
              temperature = 0.9,
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(0.9, model.defaultTemperature)
  }

  @Test
  fun `built-in model preserves capabilities when no vision specified`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1")),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    // Built-in model should retain its Koog-defined capabilities
    assertTrue(model.capabilityIds.isNotEmpty())
    assertTrue(LLMCapability.Vision.Image.id in model.capabilityIds)
    assertTrue(LLMCapability.Tools.id in model.capabilityIds)
  }

  @Test
  fun `vision false on built-in model removes only vision`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(LlmModelConfigEntry(id = "gpt-4.1", vision = false)),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertTrue(LLMCapability.Vision.Image.id !in model.capabilityIds)
    // Other capabilities still present
    assertTrue(LLMCapability.Tools.id in model.capabilityIds)
  }

  @Test
  fun `no screenshot config uses DEFAULT dimensions`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    // Read through `ScreenshotScalingConfig.DEFAULT.*` so the test tracks the framework
    // default if it ever shifts — pinning these to literals would break the moment the
    // shipped default is tuned (e.g. dimensions bumped for a larger context window).
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension1, model.screenshotScalingConfig.maxDimension1)
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension2, model.screenshotScalingConfig.maxDimension2)
  }

  @Test
  fun `screenshot max_dimensions is resolved from model config`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
              screenshot = LlmScreenshotConfig(maxDimensions = "768x512"),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(768, model.screenshotScalingConfig.maxDimension1)
    assertEquals(512, model.screenshotScalingConfig.maxDimension2)
  }

  @Test
  fun `screenshot defaults from project-level config`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
            ),
          ),
        ),
      ),
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "1024x768"),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(1024, model.screenshotScalingConfig.maxDimension1)
    assertEquals(768, model.screenshotScalingConfig.maxDimension2)
  }

  @Test
  fun `screenshot format and quality are resolved from model config`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
              screenshot = LlmScreenshotConfig(
                format = TrailblazeImageFormat.PNG,
                quality = 1.0f,
              ),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(TrailblazeImageFormat.PNG, model.screenshotScalingConfig.imageFormat)
    assertEquals(1.0f, model.screenshotScalingConfig.compressionQuality)
    // Dimensions fall back to ScreenshotScalingConfig.DEFAULT when unset — read through the
    // constant so this test tracks framework-default tuning instead of pinning to literals.
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension1, model.screenshotScalingConfig.maxDimension1)
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension2, model.screenshotScalingConfig.maxDimension2)
  }

  @Test
  fun `partial workspace yaml inherits unset peer fields from framework default not per-machine override`() {
    // Determinism guard: even if a developer has bumped their per-machine
    // `trailblaze config screenshot-quality 0.5`, a committed `defaults.screenshot:
    // {format: PNG}` should resolve to PNG + framework dimensions + framework quality —
    // not PNG + framework dimensions + 0.5 quality. Otherwise two teammates with the
    // same committed yaml would render different bytes.
    EffectiveScreenshotScalingConfig.setEffectiveDefault(
      ScreenshotScalingConfig(
        maxDimension1 = 9999,
        maxDimension2 = 4444,
        imageFormat = TrailblazeImageFormat.JPEG,
        compressionQuality = 0.5f,
      ),
    )
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(LlmModelConfigEntry(id = "test-model")),
        ),
      ),
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(format = TrailblazeImageFormat.PNG),
      ),
    )

    val model = LlmConfigResolver.resolve(config).modelLists.single().entries.single()
    assertEquals(TrailblazeImageFormat.PNG, model.screenshotScalingConfig.imageFormat)
    // Unset peer fields fall back to framework default — not the per-machine override that
    // was injected above. Read through `ScreenshotScalingConfig.DEFAULT.*` so the test stays
    // honest if the framework default ever shifts.
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension1, model.screenshotScalingConfig.maxDimension1)
    assertEquals(ScreenshotScalingConfig.DEFAULT.maxDimension2, model.screenshotScalingConfig.maxDimension2)
    assertEquals(ScreenshotScalingConfig.DEFAULT.compressionQuality, model.screenshotScalingConfig.compressionQuality)
  }

  @Test
  fun `model screenshot partial override inherits from project default field-by-field`() {
    // Model entry sets only `format`; missing `max_dimensions` and `quality` should fall
    // back to the project-level defaults, not all the way to framework. Without this the
    // per-field composition would silently drop the project default's dimensions.
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              screenshot = LlmScreenshotConfig(format = TrailblazeImageFormat.PNG),
            ),
          ),
        ),
      ),
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "1024x768", quality = 0.9f),
      ),
    )

    val model = LlmConfigResolver.resolve(config).modelLists.single().entries.single()
    assertEquals(TrailblazeImageFormat.PNG, model.screenshotScalingConfig.imageFormat)
    assertEquals(1024, model.screenshotScalingConfig.maxDimension1)
    assertEquals(768, model.screenshotScalingConfig.maxDimension2)
    assertEquals(0.9f, model.screenshotScalingConfig.compressionQuality)
  }

  @Test
  fun `model screenshot overrides project default`() {
    val config = LlmConfig(
      providers = mapOf(
        "custom" to LlmProviderConfig(
          type = LlmProviderType.OPENAI_COMPATIBLE,
          models = listOf(
            LlmModelConfigEntry(
              id = "test-model",
              contextLength = 100000,
              maxOutputTokens = 4096,
              screenshot = LlmScreenshotConfig(maxDimensions = "512x384"),
            ),
          ),
        ),
      ),
      defaults = LlmDefaultsConfig(
        screenshot = LlmScreenshotConfig(maxDimensions = "1024x768"),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(512, model.screenshotScalingConfig.maxDimension1)
    assertEquals(384, model.screenshotScalingConfig.maxDimension2)
  }

  @Test
  fun `override cached input cost`() {
    val config = LlmConfig(
      providers = mapOf(
        "openai" to LlmProviderConfig(
          models = listOf(
            LlmModelConfigEntry(
              id = "gpt-4.1",
              cost = LlmModelCostConfig(cachedInputPerMillion = 0.50),
            ),
          ),
        ),
      ),
    )

    val result = LlmConfigResolver.resolve(config)
    val model = result.modelLists.single().entries.single()
    assertEquals(0.50, model.cachedInputCostPerOneMillionTokens)
    // Other costs preserved from built-in
    assertEquals(2.00, model.inputCostPerOneMillionTokens)
    assertEquals(8.00, model.outputCostPerOneMillionTokens)
  }
}
