package xyz.block.trailblaze

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.runner.Description
import xyz.block.trailblaze.android.InstrumentationArgUtil
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.rules.SimpleTestRule
import xyz.block.trailblaze.tracing.TrailblazeTracer

class TrailblazeAndroidLoggingRule : SimpleTestRule() {

  private var startTime: Instant = Clock.System.now()

  private val isServerAvailable = runBlocking {
    val startTime = Clock.System.now()
    val isRunning = trailblazeLogServerClient.isServerRunning()
    println("isServerAvailable [$isRunning] took ${Clock.System.now() - startTime}ms")
    if (!isRunning) {
      println(
        "Log Server is not available at ${trailblazeLogServerClient.baseUrl}. Run with ./gradlew :trailblaze-server:run",
      )
    }
    isRunning
  }

  override fun ruleCreation(description: Description) {
    currentTestName = description.toTestName()
    TrailblazeLogger.startSession(currentTestName)
    subscribeToLoggingEventsAndSendToServer(
      sendOverHttp = isServerAvailable,
      writeToDisk = !isServerAvailable,
    )
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          testClassName = description.className,
          testMethodName = description.methodName,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      ),
    )
  }

  override fun beforeTestExecution(description: Description) {
    TrailblazeTracer.clear()
    super.beforeTestExecution(description)
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    val nowMs = Clock.System.now().toEpochMilliseconds()
    val testEndedLog = if (result.isSuccess) {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(
          durationMs = nowMs - startTime.toEpochMilliseconds(),
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      )
    } else {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Failed(
          durationMs = nowMs - startTime.toEpochMilliseconds(),
          exceptionMessage = result.exceptionOrNull()?.message,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = Clock.System.now(),
      )
    }
    TrailblazeLogger.log(testEndedLog)

    exportTraces()
  }

  companion object {
    private const val LOGS_DIR = "trailblaze-logs"

    lateinit var currentTestName: String
      private set

    val trailblazeLogServerClient = TrailblazeLogServerClient(
      httpClient = TrailblazeHttpClientFactory.createInsecureTrustAllCertsHttpClient(
        timeoutInSeconds = 5,
      ),
      baseUrl = InstrumentationArgUtil.logsEndpoint(),
    )

    fun subscribeToLoggingEventsAndSendToServer(
      sendOverHttp: Boolean,
      writeToDisk: Boolean,
    ) {
      TrailblazeLogger.setLogScreenshotListener { screenshotBytes ->
        val screenshotFileName = "${currentTestName}_${Clock.System.now().toEpochMilliseconds()}.png"
        // Send Log
        runBlocking(Dispatchers.IO) {
          if (sendOverHttp) {
            val logResult = trailblazeLogServerClient.postScreenshot(
              screenshotFilename = screenshotFileName,
              sessionId = TrailblazeLogger.getCurrentSessionId(),
              screenshotBytes = screenshotBytes,
            )
            if (logResult.status.value != 200) {
              println("Error while posting agent log: ${logResult.status.value}")
            }
          }
          if (writeToDisk) {
            writeScreenshotToDisk(screenshotFileName, screenshotBytes)
          }
        }
        screenshotFileName
      }
      TrailblazeLogger.setLogListener { log: TrailblazeLog ->
        // Send Log
        runBlocking(Dispatchers.IO) {
          if (sendOverHttp) {
            val logResult = trailblazeLogServerClient.postAgentLog(log)
            if (logResult.status.value != 200) {
              println("Error while posting agent log: ${logResult.status.value}")
            }
          }
          if (writeToDisk) {
            writeLogToDisk(log)
          }
        }
      }
    }

    private fun writeScreenshotToDisk(fileName: String, bytes: ByteArray) {
      try {
        FileReadWriteUtil.writeToDownloadsFile(
          context = InstrumentationRegistry.getInstrumentation().context,
          fileName = fileName,
          contentBytes = bytes,
          directory = LOGS_DIR,
        )
      } catch (e: Exception) {
        println("Error writing screenshot to disk: ${e.message}")
      }
    }

    private fun writeLogToDisk(log: TrailblazeLog) {
      try {
        val json = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
        val fileName = "${currentTestName}_${log.timestamp.toEpochMilliseconds()}.json"
        FileReadWriteUtil.writeToDownloadsFile(
          context = InstrumentationRegistry.getInstrumentation().context,
          fileName = fileName,
          contentBytes = json.toByteArray(),
          directory = LOGS_DIR,
        )
      } catch (e: Exception) {
        println("Error writing log to disk: ${e.message}")
      }
    }

    private fun exportTraces() {
//      val traceEventsJson = TrailblazeTracer.exportJson()
//      withInstrumentation {
//        FileReadWriteUtil.writeToDownloadsFile(
//          context = context,
//          fileName = "${TrailblazeLogger.getCurrentSessionId()}-trace.json",
//          contentBytes = traceEventsJson.toByteArray(),
//          directory = LOGS_DIR,
//        )
//      }
    }
  }
}
