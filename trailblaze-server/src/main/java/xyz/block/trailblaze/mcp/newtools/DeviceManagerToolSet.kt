package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

// --- Koog ToolSets ---
@Suppress("unused")
class DeviceManagerToolSet(
  private val sessionContext: TrailblazeMcpSseSessionContext?,
  private val toolRegistryUpdated: (ToolRegistry) -> Unit,
  private val targetTestAppProvider: () -> TrailblazeHostAppTarget,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  fun sayHi(me: String?): String = "Hello from $me!"

  @LLMDescription("Installed apps")
  @Tool
  suspend fun getInstalledApps(): String {
    val packages = mcpBridge.getInstalledAppIds()
    return packages
      .sorted()
      .joinToString("\n")
  }


  @LLMDescription("List connected devices.")
  @Tool
  suspend fun listConnectedDevices(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableDevices()
    )
  }

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  suspend fun connectAndroidDevice(trailblazeDeviceId: TrailblazeDeviceId): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.selectDevice(trailblazeDeviceId)
    )
  }


  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  suspend fun getAvailableAppTargets(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableAppTargets()
    )
  }


  @LLMDescription(
    "Runs a natural language prompt on the connected device.",
  )
  @Tool
  suspend fun runPrompt(
    @LLMDescription(
      """
      The natural language steps you would like performed on the device.
      NOTE: The more steps you give, the longer it will take to perform the tasks.  Prefer fewer steps.
      """
    )
    steps: List<String>,
  ): String {
    val yaml = TrailblazeYaml().encodeToString(
      TrailblazeYamlBuilder()
        .apply {
          steps.forEach { promptLine ->
            this.prompt(promptLine)
          }
        }
        .build())

    mcpBridge.runYaml(yaml)
    return "Ran Trailblaze Yaml: $yaml"
  }

  @LLMDescription(
    "This changes the enabled Trailblaze ToolSets.  This will change what tools are available to the Trailblaze device control agent.",
  )
  @Tool
  fun setAndroidToolSets(
    @LLMDescription("The list of Trailblaze ToolSet Names to enable.  Find available ToolSet IDs with the listToolSets tool.  There is an exact match on the name, so be sure to use the correct name(s).")
    toolSetNames: List<String>,
  ): String {
    return "NOT IMPLEMENTED"
  }
}
