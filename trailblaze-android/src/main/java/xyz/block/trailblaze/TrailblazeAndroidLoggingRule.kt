package xyz.block.trailblaze

import android.annotation.SuppressLint
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.Description
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TrailblazeAndroidLoggingRule : SimpleTestRule() {

  private var startTime: Long = System.currentTimeMillis()

  private val isServerAvailable = runBlocking {
    val startTime = System.currentTimeMillis()
    val isRunning = trailblazeLogServerClient.isServerRunning()
    println("isServerAvailable [$isRunning] took ${System.currentTimeMillis() - startTime}ms")
    if (!isRunning) {
      println(
        "Log Server is not available at ${trailblazeLogServerClient.baseUrl}. Run with ./gradlew :trailblaze-server:run",
      )
    }
    isRunning
  }

  override fun ruleCreation(description: Description) {
    recordLogs.clear()
    currentTestName = description.toTestName()
    TrailblazeLogger.startSession(currentTestName)
    subscribeToLoggingEventsAndSendToServer(
      sendOverHttp = isServerAvailable,
      writeToDisk = !isServerAvailable,
    )
    TrailblazeLogger.log(
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started,
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      ),
    )
  }

  override fun afterTestExecution(description: Description, result: Result<Nothing?>) {
    val testEndedLog = if (result.isSuccess) {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Succeeded(
          duration = System.currentTimeMillis() - startTime,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      )
    } else {
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Ended.Failed(
          duration = System.currentTimeMillis() - startTime,
          exceptionMessage = result.exceptionOrNull()?.message,
        ),
        session = TrailblazeLogger.getCurrentSessionId(),
        timestamp = System.currentTimeMillis(),
      )
    }
    TrailblazeLogger.log(testEndedLog)
    saveRecording(description)
  }

  private fun saveRecording(description: Description) {
    try {
      // Don't save empty recording
      if (recordLogs.isEmpty()) return
      val instructionMap: Map<String, List<TrailblazeTool>> = recordLogs
        .groupBy { it.instructions }
        .mapValues { mapEntry ->
          mapEntry.value.map {
            it.command
          }
        }
      recordLogs.clear()

      val recordingJson = TrailblazeJsonInstance.encodeToString(instructionMap)
      val fileName = "${description.toTestName()}.json"
      // This saves the test record to the test device's Downloads folder
      // It still needs to be downloaded to the local filesystem to save the recording
      FileReadWriteUtil.writeToDownloadsFile(
        context = InstrumentationRegistry.getInstrumentation().context,
        fileName = fileName,
        contentBytes = recordingJson.toByteArray(),
        directory = RECORDING_DIR,
      )
    } catch (e: Exception) {
      println("Error writing log to disk: ${e.message}")
    }
  }

  companion object {
    private const val RECORDING_DIR = "trailblazeRecordings"
    private const val LOGS_DIR = "trailblaze-logs"

    lateinit var currentTestName: String
      private set

    var recordLogs = mutableListOf<TrailblazeLog.TrailblazeToolLog>()
      private set

    val trailblazeLogServerClient = TrailblazeLogServerClient(
      httpClient = HttpClient(OkHttp) {
        engine {
          config {
            /**
             * Disabling SSL Verification for our Self-Signed Logging Certificate
             */
            val trustAllCerts = arrayOf<TrustManager>(
              @SuppressLint("CustomX509TrustManager")
              object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
              },
            )

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
            hostnameVerifier { _, _ -> true }
          }
        }
      },
      baseUrl = run {
        InstrumentationRegistry.getArguments().getString("trailblaze.logs.endpoint", "https://10.0.2.2:8443")
      },
    )

    fun subscribeToLoggingEventsAndSendToServer(
      sendOverHttp: Boolean,
      writeToDisk: Boolean,
    ) {
      TrailblazeLogger.setLogScreenshotListener { screenshotBytes ->
        val screenshotFileName = "${currentTestName}_${System.currentTimeMillis()}.png"
        // Send Log
        runBlocking(Dispatchers.Main) {
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
        runBlocking(Dispatchers.Main) {
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
        cacheRecording(log)
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
        val fileName = "${currentTestName}_${log.timestamp}.json"
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

    private fun cacheRecording(log: TrailblazeLog) {
      listOf(log)
        .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
        .filter { it.successful }
        // objective completes throw off the recording
        .filter { it.command !is ObjectiveStatusTrailblazeTool }
        .forEach { commandLog -> recordLogs.add(commandLog) }
    }
  }
}
