package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.JvmOpenAiApiKeyUtil
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.PromptStep

class InteractiveMainRunner(
  private val filterViewHierarchy: Boolean = true,
  private val setOfMarkEnabled: Boolean = true,
) {

  init {
    // Set the global set of mark flag
    println("InteractiveMainRunner config:")
    println("  filterViewHierarchy: $filterViewHierarchy")
    println("  setOfMarkEnabled: $setOfMarkEnabled")
  }

  val hostMaestroAgent by lazy {
    val hostRunner = MaestroHostRunnerImpl(
      setOfMarkEnabled = setOfMarkEnabled,
    )
    HostMaestroTrailblazeAgent(maestroHostRunner = hostRunner)
  }

  // Create the tool repo with the correct flags
  private val toolRepo by lazy {
    TrailblazeToolRepo(
      TrailblazeToolSet.getSetOfMarkToolSet(
        setOfMarkEnabled = setOfMarkEnabled,
      ),
    )
  }

  val llmModel = OpenAIModels.Chat.GPT4_1
  val llmClient = OpenAILLMClient(
    apiKey = JvmOpenAiApiKeyUtil.getApiKeyFromEnv(),
  )

  // Create OpenAI agent runner for this specific run
  val openAiRunner by lazy {
    // Create the runner
    val runner = TrailblazeRunner(
      screenStateProvider = hostMaestroAgent.maestroHostRunner.screenStateProvider,
      agent = hostMaestroAgent,
      llmModel = llmModel,
      llmClient = llmClient,
      trailblazeToolRepo = toolRepo,
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
        when (val result: AgentTaskStatus = openAiRunner.run(PromptStep(input))) {
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
