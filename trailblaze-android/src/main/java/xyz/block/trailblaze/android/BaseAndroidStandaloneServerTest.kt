package xyz.block.trailblaze.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel

/**
 * Base standalone runner test for On-Device Android Trailblaze Tests
 */
abstract class BaseAndroidStandaloneServerTest {

  @get:Rule
  val trailblazeLoggingRule = TrailblazeAndroidLoggingRule(
    trailblazeDeviceClassifiersProvider = getDeviceClassifiersProvider()
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
      coroutineScope.launch {
        work()
      }
    }
  }

  abstract fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient

  abstract fun getDeviceClassifiersProvider(): (() -> List<TrailblazeDeviceClassifier>)

  val adbReversePort =
    InstrumentationArgUtil.getInstrumentationArg(TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY)?.toInt()
      ?: TrailblazeDevicePort.DEFAULT_ADB_REVERSE_PORT
}
