package xyz.block.trailblaze.mcp.agent

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.LLMChoice
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import xyz.block.trailblaze.util.Console
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Retries a model request that failed before it reached the model: the host did not resolve, or the
 * connection was refused, unroutable, or timed out while connecting. A farm phone's first request can
 * hit a DNS miss (`Unable to resolve host "api.openai.com"`), and without this the whole trail fails
 * on it.
 *
 * Only those failures are retried, so a retry never pays for a second generation. A read timeout,
 * a reset mid-response, or an HTTP error from the provider may mean the model already ran, and those
 * surface unchanged. Koog wraps transport errors in `LLMClientException`, so the check walks the
 * cause chain.
 *
 * Sits INNERMOST, directly around the provider client, so the logger writes one request log and the
 * call budget counts one call however many attempts it took.
 *
 * @param delays The wait before each retry; its size is the number of retries.
 */
class NetworkRetryLlmClient(
  private val delegate: LLMClient,
  private val delays: List<Duration> = DEFAULT_DELAYS,
) : LLMClient() {

  override fun llmProvider(): LLMProvider = delegate.llmProvider()

  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Message.Assistant = withRetry { delegate.execute(prompt, model, tools) }

  override suspend fun executeMultipleChoices(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): LLMChoice = withRetry { delegate.executeMultipleChoices(prompt, model, tools) }

  /** Not retried: frames may already have reached the caller when the stream fails. */
  override fun executeStreaming(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): Flow<StreamFrame> = delegate.executeStreaming(prompt, model, tools)

  override suspend fun moderate(
    prompt: Prompt,
    model: LLModel,
  ): ModerationResult = withRetry { delegate.moderate(prompt = prompt, model = model) }

  override fun close() = delegate.close()

  private suspend fun <T> withRetry(block: suspend () -> T): T {
    var retry = 0
    while (true) {
      try {
        return block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val cause = connectionFailureIn(e)
        if (cause == null || retry >= delays.size) throw e
        val wait = delays[retry++]
        Console.log(
          "[LLM_RETRY] Request did not reach the model (${cause::class.simpleName}: ${cause.message}); " +
            "retry $retry of ${delays.size} in $wait",
        )
        delay(wait)
      }
    }
  }

  companion object {
    /**
     * Doubling waits, about 31s in all. A farm phone can start with no DNS for longer than 9s (every
     * host fails to resolve, not just the model's); a phone whose request goes through never waits.
     */
    val DEFAULT_DELAYS: List<Duration> = listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds)

    /**
     * The failure in [error]'s cause chain that shows the request never left the device, or null.
     * Ktor's connect timeout is a [ConnectException] on the JVM.
     */
    fun connectionFailureIn(error: Throwable): Throwable? {
      val seen = mutableSetOf<Throwable>()
      var current: Throwable? = error
      while (current != null && seen.add(current)) {
        if (current is UnknownHostException || current is ConnectException || current is NoRouteToHostException) {
          return current
        }
        current = current.cause
      }
      return null
    }
  }
}
