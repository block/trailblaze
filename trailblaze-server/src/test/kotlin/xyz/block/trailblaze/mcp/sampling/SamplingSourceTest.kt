package xyz.block.trailblaze.mcp.sampling

import ai.koog.agents.core.tools.ToolDescriptor
import xyz.block.trailblaze.agent.SamplingResult
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import org.junit.Test
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.TrailblazeMcpMode
import xyz.block.trailblaze.mcp.models.McpSessionId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for sampling source implementations.
 *
 * These tests verify that:
 * - [LocalLlmSamplingSource] correctly checks availability and handles missing config
 * - [McpClientSamplingSource] correctly checks MCP client sampling capability
 * - [SamplingSourceResolver] correctly prioritizes sources and provides helpful errors
 */
class SamplingSourceTest {

  // region LocalLlmSamplingSource tests

  @Test
  fun `LocalLlmSamplingSource isAvailable returns false when client is null`() {
    val source = LocalLlmSamplingSource(llmClient = null, llmModel = createMockLlmModel())
    assertFalse(source.isAvailable())
  }

  @Test
  fun `LocalLlmSamplingSource isAvailable returns false when model is null`() {
    val source = LocalLlmSamplingSource(llmClient = MockLlmClient(), llmModel = null)
    assertFalse(source.isAvailable())
  }

  @Test
  fun `LocalLlmSamplingSource isAvailable returns false when both are null`() {
    val source = LocalLlmSamplingSource(llmClient = null, llmModel = null)
    assertFalse(source.isAvailable())
  }

  @Test
  fun `LocalLlmSamplingSource isAvailable returns true when both are configured`() {
    val source = LocalLlmSamplingSource(
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )
    assertTrue(source.isAvailable())
  }

  @Test
  fun `LocalLlmSamplingSource description includes model id when configured`() {
    val source = LocalLlmSamplingSource(
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )
    assertTrue(source.description().contains("test-model"))
  }

  @Test
  fun `LocalLlmSamplingSource description shows not configured when model is null`() {
    val source = LocalLlmSamplingSource(llmClient = null, llmModel = null)
    assertTrue(source.description().contains("not configured"))
  }

  @Test
  fun `LocalLlmSamplingSource sample returns error when client not configured`() = runTest {
    val source = LocalLlmSamplingSource(llmClient = null, llmModel = createMockLlmModel())
    val result = source.sample("system", "user")
    assertIs<SamplingResult.Error>(result)
    assertTrue(result.message.contains("client not configured"))
  }

  @Test
  fun `LocalLlmSamplingSource sample returns error when model not configured`() = runTest {
    val source = LocalLlmSamplingSource(llmClient = MockLlmClient(), llmModel = null)
    val result = source.sample("system", "user")
    assertIs<SamplingResult.Error>(result)
    assertTrue(result.message.contains("model not configured"))
  }

  @Test
  fun `LocalLlmSamplingSource sample returns success when configured`() = runTest {
    val mockClient = MockLlmClient(responseText = "Test response from LLM")
    val source = LocalLlmSamplingSource(
      llmClient = mockClient,
      llmModel = createMockLlmModel(),
    )

    @Suppress("DEPRECATION")
    val result = source.sample(
      systemPrompt = "You are a test assistant",
      userMessage = "Hello",
    )

    assertIs<SamplingResult.Text>(result)
    assertEquals("Test response from LLM", result.completion)
    assertEquals("test-model", result.model)
  }

  @Test
  fun `LocalLlmSamplingSource sample handles LLM exception`() = runTest {
    val mockClient = MockLlmClient(throwException = RuntimeException("API Error"))
    val source = LocalLlmSamplingSource(
      llmClient = mockClient,
      llmModel = createMockLlmModel(),
    )

    val result = source.sample("system", "user")

    assertIs<SamplingResult.Error>(result)
    assertTrue(result.message.contains("API Error"))
  }

  // endregion

  // region McpClientSamplingSource tests

  @Test
  fun `McpClientSamplingSource isAvailable returns false when session is null`() {
    val sessionContext = createSessionContext()
    val mcpSamplingClient = McpSamplingClient(sessionContext)
    val source = McpClientSamplingSource(mcpSamplingClient)
    assertFalse(source.isAvailable())
  }

