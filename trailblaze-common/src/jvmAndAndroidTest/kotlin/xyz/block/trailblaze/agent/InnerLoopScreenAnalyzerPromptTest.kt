package xyz.block.trailblaze.agent

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import kotlin.test.Test

/**
 * Locks in the verify-vs-direction prompt branching in [InnerLoopScreenAnalyzer.buildUserMessage].
 *
 * A verification step is an assertion about state — the LLM should call `objectiveStatus`, not
 * take a UI action. A direction step is an instruction — the LLM should prefer a UI action over
 * reporting status. A silent regression in either direction has historically caused 4-attempt
 * loops on `catalog/multiple-items` and `catalog/conditional-item` verify steps.
 */
class InnerLoopScreenAnalyzerPromptTest {

  private val neverCalledSampler = object : SamplingSource {
    override suspend fun sampleText(
      systemPrompt: String,
      userMessage: String,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = error("sampleText not used in buildUserMessage tests")

    override suspend fun sampleToolCall(
      systemPrompt: String,
      userMessage: String,
      tools: List<TrailblazeToolDescriptor>,
      screenshotBytes: ByteArray?,
      maxTokens: Int,
      traceId: TraceId?,
      screenContext: ScreenContext?,
    ): SamplingResult = error("sampleToolCall not used in buildUserMessage tests")

    override fun isAvailable(): Boolean = true

    override fun description(): String = "never-called test sampler"
  }

  private fun analyzer(): InnerLoopScreenAnalyzer = InnerLoopScreenAnalyzer(
    samplingSource = neverCalledSampler,
    model = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1_000,
      maxOutputTokens = 1_000,
      capabilityIds = emptyList(),
    ),
  )

  @Test
  fun `verification step tells the LLM to report status, not take UI actions`() {
    val message = analyzer().buildUserMessage(
      context = RecommendationContext(
        objective = "A dialog showing 'Coffee Americano' is visible",
        isVerification = true,
      ),
      viewHierarchy = "Dialog\n  [n1] Text 'Coffee Americano'",
    )
    assertThat(message).contains("verification step")
    assertThat(message).contains("objectiveAppearsAchieved=true")
    // The direction-step bias must NOT leak into verify — that's what caused the historical loop.
    assertThat(message).doesNotContain("prefer taking a UI action")
  }

  @Test
  fun `direction step keeps the prefer-UI-action bias`() {
    val message = analyzer().buildUserMessage(
      context = RecommendationContext(
        objective = "Tap on 'Coffee Americano'",
        isVerification = false,
      ),
      viewHierarchy = "List\n  [n1] Text 'Coffee Americano'",
    )
    assertThat(message).contains("prefer taking a UI action")
    assertThat(message).doesNotContain("verification step")
  }

  @Test
  fun `isVerification defaults to false so untouched callers get direction behavior`() {
    val message = analyzer().buildUserMessage(
      context = RecommendationContext(objective = "Tap OK"),
      viewHierarchy = "",
    )
    assertThat(message).contains("prefer taking a UI action")
  }
}
