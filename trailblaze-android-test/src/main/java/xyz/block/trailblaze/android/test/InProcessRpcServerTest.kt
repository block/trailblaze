package xyz.block.trailblaze.android.test

import android.app.Activity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.android.runner.rpc.OnDeviceRpcServer
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.android.test.hierarchy.InProcessAccessibilityTree
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.llm.RunYamlCallbackResult
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceCapturedScreenState
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceScreenStateNotReadyException
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * The standalone RPC server for the in-process ANDROID_TEST driver: hosts [OnDeviceRpcServer] —
 * the same server, speaking the same protocol, that the CLI already drives on the accessibility
 * runner — inside the app under test's instrumented process, so `trailblaze run --device` can drive
 * the app in-process without the farm. A test APK subclasses this once, naming the target it serves;
 * the target's `android_test:` block points `fq_test_name` at that subclass.
 *
 * The accessibility counterpart is `AndroidStandaloneServerTest`. What differs here is thread
 * affinity: Espresso interactions must be issued from the instrumentation thread, so this `@Test`
 * parks in [TestThreadWorkQueue.drainUntilStopped] and every trail-running request crosses onto it
 * via [TestThreadWorkQueue.dispatch].
 *
 * Screen-state capture deliberately does NOT cross over, so the host's readiness polling still
 * answers while a trail occupies the test thread — and once ANDROID_TEST routes through
 * `getCurrentScreenStateViaRpc`, any MCP screen-state read can land mid-trail rather than
 * between runs. The two are safe to overlap because `AndroidHybridHierarchyCollector.collect` takes the
 * whole hierarchy — the classic-View walk and the Compose semantics walk alike — inside one
 * main-thread block, and reads its Compose roots off the view tree instead of through Espresso,
 * whose idle wait holds the main looper exclusively and throws on whichever of two threads asks
 * second. Synchronizing against recomposition rather than against the trail is the point:
 * serializing capture onto this thread would make readiness polling wait out whole runs.
 *
 * Replay stays deterministic: there is no LLM wiring here on purpose. An unrecorded prompt step
 * fails by name over RPC exactly as it does in CI; authoring happens through the host
 * CLI against a device that can record.
 *
 * This APK is also a farm APK, and the farm enumerates every `@Test` in it — so without the
 * server-port instrumentation arg this test passes as a no-op instead of parking the
 * instrumentation forever.
 */
