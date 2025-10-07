package xyz.block.trailblaze.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import xyz.block.trailblaze.android.AndroidTrailblazeDeviceInfoUtil
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifiersProvider
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger

class OnDeviceRpcServerUtils(
  private val runTrailblazeYaml: suspend (RunYamlRequest) -> Unit,
  private val trailblazeDeviceClassifiersProvider: TrailblazeDeviceClassifiersProvider? = null,
) {
  private var currPromptJob: Job? = null

  fun startServer(port: Int, wait: Boolean = true) {
    println("Starting On-Device Trailblaze Server on port $port. ")

    println("Will Wait: $wait")

    val server = embeddedServer(
      factory = CIO,
      port = port,
    ) {
      install(ContentNegotiation) {
        json(TrailblazeJsonInstance)
      }
      routing {
        get("/ping") {
          // Used to make sure the server is available
          call.respondText("""{ "status" : "Running on port $port" }""", ContentType.Application.Json)
        }
        post("/run") {
          try {
            val runYamlRequest: RunYamlRequest = call.receive()
            println("Params: $runYamlRequest")
            val testName = runYamlRequest.testName
            TrailblazeLogger.startSession(testName)
            TrailblazeLogger.sendStartLog(
              trailConfig = null,
              className = testName,
              methodName = testName,
              trailblazeDeviceInfo = AndroidTrailblazeDeviceInfoUtil.collectCurrentDeviceInfo(
                trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
                trailblazeDeviceClassifiersProvider = trailblazeDeviceClassifiersProvider,
              ),
            )
            currPromptJob?.let { job ->
              // Cancel the current prompt job if it's running'
              if (job.isActive) {
                job.cancelAndJoin()
                TrailblazeLogger.sendEndLog(false, TrailblazeException("Execution Cancelled."))
              }
            }
            currPromptJob = CoroutineScope(Dispatchers.IO).launch {
              try {
                runTrailblazeYaml(runYamlRequest)
                TrailblazeLogger.sendEndLog(true)
              } catch (e: Exception) {
                TrailblazeLogger.sendEndLog(false, e)
              }
            }
            call.respond(HttpStatusCode.OK, "Yaml Execution Started.")
          } catch (e: Exception) {
            TrailblazeLogger.sendEndLog(false, e)
            call.respond(HttpStatusCode.InternalServerError, e.stackTraceToString())
          }
        }

        route("{...}") {
          handle {
            println("Unhandled route: ${call.request.uri} [${call.request.httpMethod}]")
            call.respond(HttpStatusCode.NotFound)
          }
        }
      }
    }.start(wait = wait)
    println("Server starting...")
  }
}
