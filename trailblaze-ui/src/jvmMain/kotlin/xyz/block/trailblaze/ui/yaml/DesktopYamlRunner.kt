package xyz.block.trailblaze.ui.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.mcp.models.DeviceConnectionStatus
import xyz.block.trailblaze.mcp.utils.DeviceConnectUtils
import xyz.block.trailblaze.model.RunOnHostParams
import xyz.block.trailblaze.model.TargetTestApp

class DesktopYamlRunner(
  private val targetTestApp: TargetTestApp,
  private val onRunHostYaml: (RunOnHostParams) -> Unit,
) {
  /**
   * Executes a YAML test on the specified device, automatically choosing between
   * on-device instrumentation or host-based execution.
   *
   * This is a non-composable suspend function that can be called directly if you
   * don't need the composable wrapper.
   */
  suspend fun runYaml(
    device: TrailblazeConnectedDeviceSummary,
    runYamlRequest: RunYamlRequest,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
  ) {
    withContext(Dispatchers.IO) {
      try {
        onProgressMessage("Starting YAML test execution with driver ${device.trailblazeDriverType} : on device (${device.description})")

        when (device.trailblazeDriverType) {
          TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION -> {
            DeviceConnectUtils.uninstallAllAndroidInstrumentationProcesses(
              targetTestApps = listOf(
                targetTestApp,
                TargetTestApp.DEFAULT_ANDROID_ON_DEVICE
              ),
              deviceId = null
            )
            runYamlOnDevice(
              device = device,
              targetTestApp = targetTestApp,
              runYamlRequest = runYamlRequest,
              onProgressMessage = onProgressMessage,
              onConnectionStatus = onConnectionStatus,
            )
          }

          else -> {
            onRunHostYaml(
              RunOnHostParams(
                runYamlRequest = runYamlRequest,
                trailblazeDevicePlatform = device.platform,
                targetTestApp = targetTestApp,
                onProgressMessage = onProgressMessage
              )
            )
            onConnectionStatus(
              DeviceConnectionStatus.TrailblazeInstrumentationRunning(
                deviceId = device.instanceId
              )
            )
          }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Default) {
          onProgressMessage("Error: ${e.message}")
          onConnectionStatus(DeviceConnectionStatus.ConnectionFailure(e.message ?: "Unknown error"))
        }
      }
    }
  }

  /**
   * Executes YAML test on a device using instrumentation.
   */
  private suspend fun runYamlOnDevice(
    device: TrailblazeConnectedDeviceSummary,
    targetTestApp: TargetTestApp,
    runYamlRequest: RunYamlRequest,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    onProgressMessage: (String) -> Unit,
  ) {
    withContext(Dispatchers.IO) {
      val status = DeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = device.instanceId,
        targetTestApp = targetTestApp,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        DeviceConnectUtils.sentRequestStartTestWithYaml(
          runYamlRequest,
        )
        onProgressMessage("YAML test execution request sent to the ${device.platform} device.")
      }
    }
  }
}
