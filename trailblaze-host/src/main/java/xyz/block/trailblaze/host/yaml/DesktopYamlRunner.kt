package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils

class DesktopYamlRunner(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  private val trailblazeHostAppTargetProvider: () -> TrailblazeHostAppTarget,
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
) {

  /**
   * Shortens device description by removing UUID identifiers.
   * Example: "iPhone 16 Pro - iOS 18.4 - 55B5483E-EE63-4605-91DE-B061F19B9D1E" -> "iPhone 16 Pro - iOS 18.4"
   */
  private fun shortenDeviceDescription(description: String): String {
    // Match and remove UUID pattern (8-4-4-4-12 hex digits)
    val uuidPattern =
      Regex(" - [0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    return description.replace(uuidPattern, "")
  }

  /**
   * Executes a YAML test on the specified device, automatically choosing between
   * on-device instrumentation or host-based execution.
   *
   * This is a non-composable suspend function that can be called directly if you
   * don't need the composable wrapper.
   */
  fun runYaml(desktopAppRunYamlParams: DesktopAppRunYamlParams) {
    val targetTestApp = desktopAppRunYamlParams.targetTestApp
    val trailblazeDeviceId = desktopAppRunYamlParams.runYamlRequest.trailblazeDeviceId
    val forceStopTargetApp = desktopAppRunYamlParams.forceStopTargetApp
    val runYamlRequest = desktopAppRunYamlParams.runYamlRequest
    val onProgressMessage = desktopAppRunYamlParams.onProgressMessage
    val onConnectionStatus = desktopAppRunYamlParams.onConnectionStatus
    val additionalInstrumentationArgs = desktopAppRunYamlParams.additionalInstrumentationArgs

    trailblazeDeviceManager.createNewCoroutineScopeForDevice(trailblazeDeviceId).launch {
      println("🚀 COROUTINE STARTED for device: ${trailblazeDeviceId.instanceId}")

      val connectedTrailblazeDevice = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
        ?: trailblazeDeviceManager.loadDevicesSuspend().firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }

      if (connectedTrailblazeDevice == null) {
        onProgressMessage("Device with ID $trailblazeDeviceId not found")
        println("❌ COROUTINE ENDING (device not found) for device: ${trailblazeDeviceId.instanceId}")
        return@launch
      }

      // Wrap progress message callback to add device prefix
      val shortenedDescription = shortenDeviceDescription(trailblazeDeviceId.instanceId)
      val devicePrefix = "[$shortenedDescription]"
      val prefixedProgressMessage: (String) -> Unit = { message ->
        onProgressMessage("$devicePrefix $message")
      }

      if (forceStopTargetApp) {
        val possibleAppIds = targetTestApp?.getPossibleAppIdsForPlatform(
          trailblazeDeviceId.trailblazeDevicePlatform
        ) ?: emptySet()
        MobileDeviceUtils.ensureAppsAreForceStopped(possibleAppIds, trailblazeDeviceId)
      }

      val trailblazeDriverType = connectedTrailblazeDevice.trailblazeDriverType
      try {
        trailblazeAnalytics.runTest(trailblazeDriverType, desktopAppRunYamlParams)
        prefixedProgressMessage(
          "Starting ${trailblazeDeviceId.trailblazeDevicePlatform.displayName} test on device ${trailblazeDeviceId.instanceId} with driver type $trailblazeDriverType",
        )

        val trailblazeHostAppTarget = trailblazeHostAppTargetProvider()

        when (trailblazeDriverType) {
          TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()
            HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
              trailblazeOnDeviceInstrumentationTargetTestApps = setOf(trailblazeOnDeviceInstrumentationTarget),
              deviceId = connectedTrailblazeDevice.trailblazeDeviceId,
            )

            val onDeviceRpc = OnDeviceRpcClient(
              trailblazeDeviceId = trailblazeDeviceId,
              sendProgressMessage = prefixedProgressMessage
            )

            runYamlOnDevice(
              onDeviceRpc = onDeviceRpc,
              trailblazeConnectedDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              runYamlRequest = runYamlRequest,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
            )
          }

          else -> {
            TrailblazeHostYamlRunner.runHostYaml(
              dynamicLlmClient = dynamicLlmClientProvider(desktopAppRunYamlParams.runYamlRequest.trailblazeLlmModel),
              runOnHostParams = RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = connectedTrailblazeDevice,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                targetTestApp = targetTestApp,
                additionalInstrumentationArgs = {
                  // Not required since this is "host", but is required "on-device"
                  emptyMap()
                },
              ),
              deviceManager = trailblazeDeviceManager,
            )

            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
          }
        }
      } catch (e: Exception) {
        println("⚠️ EXCEPTION in coroutine for device ${trailblazeDeviceId.instanceId}: ${e::class.simpleName} - ${e.message}")
        prefixedProgressMessage("Error: ${e.message}")
        onConnectionStatus(
          DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
            errorMessage = e.message ?: "Unknown error",
          ),
        )
      } finally {
        println("🏁 COROUTINE FINISHED (finally block) for device: ${trailblazeDeviceId.instanceId}")
      }
    }
  }

  /**
   * Executes YAML test on a device using instrumentation.
   */
  private suspend fun runYamlOnDevice(
    onDeviceRpc: OnDeviceRpcClient,
    trailblazeConnectedDevice: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    runYamlRequest: RunYamlRequest,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    onProgressMessage: (String) -> Unit,
    additionalInstrumentationArgs: Map<String, String>,
  ): SessionId? {
    return withContext(Dispatchers.IO) {
      val status = HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = trailblazeConnectedDevice.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        onDeviceRpc.verifyServerIsRunning()
        when (val result: RpcResult<RunYamlResponse> = onDeviceRpc.rpcCall(runYamlRequest)) {
          is RpcResult.Failure -> {
            onProgressMessage("Failed to start YAML execution: ${result.message}")
            null
          }

          is RpcResult.Success -> {
            val runYamlResponse = result.data
            onProgressMessage("YAML test execution started for session: ${runYamlResponse.sessionId}")
            runYamlResponse.sessionId
          }
        }
      }
    }
  }
}
