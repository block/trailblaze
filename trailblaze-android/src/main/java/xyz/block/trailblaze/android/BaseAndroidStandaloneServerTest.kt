package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.model.CustomTrailblazeTools

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


  abstract fun handleRunRequest(runYamlRequest: RunYamlRequest)

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

  val adbReversePort =
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT

  /**
   * Creates a callback for running YAML-based tests via TrailblazeRunner.
   *
   * This callback handles session lifecycle management around [handleRunRequest].
   * Use this when constructing an [OnDeviceRpcServer] in subclasses.
   */
  fun createRunTrailblazeYamlCallback(): suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession =
    { runYamlRequest: RunYamlRequest, session: TrailblazeSession ->
      // Set the session on the logging rule so it's available to all components
      // that use sessionProvider (AndroidTrailblazeRule and its subcomponents)
      trailblazeLoggingRule.setSession(session)
      try {
        handleRunRequest(runYamlRequest)
      } finally {
        // Clear the session after execution to prevent stale sessions
        trailblazeLoggingRule.setSession(null)
      }
      session // Return the session unchanged
    }

}
