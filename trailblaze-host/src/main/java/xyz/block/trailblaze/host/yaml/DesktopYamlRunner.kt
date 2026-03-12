package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureSession
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.util.Console
import java.io.File

class DesktopYamlRunner(
  private val trailblazeDeviceManager: TrailblazeDeviceManager,
  private val trailblazeAnalytics: TrailblazeAnalytics,
  private val trailblazeHostAppTargetProvider: () -> TrailblazeHostAppTarget,
  private val dynamicLlmClientProvider: (TrailblazeLlmModel) -> DynamicLlmClient,
) {
  companion object {
    // Force early class loading of nested DeviceConnectionStatus classes
    // This prevents ClassNotFoundException in catch blocks which reference these types
    @Suppress("unused")
    private val connectionFailureClass = DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure::class
    @Suppress("unused")
    private val startingConnectionClass = DeviceConnectionStatus.WithTargetDevice.StartingConnection::class
    @Suppress("unused")
    private val instrumentationRunningClass = DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning::class
  }

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
    val onComplete = desktopAppRunYamlParams.onComplete

    // For MCP requests, reuse the existing coroutine scope to maintain persistent connections.
    // For UI/CLI requests, create a new scope (which cancels any existing one).
    val isMcpRequest = runYamlRequest.referrer == TrailblazeReferrer.MCP
    val coroutineScope = if (isMcpRequest) {
      trailblazeDeviceManager.getOrCreateCoroutineScopeForDevice(trailblazeDeviceId)
    } else {
      trailblazeDeviceManager.createNewCoroutineScopeForDevice(trailblazeDeviceId)
    }

    coroutineScope.launch {
      Console.log("🚀 COROUTINE STARTED for device: ${trailblazeDeviceId.instanceId}")
      
      // Track the execution result to report in finally block
      var executionResult: TrailExecutionResult = TrailExecutionResult.Success

      val connectedTrailblazeDevice = trailblazeDeviceManager.getDeviceState(trailblazeDeviceId)?.device
        ?: trailblazeDeviceManager.loadDevicesSuspend().firstOrNull { it.trailblazeDeviceId == trailblazeDeviceId }

      if (connectedTrailblazeDevice == null) {
        onProgressMessage("Device with ID $trailblazeDeviceId not found")
        Console.log("❌ COROUTINE ENDING (device not found) for device: ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Failed("Device not found")
        onComplete?.invoke(executionResult)
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

      // Prefer the driver type from the request (set by CLI --driver flag) over
      // the device's driver type, because TrailblazeDeviceId doesn't include the
      // driver type — all three Android variants share the same device ID.
      val trailblazeDriverType = runYamlRequest.driverType ?: connectedTrailblazeDevice.trailblazeDriverType

      // Start capture streams if enabled in desktop settings
      val appConfig = trailblazeDeviceManager.settingsRepo.serverStateFlow.value.appConfig
      val captureOptions = CaptureOptions(
        captureVideo = true,
        captureLogcat = appConfig.captureLogcat,
        spriteFrameFps = 2,
        spriteFrameHeight = 720,
        spriteJpegQuality = 3,
      )
      val captureSession = CaptureSession.fromOptions(
        captureOptions,
        trailblazeDeviceId.trailblazeDevicePlatform.name,
      )
      val captureTempDir = if (captureSession != null) {
        val appId = targetTestApp
          ?.getPossibleAppIdsForPlatform(trailblazeDeviceId.trailblazeDevicePlatform)
          ?.firstOrNull()
        val tempDir = File(
          System.getProperty("java.io.tmpdir"),
          "trailblaze-capture-${trailblazeDeviceId.instanceId}-${System.currentTimeMillis()}",
        )
        tempDir.mkdirs()
        captureSession.startAll(tempDir, trailblazeDeviceId.instanceId, appId)
        prefixedProgressMessage("Capture started (video=${captureOptions.captureVideo}, logcat=${captureOptions.captureLogcat})")
        tempDir
      } else null

      var sessionId: SessionId? = null
      // Snapshot existing session IDs so we can find newly created ones on cancellation
      val preExistingSessionIds = trailblazeDeviceManager.logsRepo.getSessionIds().toSet()

      try {
        trailblazeAnalytics.runTest(trailblazeDriverType, desktopAppRunYamlParams)
        prefixedProgressMessage(
          "Starting ${trailblazeDeviceId.trailblazeDevicePlatform.displayName} test on device ${trailblazeDeviceId.instanceId} with driver type $trailblazeDriverType",
        )

        val trailblazeHostAppTarget = trailblazeHostAppTargetProvider()

        sessionId = when (trailblazeDriverType) {
          TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> {
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

            // Set driver type on request so the on-device server knows which driver to use
            val requestWithDriverType = runYamlRequest.copy(driverType = trailblazeDriverType)

            runYamlOnDevice(
              onDeviceRpc = onDeviceRpc,
              trailblazeConnectedDevice = connectedTrailblazeDevice,
              trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
              runYamlRequest = requestWithDriverType,
              onProgressMessage = prefixedProgressMessage,
              onConnectionStatus = onConnectionStatus,
              additionalInstrumentationArgs = additionalInstrumentationArgs,
            )
          }

          else -> {
            val hostSessionId = TrailblazeHostYamlRunner.runHostYaml(
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
                composeRpcPort = desktopAppRunYamlParams.composeRpcPort,
                referrer = desktopAppRunYamlParams.runYamlRequest.referrer,
              ),
              deviceManager = trailblazeDeviceManager,
            )

            onConnectionStatus(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = connectedTrailblazeDevice.trailblazeDeviceId,
              ),
            )
            hostSessionId
          }
        }
      } catch (e: CancellationException) {
        Console.log("⚠️ COROUTINE CANCELLED for device ${trailblazeDeviceId.instanceId}")
        executionResult = TrailExecutionResult.Cancelled
        // Don't re-throw yet — let the finally block save capture artifacts first.
        // CancellationException is re-thrown after cleanup below.
      } catch (e: Exception) {
        Console.log("⚠️ EXCEPTION in coroutine for device ${trailblazeDeviceId.instanceId}: ${e::class.simpleName} - ${e.message}")
        prefixedProgressMessage("Error: ${e.message}")
        executionResult = TrailExecutionResult.Failed(e.message)
        try {
          onConnectionStatus(
            DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
              errorMessage = e.message ?: "Unknown error",
            ),
          )
        } catch (classLoadError: Throwable) {
          // Fallback if ConnectionFailure class fails to load (Kotlin multiplatform classloading issue)
          Console.log("⚠️ Failed to create ConnectionFailure instance: ${classLoadError::class.simpleName} - ${classLoadError.message}")
        }
      } finally {
        // Always stop capture and save artifacts — even on cancel/error, the video
        // recorded up to this point is valuable for debugging.
        // Clear the thread interrupt flag so capture stop methods (which use
        // Process.waitFor and Thread.sleep) don't throw InterruptedException.
        // Without this, xcrun/screenrecord get killed before finalizing the video.
        Thread.interrupted()
        if (captureSession != null) {
          // On cancellation, sessionId may not have been set yet. Find the session
          // that was created during this test run by matching the device ID in the
          // session's first log file. This handles concurrent multi-device runs.
          val resolvedSessionId = sessionId
            ?: run {
              val newSessions = trailblazeDeviceManager.logsRepo.getSessionIds()
                .filter { it !in preExistingSessionIds }
              val deviceInstanceId = trailblazeDeviceId.instanceId
              // Match by checking the first log file for this device's instance ID
              newSessions.firstOrNull { sid ->
                val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sid)
                val firstLog = File(sessionDir, "001_TrailblazeSessionStatusChangeLog.json")
                firstLog.exists() && firstLog.readText().contains(deviceInstanceId)
              } ?: newSessions.firstOrNull()
            }
          stopCaptureAndMoveArtifacts(captureSession, captureTempDir, resolvedSessionId, prefixedProgressMessage)
        }
        Console.log("🏁 COROUTINE FINISHED (finally block) for device: ${trailblazeDeviceId.instanceId}")
        onComplete?.invoke(executionResult)
        // Re-throw CancellationException to properly propagate cancellation
        if (executionResult is TrailExecutionResult.Cancelled) {
          throw CancellationException("Test cancelled for device ${trailblazeDeviceId.instanceId}")
        }
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

      // For accessibility mode, enable the service AFTER the APK is installed and
      // instrumentation has started. The service is declared in the test runner APK's
      // manifest (via library merge), so it runs in the same process as the test.
      // Enabling must happen after install so Android can find the package.
      if (runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY) {
        AccessibilityServiceSetupUtils.ensureAccessibilityServiceReady(
          deviceId = trailblazeConnectedDevice.trailblazeDeviceId,
          hostPackage = trailblazeOnDeviceInstrumentationTarget.testAppId,
          sendProgressMessage = onProgressMessage,
        )
      }

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)
        onDeviceRpc.verifyServerIsRunning()
        when (val result: RpcResult<RunYamlResponse> = onDeviceRpc.rpcCall(runYamlRequest)) {
          is RpcResult.Failure -> {
            onProgressMessage("Failed to start YAML execution: ${result.message}${result.details?.let { " | $it" } ?: ""}")
            null
          }

          is RpcResult.Success -> {
            val runYamlResponse = result.data
            onProgressMessage("YAML test execution started for session: ${runYamlResponse.sessionId}")

            // Wait for the on-device test to complete. The RPC returns immediately
            // (fire-and-forget), but we need to block until the session reaches an
            // Ended status so that logs are fully streamed before the process exits.
            awaitOnDeviceSessionCompletion(
              sessionId = runYamlResponse.sessionId,
              onProgressMessage = onProgressMessage,
            )

            runYamlResponse.sessionId
          }
        }
      }
    }
  }

  /**
   * Polls the logsRepo until the given session reaches a terminal [SessionStatus.Ended]
   * state, or until the timeout expires. This ensures on-device logs are fully received
   * before the coroutine completes and the process potentially exits.
   *
   * Reads logs directly from disk (not cached flows) so new files are detected immediately.
   */
  private suspend fun awaitOnDeviceSessionCompletion(
    sessionId: SessionId,
    onProgressMessage: (String) -> Unit,
    maxWaitMs: Long = 600_000,
    pollIntervalMs: Long = 1_000,
  ) {
    val logsRepo = trailblazeDeviceManager.logsRepo
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      val status = logsRepo.getLogsForSession(sessionId).getSessionStatus()
      if (status is SessionStatus.Ended) {
        return
      }
      delay(pollIntervalMs)
    }
    onProgressMessage("Warning: Timed out waiting for on-device session to complete")
  }

  /**
   * Stops capture streams and moves artifacts (video, logcat, metadata) into the session log
   * directory so the Timeline view can find them.
   */
  private fun stopCaptureAndMoveArtifacts(
    captureSession: CaptureSession,
    captureTempDir: File?,
    sessionId: SessionId?,
    onProgressMessage: (String) -> Unit,
  ) {
    val debugInfo = StringBuilder()
    try {
      debugInfo.appendLine("tempDir=${captureTempDir?.absolutePath}")
      debugInfo.appendLine("tempDirExistsBefore=${captureTempDir?.exists()}")
      debugInfo.appendLine("tempFilesBefore=${captureTempDir?.listFiles()?.map { it.name }}")
      val artifacts = captureSession.stopAll()
      debugInfo.appendLine("artifacts=${artifacts.size}")
      debugInfo.appendLine("artifactTypes=${artifacts.map { "${it.type}:${it.file.name}:${it.file.exists()}:${it.file.length()}" }}")
      debugInfo.appendLine("tempDirExistsAfterStop=${captureTempDir?.exists()}")
      debugInfo.appendLine("tempFilesAfterStop=${captureTempDir?.listFiles()?.map { it.name }}")
      val tempFiles = captureTempDir?.listFiles()?.map { it.name } ?: emptyList()
      debugInfo.appendLine("sessionId=$sessionId")
      Console.log("Capture stop: artifacts=${artifacts.size}, sessionId=$sessionId, tempFiles=$tempFiles")
      if (sessionId != null && captureTempDir != null && tempFiles.isNotEmpty()) {
        val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionId)
        // Move all files from the capture temp dir (artifacts, metadata, sprite metadata, etc.)
        captureTempDir.listFiles()?.forEach { file ->
          val dest = File(sessionDir, file.name)
          file.renameTo(dest)
          onProgressMessage("Capture: ${file.name} -> ${dest.absolutePath}")
        }
      }
    } catch (e: Exception) {
      debugInfo.appendLine("EXCEPTION: ${e::class.simpleName}: ${e.message}")
      Console.log("Failed to stop capture: ${e.message}")
    } finally {
      captureTempDir?.deleteRecursively()
      // Write diagnostic info to session dir
      if (sessionId != null) {
        try {
          val sessionDir = trailblazeDeviceManager.logsRepo.getSessionDir(sessionId)
          File(sessionDir, "capture_debug.txt").writeText(debugInfo.toString())
        } catch (_: Exception) {}
      }
    }
  }
}
