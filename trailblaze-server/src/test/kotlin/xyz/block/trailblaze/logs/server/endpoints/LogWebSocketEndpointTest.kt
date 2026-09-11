package xyz.block.trailblaze.logs.server.endpoints

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogServerClient
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpoints
import xyz.block.trailblaze.logs.server.ServerEndpoints.logsServerKtorEndpointsWithWireTransport
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.transport.AndroidWireTransportMode

class LogWebSocketEndpointTest {
  @Test
  fun `one protobuf socket persists logs screenshots and traces`() = testApplication {
    val logsDir = File.createTempFile("protobuf-logs", "").apply {
      delete()
      mkdirs()
    }
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val connectionCount = AtomicInteger()
    LogWebSocketEndpoint.setServerConnectionListener { connectionCount.incrementAndGet() }
    application { logsServerKtorEndpoints(logsRepo) }
    val websocketHttpClient = createClient { install(WebSockets) }
    val uploadClient = TrailblazeLogServerClient(
      httpClient = websocketHttpClient,
      baseUrl = "http://localhost",
      useBinaryTransport = true,
    )
    val session = SessionId("binary-session")
    val log = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = "home",
      screenshotFile = "home.png",
      viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
      trailblazeNodeTree = TrailblazeNode(
        nodeId = 1,
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Home"),
      ),
      deviceWidth = 1080,
      deviceHeight = 1920,
      session = session,
      timestamp = Clock.System.now(),
    )
    val screenshot = byteArrayOf(1, 2, 3, 4)

    assertTrue(runBlocking { uploadClient.sendAgentLog(log) })
    assertTrue(runBlocking { uploadClient.sendScreenshot("home.png", session, screenshot) })
    assertTrue(runBlocking { uploadClient.sendTrace(session, "{\"trace\":true}") })

    assertEquals(log, logsRepo.getLogsForSession(session).single())
    assertContentEquals(screenshot, logsRepo.getSessionDir(session).resolve("home.png").readBytes())
    assertEquals("{\"trace\":true}", logsRepo.getSessionDir(session).resolve("trace.json").readText())
    assertEquals(1, connectionCount.get())
    LogWebSocketEndpoint.setServerConnectionListener {}
    uploadClient.close()
  }

  @Test
  fun `a device-clock log over the protobuf socket is anchored like an http upload`() = testApplication {
    // `/logs-ws` funnels agent logs through the same AgentLogEndpoint.accept as the JSON POST —
    // pin that the funnel is real: a device-clock log arriving over the socket must gain the
    // hostReceivedAt anchor without losing its clock marker. (Traces on this socket treat an
    // ABSENT clock param as device — a log's absent marker means host-or-unknown; see
    // TrailblazeLog.clock.)
    val logsDir = File.createTempFile("protobuf-clock-logs", "").apply {
      delete()
      mkdirs()
    }
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    application { logsServerKtorEndpoints(logsRepo) }
    val websocketHttpClient = createClient { install(WebSockets) }
    val uploadClient = TrailblazeLogServerClient(
      httpClient = websocketHttpClient,
      baseUrl = "http://localhost",
      useBinaryTransport = true,
    )
    val session = SessionId("binary-device-clock-session")
    val deviceLog = TrailblazeLog.TrailblazeSnapshotLog(
      displayName = "home",
      screenshotFile = "home.png",
      viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
      trailblazeNodeTree = TrailblazeNode(
        nodeId = 1,
        driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Home"),
      ),
      deviceWidth = 1080,
      deviceHeight = 1920,
      session = session,
      timestamp = Clock.System.now(),
      clock = TrailblazeClockDomain.DEVICE,
    )

    assertTrue(runBlocking { uploadClient.sendAgentLog(deviceLog) })

    val persisted = logsRepo.getLogsForSession(session).single()
    assertTrue(
      persisted.hostReceivedAt != null,
      "the socket transport must anchor device-clock logs exactly like the JSON POST",
    )
    assertEquals(TrailblazeClockDomain.DEVICE, persisted.clock)
    assertEquals(deviceLog, (persisted as TrailblazeLog.TrailblazeSnapshotLog).copy(hostReceivedAt = null))
    uploadClient.close()
  }

  @Test
  fun `json rollback mode falls back before sending a protobuf log`() = testApplication {
    val logsDir =
      File.createTempFile("json-rollback-logs", "").apply {
        delete()
        mkdirs()
      }
    val logsRepo = LogsRepo(logsDir, watchFileSystem = false)
    val connectionCount = AtomicInteger()
    LogWebSocketEndpoint.setServerConnectionListener { connectionCount.incrementAndGet() }
    application {
      logsServerKtorEndpointsWithWireTransport(
        logsRepo = logsRepo,
        homeCallbackHandler = null,
        trailRunnerPath = null,
        installContentNegotiation = true,
        cliCallbacks = null,
        resolvedAuths = null,
        androidWireTransportMode = AndroidWireTransportMode.JSON,
        additionalRouteRegistration = null,
      )
    }
    val websocketHttpClient = createClient { install(WebSockets) }
    val uploadClient =
      TrailblazeLogServerClient(
        httpClient = websocketHttpClient,
        baseUrl = "http://localhost",
        useBinaryTransport = true,
      )
    val session = SessionId("json-rollback-session")
    val log =
      TrailblazeLog.TrailblazeSnapshotLog(
        displayName = "home",
        screenshotFile = "home.png",
        viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
        trailblazeNodeTree =
          TrailblazeNode(
            nodeId = 1,
            driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Home"),
          ),
        deviceWidth = 1080,
        deviceHeight = 1920,
        session = session,
        timestamp = Clock.System.now(),
      )

    assertTrue(runBlocking { uploadClient.sendAgentLog(log) })

    assertEquals(log, logsRepo.getLogsForSession(session).single())
    assertEquals(0, connectionCount.get())
    LogWebSocketEndpoint.setServerConnectionListener {}
    uploadClient.close()
  }
}
