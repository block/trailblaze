package xyz.block.trailblaze.report.otel

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The automatic export path, exercised against a collector that is really listening.
 *
 * Everything on this path is best-effort — a viewer that is not running must not fail a run — which
 * means a defect here has no symptom at the call site at all: the trace is written, the request
 * succeeds, and the spans simply never arrive. So these assert on what the collector received.
 */
class SessionOtelExportTest {

  private lateinit var collector: HttpServer
  private lateinit var received: CountDownLatch
  private val paths = mutableListOf<String>()

  @BeforeTest
  fun startCollector() {
    received = CountDownLatch(1)
    collector = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        synchronized(paths) { paths += exchange.requestURI.path }
        exchange.requestBody.readBytes()
        // An empty body is a well-formed OTLP success response: no partial-success block means
        // everything was accepted.
        exchange.responseHeaders.add("Content-Type", "application/x-protobuf")
        exchange.sendResponseHeaders(200, 0)
        exchange.close()
        received.countDown()
      }
      start()
    }
  }

  @AfterTest
  fun stopCollector() {
    collector.stop(0)
  }

  private fun endpointEnv(): (String) -> String? {
    val endpoint = "http://127.0.0.1:${collector.address.port}"
    return { name -> endpoint.takeIf { name == OtelTraceExport.ENDPOINT_ENV } }
  }

  private fun oneSpanTrace(): String = """
    [
      {"name":"tapOn","cat":"tool","ph":"X","ts":1000,"dur":500,"pid":7,"tid":1,
       "args":{},"sid":"${"a".repeat(16)}","trid":"${"1".repeat(32)}"}
    ]
  """.trimIndent()

  @Test
  fun `a background export reaches the collector`() {
    // The regression: the worker pool was built by a factory that returns a wrapper around the pool
    // rather than the pool itself, so constructing it threw — inside a best-effort `runCatching`.
    // Every device-uploaded trace was silently never exported.
    SessionOtelExport.pushInBackgroundIfConfigured("session-1", oneSpanTrace(), endpointEnv())

    assertTrue(received.await(20, TimeUnit.SECONDS), "the queued export never reached the collector")
    assertTrue(SessionOtelExport.awaitQuiet(20), "the export queue never drained")
    assertEquals(listOf(OtelTraceExport.TRACES_PATH), synchronized(paths) { paths.toList() })
  }

  @Test
  fun `a foreground export reaches the collector`() {
    SessionOtelExport.pushIfConfigured("session-2", oneSpanTrace(), endpointEnv())

    assertTrue(received.await(20, TimeUnit.SECONDS), "the inline export never reached the collector")
  }

  @Test
  fun `nothing is sent when no endpoint is configured`() {
    // The overwhelmingly common case, and the one that must cost nothing: no request, and no worker
    // thread started to discover there was nowhere to send.
    SessionOtelExport.pushInBackgroundIfConfigured("session-3", oneSpanTrace()) { null }

    assertTrue(SessionOtelExport.awaitQuiet(5))
    assertEquals(emptyList(), synchronized(paths) { paths.toList() })
  }

  @Test
  fun `a trace with no spans is not sent`() {
    // An empty trace file is not an error and not worth a request — a session that recorded nothing
    // should not show up in a viewer as an empty trace.
    SessionOtelExport.pushInBackgroundIfConfigured("session-4", "[]", endpointEnv())

    assertTrue(SessionOtelExport.awaitQuiet(5))
    assertEquals(emptyList(), synchronized(paths) { paths.toList() })
  }
}
