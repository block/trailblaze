package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.getToolNameFromAnnotation
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.ElementComparator

/**
 * [TrailblazeMcpBridge] implementation backed by the Revyl cloud device infrastructure.
 *
 * Routes MCP tool calls through [RevylTrailblazeAgent] and provides device
 * listing / selection via [RevylDeviceService]. This bridge is plugged into
 * [xyz.block.trailblaze.logs.server.TrailblazeMcpServer] as a drop-in
 * replacement for the local-device [xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl].
 *
 * @property revylClient The HTTP client for Revyl worker communication.
 * @property revylDeviceService Handles session provisioning and listing.
 * @property agent The standalone Trailblaze agent that dispatches tools to Revyl.
 */
class RevylMcpBridge(
  private val revylClient: RevylWorkerClient,
  private val revylDeviceService: RevylDeviceService,
  private val agent: RevylTrailblazeAgent,
) : TrailblazeMcpBridge {

  override suspend fun selectDevice(trailblazeDeviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary {
    val devices = revylDeviceService.listDevices()
    return devices.firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }
      ?: error("Device ${trailblazeDeviceId.instanceId} not found in Revyl sessions.")
  }

  override suspend fun getAvailableDevices(): Set<TrailblazeConnectedDeviceSummary> {
    return revylDeviceService.listDevices()
  }

  override suspend fun getInstalledAppIds(): Set<String> {
    // Revyl doesn't expose an installed-apps endpoint — return empty for PoC
    return emptySet()
  }

  override fun getAvailableAppTargets(): Set<TrailblazeHostAppTarget> {
    return setOf(TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget)
  }

  override suspend fun runYaml(yaml: String, startNewSession: Boolean): String {
    Console.log("RevylMcpBridge: runYaml not supported for Revyl cloud devices (use tool calls instead)")
    return "unsupported"
  }

  override fun getCurrentlySelectedDeviceId(): TrailblazeDeviceId? {
    return revylDeviceService.getCurrentDeviceId()
  }

  override suspend fun getCurrentScreenState(): ScreenState? {
    val session = revylClient.getSession() ?: return null
    return RevylScreenState(revylClient, session.platform)
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

    return when (result.result) {
      is xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success ->
        "Successfully executed $toolName on Revyl cloud device."
      is xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error ->
        "Error executing $toolName: ${(result.result as xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error).errorMessage}"
    }
  }

  override suspend fun endSession(): Boolean {
    return try {
      revylClient.stopSession()
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

/**
 * No-op [ElementComparator] used when memory-based assertions are not needed.
 */
private object NoOpElementComparator : ElementComparator {
  override fun getElementValue(prompt: String): String? = null
  override fun evaluateBoolean(statement: String): BooleanAssertionTrailblazeTool =
    BooleanAssertionTrailblazeTool(result = true, reason = "No-op comparator")
  override fun evaluateString(query: String): StringEvaluationTrailblazeTool =
    StringEvaluationTrailblazeTool(result = "", reason = "No-op comparator")
  override fun extractNumberFromString(input: String): Double? = null
}
