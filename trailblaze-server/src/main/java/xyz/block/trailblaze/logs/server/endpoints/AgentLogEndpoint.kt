package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import org.jetbrains.annotations.TestOnly
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

/**
 * Handles POST requests to the /agentlog endpoint to accept `TrailblazeLog` requests.
 */
object AgentLogEndpoint {

  @TestOnly
  private var logListener: (TrailblazeLog) -> Unit = {}

  /**
   * Useful for validating the logs that were received by the server in testing scenarios.
   */
  @TestOnly
  fun setServerReceivedLogsListener(logListener: (TrailblazeLog) -> Unit) {
    this.logListener = logListener
  }

  val countBySession = mutableMapOf<String, Int>()

  fun getNextLogCountForSession(sessionId: String): Int = synchronized(countBySession) {
    val newValue = (countBySession[sessionId] ?: 0) + 1
    countBySession[sessionId] = newValue
    newValue
  }

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    post("/agentlog") {
      val json = call.receiveText()
      val logEvent = try {
        TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(json)
      } catch (e: Exception) {
        throw TrailblazeException(
          message = buildString {
            appendLine("Failed to decode log event: ${e.message}.")
            appendLine("This usually happens when the device and desktop apps are running different versions.")
            appendLine("Try restarting them.")
          },
          e,
        )
      }
      logListener(logEvent)
      val sessionDir = logsRepo.getSessionDir(logEvent.session)

      val logCount = getNextLogCountForSession(logEvent.session)

      val jsonLogFilename =
        File(sessionDir, "agent_${logCount}_${logEvent::class.java.simpleName}.json")
      jsonLogFilename.writeText(
        TrailblazeJsonInstance.encodeToString<TrailblazeLog>(
          logEvent,
        ),
      )
      call.respondText("Log received and saved as $jsonLogFilename")
    }
  }
}
