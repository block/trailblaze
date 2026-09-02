package xyz.block.trailblaze.host.compose

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.compose.driver.ComposeTrailblazeAgent
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcClient
import xyz.block.trailblaze.compose.driver.rpc.ComposeRpcTrailblazeAgent
import xyz.block.trailblaze.compose.driver.tools.ComposeToolSetIds
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.rules.BaseComposeTest
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Plugs the Compose desktop driver into the host: a Compose app that embeds a `ComposeRpcServer`
 * is driven over local HTTP RPC — no device transport, no instrumentation.
 *
 * One logical instance per host (the desktop window itself), discovered by probing the RPC port.
 * Listing is [DeviceListingVisibility.LISTED], but note the device manager's web-mode filter still
 * gates virtual devices (any driver whose platform `usesVirtualDevice`) as a group — a user-facing
 * environment setting, not a per-driver fact, so it stays in the manager.
 *
 * Two port knobs meet in this class, deliberately not collapsed. [rpcProbePort] is app-lifetime
 * discovery config: what the background pass pings, fixed when the app config constructs this
 * descriptor. `RunOnHostParams.composeRpcPort` is a per-run override (`--compose-port`) that a
 * background pass can't know and doesn't need to: the CLI synthesizes the compose device for
 * such runs rather than gating on discovery, so an app on a non-default port runs fine — it just
 * isn't listed, same as before the descriptor existed. Both default to the same
 * [TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT], so in the no-flags case probe and run agree.
 */
