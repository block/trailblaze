package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getDeviceSpecificPort
import xyz.block.trailblaze.mcp.TrailblazeMcpSseSessionContext
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.McpPromptRequestData
import xyz.block.trailblaze.mcp.android.ondevice.rpc.models.SelectToolSet
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils.getDeviceModelName

// --- Koog ToolSets ---
@Suppress("unused")
class AndroidOnDeviceFromHostToolSet(
  private val sessionContext: TrailblazeMcpSseSessionContext?,
  private val toolRegistryUpdated: (ToolRegistry) -> Unit,
  private val targetTestAppProvider: () -> TrailblazeHostAppTarget,
) : ToolSet {

  // Store active connection processes and their status
  private var activeAdbOnDeviceConnections: DeviceConnectionStatus? = null

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  fun sayHi(me: String?): String = "Hello from $me!"

  @LLMDescription("Installed apps")
  @Tool
  fun getInstalledApps(): String {
    val activeConnection = activeAdbOnDeviceConnections as? DeviceConnectionStatus.WithTargetDevice
      ?: return "No device connected."

    val packages = AndroidHostAdbUtils.listInstalledPackages(activeConnection.trailblazeDeviceId)
    return packages
      .sorted()
      .joinToString("\n")
  }


  @LLMDescription("List connected devices.")
  @Tool
  fun listConnectedDevices(): String {
    return "Not implemented."
  }

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool
  fun connectAndroidDevice(): String {
    val connectionStatus = runBlocking { connectAndroidDeviceInternal(targetTestAppProvider()) }
    return when (connectionStatus) {
      is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure -> {
        "Connection failed: ${connectionStatus.errorMessage}"
      }

      is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning -> {
        activeAdbOnDeviceConnections = connectionStatus
        "Successfully connected to device ${connectionStatus.trailblazeDeviceId}. Trailblaze instrumentation is running."
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
      return DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
        errorMessage = "No devices found. Please ensure your device is connected and ADB is running.",
      )
    }
    if (adbDevices.size > 1) {
      return DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
        "Multiple devices found. Please specify a device ID to connect to.  Available Devices: ${adbDevices.joinToString { it.instanceId }}.",
      )
    }

    if (sessionContext == null) {
      return DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
        "Error: Session context is null. Cannot send progress messages.",
      )
    }

    val device = adbDevices.first()
    val deviceId = TrailblazeDeviceId(
      instanceId = device.instanceId,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    )

    // Start the connection process in the background
    return try {
      val androidDeviceName = getDeviceModelName(deviceId)
      sessionContext.sendIndeterminateProgressMessage(
        "Starting connection process for device: $androidDeviceName (${deviceId.instanceId})",
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
        "Failed to start connection process for device: ${device.instanceId}. Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(
        errorMessage,
      )
      DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
        errorMessage = errorMessage,
      )
    }
  }

  val ioScope = CoroutineScope(Dispatchers.IO)

  private fun getActiveConnection(): DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning? = run {
    activeAdbOnDeviceConnections as? DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning
  }

  private fun isThereAnActiveConnection(): Boolean = getActiveConnection() != null

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

    val activeConnection: DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning =
      getActiveConnection() ?: return "A device must be connected first."

    try {
      return runBlocking {
        val rpcClient = OnDeviceRpcClient(activeConnection.trailblazeDeviceId)
        val result: RpcResult<String> = rpcClient.rpcCall(SelectToolSet(toolSetNames))
        when (result) {
          is RpcResult.Success -> result.data
          is RpcResult.Failure -> "Error: ${result.message}${result.details?.let { " - $it" } ?: ""}"
        }
      }
    } catch (e: Exception) {
      val errorMessage =
        "Exception sending HTTP request to device ${activeConnection.trailblazeDeviceId}. Error: ${e.message}"
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

    val activeConnection: DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning =
      getActiveConnection() ?: return "A device must be connected first."

    return sendPromptToAndroidOnDevice(
      originalPrompt = prompt,
      steps = listOf(prompt),
      trailblazeDeviceId = activeConnection.trailblazeDeviceId,
      sessionContext = sessionContext,
    )
  }

  private fun sendPromptToAndroidOnDevice(
    originalPrompt: String,
    steps: List<String>,
    trailblazeDeviceId: TrailblazeDeviceId,
    sessionContext: TrailblazeMcpSseSessionContext,
  ): String {
    println("Sending prompt $steps to device $trailblazeDeviceId.")

    val deviceSpecificPort = trailblazeDeviceId.getDeviceSpecificPort()
    sessionContext.sendIndeterminateProgressMessage(
      "Setting up port forwarding for device $trailblazeDeviceId on port $deviceSpecificPort.",
    )
    // This tool sends a prompt to the local server running on a device-specific port
    try {
      AndroidHostAdbUtils.adbPortForward(
        deviceId = trailblazeDeviceId,
        localPort = deviceSpecificPort
      )
    } catch (e: Exception) {
      return "Failed to set up port forwarding for device $trailblazeDeviceId on port $deviceSpecificPort. Error: ${e.message}"
    }

    val promptRequestData = McpPromptRequestData(
      fullPrompt = originalPrompt,
      steps = steps,
    )

    try {
      sessionContext.sendIndeterminateProgressMessage(
        "Running prompt on device $trailblazeDeviceId with steps: ${steps.joinToString(", ")}.",
      )
      val onDeviceRpc = OnDeviceRpcClient(trailblazeDeviceId)
      return runBlocking {
        val result: RpcResult<String> = onDeviceRpc.rpcCall(promptRequestData)
        when (result) {
          is RpcResult.Success -> result.data
          is RpcResult.Failure -> "Error: ${result.message}${result.details?.let { " - $it" } ?: ""}"
        }
      }
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device $trailblazeDeviceId. Error: ${e.message}"
      sessionContext.sendIndeterminateProgressMessage(errorMessage)
      return errorMessage
    }
  }
}
