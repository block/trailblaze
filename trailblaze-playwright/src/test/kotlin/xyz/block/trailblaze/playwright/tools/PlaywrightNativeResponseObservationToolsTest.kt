package xyz.block.trailblaze.playwright.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.network.WebResponseObservationManager
import xyz.block.trailblaze.playwright.network.WebResponseObservationRegistry
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaywrightNativeResponseObservationToolsTest {
  private lateinit var server: HttpServer
  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var page: Page
  private lateinit var origin: String
  private lateinit var delayedRequestStarted: CountDownLatch
  private lateinit var releaseDelayedResponse: CountDownLatch

  @Before fun setUp() {
    delayedRequestStarted = CountDownLatch(1)
    releaseDelayedResponse = CountDownLatch(1)
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      if (exchange.requestURI.path == "/target" || exchange.requestURI.path == "/delayed") {
        if (exchange.requestURI.path == "/delayed") {
          delayedRequestStarted.countDown()
          releaseDelayedResponse.await(2, TimeUnit.SECONDS)
        }
        exchange.sendResponseHeaders(204, -1)
        exchange.close()
        return@createContext
      }
      val body = when (exchange.requestURI.path) {
        "/iframe" -> "<script>fetch('/target?secret=iframe',{method:'POST'})</script>"
        "/navigate" -> "<script>fetch('/target?secret=do-not-log',{method:'POST'})</script>"
        else -> "<h1>Ready</h1>"
      }.toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    origin = "http://127.0.0.1:${server.address.port}"
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    page = browser.newPage()
    page.navigate("$origin/page")
  }

  @After fun tearDown() {
    browser.close()
    playwright.close()
    server.stop(0)
  }

  private fun context(sessionId: String = "response-test") = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      TrailblazeDeviceId("web-test", TrailblazeDevicePlatform.WEB),
      TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      1280,
      800,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(SessionId(sessionId), Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private fun begin(timeoutMs: Long = 1_000) = PlaywrightNativeBeginResponseObservationTool(
    observationId = "proof",
    origin = origin,
    path = "/target",
    method = WebResponseHttpMethod.POST,
    timeoutMs = timeoutMs,
  )

  @Test
  fun `main-frame post-arm response matches across navigation without retaining its query`() = runBlocking {
    assertIs<TrailblazeToolResult.Success>(begin().executeWithPlaywright(page, context()))
    page.navigate("$origin/navigate")

    val result = PlaywrightNativeAssertResponseObservedTool("proof")
      .executeWithPlaywright(page, context())

    val success = assertIs<TrailblazeToolResult.Success>(result)
    assertContains(success.message.orEmpty(), "POST $origin/target with HTTP 204")
    assertFalse(success.message.orEmpty().contains("secret"))
  }

  @Test
  fun `same-page iframe traffic fails closed`() = runBlocking {
    assertIs<TrailblazeToolResult.Success>(begin(250).executeWithPlaywright(page, context()))
    page.setContent("<iframe src='$origin/iframe'></iframe>")
    page.waitForTimeout(300.0)

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      PlaywrightNativeAssertResponseObservedTool("proof").executeWithPlaywright(page, context())
    )
    Unit
  }

  @Test
  fun `pre-arm response is stale and cannot match`() = runBlocking {
    WebResponseObservationRegistry.manager(page, "response-test")
    page.evaluate("fetch('/target',{method:'POST'}).then(r => r.text())")
    assertIs<TrailblazeToolResult.Success>(begin(100).executeWithPlaywright(page, context()))
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      PlaywrightNativeAssertResponseObservedTool("proof").executeWithPlaywright(page, context())
    )
    Unit
  }

  @Test
  fun `request started before arm cannot match after its delayed response arrives`() = runBlocking {
    WebResponseObservationRegistry.manager(page, "response-test")
    page.evaluate("fetch('/delayed',{method:'POST'}); undefined")
    assertTrue(delayedRequestStarted.await(1, TimeUnit.SECONDS))

    val delayedObservation = begin(250).copy(path = "/delayed")
    assertIs<TrailblazeToolResult.Success>(delayedObservation.executeWithPlaywright(page, context()))
    releaseDelayedResponse.countDown()

    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      PlaywrightNativeAssertResponseObservedTool("proof").executeWithPlaywright(page, context())
    )
    Unit
  }

  @Test
  fun `cancel observation is idempotent and immediately releases state`() = runBlocking {
    assertIs<TrailblazeToolResult.Success>(begin().executeWithPlaywright(page, context()))
    val manager = WebResponseObservationRegistry.manager(page, "response-test")
    assertEquals(1, manager.activeCount())

    val cancel = PlaywrightNativeCancelResponseObservationTool("proof")
    assertIs<TrailblazeToolResult.Success>(cancel.executeWithPlaywright(page, context()))
    assertIs<TrailblazeToolResult.Success>(cancel.executeWithPlaywright(page, context()))
    assertEquals(0, manager.activeCount())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      PlaywrightNativeAssertResponseObservedTool("proof").executeWithPlaywright(page, context())
    )
    Unit
  }

  @Test
  fun `session rollover and page close clear abandoned observations`() {
    val first = WebResponseObservationRegistry.manager(page, "first")
    assertIs<WebResponseObservationManager.BeginResult.Success>(
      first.begin(
        "proof",
        WebResponseObservationManager.Criteria(origin, "/target", "POST", 200, 299),
        1_000,
      )
    )
    val second = WebResponseObservationRegistry.manager(page, "second")
    assertEquals(0, second.activeCount())
    assertIs<WebResponseObservationManager.BeginResult.Success>(
      second.begin(
        "proof",
        WebResponseObservationManager.Criteria(origin, "/target", "POST", 200, 299),
        1_000,
      )
    )
    page.close()
    assertEquals(0, second.activeCount())
  }

  @Test
  fun `matcher input validation rejects unsafe paths and cross-origin observations`() = runBlocking {
    assertFalse(PlaywrightNativeBeginResponseObservationTool.isSafeLiteralPath("/a/../target"))
    assertFalse(PlaywrightNativeBeginResponseObservationTool.isSafeLiteralPath("/target%2Fsecret"))
    val result = begin().copy(origin = "https://example.com")
      .executeWithPlaywright(page, context())
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
    Unit
  }
}
