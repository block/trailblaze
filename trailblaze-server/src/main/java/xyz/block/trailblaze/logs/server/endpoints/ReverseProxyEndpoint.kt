package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray
import xyz.block.trailblaze.http.ReverseProxyHeaders
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * Registers an endpoint to display LLM conversation as an html chat view.
 */
object ReverseProxyEndpoint {

  val client = HttpClient(OkHttp) {
    install(Logging) {
      logger = object : Logger {
        override fun log(message: String) {
          // Log the request and response details
          println("ReverseProxy: $message")
        }
      }
      level = LogLevel.NONE
    }
  }

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    route("/reverse-proxy") {
      handle {
        val callBytes = call.receiveChannel().toByteArray()
        val httpMethod = call.request.httpMethod
        val callHeaders = call.request.headers
        val targetUrl = callHeaders[ReverseProxyHeaders.ORIGINAL_URI]
          ?: error("No header value for ${ReverseProxyHeaders.ORIGINAL_URI}")

        val proxiedResponse = client.request(targetUrl) {
          this.method = httpMethod
          this.headers.appendAll(callHeaders)
          this.headers.remove(HttpHeaders.Host)
          this.headers.remove(HttpHeaders.ContentLength)
          this.headers.remove(ReverseProxyHeaders.ORIGINAL_URI)
          if (httpMethod != HttpMethod.Get && httpMethod != HttpMethod.Head) {
            setBody(callBytes)
          }
        }

        // Copy status, headers, and body to the response
        proxiedResponse.headers.forEach { key, values ->
          if (!HttpHeaders.isUnsafe(key) && key != HttpHeaders.ContentLength) {
            values.forEach { call.response.headers.append(key, it) }
          }
        }
        val proxiedRequestResponseBytes = proxiedResponse.bodyAsChannel().toByteArray()
        call.respond(
          ByteArrayContent(
            proxiedRequestResponseBytes,
            proxiedResponse.contentType(),
            proxiedResponse.status,
          ),
        )
      }
    }
  }
}
