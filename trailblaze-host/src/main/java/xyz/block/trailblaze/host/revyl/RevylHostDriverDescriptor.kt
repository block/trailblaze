package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.devices.HostProbe
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylDeviceTarget
import xyz.block.trailblaze.revyl.RevylScreenState
import xyz.block.trailblaze.util.Console
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.exception.TrailblazeSessionCancelledException
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.agent.KoogTestAgentRunner
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.revyl.RevylSession
import xyz.block.trailblaze.revyl.tools.RevylToolSetIds
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Plugs Revyl's cloud device farm into the host.
 *
 * Covers both Revyl enum entries in one descriptor: they are one CLI, one API key, and one device
 * catalog, so splitting them would run the catalog probe twice per discovery pass.
 *
 * Devices are [DeviceListingVisibility.ADDRESSABLE_NOT_LISTED] — a trail can name one with
 * `--device revyl-...`, but they stay out of browsable listings, which are for what's attached to
 * this machine.
 */
class RevylHostDriverDescriptor(
  private val cliClient: RevylCliClient = RevylCliClient(),
) : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(
    TrailblazeDriverType.REVYL_ANDROID,
    TrailblazeDriverType.REVYL_IOS,
  )

  override val listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED

  /**
   * Per-device clients for the sessions currently running, so an MCP conversation's follow-up
   * calls reach the cloud device its earlier calls provisioned instead of starting a new one.
   *
   * Lives here rather than on the shared device manager because it is Revyl's alone: nothing
   * outside this descriptor can do anything with a [RevylCliClient].
   */
  private val activeClientsByDevice: MutableMap<TrailblazeDeviceId, RevylCliClient> =
    ConcurrentHashMap()

  /**
   * The two platform defaults plus whatever models the account's catalog offers, or nothing at all
   * when the `revyl` CLI isn't installed — that absence is what "Revyl is unavailable here" means.
   *
   * A catalog probe that times out or fails degrades to the defaults rather than to nothing: the
   * platform defaults don't depend on the catalog, so losing it shouldn't take Revyl offline.
   *
   * Ignores [inventory]: Revyl's devices live behind its own CLI, not a host transport.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> {
    if (!cliClient.isCliAvailable) return emptyList()

    val targets = HostProbe.withTimeout(10, "revyl-catalog", "device targets") {
      cliClient.getDeviceTargets()
    } ?: emptyList()

    return DEFAULT_DEVICES + catalogDevices(targets)
  }

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    HostYamlRunResult(
      runRevylYaml(
        dynamicLlmClient = deps.dynamicLlmClient,
        runOnHostParams = params,
        deviceManager = deps.deviceManager,
        logsDir = deps.logsDir,
      ),
    )

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? {
    val activeClient = activeClientsByDevice[deviceId]
    if (activeClient == null) {
      Console.log("No active Revyl session for ${deviceId.instanceId}, screen state unavailable")
      return null
    }
    val platform = if (driverType == TrailblazeDriverType.REVYL_ANDROID) "android" else "ios"
    return RevylScreenState(activeClient, platform)
  }

  /**
   * Revyl cloud device path: provisions a device via [RevylCliClient] and runs
   * the trail using [RevylTrailblazeAgent] with standard mobile tools.
   *
   * The CLI handles device provisioning, app install, and AI-powered target
   * grounding. Screenshots come from [RevylScreenState].
   *
   */
  private suspend fun runRevylYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
    logsDir: File?,
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

    val sessionCliClient: RevylCliClient
    val session: RevylSession
    try {
      sessionCliClient = RevylCliClient()
      session = if (instanceId.startsWith("revyl-model:")) {
        val payload = instanceId.removePrefix("revyl-model:")
        val parts = payload.split("::", limit = 2)
        val modelName = parts[0]
        val osVer = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (osVer != null) {
          sessionCliClient.startSession(platform = platform, deviceModel = modelName, osVersion = osVer)
        } else {
          Console.log("RevylYaml: device '$modelName' missing OS version — using platform default")
          sessionCliClient.startSession(platform = platform)
        }
      } else {
        sessionCliClient.startSession(platform = platform)
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
      activeClientsByDevice[trailblazeDeviceId] = sessionCliClient
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
        RevylScreenState(sessionCliClient, platform, session.screenWidth, session.screenHeight)
      }

      val loggingRule = HostTrailblazeLoggingRule(
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        logsDir = logsDir,
        noLogging = runOnHostParams.noLogging,
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
        // Bind the repo to the Revyl driver so the KOOG verify-step surface scopes to
        // `revyl_verification` (see TrailblazeToolRepo.verifyStepToolDescriptors / VERIFY_SCOPE_DRIVERS).
        // Without this the repo's driverType is null and verify scoping no-ops. Also keeps the
        // generic `requestDetailedViewHierarchy` inspection tool off the Revyl KOOG surface (Revyl
        // is excluded from KOOG_INSPECTION_DRIVERS — its agent can't run that generic tool).
        driverType = runOnHostParams.trailblazeDriverType,
      )

      val agent = RevylTrailblazeAgent(
        cliClient = sessionCliClient,
        platform = platform,
        trailblazeLogger = loggingRule.logger,
        trailblazeDeviceInfoProvider = { trailblazeDeviceInfo },
        sessionProvider = {
          loggingRule.session ?: error("Session not available - ensure test is running")
        },
        trailblazeToolRepo = toolRepo,
      )

      val elementComparator = TrailblazeElementComparator(
        screenStateProvider = screenStateProvider,
        llmClient = dynamicLlmClient.createLlmClient(),
        trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
        toolRepo = toolRepo,
      )

      // Brain selection (legacy or KOOG). Recordings replay uniformly via the runner-util below
      // regardless of agent — only unrecorded steps reach the selected brain.
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
            systemPromptTemplate = TrailblazeRunner.composeSystemPrompt(),
          )
        } else {
          TrailblazeRunner(
            screenStateProvider = screenStateProvider,
            agent = agent,
            llmClient = dynamicLlmClient.createLlmClient(),
            trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
            trailblazeToolRepo = toolRepo,
            trailblazeLogger = loggingRule.logger,
            sessionProvider = {
              loggingRule.session ?: error("Session not available - ensure test is running")
            },
            maxSteps = runYamlRequest.maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
          )
        }

      val trailblazeYaml = createTrailblazeYaml(
        customTrailblazeToolClasses = revylToolSet.toolClasses,
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
        // batching pattern elsewhere. This agent's buildExecutionContext doesn't cache per-call
        // device state today either (Revyl's device state lives in the cloud device, dispatched
        // fresh per tool via cliClient) — the benefit here is reduced frame/ThreadLocal churn,
        // not a clipboard-style state-survival fix.
        sharedToolBatch = { block -> agent.runInSharedToolBatch(block) },
      )

      val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
      return TrailblazeHostYamlRunner.executeTrailSession(
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
        TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny(
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

        // See the Compose runner above: envelope-tolerant decode keeps single-tool MCP dispatch off
        // the legacy list-shape parser while trail documents decode unchanged.
        val trailItems: List<TrailYamlItem> = trailblazeYaml.decodeTrailOrToolEnvelope(
          runYamlRequest.yaml,
          deviceClassifiers = trailblazeDeviceInfo.classifiers,
        )
        val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

        // Honor `config.skip:` before SessionStarted is logged — matches the CLI's pre-flight
        // `planTrailExecution` planner. See parallel comment at the ComposeRpc site.
        trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
          Console.log(
            "[Trailblaze] Skipping trail" +
              (runYamlRequest.trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
          )
          return@executeTrailSession session.sessionId
        }

        // Seed the agent's memory before any tool runs — see parallel comment at the
        // ComposeRpc site for the composition and why this covers both `{{var}}`
        // interpolation and scripted tools' `ctx.memory`.
        val resolvedInitialMemory = agent.memory.seedFrom(
          yamlDefaults = trailConfig?.memory,
          cliSeeds = runYamlRequest.initialMemorySeeds,
          cliSensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
        )
        agent.memory.seedArgs(TrailArgBinder.decodeProvided(runYamlRequest.initialArgs))
        val sensitiveMemoryKeys: Set<String> = agent.memory.sensitiveKeys.toSet()

        if (runYamlRequest.config.sendSessionStartLog) {
          // See ComposeRpc site — derive a readable Suite::test identity from the path.
          val derivedTestIdentity = runYamlRequest.trailFilePath?.let {
            TrailRecordings.deriveTestIdentityFromTrailPath(it, fallbackClassName = "Revyl")
          }
          loggingRule.logger.log(
            session,
            TrailblazeLog.TrailblazeSessionStatusChangeLog(
              sessionStatus = SessionStatus.Started(
                trailConfig = trailConfig,
                trailFilePath = runYamlRequest.trailFilePath,
                testClassName = derivedTestIdentity?.className ?: "Revyl",
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
            is TrailYamlItem.PromptsTrailItem ->
              // Agent-agnostic: replays recorded steps deterministically and delegates only
              // unrecorded steps to the selected runner (legacy / KOOG). Default unchanged.
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

        Console.log("✅ Revyl execution completed for device: ${trailblazeDeviceId.instanceId}")
        onProgressMessage("Test execution completed successfully")

        if (runYamlRequest.config.sendSessionEndLog) {
          loggingRule.captureFinalScreenshot(session, screenStateProvider)
          loggingRule.endSession(session, isSuccess = true)
        }

        TrailblazeHostYamlRunner.generateAndSaveRecording(
          sessionId = session.sessionId,
          logsDir = loggingRule.logsRepo.logsDir,
          customToolClasses = revylToolSet.toolClasses,
        )

        session.sessionId
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: TrailblazeSessionCancelledException) {
      // executeTrailSession already logged the cancel and ended the session.
      // Order matters: TSCE extends Exception (not CancellationException), so it
      // must be caught before the generic branch below — same constraint as
      // DesktopYamlRunner.runYaml's catch order.
      throw e
    } catch (e: Exception) {
      Console.log("Revyl setup failed for device: ${trailblazeDeviceId.instanceId} - ${e::class.simpleName}: ${e.message}")
      onProgressMessage("Error: ${e.message}")
      // Re-throw so DesktopYamlRunner.runYaml's outer catch sets executionResult = Failed.
      // Returning null was the silent-failure pattern previously fixed for executeTrailSession.
      throw e
    } finally {
      if (runYamlRequest.config.sendSessionEndLog) {
        activeClientsByDevice.remove(trailblazeDeviceId)
        try { sessionCliClient.stopSession() } catch (_: Exception) { }
      }
    }
  }

  companion object {
    /**
     * Offered whenever the CLI is installed — Revyl provisions one on demand, so there is nothing
     * to detect and no reason to make the user pick a model to get started.
     */
    val DEFAULT_DEVICES = listOf(
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.REVYL_ANDROID,
        instanceId = "revyl-android-phone",
        description = "Revyl Android (Default)",
      ),
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.REVYL_IOS,
        instanceId = "revyl-ios-iphone",
        description = "Revyl iOS (Default)",
      ),
    )

    /**
     * The account's device catalog as addressable devices.
     *
     * The instance id encodes model and OS version because that pair is what
     * `RevylCliClient.startSession` needs to provision the right cloud device, and a device
     * summary carries no other place to put it.
     */
    internal fun catalogDevices(
      targets: List<RevylDeviceTarget>,
    ): List<TrailblazeConnectedDeviceSummary> = targets.map { target ->
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = if (target.platform == TrailblazeDevicePlatform.ANDROID) {
          TrailblazeDriverType.REVYL_ANDROID
        } else {
          TrailblazeDriverType.REVYL_IOS
        },
        instanceId = "revyl-model:${target.model}::${target.osVersion}",
        description = "Revyl ${target.model} (${target.osVersion})",
      )
    }
  }
}
