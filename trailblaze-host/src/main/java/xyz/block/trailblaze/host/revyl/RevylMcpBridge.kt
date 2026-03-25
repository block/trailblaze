package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * [TrailblazeMcpBridge] backed by the Revyl CLI for cloud device interactions.
 *
 * Routes MCP tool calls through [RevylTrailblazeAgent] and provides device
 * listing/selection via [RevylDeviceService]. Supports both trail replay
 * (YAML tool execution) and blaze exploration (AI-driven via [BlazeGoalPlanner]).
 *
 * @property cliClient CLI-based client for Revyl device interactions.
 * @property revylDeviceService Handles session provisioning and listing.
 * @property agent The Trailblaze agent that dispatches tools via CLI.
 * @property platform Device platform ("ios" or "android").
 */
class RevylMcpBridge(
  private val cliClient: RevylCliClient,
  private val revylDeviceService: RevylDeviceService,
  private val agent: RevylTrailblazeAgent,
  private val platform: String = "android",
) : TrailblazeMcpBridge {

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    val session = cliClient.getSession(trailblazeDeviceId.instanceId)
      ?: error("No Revyl session found for device ${trailblazeDeviceId.instanceId}")
    cliClient.useSession(session.index)
    val devices = revylDeviceService.listDevices()
    return devices.firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }
      ?: error("Device ${trailblazeDeviceId.instanceId} not found in Revyl sessions.")
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    return revylDeviceService.listDevices()
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    return emptySet()
  }

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> {
    return setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget)
  }

  /**
   * Executes a YAML trail on the Revyl cloud device.
   *
   * For trail/tool-based YAML: parses the YAML into [TrailblazeTool] instances
   * and replays them sequentially via [executeTrailblazeTool].
   *
   * For blaze mode ([AgentImplementation.MULTI_AGENT_V3]): constructs a
   * [BlazeGoalPlanner] with [AgentUiActionExecutor] and runs AI-driven
   * exploration against the cloud device.
   *
   * @param yaml Raw YAML content to execute.
   * @param startNewSession Whether to start a fresh session (ignored for Revyl).
   * @param agentImplementation Which agent implementation to use for execution.
   * @return A summary string with the number of tools executed or blaze result.
   */
  override suspend fun runYaml(yaml: String, startNewSession: Boolean, agentImplementation: AgentImplementation): String {
    Console.log("RevylMcpBridge: runYaml invoked (implementation=$agentImplementation)")

    if (agentImplementation == AgentImplementation.MULTI_AGENT_V3) {
      return blazeExecute(yaml)
    }

    val trailblazeYaml = TrailblazeYaml.Default
    val items = trailblazeYaml.decodeTrail(yaml)

    var executedCount = 0

    val toolItems = items.filterIsInstance<TrailYamlItem.ToolTrailItem>()
    for (toolItem in toolItems) {
      for (wrapper in toolItem.tools) {
        executeTrailblazeTool(wrapper.trailblazeTool)
        executedCount++
      }
    }

    val promptItems = items.filterIsInstance<TrailYamlItem.PromptsTrailItem>()
    for (promptItem in promptItems) {
      for (step in promptItem.promptSteps) {
        val tools = step.recording?.tools ?: continue
        for (wrapper in tools) {
          executeTrailblazeTool(wrapper.trailblazeTool)
          executedCount++
        }
      }
    }

    Console.log("RevylMcpBridge: runYaml completed ($executedCount tools executed)")
    return "completed:$executedCount"
  }

  /**
   * Stub for AI-driven blaze exploration via [RevylBlazeSupport].
   *
   * Blaze mode requires an LLM-backed ScreenAnalyzer and TrailblazeToolRepo
   * that the host application must provide. Use [RevylBlazeSupport.createBlazeRunner]
   * to construct a fully configured [BlazeGoalPlanner] backed by Revyl cloud
   * devices — it takes your existing LLM dependencies and returns a ready-to-run
   * planner.
   *
   * @param yaml YAML containing blaze objectives.
   * @return Status message directing callers to [RevylBlazeSupport].
   * @see RevylBlazeSupport.createBlazeRunner
   */
  private suspend fun blazeExecute(yaml: String): String {
    Console.log("RevylMcpBridge: blaze (V3) mode requested — use RevylBlazeSupport.createBlazeRunner() for host-level wiring")
    return "blaze:use-RevylBlazeSupport.createBlazeRunner"
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? {
    return revylDeviceService.getCurrentDeviceId()
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    val session = cliClient.getActiveRevylSession() ?: return null
    return RevylScreenState(
      cliClient,
      session.platform,
      sessionScreenWidth = session.screenWidth,
      sessionScreenHeight = session.screenHeight,
    )
  }

  /**
   * Executes a [TrailblazeTool] by delegating to [RevylTrailblazeAgent].
   *
   * @param tool The tool to execute on the cloud device.
   * @return A human-readable result description.
   */
  override suspend fun executeTrailblazeTool(tool: TrailblazeTool): String {
    val toolName = tool.getToolNameFromAnnotation()
    Console.log("RevylMcpBridge: executing tool '$toolName'")

    val result = agent.runTrailblazeTools(
      tools = listOf(tool),
      elementComparator = NoOpElementComparator,
    )

    return when (val outcome = result.result) {
      is TrailblazeToolResult.Success ->
        outcome.message ?: "Successfully executed $toolName on Revyl cloud device."
      is TrailblazeToolResult.Error ->
        "Error executing $toolName: ${outcome.errorMessage}"
    }
  }

  override suspend fun endSession(): Boolean {
    return try {
      cliClient.stopSession()
      true
    } catch (_: Exception) {
      false
    }
  }

  override fun selectAppTarget(appTargetId: String): String? {
    return if (appTargetId == "none") "None" else null
  }

  override fun getCurrentAppTargetId(): String? {
    return "none"
  }
}

private object NoOpElementComparator : ElementComparator {
  override fun getElementValue(prompt: String): String? = null
  override fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool =
    BooleanAssertionTrailblazeTool(result = true, reason = "No-op comparator")
  override fun evaluateString(query: String): StringEvaluationTrailblazeTool =
    StringEvaluationTrailblazeTool(result = "", reason = "No-op comparator")
  override fun extractNumberFromString(input: String): Double? = null
}
