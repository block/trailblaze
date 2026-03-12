package xyz.block.trailblaze.host.recording

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Transforms recorded deterministic tool actions into natural language trail YAML
 * by calling an LLM. Takes the raw recorded YAML (tool calls like tapOnPoint,
 * inputText, swipeWithRelativeCoordinates) and produces a trail with natural language
 * step descriptions alongside the recorded tool calls.
 */
class RecordingLlmService(
  private val trailblazeLlmModel: TrailblazeLlmModel,
  private val tokenProvider: TrailblazeDynamicLlmTokenProvider,
) : java.io.Closeable {

  private val baseClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
    timeoutInSeconds = 120,
  )

  private val llmClient: LLMClient by lazy {
    tokenProvider.getLLMClientForProviderIfAvailable(
      trailblazeLlmModel.trailblazeLlmProvider,
      baseClient,
    ) ?: error(
      "No API key configured for ${trailblazeLlmModel.trailblazeLlmProvider.display}. " +
        "Set the appropriate environment variable in Settings.",
    )
  }

  /**
   * Transforms raw recorded tool YAML into a natural language trail.
   *
   * @param recordedYaml The YAML output from [InteractionRecorder.generateTrailYaml],
   *   containing deterministic tool calls (e.g., tapOnElementWithText, inputText).
   * @return Trail YAML with natural language step descriptions and tool recordings.
   */
  @OptIn(ExperimentalUuidApi::class)
  suspend fun transformToNaturalLanguageTrail(recordedYaml: String): String =
    withContext(Dispatchers.IO) {
      val systemPrompt = buildSystemPrompt()
      val userPrompt = buildUserPrompt(recordedYaml)

      val messages = listOf(
        Message.System(
          content = systemPrompt,
          metaInfo = ai.koog.prompt.message.RequestMetaInfo.create(kotlin.time.Clock.System),
        ),
        Message.User(
          content = userPrompt,
          metaInfo = ai.koog.prompt.message.RequestMetaInfo.create(kotlin.time.Clock.System),
        ),
      )

      val promptId = Uuid.random().toString()
      // llmClient is lazy — first access may trigger auth (e.g., Databricks SSO browser flow).
      // Running on Dispatchers.IO ensures this doesn't block the UI thread.
      val responses: List<Message.Response> = llmClient.execute(
        prompt = Prompt(
          messages = messages,
          id = promptId,
          params = LLMParams(
            temperature = null,
            speculation = null,
            schema = null,
            toolChoice = null,
          ),
        ),
        model = trailblazeLlmModel.toKoogLlmModel(),
        tools = emptyList(),
      )

      val responseText = responses.filterIsInstance<Message.Assistant>()
        .joinToString("\n") { it.content }

      extractYamlFromResponse(responseText)
    }

  private fun buildSystemPrompt(): String = """
You are an expert at writing Trailblaze UI test trails. Trailblaze is a natural language UI testing framework.

A trail YAML file contains natural language step descriptions paired with recorded tool calls. Each step has:
- A natural language description (the "step" field) that describes what the user is doing
- A "recording" section with the exact tool calls that implement that step

Your job is to take a list of raw recorded tool calls and produce a well-structured trail YAML with clear, concise natural language descriptions for each step.

Rules:
1. Group related tool calls into logical steps (e.g., typing text into a field should be one step, not separate steps for tap + inputText)
2. Write step descriptions in imperative mood: "Tap the Sign In button", "Enter email address", "Scroll down"
3. For tap actions, describe WHAT is being tapped, not coordinates
4. For text input, mention what is being typed and where
5. For swipe/scroll actions, describe the direction and purpose
6. For verification steps, use "verify" instead of "step"
7. Keep descriptions concise but specific enough to understand without seeing the screen
8. Output ONLY valid YAML, no markdown fences or explanations
  """.trimIndent()

  private fun buildUserPrompt(recordedYaml: String): String = """
Transform the following recorded tool calls into a natural language trail YAML.

Each tool call should become a step with a natural language description and the original tool call preserved as a recording.

Recorded tools:
$recordedYaml

Produce a trail YAML in this format:
- prompts:
    - step: <natural language description>
      recording:
        tools:
          - <toolName>:
              <params>
    - step: <natural language description>
      recording:
        tools:
          - <toolName>:
              <params>
  """.trimIndent()

  override fun close() {
    baseClient.close()
  }

  private fun extractYamlFromResponse(response: String): String {
    // Strip markdown code fences if the LLM wrapped the output
    val stripped = response
      .replace(Regex("^```ya?ml\\s*\n", RegexOption.MULTILINE), "")
      .replace(Regex("\n```\\s*$", RegexOption.MULTILINE), "")
      .trim()
    return stripped
  }
}
