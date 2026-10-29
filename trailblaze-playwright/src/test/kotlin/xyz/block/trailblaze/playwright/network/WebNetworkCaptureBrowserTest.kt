package xyz.block.trailblaze.playwright.network

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WebNetworkCapture] against a real browser whose page keeps sending requests while nothing calls
 * Playwright, as a browser a daemon keeps between sessions does. The client holds those requests'
 * events until the next call, so these tests pin which capture they land in.
 */
class WebNetworkCaptureBrowserTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  private lateinit var server: HttpServer
  private lateinit var playwright: Playwright
  private lateinit var browser: Browser
  private lateinit var context: BrowserContext
  private lateinit var page: Page

  /** Every tick the server has answered, by the page's counter. */
  private val ticksServed: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  @Before fun setUp() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val path = exchange.requestURI.path
      if (path == "/tick") {
        TICK.find(exchange.requestURI.query.orEmpty())?.let { ticksServed += it.groupValues[1].toInt() }
      }
      val body = when (path) {
        "/page" -> "<script>let n=0;setInterval(()=>fetch('/tick?n='+(n++)),$TICK_INTERVAL_MS)</script>"
        else -> "ok"
      }.toByteArray()
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()
    playwright = Playwright.create()
    browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    context = browser.newContext()
    page = context.newPage()
    // The browser manager traces every page's requests, and that listener is what makes the driver
    // send request events between captures. Without one there is no backlog to misattribute.
    page.onRequest {}
    page.navigate("http://127.0.0.1:${server.address.port}/page")
  }

  @After fun tearDown() {
    WebNetworkCapture.stop(context)
    browser.close()
    playwright.close()
    server.stop(0)
  }

  @Test
  fun `requests sent before a capture started are not recorded as its traffic`() {
    // Nothing calls Playwright here, so these requests' events wait in the client.
    awaitTicksServedAfter(-1)
    val sentBeforeStart = ticksServed.toSet()

    val dir = tmp.newFolder()
    WebNetworkCapture.start(context, "session-b", dir)
    awaitTicksServedAfter(sentBeforeStart.max())
    WebNetworkCapture.stop(context)

    val recorded = recordedTicks(dir)
    assertTrue(recorded.isNotEmpty(), "the capture recorded none of the page's own ticks")
    assertEquals(emptySet(), recorded intersect sentBeforeStart)
  }

  @Test
  fun `requests sent during a capture are recorded even if nothing called Playwright before stop`() {
    val dir = tmp.newFolder()
    WebNetworkCapture.start(context, "session-a", dir)
    // Nothing calls Playwright from here to stop, so only stop can dispatch these ticks' events.
    awaitTicksServedAfter(ticksServed.maxOrNull() ?: -1)
    val servedBeforeStop = ticksServed.toSet()
    WebNetworkCapture.stop(context)

    val recorded = recordedTicks(dir)
    assertTrue(recorded.size >= MIN_TICKS, "the capture recorded only ${recorded.size} ticks")
    // Cut at the first recorded tick: a tick in flight across start is fairly either capture's.
    assertEquals(emptySet(), servedBeforeStop.filter { it >= recorded.min() }.toSet() - recorded)
  }

  /** Waits, without calling Playwright, until the page has sent [MIN_TICKS] ticks after [tick]. */
  private fun awaitTicksServedAfter(tick: Int) {
    val deadline = System.currentTimeMillis() + TICK_WAIT_CEILING_MS
    while (ticksServed.count { it > tick } < MIN_TICKS) {
      check(System.currentTimeMillis() < deadline) {
        "the page sent ${ticksServed.count { it > tick }} ticks after $tick within ${TICK_WAIT_CEILING_MS}ms"
      }
      Thread.sleep(TICK_INTERVAL_MS.toLong())
    }
  }

  private fun recordedTicks(dir: File): Set<Int> =
    File(dir, WebNetworkCapture.NDJSON_FILENAME).readLines()
      .filter { "\"phase\":\"REQUEST_START\"" in it }
      .mapNotNull { TICK.find(it)?.groupValues?.get(1)?.toInt() }
      .toSet()

  private companion object {
    const val TICK_INTERVAL_MS = 50
    const val MIN_TICKS = 8
    const val TICK_WAIT_CEILING_MS = 60_000L
    val TICK = Regex("""n=(\d+)""")
  }
}
