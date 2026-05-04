package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.network.NetworkEvent
import xyz.block.trailblaze.network.Phase
import xyz.block.trailblaze.playwright.network.WebNetworkCapture
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import java.io.File

@Serializable
@TrailblazeToolClass("web_assert_network_event", isVerification = true)
@LLMDescription(
  """
Assert that a specific event name appeared in the network traffic captured during this session.

Use this to verify that instrumentation signals, analytics events, or user-journey tracking
calls actually fired when a flow was completed. The check scans request URLs and request bodies
(both inlined text and on-disk blobs) for the given event name string.

Only outgoing requests (REQUEST_START phase) are checked so each network exchange is counted
once regardless of how many response events accompany it.

The tool polls up to the configured timeout so it is safe to call immediately after a UI action
without waiting for the browser to flush the analytics beacon. At least one scan always runs
regardless of timeoutMs so passing timeoutMs=0 performs a single immediate check.

This is a test assertion — it will fail the trail if the event is not found in the network log.

Example uses:
- Verify a user-journey signal fired: eventName="adjust-stock"
- Verify an analytics call was made: eventName="checkout-started"
""",
)
class PlaywrightNativeAssertNetworkEventTool(
  @param:LLMDescription(
    "The event name to search for in captured network traffic. " +
      "Matched against request URLs and request body text (case-insensitive). Must not be blank.",
  )
  val eventName: String,
  @param:LLMDescription(
    "Maximum milliseconds to wait for the event to appear in captured traffic. " +
      "Defaults to 5000. Increase for slow analytics pipelines. " +
      "One complete scan always runs before the timeout is enforced.",
  )
  val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }

    if (eventName.isBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "eventName must not be blank — an empty search string matches every captured request.",
      )
    }

    Console.log("### Asserting network event: $eventName (timeout=${timeoutMs}ms)")

    val capture = WebNetworkCapture.get(page.context())
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        "No network capture is active for this session. " +
          "Network capture must be started before running trails that use web_assert_network_event.",
      )

    val ndjsonFile = capture.ndjsonPath()
    if (!ndjsonFile.exists()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        "Network log not found at ${ndjsonFile.absolutePath}. " +
          "No requests may have been captured yet.",
      )
    }

    val sessionDir = ndjsonFile.parentFile
    var linesAlreadyScanned = 0
    var totalRequestsScanned = 0
    var totalParseErrors = 0

    // Reads only lines added since the last call, returns true when the event is found.
    // Cumulative counters track across all poll iterations for the final diagnostic message.
    fun scanNewLines(): Boolean {
      var lineIndex = 0
      var found = false
      runCatching {
        ndjsonFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
          for (line in lines) {
            lineIndex++
            if (lineIndex <= linesAlreadyScanned) continue
            if (line.isBlank()) continue
            val event = runCatching { JSON.decodeFromString<NetworkEvent>(line) }
              .onFailure { totalParseErrors++ }
              .getOrNull() ?: continue
            if (event.phase != Phase.REQUEST_START) continue
            totalRequestsScanned++
            if (containsEventName(event, sessionDir)) {
              found = true
              break
            }
          }
        }
      }
      linesAlreadyScanned = lineIndex
      return found
    }

    // Always run at least one complete scan before enforcing the timeout so that
    // timeoutMs=0 still checks the current log state rather than returning immediately.
    var found = scanNewLines()

    if (!found && timeoutMs > 0) {
      found = withTimeoutOrNull(timeoutMs) {
        while (true) {
          delay(POLL_INTERVAL_MS)
          if (scanNewLines()) return@withTimeoutOrNull true
        }
        @Suppress("UNREACHABLE_CODE")
        false
      } ?: false
    }

    if (totalParseErrors > 0) {
      Console.log(
        "### web_assert_network_event: $totalParseErrors line(s) could not be parsed from network log.",
      )
    }

    return if (found) {
      Console.log("### Network event '$eventName' confirmed in captured requests.")
      TrailblazeToolResult.Success(
        "Network event '$eventName' was found in captured requests — instrumentation signal confirmed.",
      )
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(
        "Network event '$eventName' was not found in captured requests after ${timeoutMs}ms " +
          "(scanned $totalRequestsScanned requests, $totalParseErrors parse errors). " +
          "The UJ instrumentation signal may not have fired during this session.",
      )
    }
  }

  private fun containsEventName(event: NetworkEvent, sessionDir: File): Boolean {
    if (event.url.contains(eventName, ignoreCase = true)) return true
    val bodyRef = event.requestBodyRef ?: return false
    if (bodyRef.inlineText?.contains(eventName, ignoreCase = true) == true) return true
    val blobPath = bodyRef.blobPath ?: return false
    return runCatching {
      File(sessionDir, blobPath).readText(Charsets.UTF_8).contains(eventName, ignoreCase = true)
    }.getOrDefault(false)
  }

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }
    const val DEFAULT_TIMEOUT_MS: Long = 5_000
    private const val POLL_INTERVAL_MS: Long = 250
  }
}
