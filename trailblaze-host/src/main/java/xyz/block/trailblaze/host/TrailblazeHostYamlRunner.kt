package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.logs.model.SessionId
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
  ): SessionId? {

    val trailblazeDeviceId = runOnHostParams.runYamlRequest.trailblazeDeviceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID) {
      HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
        trailblazeOnDeviceInstrumentationTargetTestApps = deviceManager.availableAppTargets.map { it.getTrailblazeOnDeviceInstrumentationTarget() }
          .toSet(),
        deviceId = trailblazeDeviceId,
      )
    }

    onProgressMessage("Initializing $trailblazeDeviceId test runner...")

    val runYamlRequest = runOnHostParams.runYamlRequest
    val hostTbRunner = object : BaseHostTrailblazeTest(
      trailblazeDriverType = runOnHostParams.trailblazeDriverType,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
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

    // Store the test instance for forceful shutdown on cancellation
    deviceManager.setActiveDriverForDevice(trailblazeDeviceId, hostTbRunner.hostRunner.loggingDriver)

    // Get the logger from the test's logging rule
    val trailblazeLogger = hostTbRunner.loggingRule.trailblazeLogger
    // REQUIRED: Initializes the Session ID based on the testName, this triggers lifecycle calls
    trailblazeLogger.resetForNewSession(runYamlRequest.testName)

    val overrideSessionId = runYamlRequest.config.overrideSessionId
    if (overrideSessionId != null) {
      // A specific session ID was provided, use that instead of the auto-generated one.
      trailblazeLogger.overrideSessionId(overrideSessionId)
    }

    onProgressMessage("Connecting to $trailblazeDeviceId device...")

    return try {
      onProgressMessage("Executing YAML test...")
      println("▶️ Starting runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = hostTbRunner.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        forceStopApp = runOnHostParams.forceStopTargetApp,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog
      )
      println("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        trailblazeLogger.sendSessionEndLog(isSuccess = true)
      } else {
        // Keep the session open
      }
       sessionId
    } catch (e: TrailblazeSessionCancelledException) {
      // Handle Trailblaze session cancellation - user cancelled via UI
      println("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      // DON'T write log here - cancellation log is written by the UI layer
      // (JvmLiveSessionDataProvider.writeCancellationLog) to avoid duplicates
      // Don't re-throw, just end gracefully
      null
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
      null
    } finally {
      // IMPORTANT: This ALWAYS executes, even when cancelled!
      // Ensures device manager state is updated and job is cleaned up
      println("🧹 Finally block executing for device: ${trailblazeDeviceId.instanceId} - calling cancelSessionForDevice")
      deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      println("🏁 Finally block completed for device: ${trailblazeDeviceId.instanceId}")
    }
  }
}
