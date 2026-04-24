package xyz.block.trailblaze.host

import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.ContentPart
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.DefaultProgressReporter
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.TrailConfig
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.blaze.PlannerLlmCall
import xyz.block.trailblaze.agent.blaze.PlannerToolCallResult
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcTrailblazeAgent
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.golden.SnapshotGoldenComparison
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.revyl.RevylTrailblazeAgent
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.revyl.RevylScreenState
import xyz.block.trailblaze.revyl.RevylSession
import xyz.block.trailblaze.host.rules.BaseComposeTest
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.sampling.LocalLlmSamplingSource
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.playwright.tools.WebToolSetIds
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.subprocess.LaunchedSubprocessRuntime
import xyz.block.trailblaze.scripting.subprocess.McpSubprocessRuntimeLauncher
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.tracing.TrailblazeTraceExporter
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.yaml.ElectronAppConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

object TrailblazeHostYamlRunner {

  /**
   * Resolved Playwright tool classes used for recording generation. Called from both the Native
   * and Electron paths; each passes its own driver type so the resolution is explicit at the
   * call site. Today the two drivers resolve to identical classes (pinned by
   * `WebToolSetCatalogTest`), but the parameter keeps this correct if the YAMLs ever diverge.
   */
  private fun resolveWebToolClasses(driverType: TrailblazeDriverType) = TrailblazeToolSetCatalog
    .resolveForDriver(driverType, WebToolSetIds.ALL)
    .toolClasses

