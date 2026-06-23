package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Rule
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlCallbackResult
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.model.CustomTrailblazeTools
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Base standalone runner test for On-Device Android Trailblaze Tests
 */
abstract class BaseAndroidStandaloneServerTest {

  /**
   * We are using a typical [TrailblazeAndroidLoggingRule] but are using it on-demand in this case
   *
   * We don't know the deviceId when the test starts, so we need to store it somewhere for the device id provider
   *
   * This must be set before a test is run
   */
  protected lateinit var trailblazeDeviceId: TrailblazeDeviceId

  @get:Rule
  val trailblazeLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceIdProvider = { trailblazeDeviceId },
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

  protected fun cancelAnyActiveRuns() {
    runTestCoroutineScope?.cancel()
    runTestCoroutineScope = null
  }

  fun startInTestCoroutineScope(work: suspend () -> Unit) {
    cancelAnyActiveRuns()
    val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
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
  val onDeviceRpcPort =
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT

  /**
   * Creates a callback for running YAML-based tests via TrailblazeRunner.
   *
   * This callback handles session lifecycle management around [handleRunRequest].
   * Use this when constructing an [OnDeviceRpcServer] in subclasses.
   */
  fun createRunTrailblazeYamlCallback(): suspend (RunYamlRequest, TrailblazeSession, AgentMemory) -> RunYamlCallbackResult =
    { runYamlRequest: RunYamlRequest, session: TrailblazeSession, agentMemory: AgentMemory ->
      // Set the session on the logging rule so it's available to all components
      // that use sessionProvider (AndroidTrailblazeRule and its subcomponents)
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
        trailblazeLoggingRule.withLogObserver(toolLogCounter) {
          handleRunRequest(runYamlRequest, agentMemory)
        }
      } finally {
        // Clear the session after execution to prevent stale sessions
        trailblazeLoggingRule.setSession(null)
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
