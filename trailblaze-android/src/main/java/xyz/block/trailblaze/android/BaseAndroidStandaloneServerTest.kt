package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel

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
    CoroutineScope(Dispatchers.IO).also { coroutineScope ->
      cancelAnyActiveRuns()
      runTestCoroutineScope = coroutineScope
      runBlocking {
        work()
      }
    }
  }

  abstract fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient

  abstract fun getDeviceClassifiers(): List<TrailblazeDeviceClassifier>

  val adbReversePort =
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ADB_REVERSE_PORT
}
