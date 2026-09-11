package xyz.block.trailblaze.host.ios

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.rules.BaseHostTrailblazeTest
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.host.yaml.RunOnHostParams

/**
 * The run body for the two iOS simulator drivers, [IosHostDriverDescriptor] and
 * [IosAxeHostDriverDescriptor].
 *
 * Shared by both rather than living on either: they differ in how they reach a simulator, not in
 * how a trail is run against one. It sits here rather than in `TrailblazeHostYamlRunner` because
 * these are its only callers — every other driver that reaches the host run entry point owns its
 * own body.
 *
 * Deliberately not named for Maestro even though [IosHostDriverDescriptor] is Maestro-backed:
 * `hostNativeSimulatorDriver` drivers such as IOS_AXE have no Maestro driver at all, and merely
 * dereferencing `hostRunner` for one throws. The two guards below are what that costs.
 */
internal suspend fun runIosSimulatorYaml(
  dynamicLlmClient: DynamicLlmClient,
  runOnHostParams: RunOnHostParams,
  deviceManager: TrailblazeDeviceManager,
  logsDir: File?,
): HostYamlRunResult {

  val trailblazeDeviceId = runOnHostParams.runYamlRequest.trailblazeDeviceId
  val onProgressMessage = runOnHostParams.onProgressMessage


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
    deviceClassifierOverride = runYamlRequest.deviceClassifierOverride.map(::TrailblazeDeviceClassifier),
    logsDir = logsDir,
    noLogging = runOnHostParams.noLogging,
  ) {
    // Honor the agent implementation chosen for THIS run (CLI --agent / settings / request),
    // overriding BaseHostTrailblazeTest's JUnit-eval system-property default so
    // KOOG_STRATEGY_GRAPH takes effect on this local-simulator path exactly like the web / Revyl /
    // on-device paths. Default (TRAILBLAZE_RUNNER) is unchanged.
    override val agentImplementation: AgentImplementation = runYamlRequest.agentImplementation

    override fun ensureTargetAppIsStopped() {
      // Convert the YAML-ordered List to a Set for ensureAppsAreForceStopped, which takes
      // membership-style Set<String>.
      val possibleAppIds = runOnHostParams.targetTestApp
        ?.getPossibleAppIdsForPlatform(runOnHostParams.trailblazeDevicePlatform)
        ?.toSet()
        ?: emptySet()
      MobileDeviceUtils.ensureAppsAreForceStopped(
        possibleAppIds = possibleAppIds,
        trailblazeDeviceId = trailblazeDeviceId
      )
    }
  }

  // Store the test instance for forceful shutdown on cancellation. Host-native iOS drivers
  // have no Maestro driver — and dereferencing hostTbRunner.hostRunner would construct one —
  // so this is skipped for them.
  if (!runOnHostParams.trailblazeDriverType.hostNativeSimulatorDriver) {
    deviceManager.setActiveDriverForDevice(trailblazeDeviceId, hostTbRunner.hostRunner.loggingDriver)
  }

  onProgressMessage("Connecting to $trailblazeDeviceId device...")

  val keepDriverAlive = runOnHostParams.referrer == TrailblazeReferrer.MCP

  // Per-session subprocess MCP runtimes for inline scripted tools synthesized from the
  // target's `tools:` YAML. The launcher spawns each entry, runs the MCP handshake,
  // registers filtered tools into hostTbRunner.toolRepo, and hands back a teardown handle.
  // Launch must happen inside the executeTrailSession lambda — we need the SessionId for
  // the env-var contract and for the session-log directory; both are available there.
  //
  // Modeled as a mutable list of resources (empty when the target declares no `tools:`
  // with subprocess routing, populated once launch succeeds) so the cleanup lambda can
  // reference the collection directly without a forward-declared nullable var.
  val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()

  // Captured from inside the session lambda so it survives back out to the HostYamlRunResult
  // this method returns — executeTrailSession itself only hands back the SessionId.
  var lastToolResult: TrailblazeToolResult.Success? = null

  val sessionId = TrailblazeHostYamlRunner.executeTrailSession(
    loggingRule = hostTbRunner.hostLoggingRule,
    overrideSessionId = runYamlRequest.config.overrideSessionId,
    testName = runYamlRequest.testName,
    deviceLabel = "maestro:${trailblazeDeviceId.instanceId}",
    sendSessionEndLog = runYamlRequest.config.sendSessionEndLog,
    onProgressMessage = onProgressMessage,
    screenshotProvider = hostTbRunner.screenStateProvider,
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
        // Detach the iOS baguette stream (no-op unless TRAILBLAZE_IOS_STREAM_SCREENSHOT
        // engaged) so the WebSocket + ffmpeg decoder don't outlive the session. The
        // if-started guard keeps cleanup from constructing the lazy hostRunner — which
        // deliberately throws on IOS_AXE and would otherwise fail every AXe run's cleanup.
        hostTbRunner.closeStreamScreenshotSourceIfStarted()
        // Let go of the device connection the classifiers fetched for themselves. Unconditional
        // because it is this run's own hold on the shared iOS driver and nothing else can reach
        // it — not even the MCP branch below, which deliberately keeps the driver alive by
        // holding the OTHER one, registered as the device's active driver.
        hostTbRunner.releaseConnectedDeviceIfOpened()
      }
      if (keepDriverAlive) {
        Console.log("🔗 MCP referrer detected - keeping driver alive for device: ${trailblazeDeviceId.instanceId}")
        deviceManager.clearCoroutineScopeForDevice(trailblazeDeviceId)
      } else {
        deviceManager.cancelSessionForDevice(trailblazeDeviceId)
      }
    },
  ) { session ->
    // Start session-scoped capture (e.g. the iOS Simulator log stream) the moment the
    // session exists, BEFORE any trail steps run. This host runner executes the
    // whole trail synchronously, so the daemon's post-run capture activation would otherwise
    // start capture only after the trail finished and record nothing. Guarded so a
    // capture-start failure never tears down the trail.
    runCatching { runOnHostParams.onSessionStarted(session.sessionId) }
      .onFailure {
        Console.log("[runIosSimulatorYaml] onSessionStarted callback threw — continuing: ${it.message}")
      }
    // Spawn target-declared subprocess MCP servers + register their tools into the
    // session's repo. Runs before trail execution so the LLM's first tools/list reflects
    // the full registry. Fail-fast: if any spawn fails, executeTrailSession's catch path
    // reports it and the cleanup lambda tears down anything partial.
    TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny(
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
    val yamlRun = hostTbRunner.runTrailblazeYamlSuspend(
      yaml = runYamlRequest.yaml,
      forceStopApp = runOnHostParams.forceStopTargetApp,
      trailFilePath = runYamlRequest.trailFilePath,
      trailblazeDeviceId = trailblazeDeviceId,
      traceId = runYamlRequest.traceId,
      sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
      initialMemorySeeds = runYamlRequest.initialMemorySeeds,
      initialMemorySensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
      initialArgs = runYamlRequest.initialArgs,
    )
    // Surface the last successful tool's payload back out through HostYamlRunResult.
    lastToolResult = yamlRun.lastToolResult
    val sessionId = yamlRun.sessionId
    Console.log("✅ runTrailblazeYamlSuspend completed successfully for device: ${trailblazeDeviceId.instanceId}")
    onProgressMessage("Test execution completed successfully")

    if (runYamlRequest.config.sendSessionEndLog) {
      hostTbRunner.loggingRule.captureFinalScreenshot(session, hostTbRunner.screenStateProvider)
      hostTbRunner.loggingRule.endSession(session, isSuccess = true)
    }

    sessionId?.let {
      TrailblazeHostYamlRunner.generateAndSaveRecording(
        sessionId = it,
        logsDir = hostTbRunner.hostLoggingRule.logsRepo.logsDir,
        customToolClasses = runOnHostParams.targetTestApp
          ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      )
    }

    sessionId
  }
  return HostYamlRunResult(sessionId, lastToolResult)
}
