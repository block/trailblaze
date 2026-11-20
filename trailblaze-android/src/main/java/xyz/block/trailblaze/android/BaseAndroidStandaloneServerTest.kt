package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlCallbackResult
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.internal.recordingClassifiersFor

/**
 * Base standalone runner test for On-Device Android Trailblaze Tests
 */
abstract class BaseAndroidStandaloneServerTest internal constructor(
  loggingRule: TrailblazeAndroidLoggingRule?,
  rpcPort: Int?,
) {
  constructor() : this(null, null)


  /**
   * We are using a typical [TrailblazeAndroidLoggingRule] but are using it on-demand in this case
   *
   * We don't know the deviceId when the test starts, so we need to store it somewhere for the device id provider
   *
   * This must be set before a test is run
   */
  protected lateinit var trailblazeDeviceId: TrailblazeDeviceId

  private var preparingDeviceId: TrailblazeDeviceId? = null

  @get:Rule
  val trailblazeLoggingRule = loggingRule ?: TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { preparingDeviceId ?: trailblazeDeviceId },
    trailblazeDeviceClassifiersProvider = { getDeviceClassifiers() }
  )


  /**
   * Handle a single [RunYamlRequest] on-device. The [agentMemory] is the instance supplied
   * by the caller; in production it's pre-populated upstream by `RunYamlRequestHandler`
   * from `request.memorySnapshot` before [createRunTrailblazeYamlCallback] forwards it
   * here. Implementations should thread it into the constructed agent via
   * `AndroidTrailblazeRule(agentMemoryOverride = ...)` so writes from on-device tools
   * land in the same instance the caller reads from after this returns.
   *
   * Returns the last successfully-executed tool's
   * [TrailblazeToolResult.Success] (or `null` if the trail's items produced no Success — every
   * tool errored, the trail had no actionable steps, etc.). The callback wrapper
   * ([createRunTrailblazeYamlCallback]) bundles this into the [RunYamlCallbackResult] the
   * upstream handler propagates onto [xyz.block.trailblaze.llm.RunYamlResponse.toolMessage] /
   * [xyz.block.trailblaze.llm.RunYamlResponse.toolStructuredContent], so a host-side scripted-tool
   * author composing dual-mode primitives over RPC receives the same payload the host-side actual
   * would have produced.
   */
  abstract fun handleRunRequest(runYamlRequest: RunYamlRequest, agentMemory: AgentMemory): TrailblazeToolResult.Success?

  protected var runTestCoroutineScope: CoroutineScope? = null

  @Volatile private var executionJob: Job? = null

  protected fun cancelAnyActiveRuns() {
    runTestCoroutineScope?.cancel()
    runTestCoroutineScope = null
  }

  fun startInTestCoroutineScope(work: suspend () -> Unit) {
    cancelAnyActiveRuns()
    val job = Job()
    executionJob = job
    val coroutineScope = CoroutineScope(Dispatchers.IO + job)
    runTestCoroutineScope = coroutineScope
    // Use runBlocking within the scope so the calling suspend function blocks until
    // work completes, while still being cancellable via runTestCoroutineScope.cancel().
    runBlocking(coroutineScope.coroutineContext) {
      work()
    }
  }

  abstract fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient

  abstract fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier>

  /**
   * Returns custom Trailblaze tools for the specified target app.
   *
   * Subclasses can override this to provide app-specific tools that will be
   * registered with the agent's tool repository.
   *
   * @param targetAppName The name of the target app (e.g., "myApp", "anotherApp")
   * @return Custom tools for the app, or null if no custom tools are needed
   */
  open fun getCustomToolsForTargetApp(targetAppName: String?): CustomTrailblazeTools? = null

  /**
   * The port the on-device RPC server binds to inside the runner instrumentation. The host
   * bridges to this port (today via `adb forward`) so calls to localhost:<port> on the host
   * reach this server. Resolved from the instrumentation arg when present, falling back to
   * [TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT].
   */
  val onDeviceRpcPort = rpcPort ?:
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT

  /**
   * Creates a callback for running YAML-based tests through [AndroidTrailblazeRule].
   *
   * This callback handles session lifecycle management around [handleRunRequest].
   * Use this when constructing an [OnDeviceRpcServer] in subclasses.
   */
  private var runtimeSession: Pair<SessionId, TrailblazeDeviceId>? = null

  private var preparedDeviceInfo: TrailblazeDeviceInfo? = null

  protected fun prepareRunYamlRequest(request: RunYamlRequest) {
    val identity = request.config.overrideSessionId?.let {
      SessionId.sanitized(it.value) to request.trailblazeDeviceId
    }
    val continues = identity != null && runtimeSession == identity
    val bare = createTrailblazeYaml().isBareToolEnvelope(request.yaml)
    val nextOverride = when {
      !bare -> request.deviceClassifierOverride.map(::TrailblazeDeviceClassifier)
      !continues -> emptyList()
      else -> trailblazeLoggingRule.deviceClassifiersOverride
    }
    val selection = trailblazeLoggingRule.selectionClassifiersFor(nextOverride)
    val runtimeSelection = if (bare && request.deviceClassifierOverride.isNotEmpty()) {
      request.deviceClassifierOverride.map(::TrailblazeDeviceClassifier)
    } else selection
    val nextRecording = standaloneRecordingClassifiers(
      request, continues, runtimeSelection,
      trailblazeLoggingRule.recordingClassifiers.takeIf { continues && bare },
    )
    // Read physical info for this request without replacing the currently installed identity.
    preparingDeviceId = request.trailblazeDeviceId
    val nextInfo = try {
      trailblazeLoggingRule.deviceInfoFor(selection, nextRecording)
        .copy(trailblazeDeviceId = request.trailblazeDeviceId)
    } finally {
      preparingDeviceId = null
    }
    val providerInfo = if (bare) nextInfo else nextInfo.copy(classifiers = selection)
    trailblazeDeviceId = request.trailblazeDeviceId
    trailblazeLoggingRule.deviceClassifiersOverride = nextOverride
    trailblazeLoggingRule.recordingClassifiers = nextRecording
    preparedDeviceInfo = providerInfo
    runtimeSession = identity
  }

  protected fun preparedRpcDeviceInfo(): TrailblazeDeviceInfo = checkNotNull(preparedDeviceInfo)

  fun createRunTrailblazeYamlCallback(): suspend (RunYamlRequest, TrailblazeSession, AgentMemory) -> RunYamlCallbackResult =
    { runYamlRequest: RunYamlRequest, session: TrailblazeSession, agentMemory: AgentMemory ->
      // Set the session on the logging rule so it's available to all components
      // that use sessionProvider (AndroidTrailblazeRule and its subcomponents)
      runtimeSession = session.sessionId to runYamlRequest.trailblazeDeviceId
      trailblazeLoggingRule.setSession(session)
      // Count the `TrailblazeToolLog`s this dispatch emits so the host RPC agent can skip its
      // own catch-all tool-log emit when the device already logged the tool — otherwise the
      // single execution renders twice in the session report (#3818). Scoped to this one
      // request via `withLogObserver`; on-device runs are serialized, so the observer only sees
      // logs from this dispatch.
      val toolLogCount = AtomicInteger(0)
      val toolLogCounter: (TrailblazeLog) -> Unit = { log ->
        if (log is TrailblazeLog.TrailblazeToolLog) toolLogCount.incrementAndGet()
      }
      val lastToolSuccess: TrailblazeToolResult.Success? = try {
        // Cancelling the outer RPC interrupts runBlocking and cancels its actual trail job.
        runInterruptible {
          trailblazeLoggingRule.withLogObserver(toolLogCounter) {
            handleRunRequest(runYamlRequest, agentMemory)
          }
        }
      } finally {
        // Interrupting runBlocking may return before its IO child finishes teardown.
        // Keep the session installed and replacements excluded until that child has stopped.
        try {
          withContext(NonCancellable) { executionJob?.cancelAndJoin() }
        } finally {
          executionJob = null
          runTestCoroutineScope = null
          trailblazeLoggingRule.setSession(null)
        }
      }
      // Session passes through unchanged; memory writes flow through the shared instance.
      // Tool payload is mirrored into the response envelope by the handler.
      RunYamlCallbackResult(
        session = session,
        lastToolSuccess = lastToolSuccess,
        onDeviceToolLogCount = toolLogCount.get(),
      )
    }

}

/** Runtime identity prepared at standalone request entry before provider use or registration. */
internal fun standaloneRecordingClassifiers(
  request: RunYamlRequest,
  sameSession: Boolean,
  selection: List<TrailblazeDeviceClassifier>,
  previous: List<TrailblazeDeviceClassifier>?,
): List<TrailblazeDeviceClassifier>? {
  val codec = createTrailblazeYaml()
  // The handler owns malformed-YAML diagnostics and its failed execution response.
  return codec.recordingClassifiersFor(
    request.yaml, selection,
    current = if (sameSession && request.deviceClassifierOverride.isEmpty()) previous else selection,
    tolerateMalformedYaml = true,
  )
}