  /**
   * Exports trace data after a session ends. Tries posting to the server first;
   * falls back to writing directly to the session logs directory on disk.
   *
   * Wrapped in [NonCancellable] so traces are saved even when the coroutine is
   * cancelled (e.g. trail timeout). Without this, the suspend call inside `finally`
   * would throw [CancellationException] and the trace would be silently lost.
   */
  private suspend fun exportAndSaveTrace(
    sessionId: SessionId,
    loggingRule: TrailblazeLoggingRule,
    noLogging: Boolean = false,
  ) {
    if (noLogging) return
    withContext(kotlinx.coroutines.NonCancellable) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = loggingRule.trailblazeLogServerClient,
        isServerAvailable = true, // Host runner always has a server running
        writeToDisk = { traceJson ->
          val gitRoot = GitUtils.getGitRootViaCommand()
          val logsDir = if (gitRoot != null) File(gitRoot, "logs")
            else File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), "logs")
          val sessionDir = File(logsDir, sessionId.value)
          sessionDir.mkdirs()
          File(sessionDir, "trace.json").writeText(traceJson)
        },
      )
    }
  }

  /**
   * Spawn target-declared subprocess MCP servers (if any) and register their tools into the
   * session's [TrailblazeToolRepo]. Shared across every runner path so subprocess MCP is wired
   * universally — one spawn model, one stderr-file convention, one teardown shape, independent
   * of driver. Must be called from inside [executeTrailSession]'s session lambda since it
   * needs the live [SessionId].
   *
   * Returns `null` when the target declares no launchable (`script:`) entries — callers can
   * skip appending to their cleanup-tracking list in that case (an empty list in the cleanup
   * lambda is already a no-op, so the return-value check is just an optimization + a hook for
   * progress-message suppression).
   *
   * Cleanup is the caller's responsibility: wrap `runtime.shutdownAll()` in the cleanup lambda
   * inside `withContext(NonCancellable)` so teardown completes even when the surrounding
   * coroutine is cancelled (trail timeout, user abort) — otherwise subprocess + stderr-file
   * handles leak.
   */
  private suspend fun launchSubprocessMcpServersIfAny(
    targetTestApp: TrailblazeHostAppTarget?,
    config: TrailblazeConfig,
    sessionId: SessionId,
    deviceInfo: TrailblazeDeviceInfo,
    logsRepo: xyz.block.trailblaze.report.utils.LogsRepo,
    toolRepo: TrailblazeToolRepo,
    onProgressMessage: (String) -> Unit,
  ): LaunchedSubprocessRuntime? {
    val mcpServers = targetTestApp?.getMcpServers().orEmpty()
    val launchableCount = mcpServers.count { it.script != null }
    if (launchableCount == 0) return null
    onProgressMessage("Launching $launchableCount subprocess MCP server(s)...")
    return McpSubprocessRuntimeLauncher.launchAll(
      mcpServers = mcpServers,
      deviceInfo = deviceInfo,
      config = config,
      sessionId = sessionId,
      sessionLogDir = logsRepo.getSessionDir(sessionId),
      toolRepo = toolRepo,
      // Null when no HTTP server was registered for this process (unit-tested runner paths).
      // The launcher degrades gracefully — envelope omits `_meta.trailblaze.baseUrl` and no
      // callbacks can fire, which is the right behaviour for those paths.
      baseUrl = xyz.block.trailblaze.scripting.callback.JsScriptingCallbackBaseUrl.get(),
    )
  }

  /**
   * Common session lifecycle wrapper for trail execution.
   *
   * Handles session creation, standardized exception handling (cancellation,
   * failure screenshots, session end on error), trace export, and cleanup.
   * Each driver-specific method handles its own setup and passes execution
   * logic via [execute], eliminating duplicated try-catch-finally blocks.
   */
  private suspend fun executeTrailSession(
    loggingRule: TrailblazeLoggingRule,
    overrideSessionId: SessionId?,
    testName: String,
    deviceLabel: String,
    sendSessionEndLog: Boolean,
    onProgressMessage: (String) -> Unit,
    screenshotProvider: () -> ScreenState,
    noLogging: Boolean = false,
    cleanup: suspend () -> Unit = {},
    execute: suspend (TrailblazeSession) -> SessionId?,
  ): SessionId? {
    val sessionManager = loggingRule.sessionManager
    val session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(testName)
    }
    loggingRule.setSession(session)

    return try {
      execute(session)
    } catch (e: TrailblazeSessionCancelledException) {
      Console.log("🚫 Session cancelled for $deviceLabel")
      onProgressMessage("Test session cancelled")
      null
    } catch (e: CancellationException) {
      Console.log("🚫 Coroutine cancelled for $deviceLabel: ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      Console.log("❌ ${e::class.simpleName} in $deviceLabel: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      loggingRule.captureFailureScreenshot(session, screenshotProvider)
      if (sendSessionEndLog) {
        sessionManager.endSession(session, isSuccess = false, exception = e)
      }
      null
    } finally {
      exportAndSaveTrace(session.sessionId, loggingRule, noLogging = noLogging)
      loggingRule.setSession(null)
      cleanup()
    }
  }

  /**
   * Runs a Trailblaze YAML test on a specific host-connected device with the given LLM client.
   */
  suspend fun runHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    return when (runOnHostParams.trailblazeDriverType) {
      TrailblazeDriverType.PLAYWRIGHT_NATIVE ->
        runPlaywrightNativeYaml(dynamicLlmClient, runOnHostParams, deviceManager)
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON ->
        runPlaywrightElectronYaml(dynamicLlmClient, runOnHostParams, deviceManager)
      TrailblazeDriverType.COMPOSE ->
        runComposeYaml(dynamicLlmClient, runOnHostParams, deviceManager)
      TrailblazeDriverType.REVYL_ANDROID,
      TrailblazeDriverType.REVYL_IOS ->
        runRevylYaml(dynamicLlmClient, runOnHostParams, deviceManager)
      else ->
        runMaestroHostYaml(dynamicLlmClient, runOnHostParams, deviceManager)
    }
  }

  /**
   * Playwright-native path: launches a browser via [PlaywrightBrowserManager] and runs
   * the trail using [PlaywrightTrailblazeAgent] with web-native tools.
   *
   * When `sendSessionEndLog` is false (e.g. MCP interactive authoring), the browser is kept
   * alive between calls by caching the [BasePlaywrightNativeTest] instance in the device manager.
   * This mirrors the Maestro path's session reuse behaviour.
   */
  private suspend fun runPlaywrightNativeYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest

    val requestDeviceId = runYamlRequest.trailblazeDeviceId
    val keepBrowserAlive = !runYamlRequest.config.sendSessionEndLog

    // Try to reuse a cached Playwright test instance (only when keeping browser alive)
    val existingTest =
      if (keepBrowserAlive) deviceManager.getActivePlaywrightNativeTest(requestDeviceId) else null
    val isReusingTest = existingTest != null

    // Use stable device ID when reusing, unique suffix when fresh
    val trailblazeDeviceId =
      if (isReusingTest) {
        requestDeviceId
      } else {
        val sessionSuffix = UUID.randomUUID().toString().take(8)
        TrailblazeDeviceId(
          instanceId = "playwright-native-$sessionSuffix",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
      }

    onProgressMessage(
      if (isReusingTest) "Reusing Playwright-native browser session..."
      else "Initializing Playwright-native test runner..."
    )

    val playwrightTest = existingTest ?: BasePlaywrightNativeTest(
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
    )

    // Reset the browser session only when starting a new Trailblaze session.
    // In interactive blaze() mode each call is one step within the same session
    // (sendSessionStartLog=false), so we must NOT reset between steps — that would
    // navigate to about:blank and lose the current page state.
    // Must run on the Playwright thread to maintain thread affinity.
    if (isReusingTest && runYamlRequest.config.sendSessionStartLog) {
      withContext(playwrightTest.browserManager.playwrightDispatcher) {
        playwrightTest.browserManager.resetSession()
      }
    }

    // Cache the test instance for reuse across subsequent MCP calls
    if (keepBrowserAlive) {
      deviceManager.setActivePlaywrightNativeTest(requestDeviceId, playwrightTest)
    }

    onProgressMessage("Launching browser...")

    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
    return executeTrailSession(
      loggingRule = playwrightTest.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "playwright-native:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = playwrightTest.browserManager::getScreenState,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        if (!keepBrowserAlive) {
          playwrightTest.close()
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = playwrightTest.trailblazeDeviceInfo,
        logsRepo = playwrightTest.loggingRule.logsRepo,
        toolRepo = playwrightTest.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting Playwright-native runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = playwrightTest.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-native runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        playwrightTest.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_NATIVE) + customToolClasses,
      )

      sessionId
    }
  }

  /**
   * Playwright-electron path: connects to an Electron app via CDP and runs the trail
   * using [PlaywrightTrailblazeAgent] with web-native tools.
   *
   * Electron app configuration is resolved from:
   * 1. Trail YAML `config.electron` block
   * 2. Env vars (`TRAILBLAZE_ELECTRON_CDP_URL`, `TRAILBLAZE_ELECTRON_COMMAND`) as fallback
   */
  private suspend fun runPlaywrightElectronYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest

    val requestDeviceId = runYamlRequest.trailblazeDeviceId
    val keepAlive = !runYamlRequest.config.sendSessionEndLog

    val existingTest =
      if (keepAlive) deviceManager.getActivePlaywrightElectronTest(requestDeviceId) else null
    val isReusingTest = existingTest != null

    val trailblazeDeviceId =
      if (isReusingTest) {
        requestDeviceId
      } else {
        val sessionSuffix = UUID.randomUUID().toString().take(8)
        TrailblazeDeviceId(
          instanceId = "playwright-electron-$sessionSuffix",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
      }

    onProgressMessage(
      if (isReusingTest) "Reusing Playwright-electron session..."
      else "Initializing Playwright-electron test runner..."
    )

    // Resolve ElectronAppConfig from trail YAML or environment variables
    val electronConfig = resolveElectronAppConfig(runYamlRequest.yaml)

    val electronTest = existingTest ?: BasePlaywrightElectronTest(
      electronAppConfig = electronConfig,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
    )

    if (isReusingTest) {
      withContext(electronTest.browserManager.playwrightDispatcher) {
        electronTest.browserManager.resetSession()
      }
    }

    if (keepAlive) {
      deviceManager.setActivePlaywrightElectronTest(requestDeviceId, electronTest)
    }

    onProgressMessage("Connecting to Electron app...")

    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
    return executeTrailSession(
      loggingRule = electronTest.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "playwright-electron:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = electronTest.browserManager::getScreenState,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        if (!keepAlive) {
          electronTest.close()
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = electronTest.trailblazeDeviceInfo,
        logsRepo = electronTest.loggingRule.logsRepo,
        toolRepo = electronTest.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting Playwright-electron runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = electronTest.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-electron runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        electronTest.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_ELECTRON) +
          BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES + customToolClasses,
      )

      sessionId
    }
  }

  /**
   * Resolves [ElectronAppConfig] from the trail YAML config block, falling back to
   * environment variables if not specified in the YAML.
   */
  private fun resolveElectronAppConfig(yaml: String): ElectronAppConfig {
    val trailConfig = try {
      createTrailblazeYaml().extractTrailConfig(yaml)
    } catch (_: Exception) {
      null
    }

    // If the trail YAML has an electron config block, use it
    trailConfig?.electron?.let { return it }

    // Fall back to environment variables
    val cdpUrl = System.getenv("TRAILBLAZE_ELECTRON_CDP_URL")
    val command = System.getenv("TRAILBLAZE_ELECTRON_COMMAND")
    val args = System.getenv("TRAILBLAZE_ELECTRON_ARGS")
      ?.split(" ")
      ?.filter { it.isNotBlank() }
      ?: emptyList()
    val cdpPort = System.getenv("TRAILBLAZE_ELECTRON_CDP_PORT")?.toIntOrNull() ?: 9222
    val headless = System.getenv("TRAILBLAZE_ELECTRON_HEADLESS")?.toBoolean() ?: false

    return ElectronAppConfig(
      command = command,
      args = args,
      cdpUrl = cdpUrl,
      cdpPort = cdpPort,
      headless = headless,
    )
  }

  /**
   * Compose RPC path: connects to a running Compose app via [ComposeRpcClient] and runs
   * the trail using [ComposeRpcTrailblazeAgent] with Compose-native tools.
   *
   * The Compose app must already be running with an embedded [ComposeRpcServer] on the
   * configured port. No device discovery or instrumentation is needed — the CLI connects
   * directly over HTTP.
   */
  private suspend fun runComposeYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest
    val port = runOnHostParams.composeRpcPort

    // Use the request's device ID so it matches the coroutine scope registered by
    // DesktopYamlRunner. Creating a new ID here would cause cancelSessionForDevice()
    // to miss the coroutine scope, making the cancel button ineffective.
    val trailblazeDeviceId = runYamlRequest.trailblazeDeviceId

    onProgressMessage("Connecting to Compose app on port $port...")

    val rpcClient = ComposeRpcClient("http://localhost:$port")

    // Wait for the Compose app's RPC server to be ready
    val serverReady = rpcClient.waitForServer(maxAttempts = 15, delayMs = 500)
    if (!serverReady) {
      onProgressMessage("Failed to connect to Compose app on port $port")
      rpcClient.close()
      throw TrailblazeException(
        "Could not connect to Compose RPC server on port $port. " +
          "Ensure your Compose app is running with ComposeRpcServer embedded."
      )
    }

    onProgressMessage("Connected to Compose RPC server")

    val viewportWidth = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_WIDTH
    val viewportHeight = ComposeTrailblazeAgent.DEFAULT_VIEWPORT_HEIGHT

    val trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = trailblazeDeviceId,
      trailblazeDriverType = TrailblazeDriverType.COMPOSE,
      widthPixels = viewportWidth,
      heightPixels = viewportHeight,
      classifiers = listOf(TrailblazeDeviceClassifier("desktop"), TrailblazeDeviceClassifier("compose")),
    )

    // Wrap agent creation in try-catch so rpcClient is closed if setup fails before
    // the agent (which owns the client lifecycle) is constructed.
    val agent: ComposeRpcTrailblazeAgent
    val loggingRule: HostTrailblazeLoggingRule
    try {
      loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        noLogging = runOnHostParams.noLogging,
      )

      agent = ComposeRpcTrailblazeAgent(
        rpcClient = rpcClient,
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
      )
    } catch (e: Exception) {
      rpcClient.close()
      throw e
    }

    val composeToolSet = TrailblazeToolSetCatalog.resolveForDriver(
      driverType = TrailblazeDriverType.COMPOSE,
      requestedIds = ComposeToolSetIds.ALL,
    )
    val toolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "Compose RPC Tool Set",
        toolClasses = composeToolSet.toolClasses,
        yamlToolNames = composeToolSet.yamlToolNames,
      ),
    )

    val screenStateProvider = agent.screenStateProvider

    val trailblazeRunner = TrailblazeRunner(
      screenStateProvider = screenStateProvider,
      agent = agent,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      systemPromptTemplate = BaseComposeTest.COMPOSE_SYSTEM_PROMPT,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = {
        loggingRule.session ?: error("Session not available - ensure test is running")
      },
    )

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = screenStateProvider,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      toolRepo = toolRepo,
    )

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = composeToolSet.toolClasses,
    )

    val trailblazeRunnerUtil = TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        val result = agent.runTrailblazeTools(
          trailblazeTools,
          null,
          screenState = screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = screenStateProvider,
        )
        when (val toolResult = result.result) {
          is TrailblazeToolResult.Success -> toolResult
          is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
        }
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = {
        loggingRule.session ?: error("Session not available - ensure test is running")
      },
    )

    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "compose-rpc:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = screenStateProvider,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        agent.close()
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = trailblazeDeviceInfo,
        logsRepo = loggingRule.logsRepo,
        toolRepo = toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      onProgressMessage("Executing YAML test via Compose RPC...")
      Console.log("▶️ Starting Compose RPC execution for device: ${trailblazeDeviceId.instanceId}")

      val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(runYamlRequest.yaml)
      val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

      if (runYamlRequest.config.sendSessionStartLog) {
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = "ComposeRpc",
              testMethodName = "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      for (item in trailItems) {
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, runYamlRequest.useRecordedSteps)
          is TrailYamlItem.ToolTrailItem ->
            trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem ->
            item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }

      Console.log("✅ Compose RPC execution completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = composeToolSet.toolClasses,
      )

      // Run golden comparison before ending the session so failures are reflected in session status.
      val goldenResult = compareSnapshotsAgainstGoldens(session.sessionId)
      val goldenPassed = goldenResult?.passed != false

      if (runYamlRequest.config.sendSessionEndLog) {
        loggingRule.sessionManager.endSession(session, isSuccess = goldenPassed)
      }

      if (!goldenPassed) {
        val failures = goldenResult!!.results.filter { it.goldenFound && !it.passed }
        val msg = failures.joinToString("; ") {
          "'${it.snapshotName}' (${"%.2f".format(it.diffPercent)}% diff, threshold ${it.thresholdPercent}%)"
        }
        throw TrailblazeException("Golden snapshot comparison failed: $msg")
      }

      session.sessionId
    }
  }

  /**
   * Revyl cloud device path: provisions a device via [RevylCliClient] and runs
   * the trail using [RevylTrailblazeAgent] with standard mobile tools.
   *
   * The CLI handles device provisioning, app install, and AI-powered target
   * grounding. Screenshots come from [RevylScreenState].
   */
  private suspend fun runRevylYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {
    val onProgressMessage = runOnHostParams.onProgressMessage
    val runYamlRequest = runOnHostParams.runYamlRequest
    val trailblazeDeviceId = runYamlRequest.trailblazeDeviceId
    val platform = if (runOnHostParams.trailblazeDriverType == TrailblazeDriverType.REVYL_ANDROID) "android" else "ios"

    val instanceId = trailblazeDeviceId.instanceId
    val deviceLabel = if (instanceId.startsWith("revyl-model:"))
      instanceId.removePrefix("revyl-model:") else "$platform (default)"

    if (System.getenv(RevylCliClient.REVYL_API_KEY_ENV).isNullOrBlank()) {
      onProgressMessage("Error: ${RevylCliClient.REVYL_API_KEY_ENV} is not set. Configure it in Settings → Environment Variables.")
      return null
    }

    onProgressMessage("Provisioning Revyl cloud $deviceLabel...")

    val cliClient: RevylCliClient
    val session: RevylSession
    try {
      cliClient = RevylCliClient()
      session = if (instanceId.startsWith("revyl-model:")) {
        val payload = instanceId.removePrefix("revyl-model:")
        val parts = payload.split("::", limit = 2)
        val modelName = parts[0]
        val osVer = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (osVer != null) {
          cliClient.startSession(platform = platform, deviceModel = modelName, osVersion = osVer)
        } else {
          Console.log("RevylYaml: device '$modelName' missing OS version — using platform default")
          cliClient.startSession(platform = platform)
        }
      } else {
        cliClient.startSession(platform = platform)
      }
    } catch (e: Exception) {
      Console.log("Revyl session provisioning failed: ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Error: ${e.message}")
      return null
    }
    onProgressMessage("Revyl $deviceLabel ready — viewer: ${session.viewerUrl}")

    // Store the client for MCP screen state capture
    val isMcpRequest = !runYamlRequest.config.sendSessionEndLog
    if (isMcpRequest) {
      deviceManager.setActiveRevylCliClient(trailblazeDeviceId, cliClient)
    }

    // Outer try-finally guarantees the cloud device is stopped even if setup
    // (e.g. LLM client creation) fails before the inner execution try block.
    try {
      val trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = trailblazeDeviceId,
        trailblazeDriverType = runOnHostParams.trailblazeDriverType,
        widthPixels = session.screenWidth.takeIf { it > 0 }
          ?: xyz.block.trailblaze.revyl.RevylDefaults.dimensionsForPlatform(platform).first,
        heightPixels = session.screenHeight.takeIf { it > 0 }
          ?: xyz.block.trailblaze.revyl.RevylDefaults.dimensionsForPlatform(platform).second,
        metadata = mapOf("revyl_viewer_url" to session.viewerUrl),
        classifiers = listOf(
          TrailblazeDeviceClassifier(platform),
          TrailblazeDeviceClassifier("revyl-cloud"),
        ),
      )

      val screenStateProvider: () -> ScreenState = {
        RevylScreenState(cliClient, platform, session.screenWidth, session.screenHeight)
      }

      val loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
      )

      val revylToolSet = TrailblazeToolSetCatalog.resolveForDriver(
        driverType = runOnHostParams.trailblazeDriverType,
        requestedIds = RevylToolSetIds.ALL,
      )
      val toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "Revyl Native Tool Set",
          toolClasses = revylToolSet.toolClasses,
          yamlToolNames = revylToolSet.yamlToolNames,
        ),
      )

      val agent = RevylTrailblazeAgent(
        cliClient = cliClient,
        platform = platform,
        trailblazeLogger = loggingRule.logger,
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeToolRepo = toolRepo,
      )

      val trailblazeRunner = TrailblazeRunner(
        screenStateProvider = screenStateProvider,
        agent = agent,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
        trailblazeToolRepo = toolRepo,
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
      )

      val elementComparator = TrailblazeElementComparator(
        screenStateProvider = screenStateProvider,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
        toolRepo = toolRepo,
      )

      val trailblazeYaml = createTrailblazeYaml(
        customTrailblazeToolClasses = revylToolSet.toolClasses,
      )

      val trailblazeRunnerUtil = TrailblazeRunnerUtil(
        trailblazeRunner = trailblazeRunner,
        runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
          val result = agent.runTrailblazeTools(
            trailblazeTools,
            null,
            screenState = screenStateProvider(),
            elementComparator = elementComparator,
            screenStateProvider = screenStateProvider,
          )
          when (val toolResult = result.result) {
            is TrailblazeToolResult.Success -> toolResult
            is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
          }
        },
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
      )

      val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
      return executeTrailSession(
        loggingRule = loggingRule,
        overrideSessionId = runYamlRequest.config.overrideSessionId,
        testName = runYamlRequest.testName,
        deviceLabel = "revyl:${trailblazeDeviceId.instanceId}",
        sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
        onProgressMessage = onProgressMessage,
        screenshotProvider = screenStateProvider,
        noLogging = runOnHostParams.noLogging,
        cleanup = {
          withContext(NonCancellable) {
            subprocessRuntimes.forEach { it.shutdownAll() }
          }
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        },
      ) { session ->
        launchSubprocessMcpServersIfAny(
          targetTestApp = runOnHostParams.targetTestApp,
          config = runYamlRequest.config,
          sessionId = session.sessionId,
          deviceInfo = trailblazeDeviceInfo,
          logsRepo = loggingRule.logsRepo,
          toolRepo = toolRepo,
          onProgressMessage = onProgressMessage,
        )?.let { subprocessRuntimes += it }
        onProgressMessage("Executing YAML test via Revyl cloud device...")
        Console.log("▶️ Starting Revyl execution for device: ${trailblazeDeviceId.instanceId}")

        val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrail(runYamlRequest.yaml)
        val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

        if (runYamlRequest.config.sendSessionStartLog) {
          loggingRule.logger.log(
            session,
            TrailblazeLog.TrailblazeSessionStatusChangeLog(
              sessionStatus = SessionStatus.Started(
                trailConfig = trailConfig,
                trailFilePath = runYamlRequest.trailFilePath,
                testClassName = "Revyl",
                testMethodName = "run",
                trailblazeDeviceInfo = trailblazeDeviceInfo,
                rawYaml = runYamlRequest.yaml,
                hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
                trailblazeDeviceId = trailblazeDeviceId,
              ),
              session = session.sessionId,
              timestamp = Clock.System.now(),
            ),
          )
        }

        for (item in trailItems) {
          val itemResult = when (item) {
            is TrailYamlItem.PromptsTrailItem ->
              trailblazeRunnerUtil.runPromptSuspend(item.promptSteps, runYamlRequest.useRecordedSteps)
            is TrailYamlItem.ToolTrailItem ->
              trailblazeRunnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
            is TrailYamlItem.ConfigTrailItem ->
              item.config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
          }
          if (itemResult is TrailblazeToolResult.Error) {
            throw TrailblazeException(itemResult.errorMessage)
          }
        }

        Console.log("✅ Revyl execution completed for device: ${trailblazeDeviceId.instanceId}")
        onProgressMessage("Test execution completed successfully")

        if (runYamlRequest.config.sendSessionEndLog) {
          loggingRule.sessionManager.endSession(session, isSuccess = true)
        }

        generateAndSaveRecording(
          sessionId = session.sessionId,
          customToolClasses = revylToolSet.toolClasses,
        )

        session.sessionId
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.log("Revyl setup failed for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Error: ${e.message}")
      return null
    } finally {
      if (runYamlRequest.config.sendSessionEndLog) {
        deviceManager.removeActiveRevylCliClient(trailblazeDeviceId)
        try { cliClient.stopSession() } catch (_: Exception) { }
      }
    }
  }

  /**
   * Original Maestro-based path for Android/iOS/web-playwright-host devices.
   */
  private suspend fun runMaestroHostYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
  ): SessionId? {

    val trailblazeDeviceId = runOnHostParams.runYamlRequest.trailblazeDeviceId
    val onProgressMessage = runOnHostParams.onProgressMessage

    // Skip force-stop for MCP requests - MCP maintains persistent connections
    // between tool calls and we don't want to kill the driver each time.
    val isMcpRequest = runOnHostParams.referrer == TrailblazeReferrer.MCP
    
    if (runOnHostParams.trailblazeDevicePlatform == TrailblazeDevicePlatform.ANDROID && !isMcpRequest) {
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
      excludedToolClasses = runOnHostParams.targetTestApp
        ?.getExcludedToolsForDriver(
          runOnHostParams.trailblazeDriverType,
        ) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      explicitDeviceId = trailblazeDeviceId,
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

    onProgressMessage("Connecting to $trailblazeDeviceId device...")

    val keepDriverAlive = runOnHostParams.referrer == TrailblazeReferrer.MCP

    // Decision 038 session-startup wiring: per-session subprocess MCP servers declared by the
    // target's `mcp_servers:` YAML. The launcher spawns each entry, runs the MCP handshake,
    // registers filtered tools into hostTbRunner.toolRepo, and hands back a teardown handle.
    // Launch must happen inside the executeTrailSession lambda — we need the SessionId for
    // the env-var contract and for the session-log directory; both are available there.
    //
    // Modeled as a mutable list of resources (empty when the target declares no
    // `mcp_servers:`, populated once launch succeeds) so the cleanup lambda can reference
    // the collection directly without a forward-declared nullable var. Today launch is
    // called at most once per session, but the list shape naturally accommodates
    // splitting per-server launches later without reshaping the control flow.
    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()

    return executeTrailSession(
      loggingRule = hostTbRunner.loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "maestro:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = hostTbRunner.hostRunner.screenStateProvider,
      noLogging = runOnHostParams.noLogging,
      cleanup = {
        // Shut down subprocess MCP servers before the driver goes away — they're tied to
        // this session's lifetime and every registration's sessionProvider closes over
        // them. Empty list when nothing was launched; no branch needed.
        //
        // Wrapped in `NonCancellable` so teardown completes even when the surrounding
        // coroutine is cancelled (trail timeout, user abort). Without this, cancellation
        // would prevent `session.shutdown()` from running and leak the subprocess +
        // stderr-capture file handle.
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        if (keepDriverAlive) {
          Console.log("🔗 MCP referrer detected - keeping driver alive for device: ${trailblazeDeviceId.instanceId}")
          deviceManager.clearCoroutineScopeForDevice(trailblazeDeviceId)
        } else {
          deviceManager.cancelSessionForDevice(trailblazeDeviceId)
        }
      },
    ) { session ->
      // Spawn target-declared subprocess MCP servers + register their tools into the
      // session's repo. Runs before trail execution so the LLM's first tools/list reflects
      // the full registry. Fail-fast: if any spawn fails, executeTrailSession's catch path
      // reports it and the cleanup lambda tears down anything partial.
      launchSubprocessMcpServersIfAny(
        targetTestApp = runOnHostParams.targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = hostTbRunner.trailblazeDeviceInfo,
        logsRepo = hostTbRunner.hostLoggingRule.logsRepo,
        toolRepo = hostTbRunner.toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }

      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = hostTbRunner.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        forceStopApp = runOnHostParams.forceStopTargetApp,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
      )
      Console.log("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        hostTbRunner.loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = runOnHostParams.targetTestApp
          ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      )

      sessionId
    }
  }

  /**
   * Runs MULTI_AGENT_V3 on the host, driving the on-device accessibility agent via
   * [OnDeviceRpcClient] one tool call at a time.
   *
   * Caller is responsible for instrumentation setup (install APK, start server,
   * enable accessibility service) before calling this function.
   *
   * @param dynamicLlmClient LLM client for screen analysis and planning
   * @param onDeviceRpc Already-connected RPC client to the on-device server
   * @param runYamlRequest The original run request (used for config, model, trail YAML)
   * @param trailblazeDeviceId The Android device being tested
   * @param onProgressMessage Callback for progress messages
   * @param targetTestApp Optional app target (provides custom tool classes)
   * @return The host session ID on completion, or null on failure/cancellation
   */
  suspend fun runHostV3WithAccessibilityYaml(
    dynamicLlmClient: DynamicLlmClient,
    onDeviceRpc: OnDeviceRpcClient,
    runYamlRequest: RunYamlRequest,
    trailblazeDeviceId: TrailblazeDeviceId,
    onProgressMessage: (String) -> Unit,
    targetTestApp: TrailblazeHostAppTarget?,
  ): SessionId? {
    val customToolClasses = targetTestApp
      ?.getCustomToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)
      ?: emptySet()
    val excludedToolClasses = targetTestApp
      ?.getExcludedToolsForDriver(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)
      ?: emptySet()

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )

    // Decode trail YAML to extract prompt steps for V3
    val trailItems = try {
      trailblazeYaml.decodeTrail(runYamlRequest.yaml)
    } catch (e: Exception) {
      onProgressMessage("Failed to decode trail YAML: ${e.message}")
      return null
    }
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)
    val toolItems = trailItems.filterIsInstance<TrailYamlItem.ToolTrailItem>()
    val promptSteps = trailItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }

    if (promptSteps.isEmpty()) {
      onProgressMessage("No prompt steps found in trail YAML for V3 execution")
      return null
    }

    // Query the device for its actual classifiers via a lightweight screen state probe.
    val classifiers = queryDeviceClassifiers(onDeviceRpc).ifEmpty {
      listOf(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier())
    }

    // Set up host-side logging (session start/end logs are emitted here, not on-device)
    val loggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 0,
          heightPixels = 0,
          classifiers = classifiers,
        )
      },
    )

    val llmClient = dynamicLlmClient.createLlmClient()
    val trailblazeLlmModel = runYamlRequest.trailblazeLlmModel

    val samplingSource = LocalLlmSamplingSource(
      llmClient = llmClient,
      llmModel = trailblazeLlmModel,
      logsRepo = loggingRule.logsRepo,
      sessionIdProvider = { loggingRule.session?.sessionId },
    )

    val screenAnalyzer = InnerLoopScreenAnalyzer(
      samplingSource = samplingSource,
      model = trailblazeLlmModel,
    )

    val toolRepo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      excludedToolClasses = excludedToolClasses,
    )

    val executor = HostAccessibilityRpcClient(
      rpcClient = onDeviceRpc,
      toolRepo = toolRepo,
      runYamlRequestTemplate = runYamlRequest,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
      toolExecutionContextProvider = {
        TrailblazeToolExecutionContext(
          screenState = null,
          traceId = null,
          trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
          sessionProvider = { loggingRule.session ?: error("Session not available") },
          trailblazeLogger = loggingRule.logger,
          memory = AgentMemory(),
        )
      },
    )

    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "v3-accessibility:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = {
        runBlocking { executor.captureScreenState() } ?: error("No screen state available")
      },
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
        executor.close()
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
        logsRepo = loggingRule.logsRepo,
        toolRepo = toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      if (runYamlRequest.config.sendSessionStartLog) {
        val deviceInfo = loggingRule.trailblazeDeviceInfoProvider()
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = "HostAccessibilityV3",
              testMethodName = "run",
              trailblazeDeviceInfo = deviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      val plannerLlmCall: PlannerLlmCall = { systemPrompt, userMessage, tools, _, screenshotBytes ->
        val metaInfo = RequestMetaInfo.create(kotlin.time.Clock.System)
        val userMsg = if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
          Message.User(
            parts = buildList {
              add(ContentPart.Text(userMessage))
              add(
                ContentPart.Image(
                  content = AttachmentContent.Binary.Bytes(screenshotBytes),
                  format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
                ),
              )
            },
            metaInfo = metaInfo,
          )
        } else {
          Message.User(content = userMessage, metaInfo = metaInfo)
        }
        val koogPrompt = Prompt(
          messages = listOf(
            Message.System(content = systemPrompt, metaInfo = metaInfo),
            userMsg,
          ),
          id = "host_accessibility_v3_planner",
          params = LLMParams(toolChoice = LLMParams.ToolChoice.Required),
        )
        val responses = llmClient.execute(koogPrompt, trailblazeLlmModel.toKoogLlmModel(), tools)
        val toolCall = responses.filterIsInstance<Message.Tool.Call>().firstOrNull()
        val toolArgsJson = toolCall?.content ?: "{}"
        val toolArgs = try {
          Json.parseToJsonElement(toolArgsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
          JsonObject(emptyMap())
        }
        PlannerToolCallResult.fromRaw(toolCall?.tool ?: tools.firstOrNull()?.name ?: "unknown", toolArgs)
      }

      val progressListener = loggingRule.logger.createProgressListener(session)
      val progressReporter = DefaultProgressReporter(progressListener)
      val availableToolsProvider = {
        toolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() }
      }

      val v3Runner = MultiAgentV3Runner.create(
        screenAnalyzer = screenAnalyzer,
        executor = executor,
        plannerLlmCall = plannerLlmCall,
        config = BlazeConfig.DEFAULT,
        progressReporter = progressReporter,
        deviceId = trailblazeDeviceId,
        availableToolsProvider = availableToolsProvider,
      )

      onProgressMessage("Starting V3 runner on host with accessibility driver (${promptSteps.size} steps)...")

      // Execute pre-action tools (e.g. launchApp) before running V3 prompt steps.
      // Reuse the host's top-level session ID so pre-action logs land in the same
      // on-device session directory as the main V3 loop — matches the per-tool
      // dispatch path in HostAccessibilityRpcClient.execute().
      //
      // Pre-action failure short-circuits the trail: `launchApp` failing means the main V3
      // loop would otherwise run against the wrong app state, producing a confusing
      // mid-trail failure instead of a clean "couldn't launch the app under test" one.
      var preActionFailure: String? = null
      preActionLoop@ for (toolItem in toolItems) {
        for (toolWrapper in toolItem.tools) {
          val toolYaml = trailblazeYaml.encodeToString(
            listOf(TrailYamlItem.ToolTrailItem(listOf(toolWrapper))),
          )
          val singleToolRequest = runYamlRequest.copy(
            yaml = toolYaml,
            agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
            config = runYamlRequest.config.copy(
              overrideSessionId = session.sessionId,
              sendSessionStartLog = false,
              sendSessionEndLog = false,
            ),
          )
          val ok = executor.executePreAction(singleToolRequest)
          if (!ok) {
            preActionFailure =
              "Pre-action '${toolWrapper.trailblazeTool::class.simpleName ?: "unknown"}' " +
                "failed on-device; aborting trail before V3 prompt steps run. See prior log lines " +
                "for the on-device error message."
            break@preActionLoop
          }
        }
      }

      // Recording-first with AI-level retry budget. AI_ONLY here caused every step to re-plan
      // via LLM even when the recording matched — why the V3 a11y step had been running 100%
      // AI-driven on main.
      //
      // Skip the V3 trail entirely if a pre-action failed — see preActionFailure above.
      val trailSuccess: Boolean
      val trailErrorMessage: String?
      if (preActionFailure != null) {
        onProgressMessage("V3 trail aborted: $preActionFailure")
        trailSuccess = false
        trailErrorMessage = preActionFailure
      } else {
        val result = v3Runner.trail(
          steps = promptSteps,
          config = TrailConfig.RECORDING_WITH_AI_RETRIES,
          sessionId = session.sessionId,
        )
        trailSuccess = result.success
        trailErrorMessage = result.errorMessage
        onProgressMessage(
          if (result.success) "V3 trail completed successfully"
          else "V3 trail failed: ${result.errorMessage}",
        )
      }

      if (runYamlRequest.config.sendSessionEndLog) {
        val sessionManager = loggingRule.sessionManager
        if (trailSuccess) {
          sessionManager.endSession(session, isSuccess = true)
        } else {
          sessionManager.endSession(
            session,
            isSuccess = false,
            exception = Exception(trailErrorMessage ?: "Trail execution failed"),
          )
        }
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = customToolClasses,
      )

      session.sessionId
    }
  }

  /**
   * Runs the legacy [TrailblazeRunner] on the host with tool execution delegated to an
   * on-device driver (accessibility or instrumentation) via RPC.
   *
   * The agent loop (LLM calls, tool selection) runs on the host JVM. Each individual tool
   * call is serialized as single-step trail YAML and sent to the device. The device
   * executes the tool via whichever driver is specified in the request's `driverType`.
   * Mirrors the [runHostV3WithAccessibilityYaml] pattern but using [TrailblazeRunner]
   * instead of [MultiAgentV3Runner].
   */
  suspend fun runHostTrailblazeRunnerWithOnDeviceRpc(
    dynamicLlmClient: DynamicLlmClient,
    onDeviceRpc: OnDeviceRpcClient,
    runYamlRequest: RunYamlRequest,
    trailblazeDeviceId: TrailblazeDeviceId,
    onProgressMessage: (String) -> Unit,
    targetTestApp: TrailblazeHostAppTarget?,
  ): SessionId? {
    val driverType = runYamlRequest.driverType
      ?: TrailblazeDriverType.DEFAULT_ANDROID
    val customToolClasses = targetTestApp
      ?.getCustomToolsForDriver(driverType)
      ?: emptySet()
    val excludedToolClasses = targetTestApp?.getExcludedToolsForDriver(driverType) ?: emptySet()

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = customToolClasses,
    )

    val trailItems = try {
      trailblazeYaml.decodeTrail(runYamlRequest.yaml)
    } catch (e: Exception) {
      onProgressMessage("Failed to decode trail YAML: ${e.message}")
      return null
    }
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Query the device for its actual classifiers via a lightweight screen state probe.
    val classifiers = queryDeviceClassifiers(onDeviceRpc).ifEmpty {
      listOf(TrailblazeDevicePlatform.ANDROID.asTrailblazeDeviceClassifier())
    }

    val loggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = trailblazeDeviceId,
          trailblazeDriverType = driverType,
          widthPixels = 0,
          heightPixels = 0,
          classifiers = classifiers,
        )
      },
    )

    val trailblazeLlmModel = runYamlRequest.trailblazeLlmModel
    val llmClient = dynamicLlmClient.createLlmClient()

    val toolRepo = TrailblazeToolRepo.withDynamicToolSets(
      customToolClasses = customToolClasses,
      excludedToolClasses = excludedToolClasses,
    )

    val agent = HostOnDeviceRpcTrailblazeAgent(
      rpcClient = onDeviceRpc,
      runYamlRequestTemplate = runYamlRequest,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = loggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
      customToolClasses = customToolClasses,
      requireAndroidAccessibilityServiceOnRewarm =
        runYamlRequest.driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      trailblazeToolRepo = toolRepo,
    )

    val runner = TrailblazeRunner(
      agent = agent,
      screenStateProvider = agent.screenStateProvider,
      llmClient = llmClient,
      trailblazeLlmModel = trailblazeLlmModel,
      trailblazeToolRepo = toolRepo,
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
    )

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = agent.screenStateProvider,
      llmClient = llmClient,
      trailblazeLlmModel = trailblazeLlmModel,
      toolRepo = toolRepo,
    )

    val runnerUtil = TrailblazeRunnerUtil(
      trailblazeRunner = runner,
      runTrailblazeTool = { tools ->
        val result = agent.runTrailblazeTools(
          tools = tools,
          traceId = null,
          screenState = agent.screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = agent.screenStateProvider,
        )
        when (val toolResult = result.result) {
          is TrailblazeToolResult.Success -> toolResult
          is TrailblazeToolResult.Error -> throw TrailblazeException(toolResult.errorMessage)
        }
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { loggingRule.session ?: error("Session not available") },
    )

    val subprocessRuntimes = mutableListOf<LaunchedSubprocessRuntime>()
    return executeTrailSession(
      loggingRule = loggingRule,
      overrideSessionId = runYamlRequest.config.overrideSessionId,
      testName = runYamlRequest.testName,
      deviceLabel = "rpc-runner:${trailblazeDeviceId.instanceId}",
      sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
      onProgressMessage = onProgressMessage,
      screenshotProvider = {
        runBlocking { agent.captureScreenState() } ?: error("No screen state available")
      },
      cleanup = {
        withContext(NonCancellable) {
          subprocessRuntimes.forEach { it.shutdownAll() }
        }
      },
    ) { session ->
      launchSubprocessMcpServersIfAny(
        targetTestApp = targetTestApp,
        config = runYamlRequest.config,
        sessionId = session.sessionId,
        deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
        logsRepo = loggingRule.logsRepo,
        toolRepo = toolRepo,
        onProgressMessage = onProgressMessage,
      )?.let { subprocessRuntimes += it }
      if (runYamlRequest.config.sendSessionStartLog) {
        val deviceInfo = loggingRule.trailblazeDeviceInfoProvider()
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = "HostOnDeviceRpcRunner",
              testMethodName = "run",
              trailblazeDeviceInfo = deviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      onProgressMessage(
        "Starting TrailblazeRunner on host with ${driverType.name.lowercase()} driver via RPC (${trailItems.size} trail items)...",
      )

      for (item in trailItems) {
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            runnerUtil.runPromptSuspend(item.promptSteps, useRecordedSteps = true)
          is TrailYamlItem.ToolTrailItem ->
            runnerUtil.runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem ->
            item.config.context?.let { runner.appendToSystemPrompt(it) }
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
      }

      onProgressMessage("TrailblazeRunner accessibility execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        loggingRule.sessionManager.endSession(session, isSuccess = true)
      }

      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = customToolClasses,
      )

      session.sessionId
    }
  }

  /**
   * Queries the on-device agent for its device classifiers via a lightweight screen state probe.
   * Returns the classifiers (e.g., ["android", "phone"]) or an empty list if the device
   * doesn't provide them (older on-device agents without classifier support).
   */
  private suspend fun queryDeviceClassifiers(
    onDeviceRpc: OnDeviceRpcClient,
  ): List<TrailblazeDeviceClassifier> {
    val probe = GetScreenStateRequest(
      includeScreenshot = false,
      includeAnnotatedScreenshot = false,
    )
    return when (val result = onDeviceRpc.rpcCall(probe)) {
      is RpcResult.Success -> {
        result.data.deviceClassifiers?.map { TrailblazeDeviceClassifier(it) } ?: emptyList()
      }
      is RpcResult.Failure -> {
        Console.log(
          "[Trailblaze] Failed to query device classifiers from on-device agent; " +
            "falling back to default classifiers. RPC failure: ${result.message}",
        )
        emptyList()
      }
    }
  }

  /**
   * Result of recording generation, containing info needed to copy the recording
   * back to the trail source directory.
   */
  data class RecordingResult(
    val recordingFile: File,
    val deviceClassifiers: List<xyz.block.trailblaze.devices.TrailblazeDeviceClassifier>,
    val driverType: String?,
  )

  /**
   * Compares each snapshot taken during [sessionId] against its checked-in golden file.
   * Goldens are resolved from the trail file's directory using the pattern:
   * `{device-classifier}.{snapshot-name}.golden.png`
   *
   * Missing goldens are silently skipped (not a failure) — this allows new trails to run
   * without goldens until they are deliberately captured and committed.
   */
  private fun compareSnapshotsAgainstGoldens(sessionId: SessionId): SnapshotGoldenComparison.GoldenComparisonResult? {
    return try {
      val gitRoot = GitUtils.getGitRootViaCommand() ?: return null
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      if (!sessionDir.exists()) return null

      val logs = sessionDir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { file ->
          try { TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText()) }
          catch (_: Exception) { null }
        }
        ?.sortedBy { it.timestamp }
        ?: return null

      val comparison = SnapshotGoldenComparison.compare(
        sessionId = sessionId,
        sessionDir = sessionDir,
        logs = logs,
      )

      Console.log("[Golden] ${comparison.summary}")
      comparison.results.forEach { r ->
        if (r.goldenFound && !r.passed) {
          Console.log(
            "[Golden] ❌ '${r.snapshotName}': ${"%.2f".format(r.diffPercent)}% diff" +
              " (${r.pixelDifferences}/${r.totalPixels} pixels," +
              " threshold ${r.thresholdPercent}%)"
          )
        }
      }

      comparison
    } catch (e: Exception) {
      Console.log("[Golden] Comparison error (non-fatal): ${e.message}")
      null
    }
  }

  private fun generateAndSaveRecording(
    sessionId: SessionId,
    customToolClasses: Set<kotlin.reflect.KClass<out xyz.block.trailblaze.toolcalls.TrailblazeTool>> = emptySet(),
  ): RecordingResult? {
    try {
      val gitRoot = GitUtils.getGitRootViaCommand()
      if (gitRoot == null) {
        Console.log("Could not determine git root, skipping recording generation")
        return null
      }
      val sessionDir = File(File(gitRoot, "logs"), sessionId.value)
      if (!sessionDir.exists()) {
        Console.log("Session directory not found at ${sessionDir.absolutePath}, skipping recording generation")
        return null
      }

      val logFiles = sessionDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
      val logs = logFiles.mapNotNull { file ->
        try {
          TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(file.readText())
        } catch (_: Exception) {
          null
        }
      }.sortedBy { it.timestamp }

      if (logs.isEmpty()) {
        Console.log("No logs found for session, skipping recording generation")
        return null
      }

      // Extract session config from the Started status log to enrich the recording
      val startedStatus = logs
        .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .map { it.sessionStatus }
        .filterIsInstance<xyz.block.trailblaze.logs.model.SessionStatus.Started>()
        .firstOrNull()

      val sessionTrailConfig = startedStatus?.let { started ->
        val originalConfig = started.trailConfig
        // Merge original config with device/driver info from the session
        xyz.block.trailblaze.yaml.TrailConfig(
          id = originalConfig?.id,
          title = originalConfig?.title,
          description = originalConfig?.description,
          priority = originalConfig?.priority,
          context = originalConfig?.context,
          source = originalConfig?.source,
          metadata = originalConfig?.metadata,
          target = originalConfig?.target,
          driver = started.trailblazeDeviceInfo.trailblazeDriverType.name,
          platform = started.trailblazeDeviceInfo.platform.name.lowercase(),
        )
      }

      val recordingYaml = logs.generateRecordedYaml(
        sessionTrailConfig = sessionTrailConfig,
        customToolClasses = customToolClasses,
      )

      // Save to session directory
      val sessionRecordingFile = File(sessionDir, "recording.trail.yaml")
      sessionRecordingFile.writeText(recordingYaml)
      Console.log("Recording saved to: ${sessionRecordingFile.absolutePath}")

      val classifiers = startedStatus?.trailblazeDeviceInfo?.classifiers ?: emptyList()
      val driverType = startedStatus?.trailblazeDeviceInfo?.trailblazeDriverType?.name

      return RecordingResult(
        recordingFile = sessionRecordingFile,
        deviceClassifiers = classifiers,
        driverType = driverType,
      )
    } catch (e: Exception) {
      Console.log("Failed to generate recording: ${e.message}")
      return null
    }
  }
}
