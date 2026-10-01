package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isSameInstanceAs
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/**
 * Contract of [NetworkRetryLlmClient]: a request that failed before it reached the model is sent
 * again, and every other failure surfaces on the first attempt so no generation is paid for twice.
 */
class NetworkRetryLlmClientTest {

  /** Throws [failures] in order, then answers; [attempts] is what reached this client. */
  private class FlakyLlmClient(private val failures: List<Exception>) : LLMClient() {
    var attempts = 0
    val reply = Message.Assistant(content = "ok", metaInfo = ResponseMetaInfo.create(KoogClock.System))

    override fun llmProvider(): LLMProvider = TrailblazeLlmProvider.NONE_KOOG_LLM_PROVIDER
    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
      failures.getOrNull(attempts++)?.let { throw it }
      return reply
    }
    override suspend fun executeMultipleChoices(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): LLMChoice {
      failures.getOrNull(attempts++)?.let { throw it }
      return emptyList()
    }
    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> = emptyFlow()
    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
      ModerationResult(isHarmful = false, categories = emptyMap())
    override fun close() = Unit
  }

  private val model = TrailblazeLlmModels.GPT_4O_MINI.toKoogLlmModel()

  private fun prompt() = Prompt(
    messages = listOf(Message.User(content = "do the thing", metaInfo = RequestMetaInfo.create(KoogClock.System))),
    id = "test",
    params = LLMParams(temperature = null, speculation = null, schema = null),
  )

  /** How Koog's OpenAI client reports a farm phone's DNS miss. */
  private fun dnsMiss() = LLMClientException(
    "OpenAILLMClient",
    "Unable to resolve host \"api.openai.com\"",
    UnknownHostException("Unable to resolve host \"api.openai.com\": No address associated with hostname"),
  )

  private fun client(delegate: LLMClient, retries: Int = 3) =
    NetworkRetryLlmClient(delegate, delays = List(retries) { Duration.ZERO })

  @Test
  fun `a DNS miss wrapped by Koog is retried and the answer comes back`() {
    val provider = FlakyLlmClient(listOf(dnsMiss(), dnsMiss()))

    val answer = runBlocking { client(provider).execute(prompt(), model, emptyList()) }

    assertThat(answer).isSameInstanceAs(provider.reply)
    assertThat(provider.attempts).isEqualTo(3)
  }

  @Test
  fun `a refused or timed-out connect is retried for multiple choices too`() {
    val provider = FlakyLlmClient(listOf(ConnectException("refused"), ConnectTimeoutException("connect timeout", null)))

    runBlocking { client(provider).executeMultipleChoices(prompt(), model, emptyList()) }

    assertThat(provider.attempts).isEqualTo(3)
  }

  @Test
  fun `the last failure surfaces once the retries are spent`() {
    val last = dnsMiss()
    val provider = FlakyLlmClient(listOf(dnsMiss(), dnsMiss(), last))

    val thrown = assertFailsWith<LLMClientException> {
      runBlocking { client(provider, retries = 2).execute(prompt(), model, emptyList()) }
    }

    assertThat(thrown).isSameInstanceAs(last)
    assertThat(provider.attempts).isEqualTo(3)
  }

  @Test
  fun `a failure after the request may have reached the model is not retried`() {
    val readTimeout = LLMClientException("OpenAILLMClient", "timed out", SocketTimeoutException("Read timed out"))
    val providerError = LLMClientException("OpenAILLMClient", "Expected status code 200 but was 500", null)

    for (error in listOf(readTimeout, providerError)) {
      val provider = FlakyLlmClient(listOf(error))
      val thrown = assertFailsWith<LLMClientException> {
        runBlocking { client(provider).execute(prompt(), model, emptyList()) }
      }
      assertThat(thrown).isSameInstanceAs(error)
      assertThat(provider.attempts).isEqualTo(1)
    }
  }
}
