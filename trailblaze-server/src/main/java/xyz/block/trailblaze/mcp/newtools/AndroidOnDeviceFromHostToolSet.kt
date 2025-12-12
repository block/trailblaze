package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpc
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.McpPromptRequestData
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.SelectToolSet
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils

// --- Koog ToolSets ---
@Suppress("unused")
class AndroidOnDeviceFromHostToolSet(
  private val sessionContext: TrailblazeMcpSseSessionContext?,
  private val toolRegistryUpdated: (ToolRegistry) -> Unit,
  private val targetTestAppProvider: () -> TrailblazeHostAppTarget,
) : ToolSet {

  companion object Companion {
    const val ON_DEVICE_ANDROID_MCP_SERVER_PORT = 52526
  }

  // Store active connection processes and their status
  private var activeAdbOnDeviceConnections: DeviceConnectionStatus = DeviceConnectionStatus.NoConnection(
    deviceId = null,
  )

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  fun sayHi(me: String?): String = "Hello from $me!"


  @LLMDescription("Installed apps")
  @Tool
  fun getInstalledApps(): String {
    val result = AndroidHostAdbUtils.execAdbShellCommand(deviceId = null, args = listOf("pm", "list", "packages"))
    val packages = result.lines().map { it.substringAfter("package:") }
    return packages
      .sorted()
      .joinToString("\n")
  }

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  fun connectAndroidDevice(): String {
    val connectionStatus = runBlocking { connectAndroidDeviceInternal(targetTestAppProvider()) }
    return when (connectionStatus) {
      is DeviceConnectionStatus.ConnectionFailure -> {
        "Connection failed: ${connectionStatus.errorMessage}"
      }

      is DeviceConnectionStatus.TrailblazeInstrumentationRunning -> {
        activeAdbOnDeviceConnections = connectionStatus
        "Successfully connected to device ${connectionStatus.deviceId}. Trailblaze instrumentation is running."
      }

      else -> {
        "Unexpected connection status: ${connectionStatus.statusText}"
      }
    }
  }

  suspend fun connectAndroidDeviceInternal(targetTestApp: TrailblazeHostAppTarget): DeviceConnectionStatus {
    if (isThereAnActiveConnection()) {
      return getActiveConnection() ?: error("No active connection")
    }

    val adbDevices = HostAndroidDeviceConnectUtils.getAdbDevices()
    if (adbDevices.isEmpty()) {
      return DeviceConnectionStatus.ConnectionFailure(
        errorMessage = "No devices found. Please ensure your device is connected and ADB is running.",
        deviceId = null,
      )
    }
    if (adbDevices.size > 1) {
      return DeviceConnectionStatus.ConnectionFailure(
        "Multiple devices found. Please specify a device ID to connect to.  Available Devices: ${adbDevices.joinToString { it.id }}.",
        deviceId = null,
      )
    }

    if (sessionContext == null) {
      return DeviceConnectionStatus.ConnectionFailure(
        "Error: Session context is null. Cannot send progress messages.",
        deviceId = adbDevices.first().id,
      )
    }

    val device = adbDevices.first()
    val deviceId = device.id

    // Start the connection process in the background
    return try {
      sessionContext.sendIndeterminateProgressMessage(
        "Starting connection process for device: ${device.name} ($deviceId)",
      )
      val deviceConnectionStatus: DeviceConnectionStatus =
        HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
          sendProgressMessage = { sessionContext.sendIndeterminateProgressMessage(it) },
          deviceId = deviceId,
          trailblazeOnDeviceInstrumentationTarget = targetTestApp.getTrailblazeOnDeviceInstrumentationTarget(),
        )

      return deviceConnectionStatus
    } catch (e: Exception) {
      val errorMessage =
        "Failed to start connection process for device: ${device.name} (${device.id}). Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(
        errorMessage,
      )
      DeviceConnectionStatus.ConnectionFailure(
        errorMessage = errorMessage,
        deviceId = deviceId,
      )
    }
  }

  val ioScope = CoroutineScope(Dispatchers.IO)

  private fun getActiveConnection(): DeviceConnectionStatus.TrailblazeInstrumentationRunning? = run {
    activeAdbOnDeviceConnections as? DeviceConnectionStatus.TrailblazeInstrumentationRunning
  }

  private fun isThereAnActiveConnection(): Boolean = getActiveConnection() != null

  @LLMDescription(
    "Call this to list the available Trailblaze Tool Sets on the connected device.",
  )
  @Tool
  fun listAndroidToolSets(): String {
    if (sessionContext == null) {
      return "Session context is null. Cannot send progress messages or connect to device."
    }

    val activeConnection: DeviceConnectionStatus.TrailblazeInstrumentationRunning? = getActiveConnection()
    if (activeConnection == null) {
      return "A device must be connected first."
    }

    if (activeConnection.deviceId == null) {
      return "Device status reported connected, but deviceId was unavailable."
    }

    try {
      val psuedoRpcResult = OnDeviceRpc(ON_DEVICE_ANDROID_MCP_SERVER_PORT) {
        sessionContext.sendIndeterminateProgressMessage(it)
      }.listAvailableToolSets()
      return psuedoRpcResult
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device ${activeConnection.deviceId}. Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(errorMessage)
      return errorMessage
    }
  }

  @LLMDescription(
    "This changes the enabled Trailblaze ToolSets.  This will change what tools are available to the Trailblaze device control agent.",
  )
  @Tool
  fun setAndroidToolSets(
    @LLMDescription("The list of Trailblaze ToolSet Names to enable.  Find available ToolSet IDs with the listToolSets tool.  There is an exact match on the name, so be sure to use the correct name(s).")
    toolSetNames: List<String>,
  ): String {
    if (sessionContext == null) {
      return "Session context is null. Cannot send progress messages or connect to device."
    }

    val activeConnection: DeviceConnectionStatus.TrailblazeInstrumentationRunning? = getActiveConnection()
    if (activeConnection == null) {
      return "A device must be connected first."
    }

    if (activeConnection.deviceId == null) {
      return "Device status reported connected, but deviceId was unavailable."
    }

    try {
      val psuedoRpcResult = OnDeviceRpc(ON_DEVICE_ANDROID_MCP_SERVER_PORT) {
        sessionContext.sendIndeterminateProgressMessage(it)
      }.setToolSets(SelectToolSet(toolSetNames))
      return psuedoRpcResult
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device ${activeConnection.deviceId}. Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(errorMessage)
      return errorMessage
    }
  }

  @LLMDescription(
    """
Send a natural language instruction to control the currently connected device.
Use this when someone requests any user action.
The prompt/action/request will be sent to the mobile device to be run.
""",
  )
  @Tool
  fun promptAndroid(
    @LLMDescription("The original prompt.")
    prompt: String,
  ): String {
    if (sessionContext == null) {
      return "Session context is null. Cannot send progress messages or connect to device."
    }

    val activeConnection: DeviceConnectionStatus.TrailblazeInstrumentationRunning? = getActiveConnection()
    if (activeConnection == null) {
      return "A device must be connected first."
    }

    if (activeConnection.deviceId == null) {
      return "Device status reported connected, but deviceId was unavailable."
    }

    return sendPromptToAndroidOnDevice(
      originalPrompt = prompt,
      steps = listOf(prompt),
      deviceId = activeConnection.deviceId!!,
      sessionContext = sessionContext,
    )
  }

  private fun sendPromptToAndroidOnDevice(
    originalPrompt: String,
    steps: List<String>,
    deviceId: String,
    sessionContext: TrailblazeMcpSseSessionContext,
  ): String {
    println("Sending prompt $steps to device $deviceId.")

    sessionContext.sendIndeterminateProgressMessage(
      "Setting up port forwarding for device $deviceId on port $ON_DEVICE_ANDROID_MCP_SERVER_PORT.",
    )
    // This tool sends a prompt to the local server running on port 52526
    try {
      AndroidHostAdbUtils.adbPortForward(deviceId, ON_DEVICE_ANDROID_MCP_SERVER_PORT)
    } catch (e: Exception) {
      return "Failed to set up port forwarding for device $deviceId on port $ON_DEVICE_ANDROID_MCP_SERVER_PORT. Error: ${e.message}"
    }

    val promptRequestData = McpPromptRequestData(
      fullPrompt = originalPrompt,
      steps = steps,
    )

    try {
      sessionContext.sendIndeterminateProgressMessage(
        "Running prompt on device $deviceId with steps: ${steps.joinToString(", ")}.",
      )
      val psuedoRpcResult = OnDeviceRpc(ON_DEVICE_ANDROID_MCP_SERVER_PORT) {
        sessionContext.sendIndeterminateProgressMessage(it)
      }.prompt(promptRequestData)
      return psuedoRpcResult
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device $deviceId. Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(errorMessage)
      return errorMessage
    }
  }
}