class ComposeHostDriverDescriptor(
  private val rpcProbePort: Int = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT,
) : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.COMPOSE)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * One "self" entry when a Compose app's RPC server answers the ping, nothing otherwise. The
   * instance id makes the device address as `desktop/self` — one logical instance per host, the
   * desktop window itself; the platform is `DESKTOP` per [TrailblazeDriverType.COMPOSE]'s platform.
   *
   * Ignores [inventory]: the Compose driver's "device" is a local RPC endpoint, not a host
   * transport. The probe self-bounds at 500ms per connect/read, so an unresponsive port degrades
   * to absence quickly rather than eating the discovery budget.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> {
    if (!isComposeRpcAvailable()) return emptyList()
    return listOf(
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.COMPOSE,
        instanceId = SELF_INSTANCE_ID,
        description = "Compose Desktop (RPC)",
      ),
    )
  }

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    HostYamlRunResult(
      runComposeYaml(
        dynamicLlmClient = deps.dynamicLlmClient,
        runOnHostParams = params,
        deviceManager = deps.deviceManager,
        logsDir = deps.logsDir,
      ),
    )

  /**
   * No capture path is wired for the Compose desktop driver at the device-manager level —
   * preserved from the pre-descriptor arm. Interactive (MCP) capture goes through the bridge's
   * own Compose RPC screen-state provider, which never reaches this.
   */
  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? {
    Console.log("⚠️ Screen state capture not supported for ${driverType.name} driver")
    return null
  }

  /**
   * Quick probe to check if the Compose RPC server is responding.
   * Uses a 500ms connect/read timeout — if nothing is listening, this fails fast.
   */
  private fun isComposeRpcAvailable(): Boolean {
    var connection: HttpURLConnection? = null
    return try {
      val url = URI("http://localhost:$rpcProbePort/ping").toURL()
      connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = 500
      connection.readTimeout = 500
      connection.responseCode == 200
    } catch (_: Exception) {
      false
    } finally {
      connection?.disconnect()
    }
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
    logsDir: File?,
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
      // Bind the repo to the Compose driver so the KOOG tool surface matches it: COMPOSE is not in
      // KOOG_INSPECTION_DRIVERS, so the generic `requestDetailedViewHierarchy` inspection tool (which
      // a null-driver repo injects) stays off the surface — Compose's own `compose_request_details`
      // is the detail tool. KOOG-path only; the default runner's surface is unaffected.
      driverType = TrailblazeDriverType.COMPOSE,
    )

    // Wrap agent creation in try-catch so rpcClient is closed if setup fails before
    // the agent (which owns the client lifecycle) is constructed.
    val agent: ComposeRpcTrailblazeAgent
    val loggingRule: HostTrailblazeLoggingRule
    try {
      loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        noLogging = runOnHostParams.noLogging,
        logsDir = logsDir,
      )

      agent = ComposeRpcTrailblazeAgent(
        rpcClient = rpcClient,
        trailblazeLogger = loggingRule.logger,
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        // Thread the session tool repo through so framework-tool composition resolves by name and
        // so the KOOG strategy graph's dynamic-tool execution context is satisfied.
        trailblazeToolRepo = toolRepo,
      )
    } catch (e: Exception) {
      rpcClient.close()
      throw e
    }

    val screenStateProvider = agent.screenStateProvider

    val elementComparator = TrailblazeElementComparator(
      screenStateProvider = screenStateProvider,
      llmClient = dynamicLlmClient.createLlmClient(),
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      toolRepo = toolRepo,
    )

    // Brain selection (legacy or KOOG). Recordings replay uniformly via the runner-util below
    // regardless of agent — only unrecorded steps reach the selected brain. Mirrors the Revyl /
    // on-device wiring; the default TRAILBLAZE_RUNNER path is unchanged.
    val trailblazeRunner: TestAgentRunner =
      if (runYamlRequest.agentImplementation == AgentImplementation.KOOG_STRATEGY_GRAPH) {
        KoogTestAgentRunner(
          agent = agent,
          toolRepo = toolRepo,
          screenStateProvider = screenStateProvider,
          elementComparator = elementComparator,
          llmClient = dynamicLlmClient.createLlmClient(),
          trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
          logger = loggingRule.logger,
          sessionProvider = { loggingRule.session ?: error("Session not available - ensure test is running") },
          maxLlmCalls = runYamlRequest.maxLlmCalls,
          // Use the same Compose-desktop system prompt the legacy runner uses (not the generic
          // mobile prompt) so the agent gets the Compose semantics-tree + takeSnapshot guidance.
          systemPromptTemplate = BaseComposeTest.COMPOSE_SYSTEM_PROMPT,
        )
      } else {
        TrailblazeRunner(
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
          maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
        )
      }

    val trailblazeYaml = createTrailblazeYaml(
      customTrailblazeToolClasses = composeToolSet.toolClasses,
    )

    val trailblazeRunnerUtil = TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = { trailblazeTools: List<TrailblazeTool> ->
        agent.runTrailblazeTools(
          trailblazeTools,
          runYamlRequest.traceId,
          screenState = screenStateProvider(),
          elementComparator = elementComparator,
          screenStateProvider = screenStateProvider,
        ).result
      },
      trailblazeLogger = loggingRule.logger,
      sessionProvider = {
        loggingRule.session ?: error("Session not available - ensure test is running")
      },
      sessionUpdater = { loggingRule.setSession(it) },
      // Shares one execution context + snapshot frame across the recording, matching the
      // batching pattern elsewhere. Unlike the Android/host-Maestro wiring, this agent's
      // buildExecutionContext doesn't cache per-call device state today, so the benefit here
      // is reduced frame/ThreadLocal churn rather than a clipboard-style state-survival fix.
      sharedToolBatch = { block -> agent.runInSharedToolBatch(block) },
    )

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return TrailblazeHostYamlRunner.executeTrailSession(
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
      TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny(
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

      // decodeTrailOrToolEnvelope (superset of decodeTrail): a trail document decodes exactly as
      // before; a bare tool-wrapper envelope (single-tool MCP dispatch) decodes via decodeTools,
      // never the legacy list-shape trail parser.
      val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrailOrToolEnvelope(
        runYamlRequest.yaml,
        deviceClassifiers = trailblazeDeviceInfo.classifiers,
      )
      val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

      // Honor `config.skip:` before SessionStarted is logged — matches the CLI's pre-flight
      // `planTrailExecution` planner. Short-circuit here so the runner never opens a session,
      // runs the actionable-steps guard, or iterates trail items for a skip-marked trail.
      trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
        Console.log(
          "[Trailblaze] Skipping trail" +
            (runYamlRequest.trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
        )
        return@executeTrailSession session.sessionId
      }

      // Seed the agent's memory before any tool runs — same [AgentMemory.seedFrom]
      // composition as the V3 site: YAML `config.memory:` defaults, then CLI `--memory`
      // overrides, then CLI `--secret` (sensitive; excluded from the returned snapshot).
      // The agent threads this memory into every tool execution context, so `{{var}}`
      // interpolation and scripted tools' `ctx.memory` both see the seeds.
      val resolvedInitialMemory = agent.memory.seedFrom(
        yamlDefaults = trailConfig?.memory,
        cliSeeds = runYamlRequest.initialMemorySeeds,
        cliSensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
      )
      // Seed the `args.` namespace from the CLI-bound values AFTER memory, so a token-valued
      // default (`default: '{{memory.email}}'`) resolves against the just-seeded memory.
      agent.memory.seedArgs(TrailArgBinder.decodeProvided(runYamlRequest.initialArgs))
      val sensitiveMemoryKeys: Set<String> = agent.memory.sensitiveKeys.toSet()

      if (runYamlRequest.config.sendSessionStartLog) {
        // CLI / daemon runs have no JUnit Description, so derive a readable Suite::test
        // identity from the trail path instead of a bare "ComposeRpc::run" (see
        // deriveTestIdentityFromTrailPath). The driver name stays the path-less fallback.
        val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
          TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "ComposeRpc")
        }
        loggingRule.logger.log(
          session,
          TrailblazeLog.TrailblazeSessionStatusChangeLog(
            sessionStatus = SessionStatus.Started(
              trailConfig = trailConfig,
              trailFilePath = runYamlRequest.trailFilePath,
              testClassName = derivedTestIdentity?.className ?: "ComposeRpc",
              testMethodName = derivedTestIdentity?.methodName ?: "run",
              trailblazeDeviceInfo = trailblazeDeviceInfo,
              rawYaml = runYamlRequest.yaml,
              hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
              trailblazeDeviceId = trailblazeDeviceId,
              resolvedInitialMemory = resolvedInitialMemory,
              sensitiveMemoryKeys = sensitiveMemoryKeys,
            ),
            session = session.sessionId,
            timestamp = Clock.System.now(),
          ),
        )
      }

      TrailblazeHostYamlRunner.requireActionableSteps(
        trailblazeYaml = trailblazeYaml,
        trailItems = trailItems,
        trailName = trailConfig?.title ?: runYamlRequest.trailFilePath,
      )

      for (item in trailItems) {
        val itemResult = when (item) {
          // Agent-agnostic: replays recorded steps deterministically and delegates only unrecorded
          // steps to the selected runner (legacy / KOOG). ComposeRpcTrailblazeAgent is now a
          // BaseTrailblazeAgent, so the KOOG strategy graph drives it through the same seam as the
          // other drivers. Default unchanged.
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(
              prompts = item.promptSteps,
              useRecordedSteps = runYamlRequest.useRecordedSteps,
              selfHeal = runYamlRequest.config.selfHeal,
            )
          is TrailYamlItem.TrailheadTrailItem ->
            trailblazeRunnerUtil.runPromptSuspend(
              prompts = listOf(item.trailhead.toPromptStep()),
              useRecordedSteps = true,
              selfHeal = runYamlRequest.config.selfHeal,
            )
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

      TrailblazeHostYamlRunner.generateAndSaveRecording(
        sessionId = session.sessionId,
        logsDir = loggingRule.logsRepo.logsDir,
        customToolClasses = composeToolSet.toolClasses,
      )

      // Run snapshot comparison (baseline run when configured, else checked-in goldens) before
      // ending the session so failures are reflected in session status.
      val goldenResult = TrailblazeHostYamlRunner.compareSnapshotsAgainstGoldens(
        sessionId = session.sessionId,
        logsDir = loggingRule.logsRepo.logsDir,
        snapshotBaselineRef = runOnHostParams.snapshotBaselineRef,
        snapshotBaselineThresholdPercent = runOnHostParams.snapshotBaselineThresholdPercent,
      )
      val goldenPassed = goldenResult?.passed != false

      if (runYamlRequest.config.sendSessionEndLog) {
        if (goldenPassed) {
          loggingRule.captureFinalScreenshot(session, screenStateProvider)
        } else {
          loggingRule.captureFailureScreenshot(session, screenStateProvider)
        }
        loggingRule.endSession(session, isSuccess = goldenPassed)
      }

      if (!goldenPassed) {
        val failures = goldenResult!!.results.filter { it.goldenFound && !it.passed }
        val msg = failures.joinToString("; ") {
          "'${it.snapshotName}' (${"%.2f".format(it.diffPercent)}% diff, threshold ${it.thresholdPercent}%)"
        }
        throw TrailblazeException(
          "${goldenResult.referenceLabel.replaceFirstChar { it.uppercase() }} snapshot comparison failed: $msg",
        )
      }

      session.sessionId
    }
  }
  companion object {
    /** See [discoverDevices] — `desktop/self` is the one logical Compose instance on a host. */
    const val SELF_INSTANCE_ID = "self"
  }
}
