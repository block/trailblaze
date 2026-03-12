package xyz.block.trailblaze.llm

import xyz.block.trailblaze.agent.AgentTier

/**
 * Predefined LLM model configurations for Trailblaze.
 *
 * This object provides ready-to-use [TrailblazeLlmModel] instances organized by:
 * - **Provider**: OpenAI, Anthropic, Google
 * - **Tier**: Inner agent (cheap/fast) vs Outer agent (expensive/capable)
 *
 * ## Two-Tier Agent Model Selection
 *
 * For the two-tier agent architecture:
 * - **Inner Agent (Screen Analysis)**: Use cheap vision models like [GPT_4O_MINI], [CLAUDE_HAIKU], [GEMINI_FLASH]
 * - **Outer Agent (Planning)**: Use capable models like [GPT_4O], [CLAUDE_SONNET], [O1_MINI]
 *
 * ## Cost Information
 *
 * Costs are in USD per 1 million tokens, sourced from:
 * - [LiteLLM model prices](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json)
 * - Official provider pricing pages
 *
 * @see TrailblazeLlmModel
 * @see AgentTier
 */
object TrailblazeLlmModels {

  // ==========================================================================
  // Inner Agent Models (Cheap, Vision-Capable)
  // ==========================================================================

  /**
   * GPT-4o Mini - Recommended inner agent model for OpenAI.
   *
   * Fast, cheap vision model ideal for repetitive screen analysis.
   * Input: $0.15/1M tokens, Output: $0.60/1M tokens
   */
  val GPT_4O_MINI = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-4o-mini",
    inputCostPerOneMillionTokens = 0.15,
    outputCostPerOneMillionTokens = 0.60,
    contextLength = 128_000,
    maxOutputTokens = 16_384,
    capabilityIds = listOf("vision", "tools"),
  )

  /**
   * Claude 3.5 Haiku - Recommended inner agent model for Anthropic.
   *
   * Fast, economical vision model for screen analysis.
   * Input: $0.80/1M tokens, Output: $4.00/1M tokens
   */
  val CLAUDE_HAIKU = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.ANTHROPIC,
    modelId = "claude-3-5-haiku-20241022",
    inputCostPerOneMillionTokens = 0.80,
    outputCostPerOneMillionTokens = 4.00,
    contextLength = 200_000,
    maxOutputTokens = 8_192,
    capabilityIds = listOf("vision", "tools"),
  )

  /**
   * Gemini 2.0 Flash - Recommended inner agent model for Google.
   *
   * Very fast, affordable vision model.
   * Input: $0.10/1M tokens, Output: $0.40/1M tokens (estimated)
   */
  val GEMINI_FLASH = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE,
    modelId = "gemini-2.0-flash",
    inputCostPerOneMillionTokens = 0.10,
    outputCostPerOneMillionTokens = 0.40,
    contextLength = 1_000_000,
    maxOutputTokens = 8_192,
    capabilityIds = listOf("vision", "tools"),
  )

  // ==========================================================================
  // Outer Agent Models (Capable, Reasoning)
  // ==========================================================================

  /**
   * GPT-4o - Recommended outer agent model for OpenAI.
   *
   * Strong reasoning and planning capabilities.
   * Input: $2.50/1M tokens, Output: $10.00/1M tokens
   */
  val GPT_4O = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "gpt-4o",
    inputCostPerOneMillionTokens = 2.50,
    outputCostPerOneMillionTokens = 10.00,
    contextLength = 128_000,
    maxOutputTokens = 16_384,
    capabilityIds = listOf("vision", "tools"),
  )

  /**
   * Claude 3.5 Sonnet - Recommended outer agent model for Anthropic.
   *
   * Excellent reasoning with good cost/capability balance.
   * Input: $3.00/1M tokens, Output: $15.00/1M tokens
   */
  val CLAUDE_SONNET = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.ANTHROPIC,
    modelId = "claude-3-5-sonnet-20241022",
    inputCostPerOneMillionTokens = 3.00,
    outputCostPerOneMillionTokens = 15.00,
    contextLength = 200_000,
    maxOutputTokens = 8_192,
    capabilityIds = listOf("vision", "tools"),
  )

  /**
   * o1-mini - OpenAI reasoning model for complex planning.
   *
   * Specialized for reasoning tasks, good for outer agent planning.
   * Input: $3.00/1M tokens, Output: $12.00/1M tokens
   */
  val O1_MINI = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "o1-mini",
    inputCostPerOneMillionTokens = 3.00,
    outputCostPerOneMillionTokens = 12.00,
    contextLength = 128_000,
    maxOutputTokens = 65_536,
    capabilityIds = listOf("reasoning"),
  )

  /**
   * Gemini 2.0 Pro - Capable outer agent model for Google.
   *
   * Strong reasoning and multimodal capabilities.
   * Input: $1.25/1M tokens, Output: $5.00/1M tokens (estimated)
   */
  val GEMINI_PRO = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.GOOGLE,
    modelId = "gemini-2.0-pro",
    inputCostPerOneMillionTokens = 1.25,
    outputCostPerOneMillionTokens = 5.00,
    contextLength = 2_000_000,
    maxOutputTokens = 8_192,
    capabilityIds = listOf("vision", "tools", "reasoning"),
  )

  // ==========================================================================
  // Model Collections
  // ==========================================================================

  /**
   * All models suitable for the inner agent (screen analysis).
   *
   * These models are cheap, fast, and vision-capable.
   */
  val INNER_AGENT_MODELS: List<TrailblazeLlmModel> = listOf(
    GPT_4O_MINI,
    CLAUDE_HAIKU,
    GEMINI_FLASH,
  )

  /**
   * All models suitable for the outer agent (planning/reasoning).
   *
   * These models have strong reasoning capabilities.
   */
  val OUTER_AGENT_MODELS: List<TrailblazeLlmModel> = listOf(
    GPT_4O,
    CLAUDE_SONNET,
    O1_MINI,
    GEMINI_PRO,
  )

  /**
   * All available predefined models.
   */
  val ALL_MODELS: List<TrailblazeLlmModel> = INNER_AGENT_MODELS + OUTER_AGENT_MODELS

  /**
   * Default inner agent model (GPT-4o Mini - best price/performance).
   */
  val DEFAULT_INNER_MODEL = GPT_4O_MINI

  /**
   * Default outer agent model (GPT-4o - reliable and capable).
   */
  val DEFAULT_OUTER_MODEL = GPT_4O

  /**
   * Returns recommended models for the given agent tier.
   *
   * @param tier The agent tier to get models for
   * @return List of models suitable for that tier
   */
  fun modelsForTier(tier: AgentTier): List<TrailblazeLlmModel> = when (tier) {
    AgentTier.INNER -> INNER_AGENT_MODELS
    AgentTier.OUTER -> OUTER_AGENT_MODELS
  }

  /**
   * Returns the default model for the given agent tier.
   *
   * @param tier The agent tier
   * @return The recommended default model for that tier
   */
  fun defaultModelForTier(tier: AgentTier): TrailblazeLlmModel = when (tier) {
    AgentTier.INNER -> DEFAULT_INNER_MODEL
    AgentTier.OUTER -> DEFAULT_OUTER_MODEL
  }
}
