package xyz.block.trailblaze.android

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.MessagePart
import ai.koog.utils.time.KoogClock
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import maestro.orchestra.Command
import org.junit.runner.Description
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.AndroidAssetsUtil
import xyz.block.trailblaze.AndroidMaestroTrailblazeAgent
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.android.accessibility.AccessibilityServiceScreenState
import xyz.block.trailblaze.android.accessibility.AccessibilityTrailblazeAgent
import xyz.block.trailblaze.android.accessibility.OnDeviceAccessibilityServiceSetup
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.TrailblazeYamlUtil
import xyz.block.trailblaze.agent.AgentUiActionExecutor
import xyz.block.trailblaze.agent.BlazeConfig
import xyz.block.trailblaze.agent.InnerLoopScreenAnalyzer
import xyz.block.trailblaze.agent.MultiAgentV3Runner
import xyz.block.trailblaze.agent.MultiAgentV3TestAgentRunner
import xyz.block.trailblaze.agent.TrailblazeElementComparator
import xyz.block.trailblaze.agent.TrailblazeRunner
import xyz.block.trailblaze.agent.blaze.PlannerLlmCall
import xyz.block.trailblaze.agent.blaze.PlannerToolCallResult
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.android.agent.KoogLlmSamplingSource
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.android.devices.TrailblazeAndroidOnDeviceClassifier
import xyz.block.trailblaze.android.uiautomator.AndroidOnDeviceUiAutomatorScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TestAgentRunner
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.model.toTrailblazeToolRepo
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.SimpleTestRuleChain
import xyz.block.trailblaze.rules.TrailblazeRule
import xyz.block.trailblaze.rules.TrailblazeRunnerUtil
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource
import xyz.block.trailblaze.quickjs.tools.BundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.util.Console

/** Which screen-state implementation [AndroidTrailblazeRule] should build for a given run. */
internal enum class ScreenStateKind { UIAUTOMATOR, ACCESSIBILITY }

/**
 * Picks the screen-state implementation whose tree shape matches the runtime agent.
 *
 * The accessibility runtime resolves [TrailblazeNodeSelector]s against the live accessibility
 * tree, and [TrailblazeNodeSelectorResolver] is strictly typed-pair: an `AndroidMaestro` selector
 * cannot match an `AndroidAccessibility` node. If the rule built a UiAutomator screen state under
 * the accessibility driver, every selector generated from it would `NoMatch` at dispatch time —
 * the failure mode reported in the OSS bug.
 *
 * Migration mode is the exception: when `trailblaze.captureSecondaryTree=true` we're replaying
 * pre-migration Maestro selectors and need them to resolve against the UiAutomator shape, so the
 * primary stays UiAutomator regardless of driver. The accessibility tree rides on the
 * [MigrationScreenState] side channel instead.
 */
internal fun chooseScreenStateKind(
  isAccessibilityDriver: Boolean,
  isMigrationMode: Boolean,
  isAccessibilityServiceRunning: Boolean,
): ScreenStateKind = if (
  isAccessibilityDriver && !isMigrationMode && isAccessibilityServiceRunning
) {
  ScreenStateKind.ACCESSIBILITY
} else {
  ScreenStateKind.UIAUTOMATOR
}

/**
 * On-Device Android Trailblaze Rule Implementation.
 *
 * Provides stateless logger with explicit session management:
 * - Access logger via `logger` property
 * - Access current session via `session` property
 * - Use `logger.log(session, log)` for all logging operations
 *
 * Supports multiple driver types via optional [agentOverride] and [screenStateProviderOverride]:
 * - When not provided, uses the default UiAutomator-based agent and screen state
 * - For accessibility mode, callers inject accessibility-specific implementations
 */
