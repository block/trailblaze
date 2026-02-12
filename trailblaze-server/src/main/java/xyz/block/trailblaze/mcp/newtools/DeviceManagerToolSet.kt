package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

// --- Koog ToolSets ---
@Suppress("unused")
class DeviceManagerToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val toolRegistryUpdated: (ToolRegistry) -> Unit,
  private val targetTestAppProvider: () -> TrailblazeHostAppTarget,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  @LLMDescription("Installed apps")
  @Tool(TOOL_GET_INSTALLED_APPS)
  suspend fun getInstalledApps(): String {
    val packages = mcpBridge.getInstalledAppIds()
    return packages.sorted().joinToString("\n")
  }

  @LLMDescription("List connected devices.")
  @Tool(TOOL_LIST_DEVICES)
  suspend fun listConnectedDevices(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableDevices()
    )
  }

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool(TOOL_CONNECT_DEVICE)
  suspend fun connectToDevice(trailblazeDeviceId: TrailblazeDeviceId): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.selectDevice(trailblazeDeviceId)
    )
  }


  @LLMDescription("Get available app targets.")
  @Tool(TOOL_GET_APP_TARGETS)
  fun getAvailableAppTargets(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableAppTargets().map { it.id }
    )
  }

  @LLMDescription("Get the currently selected target app ID.")
  @Tool(TOOL_GET_CURRENT_TARGET)
  fun getCurrentTargetApp(): String {
    return mcpBridge.getCurrentAppTargetId() ?: "No target app selected."
  }

  @LLMDescription("Switch the current target app. Use `${TOOL_GET_APP_TARGETS}` to see valid app target IDs.")
  @Tool(TOOL_SWITCH_TARGET)
  fun switchTargetApp(
    @LLMDescription("The ID of the app target to switch to (e.g., 'cash', 'square'). Must match one of the available app target IDs.")
    appTargetId: String,
  ): String {
    val displayName = mcpBridge.selectAppTarget(appTargetId)
    return if (displayName != null) {
      "Switched target app to: $displayName ($appTargetId)"
    } else {
      val availableIds = mcpBridge.getAvailableAppTargets().map { it.id }
      "Failed to switch target app. '$appTargetId' not found. Available targets: $availableIds"
    }
  }

  @LLMDescription(
    "Runs a natural language prompt on the connected device.",
  )
  @Tool(TOOL_RUN_PROMPT)
  suspend fun runPrompt(
    @LLMDescription(
      """
      The natural language steps you would like performed on the device.
      NOTE: The more steps you give, the longer it will take to perform the tasks.  Prefer fewer steps.
      """
    )
    steps: List<String>,
  ): String {
    val yaml = TrailblazeYaml.Default.encodeToString(
      TrailblazeYamlBuilder()
        .apply {
          steps.forEach { promptLine ->
            this.prompt(promptLine)
          }
        }
        .build()
    )

    val sessionId = mcpBridge.runYaml(
      yaml = yaml,
      startNewSession = false
    )

    return buildString {
      appendLine("Execution started.")
      appendLine("Steps: ${steps.size}")
      if (sessionId != null) {
        appendLine("Session ID: $sessionId")
        appendLine()
        appendLine("The test is now running asynchronously on the device.")
        appendLine("Wait at least 30 seconds before calling getSessionResults with this session ID.")
        appendLine("If the status is still IN PROGRESS, wait another 15-30 seconds and check again.")
      }
    }
  }

  @LLMDescription(
    "End a running Trailblaze session on the connected device.",
  )
  @Tool(TOOL_END_SESSION)
  suspend fun endSession(): String {
    val wasSessionEnded = mcpBridge.endSession()
    return "Session ended with result: $wasSessionEnded"
  }

  @LLMDescription(
    "This changes the enabled Trailblaze ToolSets.  This will change what tools are available to the Trailblaze device control agent.",
  )
  @Tool(TOOL_SET_TOOLSETS)
  fun setAndroidToolSets(
    @LLMDescription("The list of Trailblaze ToolSet Names to enable.  Find available ToolSet IDs with the listToolSets tool.  There is an exact match on the name, so be sure to use the correct name(s).")
    toolSetNames: List<String>,
  ): String {
    return "NOT IMPLEMENTED"
  }

  companion object {
    // Tool names - referenced in @Tool annotations and LLM descriptions
    const val TOOL_GET_INSTALLED_APPS = "getInstalledApps"
    const val TOOL_LIST_DEVICES = "listConnectedDevices"
    const val TOOL_CONNECT_DEVICE = "connectToDevice"
    const val TOOL_GET_APP_TARGETS = "getAvailableAppTargets"
    const val TOOL_GET_CURRENT_TARGET = "getCurrentTargetApp"
    const val TOOL_SWITCH_TARGET = "switchTargetApp"
    const val TOOL_RUN_PROMPT = "runPrompt"
    const val TOOL_END_SESSION = "endSession"
    const val TOOL_SET_TOOLSETS = "setAndroidToolSets"
  }
}
