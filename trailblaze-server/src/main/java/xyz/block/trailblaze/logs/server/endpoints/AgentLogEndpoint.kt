package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import org.jetbrains.annotations.TestOnly
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.withClockMetadata
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

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

  fun register(
    routing: Routing,
    logsRepo: LogsRepo,
  ) = with(routing) {
    post("/agentlog") {
      // A bad log upload is that one request's problem, never the daemon's: respond 400 and keep
      // serving. A truncated body here is most often a device connection severed mid-upload (e.g.
      // the daemon being shut down while a run is in flight), not a device-side encoding bug.
      val json = try {
        call.receiveText()
      } catch (e: CancellationException) {
        // The call was cancelled (client disconnect mid-upload, server shutdown). Let
        // cooperative cancellation propagate — don't swallow it into a 400.
        throw e
      } catch (e: Exception) {
        Console.log("[AgentLogEndpoint] failed to read /agentlog request body: ${e.message}")
        call.respondText(
          text = "Failed to read log event body: ${e.message}",
          status = HttpStatusCode.BadRequest,
        )
        return@post
      }
      val logEvent = try {
        TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(json)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val message = buildString {
          appendLine("Failed to decode log event: ${e.message}.")
          appendLine("This usually happens when the device and desktop apps are running different versions.")
          appendLine("Try restarting them.")
        }
        Console.log("[AgentLogEndpoint] $message")
        call.respondText(text = message, status = HttpStatusCode.BadRequest)
        return@post
      }
      val jsonLogFile = accept(logEvent, logsRepo)
      call.respondText("Log received and saved as $jsonLogFile")
    }
  }

  internal fun accept(logEvent: TrailblazeLog, logsRepo: LogsRepo): java.io.File {
    // Anchor device-clock logs with the host-clock receipt time: readers derive the device→host
    // offset from it (offset ≈ hostReceivedAt - (timestamp + durationMs); each sample is skew +
    // upload latency, so readers take the per-device minimum). Host-clock logs are persisted
    // byte-identical to before — they need no anchor. Received-twice logs keep their first
    // anchor; a re-upload's later receipt time says nothing new.
    val anchored = if (logEvent.clock == TrailblazeClockDomain.DEVICE && logEvent.hostReceivedAt == null) {
      logEvent.withClockMetadata(hostReceivedAt = Clock.System.now())
    } else {
      logEvent
    }
    logListener(anchored)
    return logsRepo.saveLogToDisk(anchored)
  }
}
