package xyz.block.trailblaze.host.recording

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider

/**
 * Regression test for the empty-MessagePart.Text branch added to [RecordingLlmService].
 *
 * Koog 1.0.0's `Message.Assistant.parts` split made it substantially more reachable for an
 * LLM response to contain zero `MessagePart.Text` parts (Reasoning-only, Attachment-only, or
 * an unexpected tool call). Before the guard, the recording path silently produced an empty
 * trail YAML; the guard now throws with a clear message listing the part types received.
 *
 * Pins the failure-mode message so a future refactor that drops the throw (or relaxes it to
 * an empty-string fallback) surfaces as a test regression instead of a silent UX regression.
 */
class RecordingLlmServiceTest {

  @Test
  fun `transformToNaturalLanguageTrail throws when response has no Text parts`() {
    val toolCallOnlyClient = StubLLMClient { _ ->
      Message.Assistant(
        // Tool.Call part — no Text part — exercises the empty-textParts branch.
        part = MessagePart.Tool.Call(
          id = "call-1",
          tool = "tap",
          args = "{}",
        ),
        metaInfo = ResponseMetaInfo.create(KoogClock.System),
      )
    }
    val service = RecordingLlmService(
      trailblazeLlmModel = TrailblazeLlmModel.fallback(
        provider = TrailblazeLlmProvider.OPENAI,
        modelId = "stub-model",
      ),
      tokenProvider = StubTokenProvider(toolCallOnlyClient),
    )

    val thrown = assertThrows(IllegalStateException::class.java) {
      runBlocking { service.transformToNaturalLanguageTrail("- tools:\n    - tap: {}\n") }
    }
    assertTrue(
      "error message must mention the failure mode so users know why recording is empty," +
        " got: ${thrown.message}",
      thrown.message!!.contains("no text parts"),
    )
    assertTrue(
      "error message must list the part types received so triage doesn't require re-running," +
        " got: ${thrown.message}",
      thrown.message!!.contains("Call"),
    )
  }

  /** Minimal [TrailblazeDynamicLlmTokenProvider] that hands back a single fake LLMClient. */
  private class StubTokenProvider(private val client: LLMClient) : TrailblazeDynamicLlmTokenProvider {
    override fun supportedProviders(): Set<TrailblazeLlmProvider> = setOf(TrailblazeLlmProvider.OPENAI)
    override fun getApiTokenForProvider(llmProvider: TrailblazeLlmProvider): String? = "stub-token"
    override fun getLLMClientForProviderIfAvailable(
      trailblazeLlmProvider: TrailblazeLlmProvider,
      baseClient: HttpClient,
    ): LLMClient = client
  }

  /** Minimal [LLMClient] driven by a single supplied `execute` lambda. */
  private class StubLLMClient(
    private val executeFn: (Prompt) -> Message.Assistant,
  ) : LLMClient() {
    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI
    override suspend fun execute(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Message.Assistant = executeFn(prompt)

    override suspend fun executeMultipleChoices(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): LLMChoice = error("not used")

    override fun executeStreaming(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = error("not used")

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
      error("not used")

    override fun close() = Unit
  }
}
