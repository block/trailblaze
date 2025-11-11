package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.ios.IosHostUtils
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.model.SessionStatus.Ended
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailblazeYaml

object TrailblazeHostYamlRunner {
  // Store jobs per device instance ID to support multiple concurrent device tests
  private val jobs: MutableMap<String, Job> = mutableMapOf()

  /**
   * Cancels the running test for a specific device.
   * @return true if a job was found and cancelled, false otherwise
   */
  fun cancelSession(deviceInstanceId: String): Boolean {
    val job = jobs[deviceInstanceId]
    return if (job?.isActive == true) {
      job.cancel()
      jobs.remove(deviceInstanceId)
      true
    } else {
      false
    }
  }

  /**
   * Runs a Trailblaze YAML test on a specific host-connected device with the given LLM client.
   */
  suspend fun runHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ) {
    // Set the callback so the device manager can cancel our jobs
    deviceManager.cancelSessionCallback = ::cancelSession

    val device = runOnHostParams.device
    val deviceId = device.instanceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      HostAndroidDeviceConnectUtils.uninstallAllAndroidInstrumentationProcesses(
        trailblazeOnDeviceInstrumentationTargetTestApps = deviceManager.appTargets.map { it.getTrailblazeOnDeviceInstrumentationTarget() }
          .toSet(),
        deviceId = deviceId,
      )
    }

    // Get session manager from device manager
    val sessionManager = deviceManager.getOrCreateSessionManager(deviceId)

    onProgressMessage("Initializing ${device.trailblazeDriverType} test runner...")

    val hostTbRunner = object : BaseHostTrailblazeTest(
      trailblazeDriverType = runOnHostParams.trailblazeDriverType,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runOnHostParams.runYamlRequest.trailblazeLlmModel,
      sessionManager = sessionManager,
    ) {
      override fun ensureTargetAppIsStopped() {
        runOnHostParams.targetTestApp
          ?.getPossibleAppIdsForPlatform(
            runOnHostParams.trailblazeDevicePlatform,
          )?.let { possibleAppIds ->
            when (runOnHostParams.trailblazeDevicePlatform) {
              TrailblazeDevicePlatform.ANDROID -> {
                AndroidHostAdbUtils
                  .listInstalledPackages(
                    deviceId = deviceId,
                  )
                  .filter { installedAppId -> possibleAppIds.any { installedAppId == it } }
                  .forEach { appId ->
                    AndroidHostAdbUtils.forceStopApp(
                      deviceId = deviceId,
                      appId = appId,
                    )
                  }
              }

              TrailblazeDevicePlatform.IOS -> {
                possibleAppIds.forEach { appId ->
                  IosHostUtils.killAppOnSimulator(
                    deviceId = deviceId,
                    appId = appId,
                  )
                }
              }

              TrailblazeDevicePlatform.WEB -> {
                // Currently nothing to do here
              }
            }
          }
      }
    }

    // Get the logger from the test's logging rule
    val trailblazeLogger = hostTbRunner.loggingRule.trailblazeLogger

    // Cancel any existing job for this device
    val existingJob = jobs[deviceId]
    if (existingJob?.isActive == true) {
      // Signal cancellation through the session manager so the agent loop can detect it
      sessionManager.cancelCurrentSession()

      // Also cancel the coroutine job
      existingJob.cancel()
    }

    // Create and store a new job for this device
    val newJob = CoroutineScope(Dispatchers.IO).launch {
      val runYamlRequest: RunYamlRequest = runOnHostParams.runYamlRequest
      val testName = runYamlRequest.testName

      trailblazeLogger.startSession(testName)

      // Start session via device manager (handles session manager + state updates)
      deviceManager.startSession(deviceId, trailblazeLogger.getCurrentSessionId())

      // Extract TrailConfig from yaml
      val trailConfig: TrailConfig? = try {
        TrailblazeYaml().extractTrailConfig(runYamlRequest.yaml)
      } catch (e: Exception) {
        null
      }

      trailblazeLogger.sendStartLog(
        trailConfig = trailConfig,
        className = testName,
        methodName = testName,
        trailblazeDeviceInfo = hostTbRunner.trailblazeDeviceInfo,
      )

      onProgressMessage("Connecting to ${device.platform} device...")

      try {
        onProgressMessage("Executing YAML test...")
        hostTbRunner.runTrailblazeYaml(
          yaml = runOnHostParams.runYamlRequest.yaml,
          forceStopApp = runOnHostParams.forceStopTargetApp,
        )
        onProgressMessage("Test execution completed successfully")
        trailblazeLogger.sendEndLog(
          isSuccess = true,
        )
      } catch (e: TrailblazeSessionCancelledException) {
        // Handle Trailblaze session cancellation - user cancelled via UI
        onProgressMessage("Test session cancelled")
        trailblazeLogger.sendEndLog(
          Ended.Cancelled(
            durationMs = 0L,
            cancellationMessage = e.message ?: "Session cancelled by user",
          ),
        )
        // Don't re-throw, just end gracefully
      } catch (e: CancellationException) {
        // Handle coroutine cancellation explicitly
        onProgressMessage("Test execution cancelled")
        trailblazeLogger.sendEndLog(
          Ended.Cancelled(
            durationMs = 0L,
            cancellationMessage = "Test execution was cancelled by user",
          ),
        )
        // Re-throw to propagate cancellation
        throw e
      } catch (e: Exception) {
        onProgressMessage("Test execution failed: ${e.message}")
        trailblazeLogger.sendEndLog(
          isSuccess = false,
          exception = e,
        )
      } finally {
        // End session via device manager (handles session manager + state updates)
        deviceManager.endSession(deviceId)
        // Clean up this job from the map
        jobs.remove(deviceId)
      }
    }

    // Store the new job
    jobs[deviceId] = newJob
  }
}
