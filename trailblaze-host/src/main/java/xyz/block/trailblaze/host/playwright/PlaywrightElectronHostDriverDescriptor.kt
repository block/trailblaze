package xyz.block.trailblaze.host.playwright

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.recording.DeviceConnectionService
import xyz.block.trailblaze.host.rules.BasePlaywrightElectronTest
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.scripting.LaunchedScriptingRuntime
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.ElectronAppConfig

/**
 * Plugs the Playwright-electron web driver into the host: an already-running Electron app exposes
 * a Chrome DevTools Protocol endpoint and Playwright attaches to it.
 *
 * Separate from [PlaywrightNativeHostDriverDescriptor] even though both ride Playwright: attaching
 * to someone else's app and launching our own browser are different lifecycles with different
 * availability answers, and keeping them apart keeps "remove this driver" a one-file delete.
 *
 * [cdpBaseUrlProvider] defaults to [DeviceConnectionService.resolveElectronCdpUrl] — the same
 * resolver the connect path attaches with — so an explicit `TRAILBLAZE_ELECTRON_CDP_URL` makes
 * the device discoverable, not just `TRAILBLAZE_ELECTRON_CDP_PORT`. Otherwise the endpoint could
 * be attachable yet never listed. Injectable so tests can point the probe at a local server.
 */
