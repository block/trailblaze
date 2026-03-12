package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
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
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import xyz.block.trailblaze.ui.images.NetworkImageLoader
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
   */
  open fun ensureServerRunning() {
    val serverPort = portManager.httpPort
    val serverHttpsPort = portManager.httpsPort
    val daemon = DaemonClient(port = serverPort)
    if (daemon.isRunning()) {
      return // Server already running
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
    } else {
      Console.error("Warning: Server may not have started properly")
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
    val trailDriverType = driverString?.let { ds ->
      TrailblazeDriverType.entries.find { it.name.equals(ds, ignoreCase = true) }
    }
    val trailPlatform = trailDriverType?.platform
      ?: trailConfig?.platform?.let { p ->
        TrailblazeDevicePlatform.entries.find { it.name.equals(p, ignoreCase = true) }
      }

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
      targetAppName = null,
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
      targetTestApp = deviceManager.getCurrentSelectedTargetApp(),
      onProgressMessage = { message -> Console.info(message) },
      onConnectionStatus = { status ->
        if (status is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure) {
          errorMessage = status.errorMessage
          completionLatch.countDown()
        }
      },
        additionalInstrumentationArgs = kotlinx.coroutines.runBlocking { desktopAppConfig.additionalInstrumentationArgs() },
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
    kotlinx.coroutines.delay(3000)
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
      kotlinx.coroutines.delay(pollIntervalMs)
    }
    // Short buffer after Ended for trailing files (screenshots, etc.)
    kotlinx.coroutines.delay(3000)
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

  /**
   * Generates a `recording.trail.yaml` file in the session's log directory from
   * the session logs, if one doesn't already exist.
   *
   * Reads logs directly from disk rather than from the cached flow, because for
   * on-device instrumentation runs the flow cache may not have all logs yet when
   * this method is called.
   */
  private fun generateRecordingForSession(
    logsRepo: xyz.block.trailblaze.report.utils.LogsRepo,
    sessionId: xyz.block.trailblaze.logs.model.SessionId,
  ) {
    try {
      val gitRoot = xyz.block.trailblaze.util.GitUtils.getGitRootViaCommand() ?: return
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      val recordingFile = File(sessionDir, "recording.trail.yaml")
      if (recordingFile.exists()) return

      // Wait for all selector-based TrailblazeToolLog entries to arrive on disk.
      // On-device instrumentation logs the DelegatingTrailblazeToolLog (nodeId-based)
      // immediately but the corresponding TrailblazeToolLog (selector-based, same
      // traceId) is flushed later — sometimes 10-30s after the Ended status.
      // Poll until every DelegatingTrailblazeToolLog has a matching TrailblazeToolLog.
      val maxWaitMs = 120_000L
      val pollMs = 2_000L
      val waitStart = System.currentTimeMillis()

      fun readLogs(): List<xyz.block.trailblaze.logs.client.TrailblazeLog> {
        val logFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        return logFiles.mapNotNull { file ->
          try {
            xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
              .decodeFromString<xyz.block.trailblaze.logs.client.TrailblazeLog>(file.readText())
          } catch (_: Exception) {
            null
          }
        }.sortedBy { it.timestamp }
      }

      var logs = readLogs()
      while (System.currentTimeMillis() - waitStart < maxWaitMs) {
        val delegatingTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val selectorTraceIds = logs
          .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog>()
          .mapNotNull { it.traceId }
          .toSet()
        val missingTraceIds = delegatingTraceIds - selectorTraceIds
        if (missingTraceIds.isEmpty()) break
        Console.log("Recording: waiting for ${missingTraceIds.size} TrailblazeToolLog(s) to arrive...")
        Thread.sleep(pollMs)
        logs = readLogs()
      }

      if (logs.isEmpty()) return

      // Extract session config from the Started status log
      val startedStatus = logs
        .filterIsInstance<xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<xyz.block.trailblaze.logs.model.SessionStatus.Started>()
        .firstOrNull()

      val sessionTrailConfig = startedStatus?.let { started ->
        val originalConfig = started.trailConfig
        xyz.block.trailblaze.yaml.TrailConfig(
          id = originalConfig?.id,
          title = originalConfig?.title,
          description = originalConfig?.description,
          priority = originalConfig?.priority,
          context = originalConfig?.context,
          source = originalConfig?.source,
          metadata = originalConfig?.metadata,
          app = originalConfig?.app,
          driver = started.trailblazeDeviceInfo.trailblazeDriverType.name,
          platform = started.trailblazeDeviceInfo.platform.name.lowercase(),
        )
      }

      val recordingYaml = logs.generateRecordedYaml(sessionTrailConfig = sessionTrailConfig)
      if (recordingYaml.isBlank()) return

      recordingFile.writeText(recordingYaml)
      Console.log("Recording generated: ${recordingFile.absolutePath}")
    } catch (e: Exception) {
      Console.log("Failed to generate recording for session ${sessionId.value}: ${e.message}")
    }
  }
}
