package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils

object TrailblazeHostYamlRunner {

  /**
   * Runs a Trailblaze YAML test on a specific host-connected device with the given LLM client.
   */
  suspend fun runHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ) {

    val device = runOnHostParams.device
    val trailblazeDeviceId = device.trailblazeDeviceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
        trailblazeOnDeviceInstrumentationTargetTestApps = deviceManager.availableAppTargets.map { it.getTrailblazeOnDeviceInstrumentationTarget() }
          .toSet(),
        deviceId = trailblazeDeviceId,
      )
    }

    onProgressMessage("Initializing ${device.trailblazeDriverType} test runner...")

    val hostTbRunner = object : BaseHostTrailblazeTest(
      trailblazeDriverType = runOnHostParams.trailblazeDriverType,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runOnHostParams.runYamlRequest.trailblazeLlmModel,
      config = runOnHostParams.runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
    ) {
      override fun ensureTargetAppIsStopped() {
        val possibleAppIds = runOnHostParams.targetTestApp
          ?.getPossibleAppIdsForPlatform(runOnHostParams.trailblazeDevicePlatform)
          ?: emptySet()
        MobileDeviceUtils.ensureAppsAreForceStopped(
          possibleAppIds = possibleAppIds,
          trailblazeDeviceId = trailblazeDeviceId
        )
      }
    }

    // Get the logger from the test's logging rule
    val trailblazeLogger = hostTbRunner.loggingRule.trailblazeLogger

    // Store the test instance for forceful shutdown on cancellation
    deviceManager.setActiveDriverForDevice(trailblazeDeviceId, hostTbRunner.hostRunner.loggingDriver)

    // Launch the test in a coroutine - store Job IMMEDIATELY for instant cancellation
    val runYamlRequest: RunYamlRequest = runOnHostParams.runYamlRequest
    val testName = runYamlRequest.testName

    val sessionId = trailblazeLogger.startSession(testName)

    // Start session via device manager (handles session manager + state updates)
    deviceManager.trackActiveHostSession(
      trailblazeDeviceId = trailblazeDeviceId,
      sessionId = sessionId
    )

    onProgressMessage("Connecting to ${device.platform} device...")

    try {
      onProgressMessage("Executing YAML test...")
      println("▶️ Starting runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      hostTbRunner.runTrailblazeYamlSuspend(
        yaml = runOnHostParams.runYamlRequest.yaml,
        forceStopApp = runOnHostParams.forceStopTargetApp,
        trailFilePath = runOnHostParams.runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
      )
      println("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")
      trailblazeLogger.sendSessionEndLog(
        isSuccess = true
      )
    } catch (e: TrailblazeSessionCancelledException) {
      // Handle Trailblaze session cancellation - user cancelled via UI
      println("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      // DON'T write log here - cancellation log is written by the UI layer
      // (JvmLiveSessionDataProvider.writeCancellationLog) to avoid duplicates
      // Don't re-throw, just end gracefully
    } catch (e: CancellationException) {
      // Handle coroutine cancellation explicitly
      println("🚫 CancellationException caught for device: ${trailblazeDeviceId.instanceId} - ${e.message}")
      onProgressMessage("Test execution cancelled")
      // DON'T write log here - cancellation log is already written by the UI layer
      // to avoid duplicate logs. Just do cleanup in finally block.
      // Re-throw to propagate cancellation
      throw e
    } catch (e: Exception) {
      println("❌ Exception caught in runHostYaml for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      trailblazeLogger.sendSessionEndLog(
        isSuccess = false,
        exception = e,
      )
    } finally {
      // IMPORTANT: This ALWAYS executes, even when cancelled!
      // Ensures device manager state is updated and job is cleaned up
      println("🧹 Finally block executing for device: ${trailblazeDeviceId.instanceId} - calling cancelSessionForDevice")
      deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      println("🏁 Finally block completed for device: ${trailblazeDeviceId.instanceId}")
    }
  }
}