  @Test
  fun `McpClientSamplingSource description is correct`() {
    val sessionContext = createSessionContext()
    val mcpSamplingClient = McpSamplingClient(sessionContext)
    val source = McpClientSamplingSource(mcpSamplingClient)
    assertEquals("MCP Client Sampling", source.description())
  }

  @Test
  fun `McpClientSamplingSource sample returns error when not available`() = runTest {
    val sessionContext = createSessionContext()
    val mcpSamplingClient = McpSamplingClient(sessionContext)
    val source = McpClientSamplingSource(mcpSamplingClient)

    val result = source.sample("system", "user")

    assertIs<SamplingResult.Error>(result)
    assertTrue(result.message.contains("does not support sampling"))
  }

  // endregion

  // region SamplingSourceResolver tests

  @Test
  fun `SamplingSourceResolver resolve returns null when neither source available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = null,
      llmModel = null,
    )
    assertNull(resolver.resolve())
  }

  @Test
  fun `SamplingSourceResolver resolve falls back to local when MCP not available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )

    val source = resolver.resolve()

    assertNotNull(source)
    assertIs<LocalLlmSamplingSource>(source)
  }

  @Test
  fun `SamplingSourceResolver resolveOrThrow throws when neither available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = null,
      llmModel = null,
    )

    val exception = runCatching { resolver.resolveOrThrow() }.exceptionOrNull()

    assertNotNull(exception)
    assertIs<IllegalStateException>(exception)
    assertTrue(exception.message!!.contains("No sampling source available"))
  }

  @Test
  fun `SamplingSourceResolver resolveOrThrow returns source when available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )

    val source = resolver.resolveOrThrow()

    assertNotNull(source)
  }

  @Test
  fun `SamplingSourceResolver resolveMcpSource returns null when not available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )

    assertNull(resolver.resolveMcpSource())
  }

  @Test
  fun `SamplingSourceResolver resolveLocalSource returns null when not available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = null,
      llmModel = null,
    )

    assertNull(resolver.resolveLocalSource())
  }

  @Test
  fun `SamplingSourceResolver resolveLocalSource returns source when available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )

    val source = resolver.resolveLocalSource()

    assertNotNull(source)
    assertIs<LocalLlmSamplingSource>(source)
  }

  @Test
  fun `SamplingSourceResolver describeAvailability includes both sources`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = null,
      llmModel = null,
    )

    val description = resolver.describeAvailability()

    assertTrue(description.contains("MCP Client"))
    assertTrue(description.contains("Local LLM"))
    assertTrue(description.contains("No source available"))
  }

  @Test
  fun `SamplingSourceResolver describeAvailability shows using when source available`() {
    val sessionContext = createSessionContext()
    val resolver = SamplingSourceResolver(
      sessionContext = sessionContext,
      llmClient = MockLlmClient(),
      llmModel = createMockLlmModel(),
    )

    val description = resolver.describeAvailability()

    assertTrue(description.contains("Using:"))
    assertTrue(description.contains("Local LLM"))
  }

  // endregion

  // region Test Helpers

  private fun createSessionContext(): TrailblazeMcpSessionContext = TrailblazeMcpSessionContext(
    mcpServerSession = null,
    mcpSessionId = McpSessionId("test-session"),
    mode = TrailblazeMcpMode.MCP_CLIENT_AS_AGENT,
  )

  private fun createMockLlmModel(): TrailblazeLlmModel = TrailblazeLlmModel(
    trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
    modelId = "test-model",
    inputCostPerOneMillionTokens = 0.0,
    outputCostPerOneMillionTokens = 0.0,
    contextLength = 128000,
    maxOutputTokens = 4096,
    capabilityIds = listOf(),
  )

  // endregion
}

// region Mock Implementations

/**
 * Mock LLM client for testing.
 */
private class MockLlmClient(
  private val responseText: String = "Mock response",
  private val throwException: Exception? = null,
) : LLMClient {
  override suspend fun execute(
    prompt: Prompt,
    model: LLModel,
    tools: List<ToolDescriptor>,
  ): List<Message.Response> {
    if (throwException != null) {
      throw throwException
    }
    return listOf(
      Message.Assistant(
        content = responseText,
        metaInfo = ResponseMetaInfo.create(Clock.System),
      ),
    )
  }

  override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
    throw NotImplementedError("Not needed for tests")

  override fun llmProvider(): LLMProvider =
    throw NotImplementedError("Not needed for tests")

  override fun close() {
    // No-op for tests
  }
}

// endregion
