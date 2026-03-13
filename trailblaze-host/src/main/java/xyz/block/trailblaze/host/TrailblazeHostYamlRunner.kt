package xyz.block.trailblaze.host

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcTrailblazeAgent
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSet
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.host.rules.BaseComposeTest
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.tracing.TrailblazeTraceExporter
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeToolSet
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.GitUtils
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.ElectronAppConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

object TrailblazeHostYamlRunner {

  /**
   * Captures a screenshot at the moment of failure and logs it as a snapshot.
   * Catches all exceptions internally so it never disrupts the error handling flow.
   */
  private fun captureFailureScreenshot(
    session: TrailblazeSession,
    loggingRule: TrailblazeLoggingRule,
    screenStateProvider: () -> ScreenState,
  ) {
    try {
      val screenState = screenStateProvider()
      loggingRule.logger.logSnapshot(session, screenState, displayName = "failure_screenshot")
      Console.log("📸 Failure screenshot captured for session ${session.sessionId.value}")
    } catch (e: Exception) {
      Console.log("Failed to capture failure screenshot: ${e.message}")
    }
  }

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
  ) {
    withContext(kotlinx.coroutines.NonCancellable) {
      TrailblazeTraceExporter.exportAndSave(
        sessionId = sessionId,
        client = loggingRule.trailblazeLogServerClient,
        isServerAvailable = true, // Host runner always has a server running
        writeToDisk = { traceJson ->
          val logsDir = File(GitUtils.getGitRootViaCommand(), "logs")
          val sessionDir = File(logsDir, sessionId.value)
          sessionDir.mkdirs()
          File(sessionDir, "trace.json").writeText(traceJson)
        },
      )
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

    val allSerializationToolClasses = deviceManager.availableAppTargets
      .flatMap { it.getAllCustomToolClassesForSerialization() }
      .toSet()

    val playwrightTest = existingTest ?: BasePlaywrightNativeTest(
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      allSerializationToolClasses = allSerializationToolClasses,
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
    )

    // Reset the browser session when reusing so we start from a clean page state.
    // Must run on the Playwright thread to maintain thread affinity.
    if (isReusingTest) {
      withContext(playwrightTest.browserManager.playwrightDispatcher) {
        playwrightTest.browserManager.resetSession()
      }
    }

    // Cache the test instance for reuse across subsequent MCP calls
    if (keepBrowserAlive) {
      deviceManager.setActivePlaywrightNativeTest(requestDeviceId, playwrightTest)
    }

    val sessionManager = playwrightTest.loggingRule.sessionManager

    val overrideSessionId = runYamlRequest.config.overrideSessionId
    val session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(runYamlRequest.testName)
    }
    playwrightTest.loggingRule.setSession(session)

    onProgressMessage("Launching browser...")

    return try {
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
        sessionManager.endSession(session, isSuccess = true)
      }

      // Generate and save recording YAML from session logs
      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = PlaywrightNativeToolSet.LlmToolSet.toolClasses + customToolClasses,
      )

      sessionId
    } catch (e: TrailblazeSessionCancelledException) {
      Console.log("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      null
    } catch (e: CancellationException) {
      Console.log("🚫 CancellationException caught for device: ${trailblazeDeviceId.instanceId} - ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      Console.log("❌ Exception caught in runPlaywrightNativeYaml for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      captureFailureScreenshot(session, playwrightTest.loggingRule, playwrightTest.browserManager::getScreenState)
      sessionManager.endSession(session, isSuccess = false, exception = e)
      null
    } finally {
      Console.log("🧹 Finally block executing for Playwright-native device: ${trailblazeDeviceId.instanceId}")
      exportAndSaveTrace(session.sessionId, playwrightTest.loggingRule)
      playwrightTest.loggingRule.setSession(null)
      if (!keepBrowserAlive) {
        playwrightTest.close()
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      }
      Console.log("🏁 Finally block completed for Playwright-native device: ${trailblazeDeviceId.instanceId}")
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

    val allSerializationToolClasses = deviceManager.availableAppTargets
      .flatMap { it.getAllCustomToolClassesForSerialization() }
      .toSet()

    val electronTest = existingTest ?: BasePlaywrightElectronTest(
      electronAppConfig = electronConfig,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      allSerializationToolClasses = allSerializationToolClasses,
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

    val sessionManager = electronTest.loggingRule.sessionManager

    val overrideSessionId = runYamlRequest.config.overrideSessionId
    val session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(runYamlRequest.testName)
    }
    electronTest.loggingRule.setSession(session)

    onProgressMessage("Connecting to Electron app...")

    return try {
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
        sessionManager.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = PlaywrightNativeToolSet.LlmToolSet.toolClasses +
          BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES + customToolClasses,
      )

      sessionId
    } catch (e: TrailblazeSessionCancelledException) {
      Console.log("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      null
    } catch (e: CancellationException) {
      Console.log("🚫 CancellationException caught for device: ${trailblazeDeviceId.instanceId} - ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      Console.log("❌ Exception caught in runPlaywrightElectronYaml for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      captureFailureScreenshot(session, electronTest.loggingRule, electronTest.browserManager::getScreenState)
      sessionManager.endSession(session, isSuccess = false, exception = e)
      null
    } finally {
      Console.log("🧹 Finally block executing for Playwright-electron device: ${trailblazeDeviceId.instanceId}")
      exportAndSaveTrace(session.sessionId, electronTest.loggingRule)
      electronTest.loggingRule.setSession(null)
      if (!keepAlive) {
        electronTest.close()
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      }
      Console.log("🏁 Finally block completed for Playwright-electron device: ${trailblazeDeviceId.instanceId}")
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

    val sessionSuffix = UUID.randomUUID().toString().take(8)
    val trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "compose-$sessionSuffix",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
    )

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

    // Initialize JSON serialization with Compose tool classes.
    // Wrap agent creation in try-catch so rpcClient is closed if setup fails before
    // the agent (which owns the client lifecycle) is constructed.
    val agent: ComposeRpcTrailblazeAgent
    val loggingRule: HostTrailblazeLoggingRule
    try {
      TrailblazeJsonInstance = TrailblazeJson.createTrailblazeJsonInstance(
        allToolClasses = TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerializationByToolName +
          ComposeToolSet.LlmToolSet.toolClasses.associateBy { it.toolName() },
      )

      loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
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

    val toolRepo = TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        "Compose RPC Tool Set",
        ComposeToolSet.LlmToolSet.toolClasses,
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
      customTrailblazeToolClasses = ComposeToolSet.LlmToolSet.toolClasses,
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

    val sessionManager = loggingRule.sessionManager

    val overrideSessionId = runYamlRequest.config.overrideSessionId
    val session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(runYamlRequest.testName)
    }
    loggingRule.setSession(session)

    return try {
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

      if (runYamlRequest.config.sendSessionEndLog) {
        sessionManager.endSession(session, isSuccess = true)
      }

      // Generate and save recording YAML from session logs
      generateAndSaveRecording(
        sessionId = session.sessionId,
        customToolClasses = ComposeToolSet.LlmToolSet.toolClasses,
      )

      session.sessionId
    } catch (e: TrailblazeSessionCancelledException) {
      Console.log("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      null
    } catch (e: CancellationException) {
      Console.log("🚫 CancellationException caught for device: ${trailblazeDeviceId.instanceId} - ${e.message}")
      onProgressMessage("Test execution cancelled")
      throw e
    } catch (e: Exception) {
      Console.log("❌ Exception caught in runComposeYaml for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      captureFailureScreenshot(session, loggingRule, screenStateProvider)
      sessionManager.endSession(session, isSuccess = false, exception = e)
      null
    } finally {
      Console.log("🧹 Finally block executing for Compose RPC device: ${trailblazeDeviceId.instanceId}")
      exportAndSaveTrace(session.sessionId, loggingRule)
      loggingRule.setSession(null)
      agent.close()
      deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      Console.log("🏁 Finally block completed for Compose RPC device: ${trailblazeDeviceId.instanceId}")
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

    // Gather ALL custom tool classes from ALL available app targets for YAML deserialization.
    // This ensures any tool referenced in a trail file can be deserialized regardless of which
    // app target is selected. The LLM tool repo still uses the target-specific customToolClasses.
    val allSerializationToolClasses = deviceManager.availableAppTargets
      .flatMap { it.getAllCustomToolClassesForSerialization() }
      .toSet()

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
      allSerializationToolClasses = allSerializationToolClasses,
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

    // Get logger and session manager from the test's logging rule
    val logger = hostTbRunner.loggingRule.logger
    val sessionManager = hostTbRunner.loggingRule.sessionManager

    // Extract override session ID to avoid smart cast issues
    val overrideSessionId = runYamlRequest.config.overrideSessionId

    // Initialize session using SessionManager
    var session = if (overrideSessionId != null) {
      sessionManager.createSessionWithId(overrideSessionId)
    } else {
      sessionManager.startSession(runYamlRequest.testName)
    }

    // Set the session on the logging rule so it's available to all components
    // (hostRunner, trailblazeAgent, trailblazeRunner) that use sessionProvider
    hostTbRunner.loggingRule.setSession(session)

    onProgressMessage("Connecting to $trailblazeDeviceId device...")

    return try {
      onProgressMessage("Executing YAML test...")
      Console.log("▶️ Starting runTrailblazeYamlSuspend for device: ${trailblazeDeviceId.instanceId}")
      val sessionId = hostTbRunner.runTrailblazeYamlSuspend(
        yaml = runYamlRequest.yaml,
        forceStopApp = runOnHostParams.forceStopTargetApp,
        trailFilePath = runYamlRequest.trailFilePath,
        trailblazeDeviceId = trailblazeDeviceId,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog
      )
      Console.log("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        // End session using SessionManager
        sessionManager.endSession(session, isSuccess = true)
      } else {
        // Keep the session open
      }

      // Generate and save recording YAML from session logs
      generateAndSaveRecording(
        sessionId = sessionId,
        customToolClasses = runOnHostParams.targetTestApp
          ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      )

       sessionId
    } catch (e: TrailblazeSessionCancelledException) {
      // Handle Trailblaze session cancellation - user cancelled via UI
      Console.log("🚫 TrailblazeSessionCancelledException caught for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test session cancelled")
      // DON'T write log here - cancellation log is written by the UI layer
      // (JvmLiveSessionDataProvider.writeCancellationLog) to avoid duplicates
      // Don't re-throw, just end gracefully
      null
    } catch (e: CancellationException) {
      // Handle coroutine cancellation explicitly
      Console.log("🚫 CancellationException caught for device: ${trailblazeDeviceId.instanceId} - ${e.message}")
      onProgressMessage("Test execution cancelled")
      // DON'T write log here - cancellation log is already written by the UI layer
      // to avoid duplicate logs. Just do cleanup in finally block.
      // Re-throw to propagate cancellation
      throw e
    } catch (e: Exception) {
      Console.log("❌ Exception caught in runHostYaml for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Test execution failed: ${e.message}")
      captureFailureScreenshot(session, hostTbRunner.loggingRule, hostTbRunner.hostRunner.screenStateProvider)
      sessionManager.endSession(session, isSuccess = false, exception = e)
      null
    } finally {
      // IMPORTANT: This ALWAYS executes, even when cancelled!
      // Ensures device manager state is updated and job is cleaned up
      Console.log("🧹 Finally block executing for device: ${trailblazeDeviceId.instanceId} - calling cancelSessionForDevice")
      exportAndSaveTrace(session.sessionId, hostTbRunner.loggingRule)
      // Clear the session from the logging rule to prevent stale sessions
      hostTbRunner.loggingRule.setSession(null)

      // For MCP requests, keep the driver alive so subsequent tool calls can reuse it.
      // The driver will be closed when the MCP session explicitly ends.
      // For all other sources (UI, CLI), close the driver after each run.
      val keepDriverAlive = runOnHostParams.referrer == TrailblazeReferrer.MCP

      if (keepDriverAlive) {
        Console.log("🔗 MCP referrer detected - keeping driver alive for device: ${trailblazeDeviceId.instanceId}")
        // Don't cancel the session/close driver for MCP - just clear the coroutine scope
        deviceManager.clearCoroutineScopeForDevice(trailblazeDeviceId)
      } else {
        Console.log("🧹 Finally block executing for device: ${trailblazeDeviceId.instanceId} - calling cancelSessionForDevice")
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      }
      Console.log("🏁 Finally block completed for device: ${trailblazeDeviceId.instanceId}")
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
   * Generates a recording YAML from a completed session's logs and saves it
   * in the session directory (as `recording.trail.yaml`).
   *
   * @return [RecordingResult] if the recording was saved, or null on failure.
   */
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
          app = originalConfig?.app,
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
