package xyz.block.trailblaze.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.IosHostUtils
import xyz.block.trailblaze.host.model.HostAppTarget
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.util.HostAdbCommandUtil
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.SessionStatus.Ended
import xyz.block.trailblaze.mcp.utils.DeviceConnectUtils
import xyz.block.trailblaze.model.RunOnHostParams
import xyz.block.trailblaze.model.TargetTestApp
import xyz.block.trailblaze.yaml.TrailblazeYaml

object TrailblazeHostYamlRunner {
  private var job: Job? = null

  /**
   * Allows you to run Trailblaze yaml on a specific host-device
   */
  suspend fun runHostYaml(
    runOnHostParams: RunOnHostParams,
    allAndroidTargetTestApps: List<TargetTestApp>,
    hostAppTarget: HostAppTarget?,
  ) {
    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      DeviceConnectUtils.uninstallAllAndroidInstrumentationProcesses(
        targetTestApps = allAndroidTargetTestApps + TargetTestApp.DEFAULT_ANDROID_ON_DEVICE,
        deviceId = null,
      )
    }
    val hostTbRunner = object : BaseHostTrailblazeTest(
      trailblazeDriverType = runOnHostParams.trailblazeDriverType,
      setOfMarkEnabled = true,
      systemPromptTemplate = null,
      customToolClasses = hostAppTarget?.initialCustomToolClasses ?: setOf(),
      trailblazeToolSet = null,
    ) {
      override fun ensureTargetAppIsStopped() {
        when (runOnHostParams.trailblazeDevicePlatform) {
          TrailblazeDevicePlatform.ANDROID -> {
            hostAppTarget?.getAppId()?.let { HostAdbCommandUtil.forceStop(it) }
          }

          TrailblazeDevicePlatform.IOS -> {
            hostAppTarget?.getAppId()?.let {
              IosHostUtils.killAppOnSimulator(
                deviceId = null,
                appId = it,
              )
            }
          }

          TrailblazeDevicePlatform.WEB -> {
            // Currently nothing to do here
          }
        }
      }
    }
    if (job?.isActive == true) {
      TrailblazeLogger.sendEndLog(
        Ended.Cancelled(
          durationMs = 0L,
          cancellationMessage = "Session cancelled after the user started a new session.",
        ),
      )
    }
    job?.cancel()
    job = CoroutineScope(Dispatchers.IO).launch {
      val runYamlRequest: RunYamlRequest = runOnHostParams.runYamlRequest
      val testName = runYamlRequest.testName
      TrailblazeLogger.startSession(testName)

      // Extract TrailConfig from yaml
      val trailConfig = try {
        TrailblazeYaml().extractTrailConfig(runYamlRequest.yaml)
      } catch (e: Exception) {
        null
      }

      TrailblazeLogger.sendStartLog(
        trailConfig = trailConfig,
        className = testName,
        methodName = testName,
        trailblazeDeviceInfo = hostTbRunner.trailblazeDeviceInfo,
      )
      try {
        hostTbRunner.runTrailblazeYaml(runOnHostParams.runYamlRequest.yaml)
        TrailblazeLogger.sendEndLog(
          isSuccess = true,
        )
      } catch (e: Exception) {
        TrailblazeLogger.sendEndLog(
          isSuccess = false,
          exception = e,
        )
      }
    }
  }
}
