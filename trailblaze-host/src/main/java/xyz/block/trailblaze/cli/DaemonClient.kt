package xyz.block.trailblaze.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.server.endpoints.CliEndpoints
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShutdownResponse
import xyz.block.trailblaze.logs.server.endpoints.CliShowWindowResponse
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse

/**
 * Client for communicating with the Trailblaze daemon server.
 * 
 * The daemon runs the Trailblaze desktop app and exposes HTTP endpoints
 * for CLI commands to interact with. Uses Ktor HttpClient for HTTP requests.
 */
class DaemonClient(
  private val host: String = "localhost",
  private val port: Int = DEFAULT_PORT,
) {
  
  private val json: Json = TrailblazeJson.defaultWithoutToolsInstance
  private val baseUrl: String get() = "http://$host:$port"
  
  private val client = HttpClient(OkHttp) {
    install(ContentNegotiation) {
      json(json)
    }
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = READ_TIMEOUT_MS
    }
  }
  
  /**
   * Creates a client with longer timeout for run operations.
   */
  private val runClient = HttpClient(OkHttp) {
    install(ContentNegotiation) {
      json(json)
    }
    install(HttpTimeout) {
      connectTimeoutMillis = CONNECT_TIMEOUT_MS
      requestTimeoutMillis = RUN_TIMEOUT_MS
    }
  }
  
  /**
   * Check if the daemon is running by pinging the server.
   */
  fun isRunning(): Boolean {
    return try {
      runBlocking {
        val response = client.get("$baseUrl${CliEndpoints.PING}")
        response.status.isSuccess()
      }
    } catch (e: Exception) {
      false
    }
  }
  
  /**
   * Get detailed daemon status.
   */
  fun getStatus(): CliStatusResponse? {
    return try {
      runBlocking {
        val response = client.get("$baseUrl${CliEndpoints.STATUS}")
        if (response.status.isSuccess()) {
          json.decodeFromString(CliStatusResponse.serializer(), response.bodyAsText())
        } else {
          null
        }
      }
    } catch (e: Exception) {
      null
    }
  }
  
  /**
   * Send a run request to the daemon.
   */
  fun run(request: CliRunRequest): CliRunResponse {
    return try {
      runBlocking {
        val response = runClient.post("$baseUrl${CliEndpoints.RUN}") {
          contentType(ContentType.Application.Json)
          setBody(request)
        }
        
        val responseBody = response.bodyAsText()
        if (response.status.isSuccess()) {
          json.decodeFromString(CliRunResponse.serializer(), responseBody)
        } else {
          CliRunResponse(success = false, error = "HTTP ${response.status.value}: $responseBody")
        }
      }
    } catch (e: Exception) {
      CliRunResponse(success = false, error = e.message ?: "Connection failed")
    }
  }
  
  /**
   * Convenience method to run a RunYamlRequest.
   */
  fun run(runYamlRequest: RunYamlRequest, forceStopTargetApp: Boolean = false): CliRunResponse {
    return run(CliRunRequest(runYamlRequest = runYamlRequest, forceStopTargetApp = forceStopTargetApp))
  }
  
  /**
   * Request daemon shutdown.
   */
  fun shutdown(): CliShutdownResponse {
    return try {
      runBlocking {
        val response = client.post("$baseUrl${CliEndpoints.SHUTDOWN}") {
          contentType(ContentType.Application.Json)
          setBody("{}")
        }
        
        val responseBody = response.bodyAsText()
        if (response.status.isSuccess()) {
          json.decodeFromString(CliShutdownResponse.serializer(), responseBody)
        } else {
          CliShutdownResponse(success = false, message = "HTTP ${response.status.value}: $responseBody")
        }
      }
    } catch (e: Exception) {
      CliShutdownResponse(success = false, message = e.message ?: "Connection failed")
    }
  }
  
  /**
   * Request the daemon to show its window (bring to foreground).
   */
  fun showWindow(): CliShowWindowResponse {
    return try {
      runBlocking {
        val response = client.post("$baseUrl${CliEndpoints.SHOW_WINDOW}") {
          contentType(ContentType.Application.Json)
          setBody("{}")
        }
        
        val responseBody = response.bodyAsText()
        if (response.status.isSuccess()) {
          json.decodeFromString(CliShowWindowResponse.serializer(), responseBody)
        } else {
          CliShowWindowResponse(success = false, message = "HTTP ${response.status.value}: $responseBody")
        }
      }
    } catch (e: Exception) {
      CliShowWindowResponse(success = false, message = e.message ?: "Connection failed")
    }
  }
  
  /**
   * Wait for the daemon to become available.
   * 
   * @param maxWaitMs Maximum time to wait in milliseconds
   * @param pollIntervalMs Time between polls
   * @return true if daemon became available, false if timed out
   */
  fun waitForDaemon(maxWaitMs: Long = MAX_WAIT_FOR_DAEMON_MS, pollIntervalMs: Long = POLL_INTERVAL_MS): Boolean {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < maxWaitMs) {
      if (isRunning()) {
        return true
      }
      Thread.sleep(pollIntervalMs)
    }
    return false
  }
  
  companion object {
    /** Default daemon port (matches TrailblazeServerState.HTTP_PORT) */
    const val DEFAULT_PORT = 52525
    
    /** Connection timeout for quick checks */
    const val CONNECT_TIMEOUT_MS = 2000L
    
    /** Read timeout for quick responses */
    const val READ_TIMEOUT_MS = 5000L
    
    /** Read timeout for run requests (can take a while) */
    const val RUN_TIMEOUT_MS = 300000L // 5 minutes
    
    /** Maximum time to wait for daemon to start */
    const val MAX_WAIT_FOR_DAEMON_MS = 30000L
    
    /** Poll interval when waiting for daemon */
    const val POLL_INTERVAL_MS = 500L
  }
}