abstract class InProcessRpcServerTest(
  hostAppTarget: () -> TrailblazeHostAppTarget?,
) {

  /**
   * The host's device id, adopted from the connect-time device-info exchange so RPC-driven
   * sessions log under the id the host tracks them by; farm-style default until then.
   */
  @Volatile
  private var hostDeviceId: TrailblazeDeviceId? = null

  private val loggingRule = AndroidTestLoggingRule(
    trailblazeDeviceInfoProvider = {
      val info = AndroidTestLoggingRule.defaultDeviceInfo()
      hostDeviceId?.let { info.copy(trailblazeDeviceId = it) } ?: info
    },
  )

  /** Empty: the app under test owns its Activity, launched by [AppUnderTestLauncher]. */
  @get:Rule
  val composeRule: AndroidComposeTestRule<*, *> =
    createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  /**
   * The Activity an in-flight capture already resolved under its own deadline.
   *
   * Per-THREAD, not a field: the captor runs on a Ktor worker concurrently with a trail on the
   * test thread, and only the captor's own resolution may be pinned. The trail path reads through
   * to the unbounded wait a cold start needs.
   */
  private val captureActivity = ThreadLocal<Activity?>()

  /** Innermost, matching `AndroidTestTrailblazeTest.LAST`, and chaining [loggingRule] itself. */
  @get:Rule(order = Int.MAX_VALUE)
  val trailblazeRule = AndroidTestTrailblazeRule(
    targetProvider = {
      RuleBackedAndroidTestTarget(
        // Capture re-resolves the Activity through here, and `awaitResumedActivity` polls the full
        // 120s launch timeout. Without the pin, an Activity leaving RESUMED between the captor's
        // bounded pre-check and the capture itself parks that Ktor worker for two minutes — the
        // stall the pre-check exists to prevent, moved one call later.
        activityProvider = { captureActivity.get() ?: AppUnderTestLauncher.awaitResumedActivity() },
        composeTestRule = composeRule,
      )
    },
    loggingRule = loggingRule,
    // The target the subclass serves — advertises its trailmap's scripted tools so the same trails
    // run unmodified over RPC as on the farm.
    hostAppTarget = hostAppTarget(),
  )

  /**
   * Set once [earlyRpcServer] has started the server; false on a farm run, which passes no port.
   * Only the test thread reads or writes it.
   */
  private var serving = false

  private val workQueue = TestThreadWorkQueue()

  /**
   * Starts the RPC server before every other rule, ahead of the Compose rule's initial idle wait.
   * That wait is the app's own main-thread startup (seconds, on a cold process), and the app
   * launch follows it. Listening first lets the host's connect, device-info exchange and readiness
   * polling run alongside both, instead of starting once the app is already up. Readiness still
   * means a RESUMED Activity: until the test body launches the app, the captor answers not-ready,
   * and a run request waits in [workQueue] until the test body drains it.
   */
  @get:Rule(order = Int.MIN_VALUE)
  val earlyRpcServer: TestRule = TestRule { base, _ ->
    object : Statement() {
      override fun evaluate() {
        serving = startServerIfRequested()
        base.evaluate()
      }
    }
  }

  @Test
  fun startServer() {
    if (!serving) {
      Console.log(
        "[Trailblaze] No ${TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY} instrumentation arg — " +
          "farm run, not serving RPC.",
      )
      // Close the session out here rather than letting rule teardown do it. Teardown asks for a
      // terminal screenshot, which resolves this rule's activity-backed target — and this path
      // never launched the app, so unless some earlier class happened to leave an Activity
      // resumed in this process, that lookup polls the full 120s launch timeout for one that will
      // never arrive. Dropping the rule's session reference is what makes teardown find nothing
      // to capture; there is no screen worth a frame on a no-op anyway.
      loggingRule.session?.let { loggingRule.endSession(startedSession = it, isSuccess = true) }
      loggingRule.setSession(null)
      return
    }

    // Deliberately not an `@Before`: the farm no-op above must not pay for an app launch.
    AppUnderTestLauncher.launchAppUnderTest()
    workQueue.drainUntilStopped()
  }

  /** Starts the RPC server when the host passed a port; false (no server) on a farm run. */
  private fun startServerIfRequested(): Boolean {
    val portArg = InstrumentationRegistry.getArguments()
      .getString(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)
    // Absence is the farm no-op; PRESENT-but-unusable is a caller error and must be loud.
    // Silently treating a typo'd port as "farm run" would pass this test green while the host sat
    // waiting out its connect timeout against a port nothing ever bound.
    val port = portArg?.let {
      it.toIntOrNull()?.takeIf { p -> p in 1..65535 }
        ?: error(
          "${TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY}='$it' is not a port in 1..65535.",
        )
    } ?: return false
    val server = OnDeviceRpcServer(
      loggingRule = loggingRule,
      runTrailblazeYaml = { request, session, agentMemory ->
        workQueue.dispatch { runOnTestThread(request, session, agentMemory) }
      },
      trailblazeDeviceInfoProvider = { deviceId ->
        hostDeviceId = deviceId
        loggingRule.trailblazeDeviceInfoProvider()
      },
      screenStateCaptor = { request ->
        trailblazeRule.tryCaptureWithStableDeviceConfiguration {
          // The request's four screenshot fields ARE the host's output contract — it sends
          // whatever `trailblaze config screenshot-*` resolved to. Dropping them would return a
          // full-resolution PNG on every capture, megabytes where the host asked for a scaled
          // WEBP. Built by hand for the same reason AccessibilityScreenStateCaptor does: the
          // request carries the fields flattened, not the config.
          val screenshotScalingConfig = ScreenshotScalingConfig(
            maxDimension1 = request.screenshotMaxDimension1,
            maxDimension2 = request.screenshotMaxDimension2,
            imageFormat = request.screenshotImageFormat,
            compressionQuality = request.screenshotCompressionQuality,
          )
          // Another app in front pauses this one, so no Activity of it is RESUMED; that app's
          // window is the screen, and reading it needs no Activity here.
          val foreign = trailblazeRule.foreignScreenState(request.includeScreenshot, screenshotScalingConfig, request.includeTree)
          if (foreign != null) {
            return@tryCaptureWithStableDeviceConfiguration OnDeviceCapturedScreenState(
              screenState = foreign,
              // The app's own tree is not on screen, so there is nothing to pair it with.
              driverMigrationTreeNode = null,
              capturedAtDeviceMs = System.currentTimeMillis(),
            )
          }
          // Answer "not ready" rather than block. The rule's target resolves its Activity through
          // AppUnderTestLauncher.awaitResumedActivity, which polls for the full LAUNCH timeout —
          // on a Ktor worker that would outlast the host's whole readiness budget and log a stack
          // trace per probe. A short bound covers an Activity transition; anything longer is the
          // host's to decide about.
          val activity = AppUnderTestLauncher.resumedActivityWithin(RESUMED_ACTIVITY_TIMEOUT_MS)
            ?: throw OnDeviceScreenStateNotReadyException("No Activity is RESUMED yet")
          val captured = withCaptureActivity(activity) {
            val capture = {
              captureOrNotReady(
                includeScreenshot = request.includeScreenshot,
                // The mirror polls this captor 5x/sec with includeTree=false, for pixels only.
                // See AndroidTestScreenState.includeTree for what forwarding it saves.
                includeTree = request.includeTree,
                screenshotScalingConfig = screenshotScalingConfig,
              )
            }
            if (request.includeTree) InProcessAccessibilityTree.dualCapture(capture) else capture()
          }
          OnDeviceCapturedScreenState(
            screenState = captured,
            // Set only by a dual-tree capture; see InProcessAccessibilityTree.dualCapture.
            driverMigrationTreeNode = (captured as? MigrationScreenState)?.driverMigrationTreeNode,
            capturedAtDeviceMs = System.currentTimeMillis(),
          )
        } ?: throw OnDeviceScreenStateNotReadyException("Device configuration is changing")
      },
      // Espresso/Compose interactions synchronize with the app's idle state per action; there is
      // no accessibility-event settle to await before dispatch.
      waitForSettled = {},
      deviceClassifiers = AndroidTestLoggingRule.defaultDeviceClassifiers(),
    )
    server.startServer(port = port, wait = false)
    Console.log("[Trailblaze] In-process RPC server listening on port $port")
    return true
  }

  /**
   * Runs [capture] with [activity] pinned as what the rule's target resolves, so one capture is a
   * consistent snapshot of one Activity and cannot restart the launch wait partway through.
   *
   * `remove()` rather than restoring a previous value: nothing nests here — a captor invocation is
   * one RPC request on one worker — and leaving the reference set would pin a dead Activity for
   * whatever the pool hands that thread next.
   */
  private fun <T> withCaptureActivity(activity: Activity, capture: () -> T): T {
    captureActivity.set(activity)
    return try {
      capture()
    } finally {
      captureActivity.remove()
    }
  }

  /**
   * [AndroidTestTrailblazeRule.captureScreenState], with a lost Activity reported as not-ready.
   *
   * The pin above removes the stall, not the race: an Activity that finishes mid-capture still
   * fails the read. A crash or a backgrounded app would otherwise surface as a stack trace the
   * host reads as a real capture failure, when the honest answer is "this device has no screen to
   * give you yet" — the same classification the pre-check produces, so the host's readiness
   * polling sees one signal rather than two.
   */
  private fun captureOrNotReady(
    includeScreenshot: Boolean,
    includeTree: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig,
  ) = try {
    trailblazeRule.captureScreenState(
      includeScreenshot = includeScreenshot,
      screenshotScalingConfig = screenshotScalingConfig,
      includeTree = includeTree,
    )
  } catch (e: IllegalStateException) {
    Console.log("[Trailblaze] Screen-state capture found no resumed Activity: ${e.message}")
    throw OnDeviceScreenStateNotReadyException("No Activity is RESUMED yet")
  }

  /**
   * Session lifecycle around one dispatched run — the in-process counterpart of
   * `BaseAndroidStandaloneServerTest.createRunTrailblazeYamlCallback`, tool-log counting (#3818)
   * included. Runs on the instrumentation test thread via [TestThreadWorkQueue.dispatch].
   */
  private fun runOnTestThread(
    request: RunYamlRequest,
    session: TrailblazeSession,
    agentMemory: AgentMemory,
  ): RunYamlCallbackResult {
    loggingRule.setSession(session)
    val toolLogCount = AtomicInteger(0)
    val lastToolSuccess: TrailblazeToolResult.Success? = try {
      loggingRule.withLogObserver(
        { log -> if (log is TrailblazeLog.TrailblazeToolLog) toolLogCount.incrementAndGet() },
      ) {
        trailblazeRule.runRpcEnvelope(
          trailYaml = request.yaml,
          trailFilePath = request.trailFilePath,
          sendSessionStartLog = request.config.sendSessionStartLog,
          externalMemory = agentMemory,
          keepDeviceLanguage = request.keepDeviceLanguage,
        )
      }
    } finally {
      loggingRule.setSession(null)
    }
    return RunYamlCallbackResult(
      session = session,
      lastToolSuccess = lastToolSuccess,
      onDeviceToolLogCount = toolLogCount.get(),
    )
  }

  companion object {
    /**
     * How long a capture waits for a RESUMED Activity before reporting not-ready. Comfortably
     * covers an Activity transition while staying well inside the host's per-probe budget.
     */
    private const val RESUMED_ACTIVITY_TIMEOUT_MS = 2_000L
  }
}
