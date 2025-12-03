package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.JvmOpenAiApiKeyUtil
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.DirectionStep

class InteractiveMainRunner(
  private val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  private val filterViewHierarchy: Boolean = true,
  private val trailblazeLogger: TrailblazeLogger,
) {

  init {
    // Set the global set of mark flag
    println("InteractiveMainRunner config:")
    println("  filterViewHierarchy: $filterViewHierarchy")
    println("  setOfMarkEnabled: ${config.setOfMarkEnabled}")
  }

  val hostMaestroAgent by lazy {
    val hostRunner = MaestroHostRunnerImpl(
      setOfMarkEnabled = config.setOfMarkEnabled,
      trailblazeLogger = trailblazeLogger,
    )
    HostMaestroTrailblazeAgent(
      maestroHostRunner = hostRunner,
      trailblazeLogger = trailblazeLogger,
    )
  }

  // Create the tool repo with the correct flags
  private val toolRepo by lazy {
    TrailblazeToolRepo(
      TrailblazeToolSet.getSetOfMarkToolSet(
        setOfMarkEnabled = config.setOfMarkEnabled,
      ),
    )
  }

  val trailblazeLlmModel = OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1
  val llmClient = OpenAILLMClient(
    apiKey = JvmOpenAiApiKeyUtil.getApiKeyFromEnv(),
  )

  // Create OpenAI agent runner for this specific run
  val openAiRunner by lazy {
    // Create the runner
    val sessionManager = TrailblazeSessionManager()
    val runner = TrailblazeRunner(
      screenStateProvider = hostMaestroAgent.maestroHostRunner.screenStateProvider,
      agent = hostMaestroAgent,
      trailblazeLlmModel = trailblazeLlmModel,
      llmClient = llmClient,
      trailblazeToolRepo = toolRepo,
      trailblazeLogger = trailblazeLogger,
      sessionManager = sessionManager,
    )
    runner
  }

  fun run() {
    // query app id
    while (true) {
      print(
        """Type your next to command the agent and hit 'enter' to run it. Type 'exit' to quit.
NOTE: our prompt will be sent to the agent which will work to fulfill your request until it determines it is done.
🤖 > """,
      )
      val input = readln()

      // Check if input is empty or just whitespace
      if (input.isBlank()) {
        continue // Skip empty input and show prompt again
      }

      if (input == "exit") {
        break
      }

      runBlocking {
        when (val result: AgentTaskStatus = openAiRunner.run(DirectionStep(input))) {
          is AgentTaskStatus.InProgress -> {
            println("🤖 The agent is still working on the objective. Please wait...")
          }

          is AgentTaskStatus.Failure -> {
            println("\u001B[1;31m❌ The agent failed to complete the objective.\u001B[0m")
            when (result) {
              is AgentTaskStatus.Failure.ObjectiveFailed -> {
                println("\u001B[1;31m❌ Reason: ${result.llmExplanation}\u001B[0m")
              }

              is AgentTaskStatus.Failure.MaxCallsLimitReached -> {
                println("\u001B[1;31m❌ Reached maximum number of agent calls (${result.statusData.callCount})\u001B[0m")
              }

              else -> {
                println("\u001B[1;31m❌ Unknown failure occurred\u001B[0m")
              }
            }
          }

          is AgentTaskStatus.Success -> {
            println("\u001B[1;32m✅ The agent successfully completed the objective.")
            if (result is AgentTaskStatus.Success.ObjectiveComplete) {
              println("\u001B[1;32m✅ ${result.llmExplanation}\u001B[0m")
            }
          }
        }
        println("")
      }
    }
  }
}
