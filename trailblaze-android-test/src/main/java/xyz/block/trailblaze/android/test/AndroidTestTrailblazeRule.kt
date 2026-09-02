package xyz.block.trailblaze.android.test

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.junit.runner.Description
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.android.OnDeviceScriptedToolBundleLauncher
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.toSessionToolRepo
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.scripting.fetch.OkHttpFetchExtension
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.carriesPayload
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.utils.NoOpElementComparator
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Replays a trail file in-process, through the ANDROID_TEST driver, and logs the session so the
 * run produces a Trailblaze report.
 *
 * A trail file is the whole test:
 *
 * ```kotlin
 * class CheckoutTest : AndroidTestTrailblazeTest() {
 *   @Test fun addsItemToCart() = runFromAsset()
 * }
 * ```
 * with `src/androidTest/assets/trails/CheckoutTest/addsItemToCart.trail.yaml` next to it. That is
 * also the shape the `xyz.block.trailblaze.android-gradle` plugin generates, so a consumer module
 * pointing `baseClassFqn` at its own [AndroidTestTrailblazeTest] subclass gets one `@Test` per
 * trail with no Kotlin authored at all.
 *
 * **Replay is deterministic.** Every step must carry a recording; an unrecorded step fails the
 * trail naming the step rather than reaching for an LLM. This driver exists to be a merge-blocking
 * gate, and a gate that can improvise is a gate that can pass for the wrong reason. Authoring —
 * where the LLM does belong — happens through the host CLI against the same trail file.
 */
