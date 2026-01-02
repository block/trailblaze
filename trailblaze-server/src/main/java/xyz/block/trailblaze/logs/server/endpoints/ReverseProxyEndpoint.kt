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
import java.util.concurrent.TimeUnit

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
    engine {
      config {
        // Set timeouts for LLM requests (2 minutes to match other LLM client configs)
        val timeoutInSeconds = 120L
        writeTimeout(timeoutInSeconds, TimeUnit.SECONDS)
        readTimeout(timeoutInSeconds, TimeUnit.SECONDS)
        connectTimeout(timeoutInSeconds, TimeUnit.SECONDS)
      }
    }
  }

  fun register(routing: Routing, logsRepo: LogsRepo) = with(routing) {
    route("/reverse-proxy") {
      handle {
        try {
          val httpMethod = call.request.httpMethod
          val callHeaders = call.request.headers
          val targetUrl = callHeaders[ReverseProxyHeaders.ORIGINAL_URI]
            ?: error("No header value for ${ReverseProxyHeaders.ORIGINAL_URI}")

          println("ReverseProxy: Proxying $httpMethod request to $targetUrl")

          // Only read body for methods that typically have request bodies
          // Reading body on GET/HEAD requests can hang with HTTP/2
          val callBytes = if (httpMethod != HttpMethod.Get && httpMethod != HttpMethod.Head) {
            call.receiveChannel().toByteArray()
          } else {
            ByteArray(0)
          }

          println("ReverseProxy: Read ${callBytes.size} bytes from incoming request")

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

          println("ReverseProxy: Received response with status ${proxiedResponse.status}")

          // Copy status, headers, and body to the response
          // Filter out headers that Ktor will set automatically to avoid duplicates in HTTP/2
          proxiedResponse.headers.forEach { key, values ->
            val shouldSkip = HttpHeaders.isUnsafe(key) || 
                            key.equals(HttpHeaders.ContentLength, ignoreCase = true) ||
                            key.equals(HttpHeaders.TransferEncoding, ignoreCase = true)
            if (!shouldSkip) {
              values.forEach { call.response.headers.append(key, it) }
            }
          }
          val proxiedRequestResponseBytes = proxiedResponse.bodyAsChannel().toByteArray()
          println("ReverseProxy: Sending ${proxiedRequestResponseBytes.size} bytes back to client")
          call.respond(
            ByteArrayContent(
              proxiedRequestResponseBytes,
              proxiedResponse.contentType(),
              proxiedResponse.status,
            ),
          )
        } catch (e: Exception) {
          println("ReverseProxy ERROR: ${e.message}")
          e.printStackTrace()
          call.respond(
            io.ktor.http.HttpStatusCode.InternalServerError,
            "Reverse proxy error: ${e.message}"
          )
        }
      }
    }
  }
}
