package xyz.block.trailblaze.rules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.runner.Description
import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.client.TrailblazeLoggerInstance
import xyz.block.trailblaze.session.TrailblazeSessionManager
import xyz.block.trailblaze.tracing.TrailblazeTracer

/**
 * Base JUnit4 Logging Rule for Trailblaze Tests
 */
abstract class TrailblazeLoggingRule(
  private val logsBaseUrl: String = "https://localhost:8443",
  private val writeLogToDisk: ((currentTestName: String, log: TrailblazeLog) -> Unit) = { _, _ -> },
  private val writeScreenshotToDisk: ((sessionId: String, fileName: String, bytes: ByteArray) -> Unit) = { _, _, _ -> },
  private val writeTraceToDisk: ((sessionId: String, json: String) -> Unit) = { _, _ -> },
  val sessionManager: TrailblazeSessionManager = TrailblazeSessionManager(),
) : SimpleTestRule() {

  abstract val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo

  /**
   * Logger instance for this test run
   */
  val trailblazeLogger = TrailblazeLoggerInstance

  val trailblazeLogServerClient by lazy {
    TrailblazeLogServerClient(
      httpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
        timeoutInSeconds = 5,
      ),
      baseUrl = logsBaseUrl,
    )
  }

  private val isServerAvailable by lazy {
    val startTime = Clock.System.now()
    val isRunning = runBlocking { trailblazeLogServerClient.isServerRunning() }
    println("isServerAvailable [$isRunning] took ${Clock.System.now() - startTime}ms")
    if (!isRunning) {
      println(
        "Log Server is not available at ${trailblazeLogServerClient.baseUrl}. Run with ./gradlew :trailblaze-server:run",
      )
    }
    isRunning
  }

  var description: Description? = null
    private set

  override fun ruleCreation(description: Description) {
    this.description = description
    trailblazeLogger.startSession("${description.testClass.canonicalName}_${description.methodName}")
    sessionManager.startSession(trailblazeLogger.getCurrentSessionId())
  }

  override fun beforeTestExecution(description: Description) {
    TrailblazeTracer.clear()
    super.beforeTestExecution(description)
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    // Use consolidated method that checks session manager for max calls limit
    trailblazeLogger.sendSessionEndLog(
      sessionManager = sessionManager,
      isSuccess = result.isSuccess,
      exception = result.exceptionOrNull(),
    )
    exportTraces()
    sessionManager.endSession()
  }

  private fun exportTraces() {
    val traceEventsJson = TrailblazeTracer.exportJson()
    writeTraceToDisk(trailblazeLogger.getCurrentSessionId(), traceEventsJson)
  }

  fun subscribeToLoggingEventsAndSendToServer() {
    trailblazeLogger.setLogScreenshotListener { screenshotBytes ->
      val sessionId = trailblazeLogger.getCurrentSessionId()
      val imageFormat = ImageFormatDetector.detectFormat(screenshotBytes)
      val screenshotFileName = "${sessionId}_${Clock.System.now().toEpochMilliseconds()}.${imageFormat.fileExtension}"
      // Send Log
      runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          val logResult = trailblazeLogServerClient.postScreenshot(
            screenshotFilename = screenshotFileName,
            sessionId = sessionId,
            screenshotBytes = screenshotBytes,
          )
          if (logResult.status.value != 200) {
            println("Error while posting agent log: ${logResult.status.value}")
          }
        } else {
          writeScreenshotToDisk(sessionId, screenshotFileName, screenshotBytes)
        }
      }
      screenshotFileName
    }
    trailblazeLogger.setLogListener { log: TrailblazeLog ->
      val sessionId = trailblazeLogger.getCurrentSessionId()
      // Send Log
      runBlocking(Dispatchers.IO) {
        if (isServerAvailable) {
          val logResult = trailblazeLogServerClient.postAgentLog(log)
          if (logResult.status.value != 200) {
            println("Error while posting agent log: ${logResult.status.value}")
          }
        } else {
          writeLogToDisk(sessionId, log)
        }
      }
    }
  }

  init {
    // Ensure that logging has been subscribed to immediately
    subscribeToLoggingEventsAndSendToServer()
  }
}