class PlaywrightElectronHostDriverDescriptor(
  private val cdpBaseUrlProvider: () -> String = {
    DeviceConnectionService.resolveElectronCdpUrl(
      cdpUrlEnv = System.getenv("TRAILBLAZE_ELECTRON_CDP_URL"),
      cdpPortEnv = System.getenv("TRAILBLAZE_ELECTRON_CDP_PORT"),
    )
  },
) : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.PLAYWRIGHT_ELECTRON)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * One CDP-endpoint device when the Electron app answers `/json/version`, nothing otherwise.
   * The probe self-bounds at [PROBE_TIMEOUT_MS] wall-clock, so a dead endpoint degrades to
   * absence fast.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> {
    if (!isElectronCdpAvailable()) return emptyList()
    return listOf(
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
        instanceId = WebInstanceIds.PLAYWRIGHT_ELECTRON,
        description = "Playwright Electron (CDP)",
      ),
    )
  }

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    HostYamlRunResult(
      runPlaywrightElectronYaml(
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
  ): ScreenState? = MaestroDriverScreenStates.fromActiveDriver(deps, deviceId)

  /**
   * Playwright-electron path: connects to an Electron app via CDP and runs the trail
   * using [xyz.block.trailblaze.playwright.PlaywrightTrailblazeAgent] with web-native tools.
   *
   * Electron app configuration is resolved from:
   * 1. The resolved target's launch config
   * 2. The `TRAILBLAZE_ELECTRON_*` env vars as fallback (`TRAILBLAZE_ELECTRON_CDP_URL`,
   *    `TRAILBLAZE_ELECTRON_COMMAND`, `TRAILBLAZE_ELECTRON_ARGS`, `TRAILBLAZE_ELECTRON_CDP_PORT`,
   *    `TRAILBLAZE_ELECTRON_HEADLESS`)
   */
  private suspend fun runPlaywrightElectronYaml(
    dynamicLlmClient: DynamicLlmClient,
    runOnHostParams: RunOnHostParams,
    deviceManager: TrailblazeDeviceManager,
    logsDir: File?,
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
      if (isReusingTest) {
        "Reusing Playwright-electron session..."
      } else {
        "Initializing Playwright-electron test runner..."
      },
    )

    // Resolve ElectronAppConfig from the resolved target or environment variables
    val electronConfig = resolveElectronAppConfig(runOnHostParams.targetTestApp)

    val electronTest = existingTest ?: BasePlaywrightElectronTest(
      electronAppConfig = electronConfig,
      customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet(),
      dynamicLlmClient = dynamicLlmClient,
      trailblazeLlmModel = runYamlRequest.trailblazeLlmModel,
      config = runYamlRequest.config,
      appTarget = runOnHostParams.targetTestApp,
      trailblazeDeviceId = trailblazeDeviceId,
      // A constant, not the request's id: discovery lists the Electron app under ONE stable id,
      // and a driver-pinned Electron trail is routinely dispatched onto the `playwright-native`
      // slot — CliRunDeviceResolver prefers that slot for web trails and the driver pin is read
      // later. So the session names the device it actually drove.
      advertisedDeviceInstanceId = WebInstanceIds.PLAYWRIGHT_ELECTRON,
      maxLlmCalls = runYamlRequest.maxLlmCalls,
      // Honor the CLI `--no-capture-video` opt-out — this rule self-instruments video.
      captureVideo = runOnHostParams.captureVideo,
      logsDir = logsDir,
      noLogging = runOnHostParams.noLogging,
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

    val subprocessRuntimes = mutableListOf<LaunchedScriptingRuntime>()
    return TrailblazeHostYamlRunner.executeTrailSession(
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
      TrailblazeHostYamlRunner.launchSubprocessMcpServersIfAny(
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
        traceId = runYamlRequest.traceId,
        useRecordedSteps = runYamlRequest.useRecordedSteps,
        sendSessionStartLog = runYamlRequest.config.sendSessionStartLog,
        agentImplementation = runYamlRequest.agentImplementation,
        initialMemorySeeds = runYamlRequest.initialMemorySeeds,
        initialMemorySensitiveSeeds = runYamlRequest.initialMemorySensitiveSeeds,
        initialArgs = runYamlRequest.initialArgs,
        onStepProgress = { step, total, text ->
          onProgressMessage("Step $step/$total: $text")
        },
      )
      Console.log("✅ Playwright-electron runTrailblazeYamlSuspend completed for device: ${trailblazeDeviceId.instanceId}")
      onProgressMessage("Test execution completed successfully")

      if (runYamlRequest.config.sendSessionEndLog) {
        electronTest.loggingRule.captureFinalScreenshot(session, electronTest.browserManager::getScreenState)
        electronTest.loggingRule.endSession(session, isSuccess = true)
      }

      val customToolClasses = runOnHostParams.targetTestApp
        ?.getCustomToolsForDriver(runOnHostParams.trailblazeDriverType) ?: emptySet()
      TrailblazeHostYamlRunner.generateAndSaveRecording(
        sessionId = sessionId,
        logsDir = electronTest.loggingRule.logsRepo.logsDir,
        customToolClasses = resolveWebToolClasses(TrailblazeDriverType.PLAYWRIGHT_ELECTRON) +
          BasePlaywrightElectronTest.ELECTRON_BUILT_IN_TOOL_CLASSES + customToolClasses,
      )

      sessionId
    }
  }

  /**
   * Resolves [ElectronAppConfig] in priority order:
   *  1. The resolved target's [TrailblazeHostAppTarget.getElectronAppConfig] — the target-level
   *     home for Electron launch config. A trail carries no per-trail launch block; it selects the
   *     target + `PLAYWRIGHT_ELECTRON` driver and the launch config comes from the target.
   *  2. Environment variables (`TRAILBLAZE_ELECTRON_*`) as the final fallback.
   */
  private fun resolveElectronAppConfig(
    targetTestApp: TrailblazeHostAppTarget?,
  ): ElectronAppConfig {
    // 1. Take the resolved target's launch config.
    targetTestApp?.getElectronAppConfig()?.let { return it }

    // 2. Fall back to environment variables
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
   * Probes the CDP endpoint under a wall-clock bound, not just the socket timeouts.
   *
   * The connect/read timeouts don't cover hostname resolution — reading `responseCode` resolves
   * synchronously first — so `TRAILBLAZE_ELECTRON_CDP_URL` naming a host with a stalled DNS
   * lookup would otherwise hang this probe for as long as the OS resolver takes, and with it
   * every device-discovery refresh (the descriptor pass abandons a worker only at its 60s
   * deadline). Bounding the await on another thread keeps the same one-second ceiling the device
   * manager put on this probe before it moved here. Unlike the Compose descriptor's probe, this
   * URL is user-supplied, which is what makes resolution a real hazard.
   */
  private fun isElectronCdpAvailable(): Boolean {
    val probe = CompletableFuture.supplyAsync { probeCdpEndpoint() }
    return try {
      probe.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      // Abandoned, not cancelled: a thread stuck in a blocking resolve won't answer an interrupt,
      // and an unreachable endpoint is absence either way.
      false
    } catch (_: Exception) {
      false
    }
  }

  private fun probeCdpEndpoint(): Boolean {
    var connection: HttpURLConnection? = null
    return try {
      val baseUrl = cdpBaseUrlProvider()
      val url = URI("${baseUrl.trimEnd('/')}/json/version").toURL()
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

  companion object {
    /** The bound the device manager applied to this probe before it moved into the descriptor. */
    internal const val PROBE_TIMEOUT_MS: Long = 1_000
  }
}
