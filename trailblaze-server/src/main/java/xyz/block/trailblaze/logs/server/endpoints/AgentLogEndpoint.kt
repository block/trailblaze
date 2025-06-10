package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import org.jetbrains.annotations.TestOnly
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
  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    post("/agentlog") {
      val logEvent = call.receive<TrailblazeLog>()
      logListener(logEvent)
      val sessionDir = logsRepo.getSessionDir(logEvent.session)

      val logCount = synchronized(countBySession) {
        val newValue = (countBySession[logEvent.session] ?: 0) + 1
        countBySession[logEvent.session] = newValue
        newValue
      }

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
