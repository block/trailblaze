package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.playwright.network.WebResponseObservationManager
import xyz.block.trailblaze.playwright.network.WebResponseObservationRegistry
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
enum class WebResponseHttpMethod {
  GET,
  HEAD,
  POST,
  PUT,
  PATCH,
  DELETE,
  OPTIONS,
}

@Serializable
@TrailblazeToolClass(
  name = "web_beginResponseObservation",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Arms a bounded metadata-only response observation for scripted-tool composition.")
data class PlaywrightNativeBeginResponseObservationTool(
  val observationId: String,
  val origin: String,
  val path: String,
  val method: WebResponseHttpMethod,
  val statusMin: Int = 200,
  val statusMax: Int = 299,
  val timeoutMs: Long = 30_000,
) : PlaywrightExecutableTool {
  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val validation = validate(page)
    if (validation != null) return TrailblazeToolResult.Error.ExceptionThrown(validation)

    val canonicalOrigin = WebResponseObservationManager.canonicalOrigin(URI(origin))!!
    val manager = WebResponseObservationRegistry.manager(
      page,
      context.sessionProvider.invoke().sessionId.value,
    )
    return when (
      val result = manager.begin(
        observationId,
        WebResponseObservationManager.Criteria(
          origin = canonicalOrigin,
          path = path,
          method = method.name,
          statusMin = statusMin,
          statusMax = statusMax,
        ),
        timeoutMs,
      )
    ) {
      WebResponseObservationManager.BeginResult.Success ->
        TrailblazeToolResult.Success("Response observation armed.")
      is WebResponseObservationManager.BeginResult.Error ->
        TrailblazeToolResult.Error.ExceptionThrown(result.message)
    }
  }

  private fun validate(page: Page): String? {
    if (!OBSERVATION_ID.matches(observationId)) {
      return "observationId must match [A-Za-z0-9_-]{1,64}."
    }
    if (timeoutMs !in 1..MAX_TIMEOUT_MS) return "timeoutMs must be between 1 and $MAX_TIMEOUT_MS."
    if (statusMin !in 100..599 || statusMax !in 100..599 || statusMin > statusMax) {
      return "statusMin/statusMax must be an ordered range within 100..599."
    }
    val configured = runCatching { URI(origin) }.getOrNull()
      ?: return "origin must be a canonical http(s) origin."
    val canonical = WebResponseObservationManager.canonicalOrigin(configured)
      ?: return "origin must be a canonical http(s) origin."
    if (
      configured.rawUserInfo != null || configured.rawQuery != null || configured.rawFragment != null ||
        configured.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" } || canonical != origin
    ) {
      return "origin must be canonical and contain no credentials, path, query, or fragment."
    }
    if (configured.scheme.equals("http", ignoreCase = true) && configured.host !in LOCAL_HTTP_HOSTS) {
      return "http origins are restricted to localhost development and tests."
    }
    val pageOrigin = runCatching { WebResponseObservationManager.canonicalOrigin(URI(page.url())) }.getOrNull()
    if (pageOrigin == null || pageOrigin != canonical) {
      return "origin must equal the current page origin."
    }
    if (!isSafeLiteralPath(path)) {
      return "path must be a literal absolute path without query, fragment, wildcard, dot segments, or encoded separators."
    }
    return null
  }

  companion object {
    private val OBSERVATION_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val LOCAL_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    private const val MAX_TIMEOUT_MS = 30_000L

    internal fun isSafeLiteralPath(path: String): Boolean {
      if (!path.startsWith('/') || path.any { it.isWhitespace() || it.code < 0x20 }) return false
      if (path.contains('?') || path.contains('#') || path.contains('*') || path.contains('\\')) return false
      val lowercase = path.lowercase()
      if (lowercase.contains("%2f") || lowercase.contains("%5c") || lowercase.contains("%2e")) return false
      return path.split('/').none { it == "." || it == ".." }
    }
  }
}

@Serializable
@TrailblazeToolClass(
  name = "web_assertResponseObserved",
  surfaceToLlm = false,
  isRecordable = false,
  isVerification = true,
)
@LLMDescription("Consumes a previously armed metadata-only response observation.")
data class PlaywrightNativeAssertResponseObservedTool(
  val observationId: String,
) : PlaywrightExecutableTool {
  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (!Regex("[A-Za-z0-9_-]{1,64}").matches(observationId)) {
      return TrailblazeToolResult.Error.ExceptionThrown("Invalid response observation ID.")
    }
    val manager = WebResponseObservationRegistry.manager(
      page,
      context.sessionProvider.invoke().sessionId.value,
    )
    try {
      while (true) {
        when (val result = manager.poll(observationId)) {
          WebResponseObservationManager.PollResult.Waiting -> {
            currentCoroutineContext().ensureActive()
            page.waitForTimeout(POLL_INTERVAL_MS)
            currentCoroutineContext().ensureActive()
          }
          WebResponseObservationManager.PollResult.Missing ->
            return TrailblazeToolResult.Error.ExceptionThrown("No active response observation uses that ID.")
          is WebResponseObservationManager.PollResult.Matched -> {
            val match = result.match
            return TrailblazeToolResult.Success(
              "Observed ${match.method} ${match.origin}${match.path} with HTTP ${match.status} " +
                "after ${match.elapsedMs}ms (sequence ${match.sequence})."
            )
          }
          is WebResponseObservationManager.PollResult.TimedOut -> {
            val misses = result.nearMisses
            return TrailblazeToolResult.Error.ExceptionThrown(
              "Response observation timed out after ${result.elapsedMs}ms " +
                "(near misses: origin=${misses.wrongOrigin}, path=${misses.wrongPath}, " +
                "method=${misses.wrongMethod}, status=${misses.wrongStatus})."
            )
          }
        }
      }
    } catch (e: CancellationException) {
      manager.cancel(observationId)
      throw e
    } catch (_: Exception) {
      manager.cancel(observationId)
      return TrailblazeToolResult.Error.ExceptionThrown("Response observation could not be completed.")
    }
  }

  companion object {
    private const val POLL_INTERVAL_MS = 25.0
  }
}

@Serializable
@TrailblazeToolClass(
  name = "web_cancelResponseObservation",
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Idempotently cancels a previously armed metadata-only response observation.")
data class PlaywrightNativeCancelResponseObservationTool(
  val observationId: String,
) : PlaywrightExecutableTool {
  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (!Regex("[A-Za-z0-9_-]{1,64}").matches(observationId)) {
      return TrailblazeToolResult.Error.ExceptionThrown("Invalid response observation ID.")
    }
    WebResponseObservationRegistry.manager(
      page,
      context.sessionProvider.invoke().sessionId.value,
    ).cancel(observationId)
    return TrailblazeToolResult.Success("Response observation is no longer active.")
  }
}
