package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.ios.IosHostUtils
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils

class DesktopYamlRunner(
  private val trailblazeHostAppTarget: TrailblazeHostAppTarget,
  private val onRunHostYaml: (RunOnHostParams) -> Unit,
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

  suspend fun runYaml(desktopRunYamlParams: DesktopAppRunYamlParams) {
    runYaml(
      targetTestApp = desktopRunYamlParams.targetTestApp,
      device = desktopRunYamlParams.device,
      forceStopTargetApp = desktopRunYamlParams.forceStopTargetApp,
      runYamlRequest = desktopRunYamlParams.runYamlRequest,
      onProgressMessage = desktopRunYamlParams.onProgressMessage,
      onConnectionStatus = desktopRunYamlParams.onConnectionStatus,
      additionalInstrumentationArgs = desktopRunYamlParams.additionalInstrumentationArgs,
    )
  }

  /**
   * Executes a YAML test on the specified device, automatically choosing between
   * on-device instrumentation or host-based execution.
   *
   * This is a non-composable suspend function that can be called directly if you
   * don't need the composable wrapper.
   */
  suspend fun runYaml(
    device: TrailblazeConnectedDeviceSummary,
    forceStopTargetApp: Boolean,
    targetTestApp: TrailblazeHostAppTarget?,
    runYamlRequest: RunYamlRequest,
    onProgressMessage: (String) -> Unit,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    additionalInstrumentationArgs: (suspend () -> Map<String, String>),
  ) {
    withContext(Dispatchers.IO) {
      // Wrap progress message callback to add device prefix
      val shortenedDescription = shortenDeviceDescription(device.description)
      val devicePrefix = "[$shortenedDescription]"
      val prefixedProgressMessage: (String) -> Unit = { message ->
        onProgressMessage("$devicePrefix $message")
      }

      if (forceStopTargetApp) {
        val possibleAppIds = targetTestApp?.getPossibleAppIdsForPlatform(device.platform)
        if (!possibleAppIds.isNullOrEmpty()) {
          when (device.platform) {
            TrailblazeDevicePlatform.ANDROID -> {
              possibleAppIds.forEach { possibleAppId ->
                AndroidHostAdbUtils.forceStopApp(
                  deviceId = device.instanceId,
                  appId = possibleAppId,
                )
              }
            }

            TrailblazeDevicePlatform.IOS -> {
              possibleAppIds.forEach { possibleAppId ->
                IosHostUtils.killAppOnSimulator(
                  deviceId = device.instanceId,
                  appId = possibleAppId,
                )
              }
            }

            else -> {}
          }
        }
      }

      try {
        prefixedProgressMessage(
          "Starting YAML test execution with driver ${device.trailblazeDriverType}",
        )

        when (device.trailblazeDriverType) {
          TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION -> {
            val trailblazeOnDeviceInstrumentationTarget = targetTestApp?.getTrailblazeOnDeviceInstrumentationTarget()
              ?: trailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTarget()
            HostAndroidDeviceConnectUtils.uninstallAllAndroidInstrumentationProcesses(
              trailblazeOnDeviceInstrumentationTargetTestApps = setOf(trailblazeOnDeviceInstrumentationTarget),
              deviceId = device.instanceId,
            )
            runYamlOnDevice(
              device = device,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              runYamlRequest = runYamlRequest,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
            )
          }

          else -> {
            onRunHostYaml(
              RunOnHostParams(
                runYamlRequest = runYamlRequest,
                device = device,
                onProgressMessage = prefixedProgressMessage,
                forceStopTargetApp = forceStopTargetApp,
                desktopYamlRunnerParams = DesktopYamlRunnerParams(
                  forceStopTargetApp = forceStopTargetApp,
                  trailblazeHostAppTarget = targetTestApp,
                ),
              ),
            )
            onConnectionStatus(
              DeviceConnectionStatus.TrailblazeInstrumentationRunning(
                deviceId = device.instanceId,
              ),
            )
          }
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Default) {
          prefixedProgressMessage("Error: ${e.message}")
          onConnectionStatus(
            DeviceConnectionStatus.ConnectionFailure(
              errorMessage = e.message ?: "Unknown error",
              deviceId = device.instanceId,
            ),
          )
        }
      }
    }
  }

  /**
   * Executes YAML test on a device using instrumentation.
   */
  private suspend fun runYamlOnDevice(
    device: TrailblazeConnectedDeviceSummary,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    runYamlRequest: RunYamlRequest,
    onConnectionStatus: (DeviceConnectionStatus) -> Unit,
    onProgressMessage: (String) -> Unit,
    additionalInstrumentationArgs: (suspend () -> Map<String, String>?),
  ) {
    withContext(Dispatchers.IO) {
      val additionalInstrumentationArgs = additionalInstrumentationArgs()
      val status = HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = device.instanceId,
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        additionalInstrumentationArgs = additionalInstrumentationArgs.orEmpty(),
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        HostAndroidDeviceConnectUtils.sentRequestStartTestWithYaml(
          runYamlRequest,
        )
        onProgressMessage("YAML test execution request sent to the ${device.platform} device.")
      }
    }
  }
}
