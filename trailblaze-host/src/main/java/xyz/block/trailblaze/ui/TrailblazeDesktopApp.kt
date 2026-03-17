package xyz.block.trailblaze.ui

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.cli.CliReportGenerator
import xyz.block.trailblaze.cli.DaemonClient
import xyz.block.trailblaze.desktop.TrailblazeDesktopAppConfig
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.yaml.DesktopYamlRunner
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.findById
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.yaml.TrailblazeYaml
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Central Interface for The Trailblaze Desktop App
 */
abstract class TrailblazeDesktopApp(
  protected val desktopAppConfig: TrailblazeDesktopAppConfig,
) {
  abstract val desktopYamlRunner: DesktopYamlRunner

  abstract val trailblazeMcpServer: TrailblazeMcpServer

  abstract val deviceManager: TrailblazeDeviceManager

  abstract fun startTrailblazeDesktopApp(headless: Boolean = false)

  /**
   * Creates the CLI report generator used by `trailblaze run`.
   *
   * The base implementation uses [CliReportGenerator] backed by `trailblaze-report`.
   * Subclasses can override this to return a customized report generator.
   */
  open fun createCliReportGenerator(): CliReportGenerator = CliReportGenerator()

  /** Shortcut to the port manager owned by the settings repo. */
  val portManager: TrailblazePortManager
    get() = desktopAppConfig.trailblazeSettingsRepo.portManager

  /**
   * Applies CLI port overrides as **transient, in-memory** values.
   *
   * The overrides are NOT written to the settings file, so multiple
   * concurrent instances (each started with different `-p` flags or
   * `TRAILBLAZE_PORT` env vars) won't race on the shared config.
   */
  fun applyPortOverrides(httpPort: Int, httpsPort: Int) {
    portManager.setRuntimeOverrides(httpPort, httpsPort)
    // Update the global server URL used by NetworkImageLoader for screenshot loading
    NetworkImageLoader.currentServerBaseUrl = portManager.serverUrl
  }

  /**
   * Ensures the MCP server is running.
   *
   * This is called automatically when operations that need the server are performed
   * (e.g., OAuth callbacks, log collection). If the server is already running
   * (either from this app instance or a daemon), this is a no-op.
   *
   * @return `true` if this call started a new server (this process owns the daemon),
   *   `false` if a daemon was already running (another process owns it).
   */
  open fun ensureServerRunning(): Boolean {
    val serverPort = portManager.httpPort
    val serverHttpsPort = portManager.httpsPort
    val daemon = DaemonClient(port = serverPort)
    if (daemon.isRunning()) {
      return false // Server already running — we don't own it
    }

    Console.log("Starting Trailblaze server...")
    installRunHandler()
    runBlocking {
      trailblazeMcpServer.startStreamableHttpMcpServer(
        port = serverPort,
        httpsPort = serverHttpsPort,
        wait = false,
      )
    }

    // Wait for server to be ready
    var attempts = 0
    while (!daemon.isRunning() && attempts < 30) {
      Thread.sleep(200)
      attempts++
    }

    if (daemon.isRunning()) {
      Console.log("Server started on port $serverPort")
      return true
    } else {
      Console.error("Warning: Server may not have started properly")
      return false
    }
  }

  /**
   * Installs the `/cli/run` handler on the MCP server so that CLI processes
   * can delegate trail execution to a running daemon.
   *
   * Called automatically by [ensureServerRunning] and [startTrailblazeDesktopApp].
   */
  fun installRunHandler() {
    trailblazeMcpServer.onRunRequest = { request -> handleCliRunRequest(request) }
  }

  /**
   * Handles a CLI run request by resolving device/LLM, executing the trail,
   * and returning the result synchronously.
   */
  private suspend fun handleCliRunRequest(request: CliRunRequest): CliRunResponse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    // Determine YAML content
    val yamlContent = request.yamlContent
      ?: request.runYamlRequest?.yaml
      ?: return@withContext CliRunResponse(success = false, error = "No YAML content provided")

    // Resolve driver type from request or trail config
    val trailConfig = try {
      TrailblazeYaml().extractTrailConfig(yamlContent)
    } catch (_: Exception) {
      null
    }
    val driverString = request.driverType ?: request.runYamlRequest?.driverType?.name ?: trailConfig?.driver
    val trailDriverType = driverString?.let { TrailblazeDriverType.fromString(it) }
    val trailPlatform = trailDriverType?.platform
      ?: trailConfig?.platform?.let { TrailblazeDevicePlatform.fromString(it) }

    // Resolve device
    val resolvedRunRequest = request.runYamlRequest
    val targetDevice = if (resolvedRunRequest != null) {
      // Fully resolved mode — find the device matching the request's device ID
      val devices = deviceManager.loadDevicesSuspend(applyDriverFilter = false)
      devices.find { it.trailblazeDeviceId == resolvedRunRequest.trailblazeDeviceId }
        ?: devices.firstOrNull()
        ?: return@withContext CliRunResponse(success = false, error = "No devices connected")
    } else {
      val devices = deviceManager.loadDevicesSuspend(applyDriverFilter = false)
      resolveDevice(devices, request.deviceId, trailDriverType, trailPlatform)
        ?: return@withContext CliRunResponse(success = false, error = "No matching device found")
    }

    // Resolve LLM model
    val llmModel = resolvedRunRequest?.trailblazeLlmModel ?: run {
      if (request.llmProvider != null || request.llmModel != null) {
        desktopAppConfig.resolveLlmModel(request.llmProvider, request.llmModel)
      } else {
        try {
          desktopAppConfig.getCurrentLlmModel()
        } catch (e: Exception) {
          null
        }
      }
    } ?: return@withContext CliRunResponse(success = false, error = "No LLM configured")

    // Derive test name
    val testName = request.testName
      ?: resolvedRunRequest?.testName
      ?: request.trailFilePath?.let { File(it).parentFile?.name }
      ?: "daemon-run"

    // Build the RunYamlRequest
    val resolvedYaml = request.trailFilePath?.let { path ->
      TrailYamlTemplateResolver.resolve(yamlContent, File(path))
    } ?: yamlContent

    // Auto-detect recorded steps: if the trail YAML contains recordings, use them.
    val effectiveUseRecordedSteps = request.useRecordedSteps || try {
      val trailItems = TrailblazeYaml().decodeTrail(resolvedYaml)
      TrailblazeYaml().hasRecordedSteps(trailItems)
    } catch (_: Exception) {
      false
    }

    val runYamlRequest = resolvedRunRequest ?: RunYamlRequest(
      testName = testName,
      yaml = resolvedYaml,
      trailFilePath = request.trailFilePath,
      targetAppName = trailConfig?.app,
      useRecordedSteps = effectiveUseRecordedSteps,
      trailblazeDeviceId = targetDevice.trailblazeDeviceId,
      trailblazeLlmModel = llmModel,
      driverType = trailDriverType,
      config = TrailblazeConfig(
        setOfMarkEnabled = request.setOfMark,
        browserHeadless = !request.showBrowser,
      ),
      referrer = TrailblazeReferrer(id = "cli-daemon", display = "CLI (Daemon)"),
    )

    // Execute and wait for completion
    val completionLatch = CountDownLatch(1)
    var success = false
    var errorMessage: String? = null

    // Snapshot existing sessions to identify new ones
    val logsRepo = deviceManager.logsRepo
    val existingSessionIds = logsRepo.getSessionIds().toSet()

    val params = DesktopAppRunYamlParams(
      forceStopTargetApp = request.forceStopTargetApp,
      runYamlRequest = runYamlRequest,
      targetTestApp = trailConfig?.app?.let { desktopAppConfig.availableAppTargets.findById(it) }
        ?: deviceManager.getCurrentSelectedTargetApp(),
      onProgressMessage = { message -> Console.info(message) },
      onConnectionStatus = { status ->
        if (status is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure) {
          errorMessage = status.errorMessage
          completionLatch.countDown()
        }
      },
        additionalInstrumentationArgs = desktopAppConfig.additionalInstrumentationArgs(),
      onComplete = { result ->
        when (result) {
          is TrailExecutionResult.Success -> success = true
          is TrailExecutionResult.Failed -> errorMessage = result.errorMessage
          is TrailExecutionResult.Cancelled -> errorMessage = "Cancelled"
        }
        completionLatch.countDown()
      },
    )

    desktopYamlRunner.runYaml(params)
    completionLatch.await()

    // Wait for session logs to appear and reach a terminal state.
    // For on-device instrumentation, the RPC returns before the trail finishes executing
    // on-device, so we poll until the session reaches Ended status.
    delay(3000)
    var newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
    val maxWaitMs = 600_000L
    val pollIntervalMs = 500L
    val waitStart = System.currentTimeMillis()
    while (System.currentTimeMillis() - waitStart < maxWaitMs) {
      newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }
      val allEnded = newSessionIds.isNotEmpty() && newSessionIds.all { sessionId ->
        val status = logsRepo.getSessionInfo(sessionId)?.latestStatus
        status is SessionStatus.Ended
      }
      if (allEnded) break
      delay(pollIntervalMs)
    }
    // Short buffer after Ended for trailing files (screenshots, etc.)
    delay(3000)
    newSessionIds = logsRepo.getSessionIds().filter { it !in existingSessionIds }

    // Cross-check session status from logs (source of truth for pass/fail)
    if (success) {
      for (sessionId in newSessionIds) {
        val sessionInfo = logsRepo.getSessionInfo(sessionId)
        val status = sessionInfo?.latestStatus
        if (status is SessionStatus.Ended && status !is SessionStatus.Ended.Succeeded &&
          status !is SessionStatus.Ended.SucceededWithFallback
        ) {
          success = false
          errorMessage = "Session $sessionId ended with status: ${status::class.simpleName}"
        }
      }
    }

    // Recording generation for on-device runs is handled by the CLI after
    // receiving the response, to avoid blocking the HTTP server thread.
    // The CLI calls generateRecordingForSession() which polls for trailing
    // log files (TrailblazeToolLog) that arrive after session Ended status.

    // Extract device classifiers from the first new session for recording filename
    val classifiers = newSessionIds.firstOrNull()?.let { sessionId ->
      logsRepo.getSessionInfo(sessionId)?.trailblazeDeviceInfo?.classifiers
        ?.map { it.classifier }
    } ?: emptyList()

    CliRunResponse(
      success = success && errorMessage == null,
      sessionId = newSessionIds.firstOrNull()?.value,
      error = errorMessage,
      deviceClassifiers = classifiers,
    )
  }

  private fun resolveDevice(
    devices: List<TrailblazeConnectedDeviceSummary>,
    deviceId: String?,
    driverType: TrailblazeDriverType?,
    platform: TrailblazeDevicePlatform?,
  ): TrailblazeConnectedDeviceSummary? {
    if (deviceId != null) {
      return devices.find { it.trailblazeDeviceId.instanceId == deviceId }
        ?: devices.find { it.trailblazeDeviceId.instanceId.contains(deviceId) }
    }
    if (driverType != null) {
      return devices.find { it.trailblazeDriverType == driverType }
    }
    if (platform != null) {
      val platformDevices = devices.filter { it.platform == platform }
      return platformDevices.find { it.trailblazeDriverType == TrailblazeDriverType.PLAYWRIGHT_NATIVE }
        ?: platformDevices.firstOrNull()
    }
    return devices.firstOrNull()
  }
}
