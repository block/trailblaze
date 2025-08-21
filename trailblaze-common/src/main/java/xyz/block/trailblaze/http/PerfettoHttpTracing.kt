// commonMain
package xyz.block.trailblaze.http

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import io.ktor.util.AttributeKey
import kotlinx.datetime.Clock
import xyz.block.trailblaze.tracing.CompleteEvent
import xyz.block.trailblaze.tracing.PlatformIds
import xyz.block.trailblaze.tracing.TrailblazeTracer.traceRecorder
import kotlin.time.TimeSource

/**
 * Ktor client plugin that emits Chrome Trace "X" events for each HTTP call.
 */
class PerfettoHttpTracing private constructor(
  private val config: Config,
) {

  class Config {
    /** Trace category used in Perfetto for these slices. */
    var category: String = "http"

    /**
     * Sink for completed events. By default, calls into your recorder.
     * Replace if you want a custom buffer/export path.
     */
    var sink: (CompleteEvent) -> Unit = { event ->
      traceRecorder.add(event.toJsonObject())
    }

    /** Redact query strings from the span name. */
    var redactQuery: Boolean = false

    /** Include response content length in args (if known). */
    var includeSizes: Boolean = true

    /** Add arbitrary static args to every span (e.g., app build / env). */
    var commonArgs: Map<String, String> = emptyMap()
  }

  companion object : HttpClientPlugin<Config, PerfettoHttpTracing> {
    override val key: AttributeKey<PerfettoHttpTracing> = AttributeKey("PerfettoHttpTracing")

    override fun prepare(block: Config.() -> Unit): PerfettoHttpTracing {
      val cfg = Config().apply(block)
      return PerfettoHttpTracing(cfg)
    }

    override fun install(plugin: PerfettoHttpTracing, scope: HttpClient) {
      // We intercept HttpSend so we can time the *entire* call around engine execution.
      scope.plugin(HttpSend).intercept { request ->
        val pid = PlatformIds.pid()
        val tid = PlatformIds.tid()

        val method = request.method.value
        val urlString = request.url.buildString()
        val name = if (plugin.config.redactQuery) {
          val noQuery = URLBuilder(urlString).apply { parameters.clear() }.buildString()
          "$method $noQuery"
        } else {
          "$method $urlString"
        }

        val startWall = Clock.System.now()
        val mark = TimeSource.Monotonic.markNow()

        var status: Int? = null
        var bytesSent: Long? = null
        var bytesReceived: Long? = null
        var threw: Throwable? = null

        try {
          // Execute the request (suspends until response fully received if body read; otherwise until headers).
          val call = execute(request)

          status = call.response.status.value
          // Size hints: not always known, but include when available
          bytesSent = request.headers["Content-Length"]?.toLongOrNull()
          bytesReceived = call.response.headers[HttpHeaders.ContentLength]?.toLongOrNull()

          // Return the call downstream
          call
        } catch (t: Throwable) {
          threw = t
          throw t
        } finally {
          val dur = mark.elapsedNow()
          val base = mutableMapOf<String, String>()
          base += plugin.config.commonArgs
          base["method"] = method
          base["host"] = request.url.host
          base["path"] = request.url.encodedPath
          status?.let { base["status"] = it.toString() }
          if (plugin.config.includeSizes) {
            bytesSent?.let { base["bytes_sent"] = it.toString() }
            bytesReceived?.let { base["bytes_received"] = it.toString() }
          }
          threw?.let { base["error"] = it::class.simpleName ?: "Throwable" }

          plugin.config.sink(
            CompleteEvent(
              name = name,
              cat = plugin.config.category,
              ts = startWall,
              dur = dur,
              pid = pid,
              tid = tid,
              args = base,
            ),
          )
        }
      }
    }
  }
}
