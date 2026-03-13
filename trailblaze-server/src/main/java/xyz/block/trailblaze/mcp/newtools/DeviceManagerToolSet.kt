package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ktor.util.encodeBase64
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.DeviceAlreadyClaimedException
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.mcp.ViewHierarchyVerbosity
import xyz.block.trailblaze.mcp.utils.ScreenStateCaptureUtil
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.isInteractable
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

/**
 * Minimal MCP tool for device connection.
 *
 * This is the default toolset - provides just the `device` tool for connecting.
 * No session management here - that's for test authoring mode.
 *
 * For screen observation tools, use [ObservationToolSet].
 * For session/trail management, use [SessionManagementToolSet].
 */
@Suppress("unused")
class DeviceManagerToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
  private val deviceClaimRegistry: DeviceClaimRegistry? = null,
  private val toolSetCatalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(true),
  private val onActiveToolSetsChanged: (activeToolSetIds: List<String>, catalog: List<ToolSetCatalogEntry>) -> Unit = { _, _ -> },
) : ToolSet {

  /**
   * Action type for the device tool.
   */
  enum class DeviceAction {
    /** List all available devices */
    LIST,
    /** Connect to a specific device by ID */
    CONNECT,
    /** Auto-connect to the first available Android device */
    ANDROID,
    /** Auto-connect to the first available iOS device */
    IOS,
    /** Show info about the currently connected device */
    INFO,
  }

  /**
   * Detail level for the INFO action.
   */
  enum class DeviceDetail {
    /** Basic device summary (default) */
    SUMMARY,
    /** List installed app IDs */
    APPS,
    /** Full info including installed apps */
    FULL,
  }

  @LLMDescription(
    """
    Connect to a mobile device or get device info.

    device(action=ANDROID) → connect to Android
    device(action=IOS) → connect to iOS
    device(action=LIST) → see available devices
    device(action=INFO) → info about the connected device
    device(action=INFO, detail=APPS) → list installed apps
    device(action=INFO, detail=FULL) → full info including apps

    Your session is recorded automatically.
    Save it anytime as a reusable test: trail(action=SAVE, name="my_test")
    """
  )
  @Tool
  suspend fun device(
    @LLMDescription("Action: LIST, CONNECT, ANDROID, IOS, or INFO")
    action: DeviceAction,
    @LLMDescription("Device ID (only for CONNECT action)")
    deviceId: String? = null,
    @LLMDescription("Force takeover if device is claimed by another session (default: false)")
    force: Boolean = false,
    @LLMDescription("Detail level for INFO action: SUMMARY (default), APPS, or FULL")
    detail: DeviceDetail = DeviceDetail.SUMMARY,
  ): String {
    return when (action) {
      DeviceAction.LIST -> {
        val devices = mcpBridge.getAvailableDevices()
        if (devices.isEmpty()) {
          "No devices available. Connect an Android device/emulator or start an iOS simulator."
        } else {
          // Group by physical device (instanceId + platform) to avoid showing
          // duplicate entries for different driver types of the same device.
          // Show only the device matching the configured driver type per platform.
          val configuredAndroid = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)
          val configuredIos = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)

          val deduped = devices
            .groupBy { it.instanceId to it.platform }
            .map { (_, variants) ->
              val platform = variants.first().platform
              val configuredType = when (platform) {
                TrailblazeDevicePlatform.ANDROID -> configuredAndroid
                TrailblazeDevicePlatform.IOS -> configuredIos
                else -> null
              }
              // Prefer the variant matching the configured driver type
              variants.find { it.trailblazeDriverType == configuredType } ?: variants.first()
            }

          buildString {
            appendLine("Available devices:")
            deduped.forEach { device ->
              appendLine("  - ${device.instanceId} (${device.platform.displayName}) - ${device.description}")
            }
          }
        }
      }

      DeviceAction.INFO -> {
        val currentDeviceId = mcpBridge.getCurrentlySelectedDeviceId()
          ?: return "No device connected. Use device(action=ANDROID) or device(action=IOS) to connect first."

        when (detail) {
          DeviceDetail.SUMMARY -> {
            val driverType = mcpBridge.getDriverType()
            buildString {
              appendLine("Connected device:")
              appendLine("  Instance ID: ${currentDeviceId.instanceId}")
              appendLine("  Platform: ${currentDeviceId.trailblazeDevicePlatform.displayName}")
              if (driverType != null) {
                appendLine("  Driver: $driverType")
              }
            }
          }
          DeviceDetail.APPS -> {
            val apps = mcpBridge.getInstalledAppIds()
            if (apps.isEmpty()) {
              "No installed apps found on device."
            } else {
              buildString {
                appendLine("Installed apps (${apps.size}):")
                apps.sorted().forEach { appendLine("  - $it") }
              }
            }
          }
          DeviceDetail.FULL -> {
            val driverType = mcpBridge.getDriverType()
            val apps = mcpBridge.getInstalledAppIds()
            buildString {
              appendLine("Connected device:")
              appendLine("  Instance ID: ${currentDeviceId.instanceId}")
              appendLine("  Platform: ${currentDeviceId.trailblazeDevicePlatform.displayName}")
              if (driverType != null) {
                appendLine("  Driver: $driverType")
              }
              appendLine()
              appendLine("Installed apps (${apps.size}):")
              apps.sorted().forEach { appendLine("  - $it") }
            }
          }
        }
      }

      DeviceAction.CONNECT -> {
        if (deviceId.isNullOrBlank()) {
          return "Error: deviceId required for CONNECT action. Use LIST to see available devices."
        }
        val devices = mcpBridge.getAvailableDevices()
        val device = devices.find { it.instanceId == deviceId }
          ?: return "Error: Device '$deviceId' not found. Use LIST to see available devices."

        connectToDeviceUnified(device.trailblazeDeviceId, force)
      }

      DeviceAction.ANDROID -> {
        val devices = mcpBridge.getAvailableDevices()
        val configuredDriverType = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)
        // Prefer the device matching the configured driver type from settings.
        // Fall back to any Android device if no configured type matches.
        val androidDevice = if (configuredDriverType != null) {
          devices.find { it.trailblazeDriverType == configuredDriverType }
            ?: devices.find { it.platform == TrailblazeDevicePlatform.ANDROID }
        } else {
          devices.find { it.platform == TrailblazeDevicePlatform.ANDROID }
        }
          ?: return "No Android device available. Connect an Android device or start an emulator."

        connectToDeviceUnified(androidDevice.trailblazeDeviceId, force)
      }

      DeviceAction.IOS -> {
        val devices = mcpBridge.getAvailableDevices()
        val configuredDriverType = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)
        val iosDevice = if (configuredDriverType != null) {
          devices.find { it.trailblazeDriverType == configuredDriverType }
            ?: devices.find { it.platform == TrailblazeDevicePlatform.IOS }
        } else {
          devices.find { it.platform == TrailblazeDevicePlatform.IOS }
        }
          ?: return "No iOS device available. Start an iOS simulator."

        connectToDeviceUnified(iosDevice.trailblazeDeviceId, force)
      }
    }
  }

  private suspend fun connectToDeviceUnified(
    trailblazeDeviceId: TrailblazeDeviceId,
    force: Boolean = false,
  ): String {
    // Check exclusive device claim before connecting
    val mcpSessionId = sessionContext?.mcpSessionId?.sessionId
    if (deviceClaimRegistry != null && mcpSessionId != null) {
      try {
        deviceClaimRegistry.claim(trailblazeDeviceId, mcpSessionId, force)
      } catch (e: DeviceAlreadyClaimedException) {
        return "Error: ${e.message}"
      }
    }

    try {
      mcpBridge.selectDevice(trailblazeDeviceId)
    } catch (e: Exception) {
      // Release only the specific claim we just acquired — not all session claims.
      // The session may have a valid claim on a different device that should be preserved.
      if (deviceClaimRegistry != null && mcpSessionId != null) {
        deviceClaimRegistry.release(trailblazeDeviceId, mcpSessionId)
      }
      throw e
    }

    sessionContext?.setAssociatedDevice(trailblazeDeviceId)
    // Ensure a session exists and emit the session start log on connect
    mcpBridge.ensureSessionAndGetId()
    // Start implicit recording - user can save later with trail(action=SAVE, name="...")
    sessionContext?.startImplicitRecording()
    return "Connected to ${trailblazeDeviceId.instanceId} (${trailblazeDeviceId.trailblazeDevicePlatform.displayName}). Session recording - save anytime with trail(action=SAVE, name='...')"
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Individual tools from main (with TOOL_* constant pattern)
  // ─────────────────────────────────────────────────────────────────────────────

  @LLMDescription("Installed apps")
  @Tool(TOOL_GET_INSTALLED_APPS)
  suspend fun getInstalledApps(): String {
    val packages = mcpBridge.getInstalledAppIds()
    return packages.sorted().joinToString("\n")
  }

  @LLMDescription("List available app targets.")
  @Tool(TOOL_LIST_DEVICES)
  suspend fun listConnectedDevices(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableAppTargets().map { it.id }
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
    @LLMDescription("The ID of the app target to switch to (e.g., 'myApp', 'otherApp'). Must match one of the available app target IDs.")
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
    val yaml = createTrailblazeYaml().encodeToString(
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
      startNewSession = false,
      agentImplementation = sessionContext?.agentImplementation ?: AgentImplementation.DEFAULT,
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
    """Enable additional tool sets for device interaction. By default, only core tools (tap, input text) are available.
Use this to enable more tools when needed. You can enable multiple tool sets at once.
Call with an empty list to reset to only the core tools.""",
  )
  @Tool(TOOL_SET_ACTIVE_TOOLSETS)
  fun setActiveToolSets(
    @LLMDescription("The list of toolset IDs to enable (e.g. ['navigation', 'text-editing']). Core tools are always included.")
    toolSetIds: List<String>,
  ): String {
    val validIds = toolSetCatalog.map { it.id }.toSet()
    val invalidIds = toolSetIds.filter { it !in validIds }
    if (invalidIds.isNotEmpty()) {
      return "Unknown toolset IDs: $invalidIds. Valid IDs: ${validIds.filter { id -> !toolSetCatalog.first { it.id == id }.alwaysEnabled }}"
    }

    onActiveToolSetsChanged(toolSetIds, toolSetCatalog)

    val resolvedTools = TrailblazeToolSetCatalog.resolve(toolSetIds, toolSetCatalog)
    val toolNames = resolvedTools.map {
        it.simpleName?.removeSuffix("TrailblazeTool")?.removeSuffix("Tool") ?: it.toString()
      }
    return buildString {
      appendLine("Active tool sets updated.")
      appendLine("Enabled sets: ${(toolSetIds + "core").distinct()}")
      appendLine("Total tools available: ${resolvedTools.size}")
      appendLine("Tools: $toolNames")
    }
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
    const val TOOL_SET_ACTIVE_TOOLSETS = "setActiveToolSets"
  }
}

/**
 * Session/Trail management tools for test authoring.
 *
 * NOT registered by default - these are for test authoring workflows
 * where you're creating trails and need explicit session boundaries.
 *
 * For exploration/automation, you don't need these - just connect and use two-tier tools.
 */
@Suppress("unused")
class SessionManagementToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  @LLMDescription(
    """
    End the current trail/session. Use this when test authoring is complete.

    This marks the trail as done and finalizes any recording.
    For exploration/automation, you typically don't need this.
    """
  )
  @Tool
  suspend fun endSession(): String {
    val wasSessionEnded = mcpBridge.endSession()
    sessionContext?.clearAssociatedDevice()
    return if (wasSessionEnded) "Session ended. Trail finalized." else "No active session to end."
  }
}

