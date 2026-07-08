package xyz.block.trailblaze.llm.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import kotlin.test.Test
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider

/**
 * Guards the koog `modelVersionsMap` contract: `AnthropicLLMClient` resolves the request's
 * `LLModel` in the map by data-class equality and throws "Unsupported model" on a miss, so
 * the map must cover both built-in models and runtime-resolved models whose LLModel form
 * differs from the built-in entry (yaml overrides, custom ids, fallback constructions).
 */
class BuiltInLlmModelRegistryKoogMapTest {

  @Test
  fun `built-in anthropic models resolve by LLModel equality`() {
    val map = BuiltInLlmModelRegistry.koogModelVersionsMap(TrailblazeLlmProvider.ANTHROPIC)
    assertThat(map).isNotEmpty()
    val builtIn = BuiltInLlmModelRegistry
      .modelListForProvider(TrailblazeLlmProvider.ANTHROPIC)!!.entries.first()
    assertThat(map[builtIn.toKoogLlmModel()]).isEqualTo(builtIn.modelId)
  }

  @Test
  fun `extra model with yaml-style overrides resolves alongside built-ins`() {
    // A user-yaml override of context_length yields an LLModel that is NOT equal to the
    // built-in entry — without extraModels this lookup is the "Unsupported model" crash.
    val overridden = BuiltInLlmModelRegistry
      .modelListForProvider(TrailblazeLlmProvider.ANTHROPIC)!!.entries.first()
      .copy(contextLength = 42_000L)
    val map = BuiltInLlmModelRegistry.koogModelVersionsMap(
      TrailblazeLlmProvider.ANTHROPIC,
      extraModels = listOf(overridden),
    )
    assertThat(map[overridden.toKoogLlmModel()]).isEqualTo(overridden.modelId)
  }

  @Test
  fun `extra model not in the built-in catalog resolves`() {
    val custom = TrailblazeLlmModel.fallback(
      provider = TrailblazeLlmProvider.ANTHROPIC,
      modelId = "claude-brand-new-model",
    )
    val map = BuiltInLlmModelRegistry.koogModelVersionsMap(
      TrailblazeLlmProvider.ANTHROPIC,
      extraModels = listOf(custom),
    )
    assertThat(map[custom.toKoogLlmModel()]).isEqualTo("claude-brand-new-model")
  }

  @Test
  fun `extra models for other providers are filtered out`() {
    val openAiModel = TrailblazeLlmModel.fallback(
      provider = TrailblazeLlmProvider.OPENAI,
      modelId = "gpt-something",
    )
    val map = BuiltInLlmModelRegistry.koogModelVersionsMap(
      TrailblazeLlmProvider.ANTHROPIC,
      extraModels = listOf(openAiModel),
    )
    assertThat(map[openAiModel.toKoogLlmModel()]).isEqualTo(null)
  }
}
