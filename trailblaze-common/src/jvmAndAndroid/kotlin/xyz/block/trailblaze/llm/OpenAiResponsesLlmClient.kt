package xyz.block.trailblaze.llm

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams

/**
 * Sends OpenAI's numbered GPT models through the Responses API instead of chat completions, with
 * reasoning off where the model allows it. Every other model goes to [delegate] untouched.
 *
 * On chat completions these models re-run observation tools without completing the step, or fail a
 * verification whose expected screen is showing; through Responses they finish in two calls.
 */
class OpenAiResponsesLlmClient(private val delegate: LLMClient) : LLMClient() {

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant {
    if (!usesResponsesApi(model.id)) return delegate.execute(prompt, model, tools)
    val responsesModel = responsesModel(model)
    return delegate.execute(
      prompt.withParams(responsesParams(prompt.params, model.id)),
      responsesModel,
      tools,
    )
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
    delegate.moderate(prompt, model)

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

  override fun close() = delegate.close()

  companion object {
    /** OpenAI's numbered GPT models; not open-weight `gpt-oss`. */
    private val OPENAI_GPT = Regex("gpt-[0-9]")

    /**
     * GPT-5.1 and later accept `reasoning.effort=none`; GPT-5 Mini stops at `minimal`, so it keeps
     * its default effort. Both spellings occur: `gpt-5.6-luna` and deployment ids like `gpt-5-6`.
     */
    private val ACCEPTS_REASONING_NONE = Regex("gpt-(5[.\\-][1-9]|[6-9])")

    fun usesResponsesApi(modelId: String): Boolean = OPENAI_GPT.containsMatchIn(modelId)

    fun acceptsReasoningNone(modelId: String): Boolean =
      ACCEPTS_REASONING_NONE.containsMatchIn(modelId)

    /** koog only sends `reasoning` for a model that declares Thinking. */
    fun responsesModel(model: LLModel): LLModel =
      if (acceptsReasoningNone(model.id)) {
        model.copy(capabilities = model.capabilities.orEmpty() + LLMCapability.Thinking)
      } else {
        model
      }

    /** Carries the agent's params onto the Responses API, with reasoning off where allowed. */
    fun responsesParams(params: LLMParams, modelId: String): OpenAIResponsesParams =
      OpenAIResponsesParams(
        temperature = params.temperature,
        maxTokens = params.maxTokens,
        schema = params.schema,
        toolChoice = params.toolChoice,
        user = params.user,
        additionalProperties = params.additionalProperties,
        reasoning =
          if (acceptsReasoningNone(modelId)) ReasoningConfig(effort = ReasoningEffort.NONE)
          else null,
      )
  }
}