/**
 * Observation tools for screen state inspection.
 *
 * NOT registered by default - these tools return large payloads (screenshots, full hierarchy)
 * that should stay out of the primary context window.
 *
 * Enable via: enableToolCategories(["OBSERVATION"])
 *
 * The two-tier pattern tools (getNextActionRecommendation) handle screen state internally
 * without polluting the outer agent's context.
 */
@Suppress("unused")
class ObservationToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
) : ToolSet {

  private suspend fun getScreenStateDirectly() = ScreenStateCaptureUtil.captureScreenState(mcpBridge)

  @LLMDescription(
    """
    Get current screen state (view hierarchy and optional screenshot).

    WARNING: Returns large payload. Prefer getNextActionRecommendation for UI automation
    to keep screen data out of your context window.

    Use this only when you need to manually inspect screen state.
    """
  )
  @Tool
  suspend fun getScreenState(
    @LLMDescription("Include base64 screenshot (default: true)")
    includeScreenshot: Boolean = true,
    @LLMDescription("Verbosity: MINIMAL, STANDARD, or FULL")
    verbosity: ViewHierarchyVerbosity? = null,
  ): String {
    val screenState = getScreenStateDirectly()
      ?: return "No screen state available. Is a device connected?"

    val effectiveVerbosity = verbosity
      ?: sessionContext?.viewHierarchyVerbosity
      ?: ViewHierarchyVerbosity.MINIMAL

    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchyOriginal)

    val viewHierarchyText = when (effectiveVerbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchyOriginal)
    }

    return if (includeScreenshot) {
      val screenshot = screenState.screenshotBytes?.encodeBase64() ?: "Screenshot unavailable"
      """
      |== View Hierarchy ==
      |$viewHierarchyText
      |
      |== Screenshot (base64 PNG) ==
      |$screenshot
      """.trimMargin()
    } else {
      viewHierarchyText
    }
  }

  @LLMDescription(
    """
    Get view hierarchy only (no screenshot).

    Verbosity levels:
    - MINIMAL: Interactable elements with coordinates
    - STANDARD: Interactable elements with descriptions and hierarchy
    - FULL: Complete view hierarchy including non-interactable elements
    """
  )
  @Tool
  suspend fun viewHierarchy(
    @LLMDescription("Verbosity: MINIMAL, STANDARD, or FULL")
    verbosity: ViewHierarchyVerbosity? = null,
  ): String {
    val screenState = getScreenStateDirectly()
      ?: return "No screen state available. Is a device connected?"

    val effectiveVerbosity = verbosity
      ?: sessionContext?.viewHierarchyVerbosity
      ?: ViewHierarchyVerbosity.MINIMAL

    val vhFilter = ViewHierarchyFilter.create(
      screenWidth = screenState.deviceWidth,
      screenHeight = screenState.deviceHeight,
      platform = screenState.trailblazeDevicePlatform,
    )
    val filtered = vhFilter.filterInteractableViewHierarchyTreeNodes(screenState.viewHierarchyOriginal)

    return when (effectiveVerbosity) {
      ViewHierarchyVerbosity.MINIMAL -> buildMinimalViewHierarchy(filtered)
      ViewHierarchyVerbosity.STANDARD -> buildViewHierarchyDescription(filtered)
      ViewHierarchyVerbosity.FULL -> buildFullViewHierarchy(screenState.viewHierarchyOriginal)
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // View hierarchy formatting helpers
  // ─────────────────────────────────────────────────────────────────────────────

  private fun buildMinimalViewHierarchy(node: ViewHierarchyTreeNode): String {
    val elements = mutableListOf<String>()
    collectInteractableElements(node, elements)
    return if (elements.isEmpty()) {
      "No interactable elements found on screen."
    } else {
      elements.joinToString("\n")
    }
  }

  private fun collectInteractableElements(node: ViewHierarchyTreeNode, elements: MutableList<String>) {
    if (node.isInteractable()) {
      val selector = node.asTrailblazeElementSelector()
      val description = selector?.description() ?: node.className
      val position = node.centerPoint?.let { "@($it)" } ?: ""
      elements.add("- $description $position")
    }
    node.children.forEach { child -> collectInteractableElements(child, elements) }
  }

  private fun buildFullViewHierarchy(node: ViewHierarchyTreeNode, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val className = node.className ?: "Unknown"
    val text = node.text?.let { " '$it'" } ?: ""
    val resourceId = node.resourceId?.let { " [$it]" } ?: ""
    val position = node.centerPoint?.let { " @($it)" } ?: ""
    val interactable = if (node.isInteractable()) " *" else ""

    val thisLine = "$indent$className$text$resourceId$position$interactable"

    val childDescriptions = node.children
      .map { child -> buildFullViewHierarchy(child, depth + 1) }
      .filter { it.isNotBlank() }

    return listOf(thisLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }

  private fun buildViewHierarchyDescription(node: ViewHierarchyTreeNode, depth: Int = 0): String {
    val indent = "  ".repeat(depth)
    val selectorDescription = node.asTrailblazeElementSelector()?.description()
    val centerPoint = node.centerPoint

    val thisNodeLine = if (selectorDescription != null) {
      val positionSuffix = centerPoint?.let { " @$it" } ?: ""
      "$indent$selectorDescription$positionSuffix"
    } else {
      null
    }

    val childDepth = if (selectorDescription != null) depth + 1 else depth
    val childDescriptions = node.children
      .map { child -> buildViewHierarchyDescription(child, childDepth) }
      .filter { it.isNotBlank() }

    return listOfNotNull(thisNodeLine)
      .plus(childDescriptions)
      .joinToString("\n")
  }
}
