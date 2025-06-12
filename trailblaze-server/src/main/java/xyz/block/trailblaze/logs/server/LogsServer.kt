package xyz.block.trailblaze.logs.server

import freemarker.cache.ClassTemplateLoader
import io.ktor.http.ContentType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.freemarker.FreeMarker
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.server.LogsServer.logsDir
import xyz.block.trailblaze.logs.server.endpoints.AgentLogEndpoint
import xyz.block.trailblaze.logs.server.endpoints.DeleteLogsEndpoint
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointSessionDetail
import xyz.block.trailblaze.logs.server.endpoints.GetEndpointYamlSessionRecording
import xyz.block.trailblaze.logs.server.endpoints.LlmSessionEndpoint
import xyz.block.trailblaze.logs.server.endpoints.LogScreenshotPostEndpoint
import xyz.block.trailblaze.logs.server.endpoints.SessionJsonRecordingEndpoint
import xyz.block.trailblaze.logs.server.endpoints.SinglePageReportEndpoint
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File

fun ApplicationEngine.Configuration.envConfig(requestedHttpPort: Int, requestedHttpsPort: Int) {
  val keyStoreFile = File("build/keystore.jks")
  val keyStore = buildKeyStore {
    certificate("sampleAlias") {
      password = "foobar"
      domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
    }
  }
  keyStore.saveToFile(keyStoreFile, "123456")

  connector {
    port = requestedHttpPort
  }
  sslConnector(
    keyStore = keyStore,
    keyAlias = "sampleAlias",
    keyStorePassword = { "123456".toCharArray() },
    privateKeyPassword = { "foobar".toCharArray() },
  ) {
    port = requestedHttpsPort
    keyStorePath = keyStoreFile
  }
}

object LogsServer {

  enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
  }

  val currLogLevel = LogLevel.DEBUG

  fun printDebug(message: String) {
    if (currLogLevel <= LogLevel.DEBUG) {
      println(message)
    }
  }

  val currDir = File(System.getProperty("user.dir")).also {
    printDebug("currDir: ${it.canonicalPath}")
  }

  val gitDir = if (currDir.name == "trailblaze") {
    currDir
  } else {
    currDir.parentFile
  }.also {
    printDebug("gitDir: ${it.canonicalPath}")
  }

  val logsDir = File(gitDir, "logs").also {
    if (!it.exists()) {
      it.mkdirs()
    }
    printDebug("logsDir: ${it.canonicalPath}")
  }

  val logsRepo = LogsRepo(logsDir)

  val templatesDir = File(gitDir, "trailblaze-server/src/main/resources/templates").also {
    printDebug("templatesDir: ${it.canonicalPath}")
  }

  fun startServer(
    shouldWait: Boolean = true,
    requestedHttpPort: Int = 52525,
    requestedHttpsPort: Int = 8443,
  ): EmbeddedServer<*, *> {
    // Delete all session directories on server start
    // getSessionDirs().forEach { sessionDir -> sessionDir.deleteRecursively() }

    println("Once the server is running, you'll be able to view captured logs from any executions.")
    println("Use trailblaze using 'host' mode (from your laptop) or 'on-device' mode (within the Android device).")
    println("With the server is running, you'll be able to view captured logs from any executions.")
    println("Running the logs-server is NOT required, but extremely helpful for debugging local usage.")
    println("Open http://localhost:52525 in your browser to view the trailblaze logs.")
    println("NOTE: Gradle will continue to say 'EXECUTING' until you stop the server.")

    return embeddedServer(
      factory = Netty,
      environment = applicationEnvironment { },
      configure = {
        envConfig(
          requestedHttpPort = requestedHttpPort,
          requestedHttpsPort = requestedHttpsPort,
        )
      },
      module = Application::module,
    ).start(wait = shouldWait)
  }
}

fun Application.module() {
  install(WebSockets)
  install(ContentNegotiation) {
    json(TrailblazeJsonInstance)
  }
  install(FreeMarker) {
//    templateLoader = FileTemplateLoader(LogsServer.templatesDir)
    // Use this if deploying elsewhere, but not if running by source.
    templateLoader = ClassTemplateLoader(this::class.java.classLoader, "templates")
  }
  routing {
    get("/") {
      val sessionDirs = LogsServer.logsRepo.getSessionDirs()
      sessionDirs.forEach { sessionDir ->
        val sessionName = sessionDir.name

        val logsForSession = LogsServer.logsRepo.getLogsForSession(sessionName)
      }

      call.respond(
        FreeMarkerContent(
          "home.ftl",
          mapOf(
            "sessions" to sessionDirs.map { dir -> dir.name },
          ),
        ),
        null,
      )
    }
    get("/ping") {
      // Used to make sure the server is available
      call.respondText("""{ "status" : "OK" }""", ContentType.Application.Json)
    }
    get("/logs/session/{session}") {
      val logs = LogsServer.logsRepo.getLogsForSession(this.call.parameters["session"])
      val json = TrailblazeJsonInstance.encodeToString<List<TrailblazeLog>>(logs)
      call.respondText(json, ContentType.Application.Json)
    }

    webSocket("/updates") {
      val currSocketSession = this
      val sessionId: String? = currSocketSession.call.request.queryParameters["id"]
      CoroutineScope(Dispatchers.IO).launch {
        println("Start Watching for Session $sessionId")
        SocketsRepo.startWatchForSession(logsDir, sessionId)
      }
      SocketsRepo.webSocketConnections.add(currSocketSession)
      try {
        for (frame in incoming) {
          // You can handle incoming messages here if needed
          println("Incoming WebSocket Message: $frame")
        }
      } finally {
        println("Removing $currSocketSession for $sessionId")
        SocketsRepo.webSocketConnections.remove(currSocketSession)
      }
    }
    val logsRepo = LogsServer.logsRepo
    LlmSessionEndpoint.register(this, logsRepo)
    SessionJsonRecordingEndpoint.register(this, logsRepo)
    GetEndpointSessionDetail.register(this, logsRepo)
    AgentLogEndpoint.register(this, logsRepo)
    DeleteLogsEndpoint.register(this, logsRepo)
    GetEndpointYamlSessionRecording.register(this, logsRepo)
    LogScreenshotPostEndpoint.register(this, logsRepo)
    SinglePageReportEndpoint.register(this, logsRepo)
    staticFiles("/static", logsDir)
  }
}
