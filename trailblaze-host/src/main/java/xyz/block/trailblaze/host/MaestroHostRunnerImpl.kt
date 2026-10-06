package xyz.block.trailblaze.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import xyz.block.trailblaze.maestro.LoggingDriver
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.recording.AxeTreeOverlay
import xyz.block.trailblaze.host.recording.DeviceStreamScreenshotSource
import xyz.block.trailblaze.host.recording.StreamFrameMonitor
import xyz.block.trailblaze.host.recording.StreamScreenshotScreenState
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.host.screenstate.SecondaryTreeCarrier
import xyz.block.trailblaze.host.screenstate.SecondaryTreeResult
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.maestro.OrchestraRunner
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.block.trailblaze.util.Console

/**
 * Host-mode Maestro runner for executing Maestro commands on connected devices.
 * 
 * Uses stateless logger with explicit session management.
 * Session should be managed by the caller and passed via sessionProvider.
 *
 * When dual-tree capture is on ([HostMigrationCapture]) and the device is an iOS simulator,
 * every screen capture also carries the raw `axe describe-ui` accessibility tree as its
 * secondary tree, so an iosMaestro→iosAxe selector migration can resolve against both.
 * See [captureScreenState].
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
  /** Dual-tree capture toggle, read once per runner. See [HostMigrationCapture]. */
  private val captureSecondaryTree: Boolean = HostMigrationCapture.enabled(),
  /** Reads the secondary (Axe) tree for a simulator udid, reporting why when it can't. Seam for
   * tests; never called when [captureSecondaryTree] is false. */
  private val secondaryTreeCapture: (udid: String) -> SecondaryTreeResult = {
    AxeTreeOverlay.captureAxeTreeResult(it)
  },
  /** Builds the driver's own screen state, running the secondary capture it is handed inside
   * that build. Null uses the real [HostMaestroDriverScreenState], which touches the device;
   * tests substitute a fake so no lazy device lookup is forced. */
  private val driverScreenStateFactory: (
    (skipScreenshot: Boolean, secondaryTreeCapture: (() -> SecondaryTreeResult)?) -> ScreenState
  )? = null,
  /** Sink for the one-per-run dual-tree failure warning. Seam for tests, which cannot read
   * [Console] reliably — it caches `System.out` at class init. */
  private val migrationCaptureLog: (String) -> Unit = { Console.error(it) },
) : MaestroHostRunner {

  init {
    if (captureSecondaryTree) {
      iosUdid?.let { Console.log("[migration-capture] dual-tree capture ON for $it") }
    }
  }
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
    (connectedDevice as? MaestroConnectedDevice)?.let { buildLoggingDriver(it) }
      ?: error("MaestroHostRunner requires a Maestro-backed device; got ${connectedDevice::class.simpleName}")
  }

  /**
   * Builds the driver that logs every Maestro command, handing it the dual-tree wrap so tap /
   * swipe / input logs carry the same side channel the agent-loop captures do. Internal rather
   * than inlined into the lazy above so a test can exercise this wiring against a fake device —
   * resolving the real [connectedDevice] needs a booted simulator.
   */
  internal fun buildLoggingDriver(device: MaestroConnectedDevice): LoggingDriver =
    device.getLoggingDriver(
      trailblazeLogger = trailblazeLogger,
      sessionProvider = sessionProvider,
      screenStateDecorator = { withSecondaryTree(it) },
      secondaryTreeCapture = secondaryTreeCaptureOrNull(),
    )

  /** iOS Simulator UDID for the AXe tree overlay, or null on non-iOS. Matches the udid the record
   * stream uses (see MaestroDeviceScreenStream) so replay re-enriches with the same source. */
  val iosUdid: String?
    get() = HostMigrationCapture.iosProducerUdid(trailblazeDeviceId)

  companion object {
    var callCount = 0

    /**
     * Bound on waiting for the stream to produce a frame matching a tree capture. Covers the
     * quiet window + normal encode/transport latency with margin; a capture that can't match
     * within this falls back to one on-simulator screenshot, so a busted stream costs about as
     * much as the pre-stream path per capture rather than wedging it.
     */
    private const val STREAM_FRAME_TIMEOUT_MS = 2_500L

    /**
     * How many secondary-tree captures may fail back to back before this run stops asking.
     *
     * Three rather than one so a screen caught mid-animation does not cost the whole session, and
     * three rather than unbounded so a genuinely broken `axe` costs a few `describe-ui` timeouts
     * instead of one on every capture for the length of the run.
     */
    internal const val MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES = 3
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
   * Builds the per-turn [ScreenState], then — only when dual-tree capture is on and this is an
   * iOS simulator — wraps it in a [MigrationScreenState] carrying the raw, unmerged
   * `axe describe-ui` tree. That tree is read INSIDE the build, right after the XCTest
   * hierarchy and before the screenshot, so both trees describe the same moment.
   * A null Axe tree still wraps: null means "snapshot not usable for migration", which is what a
   * missing `axe` CLI should say rather than silently looking like a screen with no elements.
   * With the toggle off, the Axe capture never runs and the driver state is returned untouched.
   */
  private suspend fun captureScreenState(): ScreenState = withSecondaryTree(captureDriverScreenState())

  /**
   * The Axe read, or null when it must not run (toggle off, or not an iOS simulator). Handed to
   * the screen state builder rather than called here: the screenshot that follows the primary
   * tree read costs 200-500 ms, and in stream mode a frame wait follows that, so an Axe read
   * taken after the build would describe a later screen than the tree it is paired with.
   */
  private fun secondaryTreeCaptureOrNull(): (() -> SecondaryTreeResult)? {
    if (!captureSecondaryTree) return null
    val udid = iosUdid ?: return null
    return {
      if (secondaryTreeUnavailable.get()) {
        SecondaryTreeResult.failed("axe capture disabled for the rest of this run after an earlier failure")
      } else {
        // The throw is caught here rather than left to the screen state's own guard so that a
        // capture that dies (a subprocess that can't start, a parse blowup) counts toward the
        // give-up streak and gets reported, exactly like one that returns a failure.
        val result = try {
          secondaryTreeCapture(udid)
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          SecondaryTreeResult.failed(t.message ?: t::class.simpleName ?: "axe capture threw")
        }
        result.also { recordSecondaryTreeOutcome(it) }
      }
    }
  }

  /**
   * Decides whether this run keeps asking `axe` for a tree, and reports the first failure.
   *
   * Stopping matters because a host with no `axe` would otherwise pay a subprocess on every
   * capture. Stopping too eagerly matters more: a `describe-ui` that timed out while a screen was
   * animating is the common failure, the next settled screen reads fine, and a run latched off at
   * the first one yields a session `migrate-trail` has to skip entirely. So only a cause that
   * cannot heal — no binary, or one below the minimum version — stops the run immediately.
   * Transient failures stop it only after [MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES] in a row,
   * which bounds a genuinely broken `axe` at a few timeouts while leaving single bad frames
   * recoverable. Any success resets the streak.
   */
  private fun recordSecondaryTreeOutcome(result: SecondaryTreeResult) {
    if (result.tree != null) {
      consecutiveSecondaryTreeFailures.set(0)
      return
    }
    val streak = consecutiveSecondaryTreeFailures.incrementAndGet()
    val reason = result.failureReason ?: "unknown cause"
    // Two separately latched lines, not one: a run whose first failure is transient is promised a
    // retry, and if the retries then run out, that promise has to be withdrawn out loud. Each is
    // latched because the capture runs on every agent-loop capture AND every Maestro command log,
    // so per-capture reporting buried the line in its own repetition.
    if (result.permanent || streak >= MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES) {
      secondaryTreeUnavailable.set(true)
      if (warnedSecondaryTreeOff.compareAndSet(false, true)) {
        migrationCaptureLog(
          "[migration-capture] no secondary tree for $iosUdid: $reason. " +
            "Dual-tree capture is off for the rest of this run, so migrate-trail will skip this session.",
        )
      }
      return
    }
    if (warnedSecondaryTreeRetrying.compareAndSet(false, true)) {
      migrationCaptureLog(
        "[migration-capture] no secondary tree for $iosUdid: $reason. " +
          "Retrying on the next capture; only this snapshot is unusable for migration.",
      )
    }
  }

  /**
   * Wraps a screen state that already ran its own secondary capture, shared by
   * [captureScreenState] and [buildLoggingDriver] so both the agent-loop captures and the
   * Maestro command logs carry the same side channel. This never shells `axe` itself — it only
   * lifts what the build captured into a [MigrationScreenState].
   */
  private fun withSecondaryTree(driverScreenState: ScreenState): ScreenState {
    val carrier = driverScreenState as? SecondaryTreeCarrier
    if (carrier == null || !carrier.secondaryTreeCaptureRan) return driverScreenState
    return MigrationScreenState.wrap(driverScreenState, carrier.driverMigrationTreeNode)
  }

  /** Latches the "this snapshot only" warning in [recordSecondaryTreeOutcome]. */
  private val warnedSecondaryTreeRetrying = AtomicBoolean(false)

  /** Latches the "off for the rest of the run" warning in [recordSecondaryTreeOutcome]. */
  private val warnedSecondaryTreeOff = AtomicBoolean(false)

  /** Latches off the Axe read — see [recordSecondaryTreeOutcome] for when that happens. */
  private val secondaryTreeUnavailable = AtomicBoolean(false)

  /** Resets on every success — see [recordSecondaryTreeOutcome]. */
  private val consecutiveSecondaryTreeFailures = AtomicInteger(0)

  /**
   * In the default (OFF) mode this is just a [HostMaestroDriverScreenState] with its
   * on-simulator screenshot. Stream mode replaces the screenshot with a frame from the live
   * baguette stream that provably matches the tree capture (see [StreamFrameMonitor] /
   * [StreamScreenshotScreenState]); AB mode keeps the on-simulator screenshot authoritative but
   * logs a comparison line.
   */
  private suspend fun captureDriverScreenState(): ScreenState {
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
        val treeCapturedAtHostMs = base.treeCapturedAtHostMsOrNow()
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
        val treeCapturedAtHostMs = base.treeCapturedAtHostMsOrNow()
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
            // Re-capture WITH the on-simulator screenshot (the tree-only base has none). BOTH
            // trees are read again, not just the primary one: the usual reason a frame fails to
            // match is that the screen was moving, so pairing the second XCTest read with the
            // first Axe read would hand `migrate-trail` two trees from different screens — the
            // one thing dual capture exists to prevent. A second `axe` shell on this branch is
            // the cheaper mistake.
            buildDriverScreenState(skipScreenshot = false)
          }
        }
      }
      StreamScreenshotMode.OFF -> buildDriverScreenState(skipScreenshot = false) // unreachable — guarded above
    }
  }

  /**
   * When the tree read was stamped, or now for a state that doesn't carry a stamp (the injected
   * test factory). Frame matching compares a stream frame's timestamp against this, so taking
   * "now" after the build would charge the match for the screenshot and the Axe read.
   */
  private fun ScreenState.treeCapturedAtHostMsOrNow(): Long =
    (this as? SecondaryTreeCarrier)?.treeCapturedAtHostMs ?: System.currentTimeMillis()

  /**
   * Every build runs its OWN secondary capture, and there is deliberately no parameter to hand it
   * someone else's. A caller that rebuilds the screen state — the stream path does, when no frame
   * matches — is rebuilding because the screen may have moved, so replaying an earlier Axe read
   * against the new primary tree would produce exactly the mismatched pair dual capture exists to
   * prevent. Pairing is therefore structural here, not a rule a future caller has to remember.
   */
  private fun buildDriverScreenState(skipScreenshot: Boolean): ScreenState {
    val secondaryTreeCapture = secondaryTreeCaptureOrNull()
    return driverScreenStateFactory?.invoke(skipScreenshot, secondaryTreeCapture)
      ?: HostMaestroDriverScreenState(
        maestroDriver = loggingDriver,
        // Re-resolve per call so live settings changes (e.g. `trailblaze config
        // screenshot-format png` mid-session) take effect on the very next capture.
        screenshotScalingConfig = screenshotScalingConfigProvider(),
        deviceClassifiers = deviceClassifiers,
        skipScreenshot = skipScreenshot,
        trailblazeDeviceId = trailblazeDeviceId,
        secondaryTreeCapture = secondaryTreeCapture,
      )
  }

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
            ).runFlow(commands).success
          }
        },
      )
    }
  }
}