class AndroidTestTrailblazeRule(
  private val targetProvider: () -> AndroidTestTarget,
  val loggingRule: AndroidTestLoggingRule = AndroidTestLoggingRule(),
  private val metricsSink: AndroidTestMetricsSink = AndroidTestMetricsSink.NONE,
  /**
   * Log a screenshot + hierarchy snapshot before each replayed tool.
   *
   * On by default: without it the report has a step timeline but no frames, and "assertVisible
   * failed" is not a diagnosis. Each snapshot is one `UiAutomation.takeScreenshot()`, so turn it
   * off for timing work where the driver's native execution cost is the measurement.
   *
   * Off also drops the pixels from the terminal frame, so a timing run never touches UiAutomation
   * at all. Connecting it for the first time in teardown crashes the process AFTER the test has
   * passed: the runner's `Instrumentation.finish()` reaches `UiAutomation.disconnect()` while that
   * connection is still settling and it throws `Cannot call disconnect() while connecting`.
   */
  private val captureStepSnapshots: Boolean = true,
  /**
   * Emit a tool log per dispatched tool — see [AndroidTestTrailblazeAgent.logToolCalls].
   *
   * On by default here, off there. These logs ARE the report, and a trail-file run that leaves a
   * session with no steps in it has nothing to read after a failure. The switch exists because
   * serializing them needs a kotlinx.serialization runtime at least as new as Trailblaze's, and an
   * app that pins an older one crashes on the first tool; that app needs a way off, not a driver it
   * cannot use.
   */
  private val logToolCalls: Boolean = true,
  /**
   * The trailmap-manifest target the trails run against — e.g. an app's Kotlin
   * [TrailblazeHostAppTarget] or a YAML-backed one. Supplying it is what turns scripted tools on
   * for this driver: the target's `tools:` declarations (from the bundled `targets/<id>.yaml`)
   * name the QuickJS bundles [OnDeviceScriptedToolBundleLauncher] registers at session start, and
   * scripted tools see it as `ctx.target`. Null (the default) keeps the pre-scripted-tools
   * behavior: no bundles launch, and a trail naming a scripted tool fails as an unresolvable
   * `OtherTrailblazeTool`.
   */
  private val hostAppTarget: TrailblazeHostAppTarget? = null,
  /**
   * The session tool repo scripted-tool bundles register into AND `ctx.tools` name resolution
   * dispatches against. Null (the default) builds one scoped to [hostAppTarget] and this driver —
   * callers that construct their own agent must pass the same repo here, mirroring
   * `AndroidTrailblazeRule`'s contract.
   */
  trailblazeToolRepoOverride: TrailblazeToolRepo? = null,
) : SimpleTestRuleChain(loggingRule) {

  /**
   * Lazy so the device-info provider (whose classifiers some catalog scoping reads) is fully
   * initialized first. With no target this is the whole ANDROID_TEST-compatible catalog — which
   * still matters with zero scripted tools, because it is what `ctx.tools.<frameworkTool>` name
   * resolution and [xyz.block.trailblaze.BaseTrailblazeAgent.resolveDynamicTool] look tools up in.
   */
  private val trailblazeToolRepo: TrailblazeToolRepo by lazy {
    trailblazeToolRepoOverride
      ?: hostAppTarget.toSessionToolRepo(driverType = TrailblazeDriverType.ANDROID_TEST)
  }

  /**
   * Resolved once, on first use rather than at construction: the target usually reads an Activity
   * the test launches in `@Before`, which has not happened yet while rules are being built.
   */
  private val target: AndroidTestTarget by lazy { targetProvider() }

  /**
   * The caller's target, with a whole-device screenshot filling in for a target that supplies
   * none. [RuleBackedAndroidTestTarget] defaults `screenshotProvider` to null because apps differ
   * in how they capture multi-window state, but a null frame in a *report* is just a blank card,
   * so a report-bound capture falls back rather than showing nothing.
   */
  private val screenshotCapableTarget: AndroidTestTarget by lazy {
    object : AndroidTestTarget by target {
      override fun captureScreenshot(): Bitmap? =
        target.captureScreenshot() ?: AndroidTestInstrumentation.deviceScreenshot()
    }
  }

  val agent: AndroidTestTrailblazeAgent by lazy {
    AndroidTestTrailblazeAgent(
      target = target,
      trailblazeLogger = loggingRule.logger,
      trailblazeDeviceInfoProvider = loggingRule.trailblazeDeviceInfoProvider,
      sessionProvider = { currentSession() },
      trailblazeToolRepo = trailblazeToolRepo,
      resolvedTarget = hostAppTarget?.let {
        ResolvedTarget(
          target = it,
          deviceId = loggingRule.trailblazeDeviceInfoProvider().trailblazeDeviceId,
        )
      },
      // On this driver the app under test is by definition the instrumented target package — no
      // installed-packages probe needed, unlike the on-device rule.
      appId = InstrumentationRegistry.getInstrumentation().targetContext.packageName,
      metricsSink = metricsSink,
      logToolCalls = logToolCalls,
    )
  }

  private fun hierarchyOnlyState(includeTree: Boolean = true): ScreenState =
    AndroidTestScreenState(
      target = target,
      deviceClassifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers,
      includeScreenshot = false,
      includeTree = includeTree,
    )

  /**
   * Screenshot-free by design: this feeds tool dispatch, which resolves selectors against the
   * hierarchy and never reads pixels. Report frames come from [snapshotProvider].
   */
  private val screenStateProvider: () -> ScreenState = { hierarchyOnlyState() }

  private val snapshotProvider: () -> ScreenState = { snapshot(screenshotScalingConfig = null) }

  private fun snapshot(
    screenshotScalingConfig: ScreenshotScalingConfig?,
    includeTree: Boolean = true,
  ): ScreenState =
    AndroidTestScreenState(
      target = screenshotCapableTarget,
      deviceClassifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers,
      includeScreenshot = true,
      screenshotScalingConfig = screenshotScalingConfig,
      includeTree = includeTree,
    )

  private val trailblazeYaml by lazy { createTrailblazeYaml() }

  private val runnerUtil by lazy {
    TrailblazeRunnerUtil(
      runTrailblazeTool = { tools -> runTools(tools) },
      trailblazeRunner = RecordedStepsOnlyRunner(screenStateProvider),
      trailblazeLogger = loggingRule.logger,
      sessionProvider = { currentSession() },
      sessionUpdater = { loggingRule.setSession(it) },
      onBeforeRecordedTool = if (captureStepSnapshots) {
        { tool -> logStepSnapshot(tool) }
      } else {
        null
      },
      sharedToolBatch = { block -> agent.runInSharedToolBatch(block) },
    )
  }

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
    // Terminal frame for pass and fail alike. Assigned here (not in an initializer) because the
    // provider closes over [target], which is only resolvable once the test is running.
    loggingRule.failureScreenStateProvider =
      if (captureStepSnapshots) snapshotProvider else screenStateProvider
  }

  /**
   * Runs the `*.trail.yaml` asset matching the calling test's class + method name — the signature
   * the plugin's generated `@Test fun x() = runFromAsset()` shells require.
   */
  fun runFromAsset(
    yamlAssetPath: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      AndroidTestInstrumentation::assetExists,
    ),
  ) {
    val resolvedPath = TrailRecordings.findBestTrailResourcePath(
      path = yamlAssetPath,
      deviceClassifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers,
      doesResourceExist = AndroidTestInstrumentation::assetExists,
    ) ?: throw TrailblazeException("Trail asset not found: $yamlAssetPath")
    Console.log("[Trailblaze] Running trail from asset: $resolvedPath")
    run(
      trailYaml = AndroidTestInstrumentation.readAssetAsString(resolvedPath),
      trailFilePath = resolvedPath,
    )
  }

  /** Replays [trailYaml] directly. [runFromAsset] is the path a generated shell takes. */
  fun run(trailYaml: String, trailFilePath: String? = null) {
    val classifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers
    val trailItems = trailblazeYaml.decodeTrail(trailYaml, deviceClassifiers = classifiers)
    runDecoded(
      trailItems = trailItems,
      trailYaml = trailYaml,
      trailFilePath = trailFilePath,
      sendSessionStartLog = true,
      externalMemory = null,
    )
  }

  /**
   * The host-driven RPC entry: what `RunYamlRequestHandler`'s callback runs when the CLI drives
   * this instrumentation over the on-device RPC protocol. Differs from [run] exactly where the
   * transport does:
   *
   * - [trailYaml] may be a full trail document OR a per-tool dispatch envelope (the host drives
   *   the loop one authored tool at a time), so it decodes via `decodeTrailOrToolEnvelope`.
   * - The session-start log is the host's call ([sendSessionStartLog]) — the handler manages the
   *   session lifecycle and suppresses per-request starts it already emitted.
   * - [externalMemory], when present, is the handler's per-request [AgentMemory] — pre-populated
   *   from the host's `memorySnapshot` and serialized back onto the response, so tools must read
   *   and write that instance. It is never cleared or re-seeded here: the host owns its lifecycle.
   *
   * Returns the last successfully-executed tool's [TrailblazeToolResult.Success] so the handler
   * can mirror its `message`/`structuredContent` onto the RPC response envelope — the same payload
   * contract `AndroidTrailblazeRule.runSuspend` gives the accessibility runner.
   *
   * Replay stays deterministic: an unrecorded prompt step fails by name here exactly as it does on
   * the farm path — the RPC transport changes who drives, not what the driver will improvise.
   */
  fun runRpcEnvelope(
    trailYaml: String,
    trailFilePath: String?,
    sendSessionStartLog: Boolean,
    externalMemory: AgentMemory?,
  ): TrailblazeToolResult.Success? {
    val classifiers = loggingRule.trailblazeDeviceInfoProvider().classifiers
    val trailItems =
      trailblazeYaml.decodeTrailOrToolEnvelope(trailYaml, deviceClassifiers = classifiers)
    return runDecoded(
      trailItems = trailItems,
      trailYaml = trailYaml,
      trailFilePath = trailFilePath,
      sendSessionStartLog = sendSessionStartLog,
      externalMemory = externalMemory,
    )
  }

  /**
   * One fresh [ScreenState] snapshot, for callers outside the replay loop — the on-device RPC
   * `GetScreenStateRequest` captor in particular. [includeScreenshot] chooses between the
   * report-bound capture (whole-device fallback pixels) and the hierarchy-only read the tool
   * dispatch path uses.
   *
   * [screenshotScalingConfig] is the caller's declared output contract for those pixels; the RPC
   * captor passes the one its request carried, so a host asking for scaled WEBP is not handed a
   * full-resolution PNG. Null leaves the replay path's encode as it was.
   *
   * [includeTree] false skips the main-thread hierarchy walk entirely. Honored on both branches:
   * routing a tree-less request to [screenStateProvider] would walk anyway, since that provider
   * feeds tool dispatch and must always have a tree.
   */
  fun captureScreenState(
    includeScreenshot: Boolean,
    screenshotScalingConfig: ScreenshotScalingConfig? = null,
    includeTree: Boolean = true,
  ): ScreenState =
    if (includeScreenshot) {
      snapshot(screenshotScalingConfig, includeTree)
    } else {
      hierarchyOnlyState(includeTree)
    }

  private fun runDecoded(
    trailItems: List<TrailYamlItem>,
    trailYaml: String,
    trailFilePath: String?,
    sendSessionStartLog: Boolean,
    externalMemory: AgentMemory?,
  ): TrailblazeToolResult.Success? {
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      // Before the session-start log, so a skipped trail leaves no session behind at all — same
      // ordering the on-device Android rule uses.
      Console.log(
        "[Trailblaze] Skipping trail" + (trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return null
    }

    requireCompatibleDriver(trailConfig, trailFilePath)

    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
      throw TrailblazeException(
        "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file."
      )
    }

    val session = currentSession()
    if (sendSessionStartLog) {
      loggingRule.logger.log(
        session,
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = trailConfig,
            trailFilePath = trailFilePath,
            testClassName = loggingRule.description?.className ?: "AndroidTestTrailblazeRule",
            testMethodName = loggingRule.description?.methodName ?: "run",
            trailblazeDeviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
            rawYaml = trailYaml,
            hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
            trailblazeDeviceId = loggingRule.trailblazeDeviceInfoProvider().trailblazeDeviceId,
          ),
          session = session.sessionId,
          timestamp = Clock.System.now(),
        ),
      )
    }

    // The last payload-bearing Success across all items — see [runRpcEnvelope]'s return contract.
    var lastToolSuccess: TrailblazeToolResult.Success? = null
    // Both declared before the `try` so the `finally` can reach them, but ASSIGNED inside it:
    // everything from the memory swap to the bundle launch can throw, and an early throw must not
    // leave the host's per-request memory pinned on this long-lived agent for the next run to read.
    var scriptedToolRuntime: LaunchedQuickJsToolRuntime? = null
    try {
      // External memory is the RPC handler's per-request instance and the host owns its lifecycle —
      // never clear it here (a host-seeded {{var}} would be wiped before the step that reads it).
      // The farm path keeps its per-trail clear.
      agent.externalMemory = externalMemory
      if (externalMemory == null) {
        agent.clearMemory()
      }
      // `config: memory:` seeds keys the memory doesn't already carry, on BOTH paths — the RPC
      // entry takes whole trail documents, so a document's declared defaults have to land here or
      // they never land at all. Absent-keys-only is what keeps the host authoritative: a value the
      // host seeded, or a key a tool explicitly deleted this run, is never overwritten or revived.
      trailConfig?.memory?.forEach { (key, value) ->
        val memory = agent.memory
        if (!memory.has(key) && key !in memory.deletedKeys) memory.remember(key, value)
      }

      // Launch the session's scripted-tool bundles (the QuickJS-compiled `tools/*.ts` this APK
      // packaged as assets) before the first step, so a recorded step naming one — a trail whose
      // trailhead is an app's TypeScript launch orchestrator — resolves through
      // [xyz.block.trailblaze.BaseTrailblazeAgent.resolveDynamicTool] instead of failing as an
      // unresolvable `OtherTrailblazeTool`. Same launcher, same repo-registration semantics as the
      // on-device accessibility rule; teardown in `finally` under `NonCancellable` for the same
      // reason it does it — a failed or cancelled trail must not leak the QuickJS native
      // allocations or the dynamic-tool registrations into the next test's repo.
      scriptedToolRuntime = runBlocking {
        OnDeviceScriptedToolBundleLauncher.launchAll(
          toolRepo = trailblazeToolRepo,
          target = hostAppTarget,
          sessionId = session.sessionId,
          deviceInfo = loggingRule.trailblazeDeviceInfoProvider(),
          // Standard WHATWG `fetch`, OkHttp-backed — the same binding every host launcher installs,
          // so a scripted tool's HTTP works identically here, on-device, and host-dispatched.
          engineExtension = OkHttpFetchExtension(),
        )
      }
      trailItems.forEach { item ->
        val result = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            runnerUtil.runPrompt(
              prompts = item.promptSteps,
              useRecordedSteps = true,
              selfHeal = false,
            )
          is TrailYamlItem.TrailheadTrailItem ->
            runnerUtil.runPrompt(
              prompts = listOf(item.trailhead.toPromptStep()),
              useRecordedSteps = true,
              selfHeal = false,
            )
          is TrailYamlItem.ToolTrailItem ->
            runToolsSnapshotted(item.tools.map { it.trailblazeTool })
          // Nothing to do: the fields this driver reads off `config:` (skip, driver, memory) were
          // all consumed above, and `context:` is an LLM system-prompt hint with no LLM to hint.
          is TrailYamlItem.ConfigTrailItem -> TrailblazeToolResult.Success()
        }
        if (result is TrailblazeToolResult.Error) {
          throw TrailblazeException(result.errorMessage)
        }
        // Same predicate the accessibility rule's fold uses, deliberately shared rather than
        // restated: a payload-less Success — a `config:` item's synthetic one, but equally a
        // `tapOn` whose return value is just a verdict — must not overwrite a real tool's
        // payload, or `[adbShell→stdout, tapOn]` surfaces toolMessage=null to the host.
        if (result is TrailblazeToolResult.Success && result.carriesPayload()) {
          lastToolSuccess = result
        }
      }
    } finally {
      agent.externalMemory = null
      scriptedToolRuntime?.let {
        runBlocking { withContext(NonCancellable) { runCatching { it.shutdownAll() } } }
      }
    }
    return lastToolSuccess
  }

  /**
   * A trail pinned to some other driver must not SILENTLY replay here. `config.driver:` is how an
   * author says "this trail needs an accessibility tree" or "this trail shells out to adb"; the
   * in-process driver has neither, and the failure it produces further down would be attributed to
   * whichever step happened to need the missing capability.
   *
   * Explicitly forced is not silent. The `trailblaze.driverType` instrumentation arg carries force
   * semantics — "the on-device runtime uses this driver and skips the per-trail `config.driver`
   * YAML peek entirely" (`InstrumentationArgUtil.driverType`) — and this gate is the last reader of
   * the pin, so refusing under an explicit force would make that documented override a no-op for
   * exactly the driver it exists to select. The pin reaching here need not even be authored
   * top-level: lowering a unified trail resolves its per-device `devices.<classifier>.driver:` pin
   * into the effective `config.driver`, so a whole estate of per-device-pinned trails hits this
   * gate under any suite-wide driver override. Under force the pin is logged, not fatal, so a later
   * missing-capability failure still has the mismatch on record.
   */
  private fun requireCompatibleDriver(trailConfig: TrailConfig?, trailFilePath: String?) {
    val verdict = evaluateDriverPin(
      pinnedDriver = trailConfig?.driver,
      forcedDriver =
        InstrumentationRegistry.getArguments().getString(DRIVER_TYPE_INSTRUMENTATION_ARG),
      trailFilePath = trailFilePath,
    )
    when (verdict) {
      is DriverPinVerdict.Allow -> Unit
      is DriverPinVerdict.AllowForced -> Console.log(verdict.logMessage)
      is DriverPinVerdict.Refuse -> throw TrailblazeException(verdict.message)
    }
  }

  /**
   * Runs a stepless tool block one tool at a time, capturing the pre-tool frame the way
   * [TrailblazeRunnerUtil] does for recorded steps.
   *
   * No trail FILE reaches here: `decodeTrail` lowers a unified document to config, trailhead and
   * prompt items only. The shape exists for the on-device RPC envelope
   * (`decodeTrailOrToolEnvelope`), where the host drives the loop one authored tool at a time — and
   * that path does not go through `runnerUtil`, so it never reaches its `onBeforeRecordedTool`.
   * Without the capture here it would log tool calls and no frames at all, the same unreadable
   * report [captureStepSnapshots] exists to prevent, reached by a different door.
   *
   * One tool per dispatch, because a frame is only evidence if it is the screen that tool acted on;
   * capturing the whole block up front would file N copies of the first screen. Stops at the first
   * error for the same reason the recorded loop does — every later tool would run against a screen
   * the trail never reached.
   */
  private fun runToolsSnapshotted(tools: List<TrailblazeTool>): TrailblazeToolResult {
    // Returns the LAST tool's own result (not a synthetic Success) so its `message` /
    // `structuredContent` survive onto the RPC response envelope — the payload a host-side
    // scripted-tool author composing dual-mode primitives reads. An empty block stays a
    // plain Success.
    var last: TrailblazeToolResult = TrailblazeToolResult.Success()
    tools.forEach { tool ->
      if (captureStepSnapshots) logStepSnapshot(tool)
      val result = runTools(listOf(tool))
      if (result is TrailblazeToolResult.Error) return result
      last = result
    }
    return last
  }

  private fun runTools(tools: List<TrailblazeTool>): TrailblazeToolResult =
    agent.runTrailblazeTools(
      tools = tools,
      elementComparator = NoOpElementComparator,
      screenStateProvider = screenStateProvider,
    ).result

  private fun logStepSnapshot(tool: TrailblazeTool) {
    runCatching {
      loggingRule.logger.logSnapshot(
        session = currentSession(),
        screenState = snapshotProvider(),
        displayName = tool.javaClass.simpleName,
      )
    }.onFailure { e ->
      // Observational only — a missing frame must not fail the step it was documenting.
      Console.log("[Trailblaze] Could not capture step snapshot: ${e.message}")
    }
  }

  private fun currentSession() = loggingRule.session
    ?: error(
      "No Trailblaze session. AndroidTestTrailblazeRule must be applied as a JUnit @Rule — see " +
        "AndroidTestTrailblazeTest for the wiring."
    )

  /** What [evaluateDriverPin] decided about a trail's `config.driver:` pin. */
  internal sealed interface DriverPinVerdict {
    /** No pin, or the pin names this driver. */
    data object Allow : DriverPinVerdict

    /** The pin names another driver, but the run explicitly forced this one — log, don't refuse. */
    data class AllowForced(val logMessage: String) : DriverPinVerdict

    /** The pin names another driver and nothing forced this one. */
    data class Refuse(val message: String) : DriverPinVerdict
  }

  internal companion object {
    /**
     * The two spellings of this driver a `config.driver:` pin may use, matched case-insensitively.
     * Derived from the enum so a rename cannot leave this gate matching a stale name.
     */
    val ANDROID_TEST_DRIVER_NAME: String = TrailblazeDriverType.ANDROID_TEST.name
    val ANDROID_TEST_DRIVER_YAML_KEY: String = TrailblazeDriverType.ANDROID_TEST.yamlKey

    /**
     * The instrumentation arg a driver force arrives as (see `InstrumentationArgUtil.driverType`
     * for the contract). Aliased from the enum rather than spelled again: reading it through that
     * util is what this module can't do — it lives in the on-device accessibility module, which
     * this module deliberately does not depend on — but the KEY is shared, so the host writing it
     * (`HostAndroidDeviceConnectUtils.instrumentationArgsWithForcedDriver`) and this gate reading
     * it cannot drift apart.
     */
    const val DRIVER_TYPE_INSTRUMENTATION_ARG = TrailblazeDriverType.INSTRUMENTATION_ARG_KEY

    /** Whether a `config.driver:` pin names this driver, in either spelling and any case. */
    private fun namesThisDriver(value: String?): Boolean =
      value != null &&
        (
          value.equals(ANDROID_TEST_DRIVER_NAME, ignoreCase = true) ||
            value.equals(ANDROID_TEST_DRIVER_YAML_KEY, ignoreCase = true)
          )

    /**
     * Whether the `trailblaze.driverType` arg forces this driver — matched EXACTLY, unlike a pin.
     * The runtime parses that arg with `TrailblazeDriverType.valueOf` (`InstrumentationArgUtil`),
     * so anything else — the `android-test` yaml key, a case variant — is discarded there and the
     * run falls back to its default driver. Accepting a spelling the runtime threw away would turn
     * an arg that forced nothing into authorization to ignore a pin.
     */
    private fun forcesThisDriver(value: String?): Boolean = value == ANDROID_TEST_DRIVER_NAME

    /**
     * Pure decision behind [requireCompatibleDriver], separated so the force-beats-pin precedence
     * is unit-testable off-device. [forcedDriver] is the raw `trailblaze.driverType` arg value, or
     * null when the run carries no override; only a force naming THIS driver downgrades a foreign
     * pin to a log line — a force naming some other driver while this rule is executing is
     * incoherent, and keeps the refusal.
     */
    internal fun evaluateDriverPin(
      pinnedDriver: String?,
      forcedDriver: String?,
      trailFilePath: String?,
    ): DriverPinVerdict {
      val pinned = pinnedDriver?.takeIf { it.isNotBlank() } ?: return DriverPinVerdict.Allow
      if (namesThisDriver(pinned)) return DriverPinVerdict.Allow
      val trailLabel = trailFilePath?.let { " '$it'" } ?: ""
      if (forcesThisDriver(forcedDriver)) {
        return DriverPinVerdict.AllowForced(
          "[Trailblaze] Trail$trailLabel pins `config.driver: $pinned`, overridden by the explicit " +
            "$DRIVER_TYPE_INSTRUMENTATION_ARG=$forcedDriver instrumentation arg — replaying " +
            "in-process. If this trail later fails on a capability this driver lacks, this pin " +
            "is why."
        )
      }
      return DriverPinVerdict.Refuse(
        "Trail$trailLabel pins `config.driver: $pinned`, but it is running on the in-process " +
          "ANDROID_TEST driver. Force this driver for the whole run " +
          "(`-e $DRIVER_TYPE_INSTRUMENTATION_ARG $ANDROID_TEST_DRIVER_NAME`), drop the pin, or " +
          "run this trail on the driver it names."
      )
    }
  }
}

/**
 * The [TestAgentRunner] a deterministic gate gets: every entry point refuses.
 *
 * [TrailblazeRunnerUtil] reaches for this only when a step has no recording to replay, or when
 * a recorded step failed and self-heal is on (it is not, here). Both are the same authoring
 * mistake, and both are far more useful as a named failure than as an LLM improvising against a
 * merge gate.
 */
private class RecordedStepsOnlyRunner(
  override val screenStateProvider: () -> ScreenState,
) : TestAgentRunner {

  override fun run(prompt: PromptStep, stepStatus: PromptStepStatus): AgentTaskStatus =
    throw TrailblazeException(
      "Step \"${prompt.prompt}\" has no recorded tools. The in-process ANDROID_TEST driver replays " +
        "recordings only — record this trail against a device first (`trailblaze record`), then " +
        "commit the recorded steps."
    )

  override fun recover(
    promptStep: PromptStep,
    recordingResult: PromptRecordingResult.Failure,
  ): AgentTaskStatus =
    throw TrailblazeException(
      "Recorded step \"${promptStep.prompt}\" failed at ${recordingResult.failedTool.name} and " +
        "self-heal is not available on the in-process ANDROID_TEST driver."
    )

  override fun appendToSystemPrompt(context: String) = Unit
}
