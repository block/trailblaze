package xyz.block.trailblaze.host.mcp.host.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TrailblazeSessionListener
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.yaml.DirectionStep

// --- Koog ToolSets ---
@Suppress("unused")
class HostDeviceToolSet(
  private val toolRepo: TrailblazeToolRepo,
  private val sessionContext: TrailblazeMcpSseSessionContext?,
  private val logsRepo: LogsRepo,
  private val hostOpenAiRunnerProvider: () -> TrailblazeRunner,
  private val trailblazeLogger: TrailblazeLogger,
) : ToolSet {
  val ioScope = CoroutineScope(Dispatchers.IO)

  //  @Tool
  @LLMDescription("Provides a list of all available Trailblaze ToolSets that can be used on the host device.")
  fun listToolSets(): String = "Available ToolSets: ${TrailblazeToolSet.AllDefaultTrailblazeToolSets.map { it.name }}"

  //  @Tool
  @LLMDescription("Selects a set of Trailblaze tools to use for the current session.  This will only accept tools that are available in the listToolSets call.")
  fun selectToolsets(requestedToolSetNames: List<String>): String {
    val matchingToolSets =
      TrailblazeToolSet.AllDefaultTrailblazeToolSets
        .filter { requestedToolSetNames.contains(it.name) }
    toolRepo.removeAllTrailblazeTools()
    matchingToolSets.forEach {
      toolRepo.addTrailblazeToolSet(it)
    }
    return buildString {
      appendLine("Selected ToolSets: ${matchingToolSets.joinToString("\n") { it.name }}")
      appendLine(
        "With the following tools: ${
          matchingToolSets.flatMap { it.asTools() }.map { it.toolName().toolName }
        }",
      )
    }
  }

  @LLMDescription("Send a natural language instruction to the device.  This is a long running task that will report back progress as it executes.")
  @Tool
  fun prompt(
    @LLMDescription("The overall goal to accomplish.")
    prompt: String,
    @LLMDescription("The steps to accomplish the goal.  Each step will be executed in order.")
    steps: List<String>,
  ): String {
    var progressJob: Job? = null
    val sessionId = sessionContext?.mcpSseSessionId

    // Send progress notifications if we have a session context with progress service
    sessionContext?.let { sessionContext ->
      println("Executing prompt for session: $sessionId")
      progressJob = ioScope.launch {
        logsRepo.startWatchingTrailblazeSession(object : TrailblazeSessionListener {
          override val trailblazeSessionId: String = trailblazeLogger.getCurrentSessionId()

          override fun onSessionStarted() {
            sessionContext.sendIndeterminateProgressMessage("Session Started for session $sessionId")
          }

          override fun onUpdate(message: String) {
            sessionContext.sendIndeterminateProgressMessage(message)
          }

          override fun onSessionEnded() {
            sessionContext.sendIndeterminateProgressMessage("Session Ended for session $sessionId")
            println("Session $sessionId ended. Stopping progress updates.")
            progressJob?.cancel()
            logsRepo.stopWatching(this.trailblazeSessionId)
          }
        })
      }
    }

    steps.map { DirectionStep(it) }
      .forEach { hostOpenAiRunnerProvider().run(it) }

    progressJob?.cancel()
    return """
        Trailblaze run completed
    """.trimIndent()
  }
}
