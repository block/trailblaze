package xyz.block.trailblaze.logs.server

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A tunnel to the lending port reaches the daemon's device leases and none of its other routes. */
class LendingPortTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val http = HttpClient.newHttpClient()

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  private fun status(port: Int, path: String, post: Boolean = false): Int {
    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
      .let { if (post) it.POST(HttpRequest.BodyPublishers.ofString("{}")) else it.GET() }
      .build()
    return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
  }

  @Test
  fun `the lending port serves the lease routes and nothing else`() {
    val port = freePort()
    val lendingPort = freePort()
    val server = TrailblazeMcpServer(
      logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
      mcpBridge = NoopBridge,
      trailsDirProvider = { tempFolder.newFolder("trails") },
      targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
      llmModelListsProvider = { emptySet() },
    ).startStreamableHttpMcpServer(
      port = port,
      httpsPort = freePort(),
      additionalRouteRegistration = { get("/leases/devices") { call.respondText("[]") } },
      lendingPort = lendingPort,
    )
    try {
      assertEquals(200, status(lendingPort, "/leases/devices"))
      assertEquals(404, status(lendingPort, "/ping"))
      assertEquals(404, status(lendingPort, "/cli/exec", post = true))
      assertEquals(404, status(lendingPort, "/mcp", post = true))
      assertEquals(404, status(lendingPort, "/leases/../ping"))
      // The daemon's own port is unchanged.
      assertEquals(200, status(port, "/ping"))
      assertEquals(200, status(port, "/leases/devices"))
    } finally {
      server.stop(0, 0)
    }
  }

  @Test
  fun `only a lease route is served, however the path is dressed up`() {
    assertTrue(LendingPort.serves("/leases/devices"))
    assertTrue(LendingPort.serves("/leases/abc-123/call?x=1"))
    assertFalse(LendingPort.serves("/cli/exec"))
    assertFalse(LendingPort.serves("/leasesx/devices"))
    assertFalse(LendingPort.serves("/leases/../cli/exec"))
    assertFalse(LendingPort.serves("/leases/%2e%2e/cli/exec"))
    assertFalse(LendingPort.serves("/"))
  }

  @Test
  fun `the env var names the port, or none`() {
    assertNull(LendingPort.fromEnv { null })
    assertNull(LendingPort.fromEnv { " " })
    assertEquals(52520, LendingPort.fromEnv { "52520" })
    assertFailsWith<IllegalStateException> { LendingPort.fromEnv { "lend" } }
  }

  @Test
  fun `a lending daemon's port sits just below its own unless the env var names one`() {
    assertEquals(52520, LendingPort.forDaemon(52525) { null })
    assertEquals(31945, LendingPort.forDaemon(31950) { null })
    assertEquals(40000, LendingPort.forDaemon(52525) { "40000" })
  }
}