open class AndroidTrailblazeRule(
  val trailblazeLlmModel: TrailblazeLlmModel = AndroidLlmClientResolver.resolveModel(),
  val llmClient: LLMClient = AndroidLlmClientResolver.createClient(trailblazeLlmModel),
  val config: TrailblazeConfig = TrailblazeConfig.DEFAULT,
  private val trailblazeDeviceId: TrailblazeDeviceId = DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID,
  val trailblazeLoggingRule: TrailblazeAndroidLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers() },
  ),
  customToolClasses: CustomTrailblazeTools? = null,
  agentOverride: MaestroTrailblazeAgent? = null,
  screenStateProviderOverride: (() -> ScreenState)? = null,
  /**
   * Blaze configuration for V3 exploration mode.
   *
   * Defaults to [BlazeConfig.DEFAULT] — balanced settings for most exploration scenarios.
   * Only used when [AgentImplementation.MULTI_AGENT_V3] is active (via instrumentation arg
   * `-e trailblaze.agent MULTI_AGENT_V3`). The legacy [TrailblazeRunner] path ignores this
   * parameter entirely.
   *
   * Override with a custom [BlazeConfig] if you need to tune iteration counts, reflection
   * intervals, or subtask limits based on empirical OOM data from Device Farm runs.
   */
  private val blazeConfig: BlazeConfig = BlazeConfig.DEFAULT,
  /**
   * QuickJS tool bundle declarations for tools authored against `@trailblaze/tools` and
   * compiled to a JS bundle. Each entry should have `script:` set to a relative path that
   * resolves to a `.js` asset shipped in the APK — the launcher reads each via
   * [quickjsBundleSourceResolver] and registers advertised tools into the session's tool
   * repo through the [QuickJsToolBundleLauncher]. Host-only tools
   * (`_meta["trailblaze/requiresHost"]: true`) drop at registration so on-device sessions
   * never see them.
   *
   * Default empty so callers that don't exercise QuickJS-runtime tools don't have to pass
   * a list.
   */
  private val quickjsToolBundles: List<McpServerConfig> = emptyList(),
  /**
   * Resolver that maps each [quickjsToolBundles] entry to a [BundleSource] the QuickJS
   * launcher can read. Default treats the `script:` path as an Android asset. Override for
   * tests that want to hand in an inline JS fixture.
   */
  private val quickjsBundleSourceResolver: (McpServerConfig) -> BundleSource = { entry ->
    AndroidAssetBundleSource(
      assetPath = requireNotNull(entry.script) {
        "quickjsToolBundles entry is missing `script:` — `command:` entries can't bundle " +
          "into the on-device QuickJS runtime."
      },
    )
  },
  /**
   * Optional shared [AgentMemory] threaded into the constructed [AndroidMaestroTrailblazeAgent].
   * The on-device `RunYamlRequestHandler` uses this seam to populate the agent's memory from
   * the host's snapshot at request entry, and to read the post-execution state into the
   * response. Defaults to a fresh instance for the in-process / unit-test case.
   */
  agentMemoryOverride: AgentMemory? = null,
  /**
   * Per-objective cap on LLM calls forwarded into [TrailblazeRunner.maxSteps]. Surfaced as
   * the CLI's `--max-llm-calls` flag and threaded through
   * [xyz.block.trailblaze.llm.RunYamlRequest.maxLlmCalls] into this rule's constructor.
   * Null = use the runner's built-in default. Ignored when
   * [AgentImplementation.MULTI_AGENT_V3] is selected (the V3 path tunes iterations via
   * [BlazeConfig] instead, and the CLI rejects the combination at parse time).
   */
  private val maxLlmCalls: Int? = null,
  /**
   * Optional trailmap-manifest target this rule is running against. Captured so the scripted-tool
   * runtime (`_meta.trailblaze.target` → `ctx.target`) surfaces `ctx.target?.resolveAppId()` to
   * bundled tools dispatching in-process through this rule's QuickJS / MCP-bundle launchers.
   *
   * Null by default — the rule is target-agnostic out of the box, and authors writing
   * target-aware scripted tools should optional-chain (`ctx.target?.resolveAppId(...)`) so the
   * same source works either way. Callers that want `ctx.target` populated pass a target; the
   * agent then carries it and its device-resolved app id through to every execution context.
   */
  private val target: TrailblazeHostAppTarget? = null,
) : SimpleTestRuleChain(trailblazeLoggingRule),
  TrailblazeRule {

  private val agentMemory: AgentMemory = agentMemoryOverride ?: AgentMemory()

  /**
   * Pre-built `ResolvedTarget` pairing [target] with [trailblazeDeviceId]. Threaded into the
   * lazy agents below so [MaestroTrailblazeAgent.buildExecutionContext] populates the
   * scripted-tool envelope with the data fields `ctx.target` exposes. Null when no target
   * was supplied — matches the documented "no target → ctx.target undefined" contract.
   */
  private val resolvedTargetForSession: ResolvedTarget? =
    target?.let { ResolvedTarget(target = it, deviceId = trailblazeDeviceId) }

  /**
   * Resolved Android app id for the supplied target — picked at session start by intersecting
   * the target's declared `app_ids:` list against the device's installed packages via
   * `AdbCommandUtil.listInstalledApps()` (which on-device shells out to `pm list packages`).
   * Lazy so callers that never read it don't pay the shell-out, and so the value is fixed
   * for the rule's lifetime (Android instrumentation processes don't get apps installed mid-
   * test). Null when no target was supplied, or when none of the target's declared candidates
   * is installed.
   */
  private val appIdForSession: String? by lazy {
    val resolved = resolvedTargetForSession ?: return@lazy null
    runCatching {
      val installed = AdbCommandUtil.listInstalledApps().toSet()
      resolved.target.getAppIdIfInstalled(
        platform = TrailblazeDevicePlatform.ANDROID,
        installedAppIds = installed,
      )
    }
      .onFailure { e ->
        // Log-and-soft-fall-through: a probe failure (`pm list packages` shell error, weird
        // device permissions, transient ADB hiccup) still falls back to `null` so the trail can
        // attempt to launch via `ctx.target.appIds[0]`, but the failure shouldn't be invisible
        // in trail logs. Without this, a scripted tool that silently launches the wrong
        // candidate is indistinguishable from one that ran correctly — a known on-device
        // debugging dead end. The wrapping `.getOrNull()` below still returns null on failure;
        // this only adds the diagnostic signal.
        Console.log(
          "[AndroidTrailblazeRule] failed to resolve installed app id for target " +
            "'${resolved.id}' on device ${trailblazeDeviceId.instanceId}: " +
            // Null-coalesce: a bare `RuntimeException()` or an anonymous-object exception
            // can have a null message, in which case `${e.message}` interpolates the literal
            // string "null" and the log line buries the useful signal (the exception class).
            "${e::class.simpleName}: ${e.message ?: "<no message>"}"
        )
      }
      .getOrNull()
  }

  /**
   * Selects the runtime agent based on
   * [TrailblazeAndroidLoggingRule.driverTypeOverride] (resolved from the
   * `trailblaze.driverType` instrumentation arg or trail config):
   *
   *  - [TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY] →
   *    [AccessibilityTrailblazeAgent] (live accessibility-tree resolution + coordinate
   *    gestures via [TrailblazeAccessibilityService]).
   *  - any other driver → [AndroidMaestroTrailblazeAgent] (UiAutomator-backed
   *    Maestro Orchestra).
   *
   * Cross-driver-portable trail recordings (carrying both a Maestro `selector` and an
   * `androidAccessibility` nodeSelector) work under both runtimes — the right path is
   * picked by [TapOnByElementSelector] based on [MaestroTrailblazeAgent.usesAccessibilityDriver].
   *
   * Resolved lazily so [trailblazeLoggingRule] is fully initialized before we read
   * [TrailblazeAndroidLoggingRule.driverTypeOverride].
   */
  val trailblazeAgent: MaestroTrailblazeAgent by lazy {
    agentOverride ?: when (trailblazeLoggingRule.driverTypeOverride) {
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> AccessibilityTrailblazeAgent(
        trailblazeLogger = trailblazeLoggingRule.logger,
        trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
        sessionProvider = {
          trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
        },
        // Match what the pre-merge AccessibilityAwareAndroidTrailblazeRule passed —
        // without these the on-device classifier signal flows in as `emptyList()` and
        // device-shaped element matching/filtering loses the dimension.
        deviceClassifiers = TrailblazeAndroidOnDeviceClassifier.getDeviceClassifiers(),
        memory = agentMemory,
        // Surfaces `ctx.target.{id, appIds, appId}` to scripted tools dispatching
        // through `MaestroTrailblazeAgent.buildExecutionContext`. Both fields are null when
        // no [target] was supplied — the documented `ctx.target === undefined` shape.
        resolvedTarget = resolvedTargetForSession,
        appId = appIdForSession,
      )
      else -> AndroidMaestroTrailblazeAgent(
        trailblazeLogger = trailblazeLoggingRule.logger,
        trailblazeDeviceInfoProvider = trailblazeLoggingRule.trailblazeDeviceInfoProvider,
        sessionProvider = {
          trailblazeLoggingRule.session ?: error("Session not available - ensure test is running")
        },
        nodeSelectorMode = config.nodeSelectorMode,
        memory = agentMemory,
        // Propagate the host bridge's capture toggle to the on-device agent so capture-aware
        // launch tools can flip their app's debug SharedPref gates in the pre-launch seeding step.
        captureNetworkTraffic = config.captureNetworkTraffic,
        // See accessibility-agent branch above for why these are threaded into both paths —
        // scripted tools dispatched through MaestroTrailblazeAgent.buildExecutionContext need
        // `ctx.target` populated regardless of which Android driver the trail picked.
        resolvedTarget = resolvedTargetForSession,
        appId = appIdForSession,
      )
    }
  }

  private val trailblazeToolRepo =
    customToolClasses?.toTrailblazeToolRepo() ?: TrailblazeToolRepo.withDynamicToolSets()

  private val screenStateProvider: () -> ScreenState = screenStateProviderOverride ?: {
    // Source of truth is the *agent* the rule will dispatch through, not the driver type
    // label. A caller can pass [agentOverride] with a non-accessibility agent while
    // [driverTypeOverride] stays at the now-default ANDROID_ONDEVICE_ACCESSIBILITY; reading the
    // label there would build an accessibility-shaped tree that doesn't match the agent's
    // resolver. Reading the agent's [usesAccessibilityDriver] keeps screen-state and dispatch
    // aligned in both the default-construction and the agentOverride path.
    val isAccessibilityDriver = trailblazeAgent.usesAccessibilityDriver
    val migrationMode = InstrumentationArgUtil.shouldCaptureSecondaryTree()
    val deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers
    val base: ScreenState = when (
      chooseScreenStateKind(
        isAccessibilityDriver = isAccessibilityDriver,
        isMigrationMode = migrationMode,
        isAccessibilityServiceRunning = TrailblazeAccessibilityService.isServiceRunning(),
      )
    ) {
      ScreenStateKind.ACCESSIBILITY -> {
        TrailblazeAccessibilityService.waitForSettled()
        AccessibilityServiceScreenState(
          includeScreenshot = true,
          deviceClassifiers = deviceClassifiers,
        )
      }
      ScreenStateKind.UIAUTOMATOR -> AndroidOnDeviceUiAutomatorScreenState(
        includeScreenshot = true,
        deviceClassifiers = deviceClassifiers,
      )
    }
    // Migration-mode side-channel. When `trailblaze.captureSecondaryTree=true`, capture an
    // accessibility-shape tree alongside the (UiAutomator) primary state and ride it on
    // the [MigrationScreenState] decorator so the snapshot logger can persist it without
    // any change to ScreenState consumers. Off-mode returns the plain primary state.
    if (migrationMode) {
      xyz.block.trailblaze.api.MigrationScreenState.wrap(
        primary = base,
        driverMigrationTreeNode =
          xyz.block.trailblaze.android.accessibility.MigrationTreeCapture.captureOrNull(),
      )
    } else {
      base
    }
  }

  init {
    trailblazeLoggingRule.failureScreenStateProvider = screenStateProvider
  }

  private val elementComparator = TrailblazeElementComparator(
    screenStateProvider = screenStateProvider,
    llmClient = llmClient,
    trailblazeLlmModel = trailblazeLlmModel,
    toolRepo = trailblazeToolRepo,
  )

  override fun ruleCreation(description: Description) {
    super.ruleCreation(description)
  }

  /**
   * After the parent rule chain completes its setup (UiDevice, status-bar hiding, etc.),
   * enable the on-device [TrailblazeAccessibilityService]. Called unconditionally — not only
   * when the resolved driver is [TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY] — because
   * UiAutomator2 (the instrumentation driver's view-hierarchy source) reads the same OS
   * accessibility node tree that Jetpack Compose only populates when
   * `AccessibilityManager.isEnabled()` returns true in the app process. UiAutomation defaults
   * to `flags = 0` (suppresses all accessibility services), which forces `isEnabled()` to false
   * and collapses every Compose-rendered screen to a single bare `ComposeView` node with no
   * children. Calling [OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady]
   * reconnects UiAutomation with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, binds the
   * service, and flips `isEnabled()` so Compose builds its semantic tree — required for the
   * instrumentation driver on any Compose-heavy app, not only for the accessibility driver.
   *
   * For accessibility-driver runs the binding is also required so the
   * [AccessibilityTrailblazeAgent] constructed in [trailblazeAgent] can issue taps/swipes
   * (its first interaction would otherwise throw `TrailblazeAccessibilityService is not
   * running`). The service is declared in this module's manifest and merged into the consumer
   * APK, but Android only starts it after the host enables it in
   * `enabled_accessibility_services`.
   *
   * Setup must happen **after** any UiDevice/shell operations that could trigger UiAutomation
   * reconnections (which destroy a running accessibility service). The parent
   * [TrailblazeAndroidLoggingRule] does its UiDevice work in its own `beforeTestExecution`,
   * so calling super first and then enabling the service here is the correct order. Same
   * pattern as a downstream `AndroidTrailblazeRule` subclass's `beforeTestExecution`.
   */
  override fun beforeTestExecution(description: Description) {
    super.beforeTestExecution(description)
    // Bind unconditionally — needed for both the accessibility driver (taps/swipes) and the
    // instrumentation driver (Compose semantic-tree exposure under UiAutomator2). See kdoc above.
    OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady()
    // Bind for migration-mode capture (delegated to the shared helper). Subclasses that
    // insert their own UiDevice operations between this call and the test body
    // (e.g. a downstream subclass's `onBeforeTest`) MUST call the helper again *after*
    // those operations — UiAutomation reconnections destroy a running accessibility
    // service, so an early bind here can be silently torn down by the time
    // [MigrationTreeCapture] queries the service. Direct subclasses without intervening
    // UiDevice work are safe with just this call.
    xyz.block.trailblaze.android.accessibility.MigrationCaptureSetup.ensureAccessibilityBoundIfMigrationModeOn()
  }

  private val trailblazeYaml = createTrailblazeYaml(
    customTrailblazeToolClasses = customToolClasses?.allForSerializationTools() ?: setOf(),
  )

  /**
   * Agent implementation selected via instrumentation arg `-e trailblaze.agent`.
   * Defaults to [AgentImplementation.TRAILBLAZE_RUNNER] (legacy, stable).
   * Set to `MULTI_AGENT_V3` to opt into the multi-agent V3 architecture.
   */
  private val agentImplementation: AgentImplementation = InstrumentationArgUtil.agentImplementation()

  /**
   * Title of the trail currently executing (e.g. TestRail case name).
   *
   * Set at [runSuspend] entry before any step runs, then forwarded via
   * [caseTitleProvider] in the V3 runner so the inner agent receives the
   * test case title as overallObjective. This lets the agent detect impossible
   * steps early instead of exhausting retries.
   *
   * Thread-safety: JUnit creates a new rule instance per @Test method, so
   * only one [runSuspend] executes on a given instance at a time. @Volatile
   * provides the visibility guarantee when the lazy runner reads this field
   * from its coroutine thread.
   */
  @Volatile
  private var currentCaseTitle: String? = null

  private val trailblazeRunner: TestAgentRunner by lazy {
    when (agentImplementation) {
      AgentImplementation.MULTI_AGENT_V3 -> createV3Runner()
      else -> TrailblazeRunner(
        trailblazeToolRepo = trailblazeToolRepo,
        trailblazeLlmModel = trailblazeLlmModel,
        llmClient = llmClient,
        screenStateProvider = screenStateProvider,
        agent = trailblazeAgent,
        trailblazeLogger = trailblazeLoggingRule.logger,
        sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
        maxSteps = maxLlmCalls ?: TrailblazeRunner.DEFAULT_MAX_STEPS,
      )
    }
  }

  private fun createV3Runner(): MultiAgentV3TestAgentRunner {
    val samplingSource = KoogLlmSamplingSource(
      llmClient = llmClient,
      llmModel = trailblazeLlmModel,
    )
    val screenAnalyzer = InnerLoopScreenAnalyzer(
      samplingSource = samplingSource,
      model = trailblazeLlmModel,
    )
    val executor = AgentUiActionExecutor(
      agent = trailblazeAgent,
      screenStateProvider = screenStateProvider,
      toolRepo = trailblazeToolRepo,
      elementComparator = elementComparator,
    )
    val plannerLlmCall: PlannerLlmCall = { systemPrompt, userMessage, tools, _, screenshotBytes ->
      val metaInfo = RequestMetaInfo.create(KoogClock.System)
      val userMsg = if (screenshotBytes != null && screenshotBytes.isNotEmpty()) {
        Message.User(
          parts = buildList<MessagePart.RequestPart> {
            add(MessagePart.Text(userMessage))
            add(
              MessagePart.Attachment(
                source = AttachmentSource.Image(
                  content = AttachmentContent.Binary.Bytes(screenshotBytes),
                  format = ImageFormatDetector.detectFormat(screenshotBytes).mimeSubtype,
                ),
              )
            )
          },
          metaInfo = metaInfo,
        )
      } else {
        Message.User(content = userMessage, metaInfo = metaInfo)
      }
      val koogPrompt = Prompt(
        messages = listOf(Message.System(content = systemPrompt, metaInfo = metaInfo), userMsg),
        id = "android_test_planner",
        params = LLMParams(toolChoice = LLMParams.ToolChoice.Required),
      )
      val response = llmClient.execute(koogPrompt, trailblazeLlmModel.toKoogLlmModel(), tools)
      val toolCall = response.parts.filterIsInstance<MessagePart.Tool.Call>().firstOrNull()
      val toolName = toolCall?.tool ?: tools.firstOrNull()?.name ?: "unknown"
      val toolArgsJson = toolCall?.args ?: "{}"
      val toolArgs = try {
        Json.parseToJsonElement(toolArgsJson) as? JsonObject ?: JsonObject(emptyMap())
      } catch (_: Exception) {
        JsonObject(emptyMap())
      }
      PlannerToolCallResult.fromRaw(toolName, toolArgs)
    }
    val v3Runner = MultiAgentV3Runner.create(
      screenAnalyzer = screenAnalyzer,
      executor = executor,
      plannerLlmCall = plannerLlmCall,
      config = blazeConfig,
      deviceId = trailblazeDeviceId,
      availableToolsProvider = { trailblazeToolRepo.getCurrentToolDescriptors().map { it.toTrailblazeToolDescriptor() } },
    )
    var cachedFallbackSessionId: xyz.block.trailblaze.logs.model.SessionId? = null
    return MultiAgentV3TestAgentRunner(
      v3Runner = v3Runner,
      screenStateProvider = screenStateProvider,
      sessionIdProvider = {
        trailblazeLoggingRule.session?.sessionId ?: cachedFallbackSessionId ?: run {
          Console.error("⚠️ No active loggingRule session; generating fallback session ID")
          TrailblazeSessionManager.generateSessionId("android_test_fallback")
            .also { cachedFallbackSessionId = it }
        }
      },
      caseTitleProvider = { currentCaseTitle },
    )
  }

  val trailblazeRunnerUtil by lazy {
    // Per-tool screen-state capture for the deterministic Maestro→accessibility selector
    // migration. Off by default — wired only when both the dual-tree flag is set AND we're
    // running the accessibility driver (the only driver that produces the trailblazeNodeTree
    // the migration's hit-test needs). When wired, the hook fires immediately before each
    // recorded tool runs and writes a TrailblazeSnapshotLog with both view-hierarchy trees,
    // so `migrate-trail` has a per-tool pre-fire snapshot to resolve against — closing the
    // gap where multi-tool recordings only get one captured screen state per LLM round.
    val migrationCaptureEnabled =
      InstrumentationArgUtil.shouldCaptureSecondaryTree() &&
        trailblazeLoggingRule.driverTypeOverride == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    val onBeforeRecordedTool: (suspend (xyz.block.trailblaze.toolcalls.TrailblazeTool) -> Unit)? =
      if (migrationCaptureEnabled) {
        { tool: xyz.block.trailblaze.toolcalls.TrailblazeTool ->
          // Only fire the capture for the tools the migration actually rewrites. The
          // recording playback fires many non-selector tools (launchApp, custom flow tools)
          // for which a pre-fire snapshot would be wasted I/O + log size. Keep the filter
          // in lockstep with classNameFromYamlToolName in WaypointMigrateTrailCommand.
          val isMigrationTarget = tool is xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector ||
            tool is xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
          if (isMigrationTarget) {
            val accessibilityAgent = trailblazeAgent as? AccessibilityTrailblazeAgent
            val session = trailblazeLoggingRule.session
            if (accessibilityAgent != null && session != null) {
              // getScreenState() honors the captureSecondaryTree flag we plumbed through
              // AccessibilityServiceScreenState in Phase 2 — both viewHierarchy (UiAutomator
              // dump) and trailblazeNodeTree (accessibility tree) are populated.
              val screen = accessibilityAgent.getScreenState()
              trailblazeLoggingRule.logger.logSnapshot(
                session = session,
                screenState = screen,
                displayName = "preTool: ${tool::class.simpleName ?: "unknown"}",
              )
            }
          }
        }
      } else null

    TrailblazeRunnerUtil(
      trailblazeRunner = trailblazeRunner,
      runTrailblazeTool = ::runTrailblazeTool,
      trailblazeLogger = trailblazeLoggingRule.logger,
      sessionProvider = { trailblazeLoggingRule.session ?: error("Session not available - ensure test is running") },
      sessionUpdater = { trailblazeLoggingRule.setSession(it) },
      onBeforeRecordedTool = onBeforeRecordedTool,
      // Pre-wired even though postcondition assertion is dormant on this code path today
      // (screenStateProvider/waypointResolver are left null) — the moment the rule enables
      // postconditions, templated waypoints (`{{target.appId}}`) need to expand correctly.
      // Threading it now means turning the asserter on later is a one-line change, not a
      // hunt for every TrailblazeRunnerUtil call site.
      target = resolvedTargetForSession?.let {
        xyz.block.trailblaze.api.TargetTemplateContext(appId = appIdForSession, appIds = it.appIds)
      },
    )
  }

  /**
   * Returns the last successfully-executed tool's [TrailblazeToolResult.Success] (carrying
   * `message` + `structuredContent`), or `null` if the trail produced no Success. The on-device
   * [xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler] mirrors this onto the RPC response
   * envelope so a host-side scripted-tool author composing dual-mode primitives via
   * `client.callTool(...)` over the RPC path receives the same payload they would from the direct
   * host-side actual.
   */
  suspend fun runSuspend(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
    sendSessionStartLog: Boolean,
  ): TrailblazeToolResult.Success? {
    // Resolve device classifiers BEFORE decoding so a v3 trail lowers with the
    // right closest-wins recording for this on-device runner. v1 inputs ignore
    // the list. The guard in decodeTrail throws if we ever lose classifiers and
    // a v3 file has recordings, so the silent-LLM-fallback can't happen here.
    val classifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers
    val trailItems = trailblazeYaml.decodeTrail(testYaml, deviceClassifiers = classifiers)
    val trailConfig = trailblazeYaml.extractTrailConfig(trailItems)

    // Honor `config.skip:` before sending SessionStarted — matches the CLI's pre-flight
    // `planTrailExecution` planner, which short-circuits Skip items without dispatching them
    // to the device runner. Skipping here (instead of inside the per-item loop) means no
    // session, QuickJS launch, or LLM round-trip ever happens for a skip-marked trail run
    // through this rule.
    trailblazeYaml.firstSkipReason(trailItems)?.let { skipReason ->
      Console.log(
        "[Trailblaze] Skipping trail" + (trailFilePath?.let { " ($it)" } ?: "") + ": $skipReason"
      )
      return null
    }

    if (sendSessionStartLog) {
      val currentSession = trailblazeLoggingRule.session
        ?: error("Session not available when sendSessionStartLog=true. Ensure this rule is used as a @Rule in a JUnit test.")

      trailblazeLoggingRule.logger.log(
        currentSession,
        TrailblazeLog.TrailblazeSessionStatusChangeLog(
          sessionStatus = SessionStatus.Started(
            trailConfig = trailConfig,
            trailFilePath = trailFilePath,
            testClassName = trailblazeLoggingRule.description?.className ?: "AndroidTrailblazeRule",
            testMethodName = trailblazeLoggingRule.description?.methodName ?: "run",
            trailblazeDeviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
            rawYaml = testYaml,
            hasRecordedSteps = trailblazeYaml.hasRecordedSteps(trailItems),
            trailblazeDeviceId = trailblazeDeviceId,
          ),
          session = currentSession.sessionId,
          timestamp = Clock.System.now(),
        ),
      )
    }
    trailblazeAgent.clearMemory()

    // Extract title before the loop so V3's caseTitleProvider sees it on every step.
    // Scans eagerly (not item-order dependent) so the title is available even when
    // the config block appears after the first prompts block in the YAML.
    currentCaseTitle = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()?.config?.title

    if (!trailblazeYaml.hasActionableSteps(trailItems)) {
      val trailName = trailConfig?.title ?: trailFilePath ?: "unknown"
      val trailUrl = trailConfig?.metadata?.get("testRailUrl")
      throw TrailblazeException(
        "Trail '$trailName' has no executable steps — this would be a false positive pass. " +
          "Add prompts or tool steps to this trail file." +
          (trailUrl?.let { " $it" } ?: ""),
      )
    }

    // PR A5: launch the target-declared MCP bundles at session start, so advertised tools
    // are registered into [trailblazeToolRepo] before the LLM selects a tool. Tear down in
    // the `finally` so subprocess teardown still runs even if a trail step throws — the
    // QuickJS bundles register tools into [trailblazeToolRepo] at session start and tear
    // them down at session end. `withContext(NonCancellable)` on teardown so a cancelled
    // trail (timeout, abort, user cancel) still runs through to completion rather than
    // cancelling at the first suspension point inside the bundle's shutdown — without it,
    // a cancelled run would leak the QuickJS native allocation plus the dynamic-tool
    // registrations into the next session's tool repo.
    var launchedQuickjsRuntime: LaunchedQuickJsToolRuntime? = null

    // Track the last `Success` across all trail items so the on-device handler can mirror its
    // `message` / `structuredContent` onto the RPC response envelope. Mirrors
    // `BaseTrailblazeAgent.runTrailblazeTools`'s own `lastSuccessResult` semantics — the trail's
    // terminal Success is what the host's `client.callTool(...)` consumer expects.
    var lastToolSuccess: TrailblazeToolResult.Success? = null
    try {
      if (quickjsToolBundles.isNotEmpty()) {
        launchedQuickjsRuntime = QuickJsToolBundleLauncher.launchAll(
          bundles = quickjsToolBundles,
          deviceInfo = trailblazeLoggingRule.trailblazeDeviceInfoProvider(),
          sessionId = (trailblazeLoggingRule.session
            ?: error("Session not available for QuickJS bundle launch")).sessionId,
          toolRepo = trailblazeToolRepo,
          bundleSourceResolver = quickjsBundleSourceResolver,
        )
      }

      trailItems.forEach { item ->
        val itemResult = when (item) {
          is TrailYamlItem.PromptsTrailItem ->
            trailblazeRunnerUtil.runPrompt(
              prompts = item.promptSteps,
              useRecordedSteps = useRecordedSteps,
              selfHeal = config.selfHeal,
            )
          is TrailYamlItem.ToolTrailItem -> runTrailblazeTool(item.tools.map { it.trailblazeTool })
          is TrailYamlItem.ConfigTrailItem -> handleConfig(item.config)
        }
        if (itemResult is TrailblazeToolResult.Error) {
          throw TrailblazeException(itemResult.errorMessage)
        }
        // Only adopt a Success as the trail's last-tool payload if it actually carries data —
        // `handleConfig(...)` and prompt-steps that resolve to an empty `Success()` would
        // otherwise clobber a previous tool's payload (e.g. trail = [adbShell→stdout, config]
        // would surface `toolMessage=null` instead of the adbShell stdout). The `toolMessage` /
        // `toolStructuredContent` mirror is specifically the LAST DATA-RETURNING Success — empty
        // Success() values produced by non-tool items must not overwrite a payload-bearing one.
        if (itemResult is TrailblazeToolResult.Success && itemResult.carriesPayload()) {
          lastToolSuccess = itemResult
        }
      }
    } finally {
      launchedQuickjsRuntime?.let { runtime ->
        withContext(NonCancellable) {
          runCatching { runtime.shutdownAll() }
        }
      }
    }
    return lastToolSuccess
  }

  override fun run(
    testYaml: String,
    trailFilePath: String?,
    useRecordedSteps: Boolean,
  ) {
    // [TrailblazeRule.run] returns Unit — discard `runSuspend`'s `Success` payload (only the
    // RPC handler path threads that back through the response envelope).
    runBlocking {
      runSuspend(
        testYaml = testYaml,
        trailFilePath = trailFilePath,
        useRecordedSteps = useRecordedSteps,
        sendSessionStartLog = true,
      )
    }
  }

  private fun runTrailblazeTool(trailblazeTools: List<TrailblazeTool>): TrailblazeToolResult =
    trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTools,
      screenState = screenStateProvider(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    ).result

  @Deprecated("Prefer the suspend version.")
  private fun runMaestroCommandsBlocking(maestroCommands: List<Command>): TrailblazeToolResult =
    runBlocking { runMaestroCommands(maestroCommands) }

  private suspend fun runMaestroCommands(maestroCommands: List<Command>): TrailblazeToolResult {
    return when (
      val maestroResult =
        trailblazeAgent.runMaestroCommands(
          maestroCommands = maestroCommands,
          traceId = null,
        )
    ) {
      is TrailblazeToolResult.Success -> maestroResult
      is TrailblazeToolResult.Error -> throw TrailblazeException(maestroResult.errorMessage)
    }
  }

  private fun handleConfig(config: TrailConfig): TrailblazeToolResult {
    config.context?.let { trailblazeRunner.appendToSystemPrompt(it) }
    return TrailblazeToolResult.Success()
  }

  /**
   * Run natural language instructions with the agent.
   */
  override fun prompt(objective: String): Boolean {
    val runnerResult = trailblazeRunner.run(DirectionStep(objective))
    return if (runnerResult is AgentTaskStatus.Success) {
      true
    } else {
      throw TrailblazeException(runnerResult.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override fun tool(vararg trailblazeTool: TrailblazeTool): TrailblazeToolResult {
    val result = trailblazeAgent.runTrailblazeTools(
      tools = trailblazeTool.toList(),
      elementComparator = elementComparator,
      screenStateProvider = screenStateProvider,
    ).result
    return if (result is TrailblazeToolResult.Success) {
      result
    } else {
      throw TrailblazeException(result.toString())
    }
  }

  /**
   * Run a Trailblaze tool with the agent.
   */
  override suspend fun maestroCommands(vararg maestroCommand: Command): TrailblazeToolResult {
    val runCommandsResult = trailblazeAgent.runMaestroCommands(
      maestroCommand.toList(),
      null,
    )
    return if (runCommandsResult is TrailblazeToolResult.Success) {
      runCommandsResult
    } else {
      throw TrailblazeException(runCommandsResult.toString())
    }
  }

  fun runFromAsset(
    yamlAssetPath: String = TrailblazeYamlUtil.calculateTrailblazeYamlAssetPathFromStackTrace(
      AndroidAssetsUtil::assetExists,
    ),
    forceStopApp: Boolean = true,
    useRecordedSteps: Boolean = true,
    targetAppId: String? = null,
  ) {
    val computedAssetPath: String = TrailRecordings.findBestTrailResourcePath(
      path = yamlAssetPath,
      deviceClassifiers = trailblazeLoggingRule.trailblazeDeviceInfoProvider().classifiers,
      doesResourceExist = AndroidAssetsUtil::assetExists,
    ) ?: throw TrailblazeException("Asset not found: $yamlAssetPath")
    Console.log("Running from asset: $computedAssetPath")
    if (forceStopApp && targetAppId != null) {
      AdbCommandUtil.forceStopApp(targetAppId)
    }
    val yamlContent = AndroidAssetsUtil.readAssetAsString(computedAssetPath)
    run(
      testYaml = yamlContent,
      useRecordedSteps = useRecordedSteps,
      trailFilePath = yamlAssetPath,
    )
  }

  companion object {
    /**
     * Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)
     *
     * NOTE: It would be better to pass these values as instrumentation args if possible
     */
    @Deprecated("Only use this on-device when no deviceId is available (like in a connectedDebugAndroidTest)")
    val DEFAULT_JUNIT_TEST_ANDROID_ON_DEVICE_TRAILBLAZE_DEVICE_ID = TrailblazeDeviceId(
      instanceId = TrailblazeDriverType.DEFAULT_ANDROID.name,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
  }
}

/**
 * `true` when this [TrailblazeToolResult.Success] carries data the on-device-RPC return path
 * should mirror onto [xyz.block.trailblaze.llm.RunYamlResponse.toolMessage] /
 * [xyz.block.trailblaze.llm.RunYamlResponse.toolStructuredContent]. Empty `Success()` values
 * (produced by `handleConfig(...)`, by prompt-steps whose recorded path resolves without a
 * narrative, and by action-style tools whose return value is just a verdict) are NOT payload-
 * bearing and must not overwrite a previous data-returning Success in the trail-item fold.
 *
 * Pulled out to a top-level extension so the fold logic in [AndroidTrailblazeRule.runSuspend]
 * is unit-testable without standing up the full Android instrumentation harness.
 */
internal fun TrailblazeToolResult.Success.carriesPayload(): Boolean =
  message != null || structuredContent != null
