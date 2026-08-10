package xyz.block.trailblaze.host

import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.recording.DeviceStreamScreenshotSource
import xyz.block.trailblaze.host.recording.StreamFrameMonitor
import xyz.block.trailblaze.host.recording.StreamScreenshotScreenState
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.maestro.OrchestraRunner
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * Host-mode Maestro runner for executing Maestro commands on connected devices.
 * 
 * Uses stateless logger with explicit session management.
 * Session should be managed by the caller and passed via sessionProvider.
 */
class MaestroHostRunnerImpl(
  private val trailblazeDeviceId: TrailblazeDeviceId,
  val trailblazeLogger: TrailblazeLogger,
  private val sessionProvider: TrailblazeSessionProvider,
  /**
   * Providing the "App Target" can enable app specific functionality if provided
   */
  appTarget: TrailblazeHostAppTarget? = null,
  private val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  /**
   * Resolves the screenshot scaling config to apply on each capture. Defaults to a
   * lambda that re-reads [EffectiveScreenshotScalingConfig.effective] per call so a
   * `trailblaze config screenshot-*` change picks up on the next screen capture without
   * recreating the runner. Tests can pass a constant-returning lambda to pin a value.
   * The previous `val` snapshot at construction time silently ignored live settings changes.
   */
  private val screenshotScalingConfigProvider: () -> ScreenshotScalingConfig = {
    EffectiveScreenshotScalingConfig.effective
  },
) : MaestroHostRunner {
  val connectedDevice: TrailblazeConnectedDevice by lazy {
    val hostDriverType = when (trailblazeDeviceId.trailblazeDevicePlatform) {
      TrailblazeDevicePlatform.ANDROID -> error(
        "Android does not use MaestroHostRunnerImpl — use on-device drivers via RPC instead"
      )
      TrailblazeDevicePlatform.IOS -> TrailblazeDriverType.IOS_HOST
      TrailblazeDevicePlatform.WEB -> error("Web tests do not use MaestroHostRunnerImpl")
      TrailblazeDevicePlatform.DESKTOP -> error(
        "Compose desktop driver does not use MaestroHostRunnerImpl — it routes through ComposeRpcClient.",
      )
    }
    TrailblazeDeviceService.getConnectedDevice(
      trailblazeDeviceId = trailblazeDeviceId,
      driverType = hostDriverType,
      appTarget = appTarget,
    ) ?: error(
      "No connected device matching $trailblazeDeviceId found.",
    )
  }

  val loggingDriver: LoggingDriver by lazy {
    (connectedDevice as? MaestroConnectedDevice)?.getLoggingDriver(trailblazeLogger, sessionProvider)
      ?: error("MaestroHostRunner requires a Maestro-backed device; got ${connectedDevice::class.simpleName}")
  }

  /** iOS Simulator UDID for the AXe tree overlay, or null on non-iOS. Matches the udid the record
   * stream uses (see MaestroDeviceScreenStream) so replay re-enriches with the same source. */
  val iosUdid: String?
    get() = trailblazeDeviceId.instanceId
      .takeIf {
        it.isNotBlank() && trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS
      }

  companion object {
    var callCount = 0

    /**
     * Bound on waiting for the stream to produce a frame matching a tree capture. Covers the
     * quiet window + normal encode/transport latency with margin; a capture that can't match
     * within this falls back to one on-simulator screenshot, so a busted stream costs about as
     * much as the pre-stream path per capture rather than wedging it.
     */
    private const val STREAM_FRAME_TIMEOUT_MS = 2_500L
  }

  /**
   * Experimental: serve iOS agent-loop screenshots from the simulator's live baguette H.264
   * stream instead of a per-turn `simctl`/XCUITest screenshot. Read once at construction (per
   * run) — see [StreamScreenshotMode].
   */
  private val streamScreenshotMode = StreamScreenshotMode.resolveIos()

  /** Lazily started on the first capture; null until then, or when stream mode is off/unavailable. */
  @Volatile private var streamScreenshotSource: DeviceStreamScreenshotSource? = null

  /** Sticky: once baguette is found absent (or the source fails to start) we stop retrying. */
  @Volatile private var streamSourceUnavailable = false

  override val screenStateProvider: () -> ScreenState = {
    callCount++
    Console.log("screenStateProvider call count: $callCount")
    runBlocking { captureScreenState() }
  }

  /**
   * Builds the per-turn [ScreenState]. In the default (OFF) mode this is just a
   * [HostMaestroDriverScreenState] with its on-simulator screenshot. Stream mode replaces the
   * screenshot with a frame from the live baguette stream that provably matches the tree capture
   * (see [StreamFrameMonitor] / [StreamScreenshotScreenState]); AB mode keeps the on-simulator
   * screenshot authoritative but logs a comparison line.
   */
  private suspend fun captureScreenState(): ScreenState {
    // Stream screenshots are only wired for iOS (baguette is a Simulator-only transport); any
    // other platform on this runner stays on the driver's own screenshot.
    val engageStream = streamScreenshotMode != StreamScreenshotMode.OFF &&
      trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS
    if (!engageStream) return buildDriverScreenState(skipScreenshot = false)

    val source = ensureStreamSource()
      ?: return buildDriverScreenState(skipScreenshot = false) // baguette absent → on-simulator

    return when (streamScreenshotMode) {
      StreamScreenshotMode.AB_COMPARE -> {
        // On-simulator screenshot stays authoritative; also run the matcher and log enough per
        // capture to judge the stream path's viability (match rate, skew, sizes) from a run.
        val base = buildDriverScreenState(skipScreenshot = false)
        val treeCapturedAtHostMs = System.currentTimeMillis()
        when (val result = source.awaitFrameMatching(treeCapturedAtHostMs, STREAM_FRAME_TIMEOUT_MS)) {
          is StreamFrameMonitor.Result.Matched -> Console.log(
            "[stream-screenshot] AB matched: skewMs=${result.frameVsTreeSkewMs} " +
              "streamBytes=${result.jpegBytes.size} " +
              "simulatorBytes=${base.screenshotBytes?.size} treeTs=$treeCapturedAtHostMs",
          )
          is StreamFrameMonitor.Result.Unavailable -> Console.log(
            "[stream-screenshot] AB unmatched: ${result.reason} treeTs=$treeCapturedAtHostMs",
          )
        }
        base
      }
      StreamScreenshotMode.STREAM -> {
        // Skip the (slow) on-simulator screenshot: capture the tree only, stamp when it's final.
        val base = buildDriverScreenState(skipScreenshot = true)
        val treeCapturedAtHostMs = System.currentTimeMillis()
        when (val result = source.awaitFrameMatching(treeCapturedAtHostMs, STREAM_FRAME_TIMEOUT_MS)) {
          is StreamFrameMonitor.Result.Matched -> {
            Console.log(
              "[stream-screenshot] matched: skewMs=${result.frameVsTreeSkewMs} " +
                "bytes=${result.jpegBytes.size} treeTs=$treeCapturedAtHostMs",
            )
            StreamScreenshotScreenState(delegate = base, streamJpegBytes = result.jpegBytes)
          }
          is StreamFrameMonitor.Result.Unavailable -> {
            Console.log(
              "[stream-screenshot] unmatched (${result.reason}) — falling back to on-simulator screenshot",
            )
            // Re-capture WITH the on-simulator screenshot (the tree-only base has none).
            buildDriverScreenState(skipScreenshot = false)
          }
        }
      }
      StreamScreenshotMode.OFF -> buildDriverScreenState(skipScreenshot = false) // unreachable — guarded above
    }
  }

  private fun buildDriverScreenState(skipScreenshot: Boolean): ScreenState =
    HostMaestroDriverScreenState(
      maestroDriver = loggingDriver,
      // Re-resolve per call so live settings changes (e.g. `trailblaze config
      // screenshot-format png` mid-session) take effect on the very next capture.
      screenshotScalingConfig = screenshotScalingConfigProvider(),
      deviceClassifiers = deviceClassifiers,
      skipScreenshot = skipScreenshot,
    )

  /**
   * Lazily starts the baguette stream source on the first stream/AB capture. Returns null (and
   * latches [streamSourceUnavailable]) when baguette isn't installed or the source fails to
   * start — the caller then stays on the on-simulator screenshot for the rest of the session.
   */
  private fun ensureStreamSource(): DeviceStreamScreenshotSource? {
    if (streamSourceUnavailable) return null
    streamScreenshotSource?.let { return it }
    val created = DeviceStreamScreenshotSource.forIos(trailblazeDeviceId)
    return try {
      if (created.start()) {
        streamScreenshotSource = created
        created
      } else {
        runCatching { created.close() }
        streamSourceUnavailable = true
        null
      }
    } catch (e: Exception) {
      Console.log("[stream-screenshot] failed to start iOS stream source: ${e.message}")
      runCatching { created.close() }
      streamSourceUnavailable = true
      null
    }
  }

  override fun closeStreamScreenshotSource() {
    streamScreenshotSource?.let {
      streamScreenshotSource = null
      runCatching { it.close() }
    }
  }

  override fun runMaestroYaml(yaml: String): TrailblazeToolResult {
    val flowFile = File.createTempFile("flow", ".yaml").also {
      it.writeText(
        yaml,
      )
    }
    return runFlowFile(flowFile)
  }

  override fun runFlowFile(flowFile: File): TrailblazeToolResult {
    val env: Map<String, String> = emptyMap()
    val maestroCommands: List<MaestroCommand> = YamlCommandReader.readCommands(flowFile.toPath())
      .withEnv(env.withInjectedShellEnvVars().withDefaultEnvVars(flowFile))
    return runMaestroCommandsInternal(
      commands = maestroCommands,
      traceId = null,
    )
  }

  override fun runMaestroCommand(vararg commands: Command): TrailblazeToolResult = runMaestroCommands(
    commands = commands.toList(),
    traceId = null,
  )

  override fun runMaestroCommands(
    commands: List<Command>,
    traceId: TraceId?,
  ): TrailblazeToolResult = runMaestroCommandsInternal(
    commands = commands.map { MaestroCommand(it) },
    traceId = traceId,
  )

  private fun runMaestroCommandsInternal(
    commands: List<MaestroCommand>,
    traceId: TraceId?,
  ): TrailblazeToolResult {
    // Use OrchestraRunner to execute commands with standardized callbacks
    return runBlocking {
      OrchestraRunner.runCommands(
        maestro = Maestro(loggingDriver),
        commands = commands,
        traceId = traceId,
        trailblazeLogger = trailblazeLogger,
        sessionProvider = sessionProvider,
        screenStateProvider = screenStateProvider,
        orchestraFactory = { callbacks ->
          // Create Orchestra executor with standardized callbacks
          object : OrchestraRunner.OrchestraExecutor {
            override suspend fun execute(commands: List<MaestroCommand>): Boolean = Orchestra(
              maestro = Maestro(loggingDriver),
              onCommandComplete = callbacks.onCommandComplete,
              onCommandFailed = { index, command, throwable ->
                callbacks.onCommandFailed(index, command, throwable)
                Orchestra.ErrorResolution.FAIL
              },
            ).runFlow(commands)
          }
        },
      )
    }
  }
}
