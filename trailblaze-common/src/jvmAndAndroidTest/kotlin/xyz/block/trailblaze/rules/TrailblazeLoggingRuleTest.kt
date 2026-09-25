package xyz.block.trailblaze.rules

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import org.junit.runner.Description
import xyz.block.trailblaze.agent.AgentTier
import xyz.block.trailblaze.agent.model.PromptStepStatus
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.tracing.TraceLevel
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.yaml.DirectionStep

/**
 * Tests for the two consolidated entry points shared by all run methods (JUnit hook, on-device
 * RPC, host CLI):
 *
 * - [TrailblazeLoggingRule.captureFailureScreenshot] — regressions here would silently break
 *   failure-screenshot capture across every path.
 * - [TrailblazeLoggingRule.endSession] — regressions here silently mislabel a run's outcome. The
 *   original bug: host runners ended the session with the immutable instance they started with, so
 *   a mid-run `withSelfHealUsed()` copy (published via [TrailblazeLoggingRule.setSession]) was
 *   dropped and a self-healed pass reported as plain `Succeeded`.
 *
 * Both cover the behavioral branches directly.
 */
class TrailblazeLoggingRuleTest {

  @Test
  fun `captureFailureScreenshot(session) invokes provider AND emits a snapshot log`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    var invocations = 0
    rule.failureScreenStateProvider = {
      invocations++
      FakeScreenState
    }

    rule.captureFailureScreenshot(session())

    assertEquals(1, invocations, "provider should be invoked exactly once")
    val snapshotLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeSnapshotLog>()
    assertEquals(
      1,
      snapshotLogs.size,
      "expected exactly one TrailblazeSnapshotLog to be emitted; got ${captured.map { it::class.simpleName }}",
    )
    assertEquals(
      "failure_screenshot",
      snapshotLogs.single().displayName,
      "snapshot log's displayName must match the contract consumers filter on",
    )
  }

  @Test
  fun `captureFailureScreenshot(session) no-ops when provider is unwired`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    // failureScreenStateProvider is intentionally left un-initialized.

    // Should return cleanly — no UninitializedPropertyAccessException, no throw.
    // The isInitialized guard is the only thing preventing an exception here; if
    // this test passes it means the guard is present.
    rule.captureFailureScreenshot(session())

    assertTrue(
      captured.none { it is TrailblazeLog.TrailblazeSnapshotLog },
      "no snapshot log should be emitted when provider is unwired",
    )
  }

  @Test
  fun `captureFailureScreenshot(session, provider) swallows provider exceptions`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))

    // Using the explicit-provider overload to avoid coupling to the field state.
    // If the try/catch is removed or narrowed, this test will propagate the throw
    // and fail.
    rule.captureFailureScreenshot(session()) {
      throw RuntimeException("simulated driver failure")
    }

    assertTrue(
      captured.none { it is TrailblazeLog.TrailblazeSnapshotLog },
      "no snapshot log should be emitted when the provider throws",
    )
  }

  @Test
  fun `captureFailureScreenshot(session) swallows provider exceptions via the wired path`() {
    val rule = TestLoggingRule()
    rule.failureScreenStateProvider = { throw IllegalStateException("simulated wiring failure") }

    // Same contract as the explicit-provider overload — verifies both paths share
    // the same exception containment.
    rule.captureFailureScreenshot(session())
  }

  // -- endSession: self-heal state must survive to the emitted end status --

  @Test
  fun `endSession reports SucceededWithSelfHeal when self-heal was marked mid-run`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    val started = session()
    rule.setSession(started)

    // What TrailblazeRunnerUtil.markSelfHealUsed does: publish an immutable COPY carrying the flag.
    rule.setSession(started.withSelfHealUsed())

    // The caller still holds the pre-copy instance — the situation every host runner is in.
    rule.endSession(started, isSuccess = true)

    // The status TYPE is the contract consumers branch on (report labels, and
    // AiGeneratedTrails deciding whether to re-record); duration is wall-clock, so don't pin it.
    assertTrue(
      endStatus(captured) is SessionStatus.Ended.SucceededWithSelfHeal,
      "a self-healed run must not be reported as a clean pass; got ${endStatus(captured)}",
    )
  }

  @Test
  fun `endSession reports FailedWithSelfHeal when a self-healed run still fails`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    val started = session()
    rule.setSession(started.withSelfHealUsed())

    rule.endSession(started, isSuccess = false, exception = RuntimeException("boom"))

    assertTrue(
      endStatus(captured) is SessionStatus.Ended.FailedWithSelfHeal,
      "expected FailedWithSelfHeal; got ${endStatus(captured)}",
    )
  }

  @Test
  fun `endSession reports plain Succeeded when self-heal never fired`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    val started = session()
    rule.setSession(started)

    rule.endSession(started, isSuccess = true)

    assertTrue(
      endStatus(captured) is SessionStatus.Ended.Succeeded,
      "a clean run must stay plain Succeeded; got ${endStatus(captured)}",
    )
  }

  @Test
  fun `endSession ignores a live reference belonging to a different session`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    val started = session()
    // A reused rule pointing at some OTHER session that happens to have self-healed. Its state
    // must not leak into this session's verdict, and the emitted log must name THIS session.
    rule.setSession(session(id = "other_session").withSelfHealUsed())

    rule.endSession(started, isSuccess = true)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().single()
    assertTrue(
      log.sessionStatus is SessionStatus.Ended.Succeeded,
      "another session's self-heal must not be attributed here; got ${log.sessionStatus}",
    )
    assertEquals(started.sessionId, log.session, "must end the session the caller started")
  }

  @Test
  fun `endSession falls back to the caller's session when no live reference is set`() {
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))
    val started = session()
    // No setSession at all — the end call must still emit rather than throw or drop the log.

    rule.endSession(started, isSuccess = true)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().single()
    assertEquals(started.sessionId, log.session)
  }

  // -- Trace durability: the trace must be on disk before anyone can see the run is over --

  @Test
  fun `endSession writes the trace before emitting the terminal status`() {
    // The Ended log is what every follower reads as "this run is complete": the daemon closes its
    // live stream on it and the live report rebuilds its payload on it, reading trace.json once.
    // Writing the trace afterwards means that read finds nothing, takes the absence as final, and
    // the run's tracer spans are missing from its report for good. Assert the ORDER, not merely
    // that both happened — both happened before this fix too, in the order that loses the spans.
    val order = mutableListOf<String>()
    val rule = TestLoggingRule(
      additionalLogEmitter = LogEmitter { log ->
        if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) order += "end"
      },
      writeTraceToDisk = { _, _ -> order += "trace" },
    )

    rule.endSession(session(), isSuccess = true)

    assertEquals(
      listOf("trace", "end"),
      order,
      "trace.json must be durable before the terminal status a follower stops reading on",
    )
  }

  @Test
  fun `ending with an explicit status also writes the trace first`() {
    // The overload the on-device RPC's timeout path needs: it names Ended.Cancelled rather than
    // deriving a status from a success flag, so before this existed it could only reach the session
    // manager directly — and a cancelled run's report had no tracer spans at all. Same order
    // contract as the success-flag overload, asserted the same way.
    val order = mutableListOf<String>()
    val rule = TestLoggingRule(
      additionalLogEmitter = LogEmitter { log ->
        if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) order += "end"
      },
      writeTraceToDisk = { _, _ -> order += "trace" },
    )

    rule.endSession(
      session(),
      SessionStatus.Ended.Cancelled(durationMs = 1_000L, cancellationMessage = "timed out"),
    )

    assertEquals(listOf("trace", "end"), order)
  }

  @Test
  fun `endSession files the trace under the session it is ending`() {
    // The export otherwise reads the rule's live reference, which a host runner may have already
    // cleared or repointed — filing the run's spans under "unknown" or another session's id.
    val written = mutableListOf<SessionId>()
    val rule = TestLoggingRule(writeTraceToDisk = { id, _ -> written += id })
    val started = session()
    // No live reference at all — the fallback path endSession already documents.

    rule.endSession(started, isSuccess = true)

    assertEquals(listOf(started.sessionId), written)
  }

  @Test
  fun `a session that ends through both entry points exports its trace only once`() {
    // The exporter DRAINS the tracer, so a second export carries only what was recorded since the
    // first — and two of the three trace writers replace the file rather than merging. A run that
    // calls endSession itself and then goes through the JUnit teardown (the in-process on-device
    // tests do exactly this) would be left with a trace.json holding only its teardown spans.
    val written = mutableListOf<SessionId>()
    val rule = TestLoggingRule(writeTraceToDisk = { id, _ -> written += id })
    val started = session()
    rule.setSession(started)

    rule.endSession(started, isSuccess = true)
    rule.afterTestExecution(DESCRIPTION, Result.success(null))

    assertEquals(listOf(started.sessionId), written, "the second entry point must not drain again")
  }

  @Test
  fun `a trace writer that throws still lets the run report its outcome`() {
    // Containment matters more here than the trace does: this export now runs BEFORE the terminal
    // status, so an uncaught throw would leave the run with no end log at all — stuck "running" in
    // the session list forever, which is strictly worse than the missing spans being fixed.
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(
      additionalLogEmitter = LogEmitter(captured::add),
      writeTraceToDisk = { _, _ -> throw IllegalStateException("disk full") },
    )

    rule.endSession(session(), isSuccess = true)

    assertTrue(
      endStatus(captured) is SessionStatus.Ended.Succeeded,
      "the terminal status must be emitted even when the trace cannot be written",
    )
  }

  @Test
  fun `a no-logging run writes no trace`() {
    // --no-logging promises neither HTTP nor disk writes, and moving the export ahead of the end
    // log must not quietly start writing one.
    val captured = mutableListOf<TrailblazeLog>()
    val written = mutableListOf<SessionId>()
    val rule = TestLoggingRule(
      additionalLogEmitter = LogEmitter(captured::add),
      writeTraceToDisk = { id, _ -> written += id },
      noLogging = true,
    )

    rule.endSession(session(), isSuccess = true)

    assertEquals(emptyList(), written, "a suppressed run must not write a trace to disk")
    assertTrue(captured.isEmpty(), "a suppressed run emits nothing either")
  }

  @Test
  fun `a no-logging run leaves a concurrent run's buffered spans in the recorder`() {
    // The recorder is process-wide and a daemon runs trails concurrently. A suppressed run that
    // emptied it on its way out would take the other run's spans with it, and that run exports an
    // incomplete trace with nothing to say so. Keeping a suppressed run's own spans out of the
    // next session is not this rule's job — every start already clears when it records alone.
    TrailblazeTracer.clear()
    // Pinned rather than inherited: this JVM's TRAILBLAZE_TRACE_LEVEL would otherwise decide
    // whether the fixture records anything at all.
    TrailblazeTracer.withLevel(TraceLevel.NORMAL) { TrailblazeTracer.trace("a-concurrent-run-step") { } }
    assertTrue(
      TrailblazeTracer.exportJson().contains("a-concurrent-run-step"),
      "fixture: the span must be buffered before the suppressed run ends",
    )

    TestLoggingRule(noLogging = true).endSession(session(), isSuccess = true)

    assertTrue(
      TrailblazeTracer.exportJson().contains("a-concurrent-run-step"),
      "a suppressed run must not drain a recorder another run is still filling",
    )
    TrailblazeTracer.clear()
  }

  // -- Clock-domain stamping: the emitter is the only place that knows which clock it is on --

  @Test
  fun `a device-clock rule stamps DEVICE on every emitted log`() {
    // Must happen at EMISSION, not server ingestion: this emitter's server is unreachable, so the
    // log takes the disk-fallback path — exactly the device logs that never pass through the host
    // and would otherwise carry no clock domain at all. Assert on the DISK artifact, not just the
    // observer copy: the pulled-back file is what readers of a disk-fallback session decode, and
    // a regression that stamped only the observer copy would leave those files unmarked.
    val captured = mutableListOf<TrailblazeLog>()
    val written = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(
      additionalLogEmitter = LogEmitter(captured::add),
      useDeviceClock = true,
      writeLogToDisk = { _, log -> written += log },
    )

    rule.endSession(session(), isSuccess = true)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().single()
    assertEquals(TrailblazeClockDomain.DEVICE, log.clock)
    val diskLog = written.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().single()
    assertEquals(
      TrailblazeClockDomain.DEVICE,
      diskLog.clock,
      "the disk-fallback write must carry the same stamped log the observers saw",
    )
  }

  @Test
  fun `a host-clock rule stamps HOST rather than leaving the domain absent`() {
    // Positively, not by omission: a reader can't distinguish an unstamped host log from a log
    // written before the field existed, so it has to guess — and the report's profiler guesses
    // from the log CLASS, which reads a host driver's logs as device-stamped.
    val captured = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(additionalLogEmitter = LogEmitter(captured::add))

    rule.endSession(session(), isSuccess = true)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>().single()
    assertEquals(TrailblazeClockDomain.HOST, log.clock)
  }

  @Test
  fun `a log server that answers after the first ping failed receives the later logs`() {
    // The on-device runner used to decide "no server" from one lazy ping, then wrote every log for
    // the rest of the process to disk — where nothing on the host ever looked — so a device that
    // came up before its log channel did produced a recording with no tool logs at all.
    // The port stays reserved through phase 1. Closing the socket just to learn its number leaves
    // the port free while the phase-1 probe runs, and another test JVM on this machine can take it
    // in that gap — HttpServer.create then dies with BindException, or that other process answers
    // the probe and the disk-fallback assertion below is measuring someone else. The reserved
    // socket never accepts, so phase 1 times out instead of being refused: the same miss.
    val reserved = ServerSocket(0)
    val port = reserved.localPort
    val written = mutableListOf<TrailblazeLog>()
    val rule = TestLoggingRule(
      writeLogToDisk = { _, log -> written += log },
      logsBaseUrl = "http://127.0.0.1:$port",
      logServerRetryAfterMs = 0L,
    )

    rule.endSession(session("before-server"), isSuccess = true)
    assertEquals(1, written.size, "with nothing listening, the log falls back to disk")

    val received = AtomicInteger(0)
    reserved.close()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    try {
      server.createContext("/ping") { exchange ->
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.close()
      }
      server.createContext("/agentlog") { exchange ->
        exchange.requestBody.readBytes()
        received.incrementAndGet()
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.close()
      }
      server.start()

      rule.endSession(session("after-server"), isSuccess = true)
    } finally {
      server.stop(0)
    }

    assertEquals(1, received.get(), "once the server answers, the next log must reach it")
    assertEquals(1, written.size, "a log the server accepted must not also fall back to disk")
  }

  @Test
  fun `a tool catalog the server missed is sent again with the next request`() {
    // A catalog is written once per session and every later request only names it. An upload
    // that fails falls back to the device's disk without telling the logger, so without a
    // re-send the host holds a session of requests pointing at a catalog it never received.
    val acceptedCatalogs = AtomicInteger(0)
    val rejectedCatalogs = AtomicInteger(0)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/ping") { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.close()
    }
    server.createContext("/agentlog") { exchange ->
      val isCatalog = exchange.requestBody.readBytes().decodeToString().contains("TrailblazeToolCatalogLog")
      val status = when {
        !isCatalog -> 200
        rejectedCatalogs.getAndIncrement() == 0 -> 500
        else -> 200.also { acceptedCatalogs.incrementAndGet() }
      }
      exchange.sendResponseHeaders(status, 0)
      exchange.responseBody.close()
    }
    server.start()
    try {
      val written = mutableListOf<TrailblazeLog>()
      val rule = TestLoggingRule(
        writeLogToDisk = { _, log -> written += log },
        logsBaseUrl = "http://127.0.0.1:${server.address.port}",
      )
      val session = session("catalog-resend")

      repeat(3) { rule.logLlmRequestOffering(session, listOf("tap", "inputText")) }

      assertEquals(1, acceptedCatalogs.get(), "the missed catalog must reach the server exactly once more")
      assertEquals(
        listOf("TrailblazeToolCatalogLog"),
        written.map { it::class.simpleName },
        "only the rejected upload falls back to disk",
      )
    } finally {
      server.stop(0)
    }
  }

  // -- Which log endpoints skip the device's HTTP proxy --

  @Test
  fun `a device-local log endpoint is reached without the device's proxy`() {
    // The bug: a network capture points the device's global HTTP proxy at the host, and a daemon
    // that dies mid-capture leaves it pointing at a port nothing listens on. Every log upload then
    // died in that proxy, even though the log server is on the other end of an `adb reverse` and
    // never needed it. Here the proxy WOULD answer this endpoint, so routing through it would
    // succeed — the disk fallback is what proves the log channel went direct.
    withFakeProxyAsSystemDefault(answeringOnlyFor = "//localhost:1/") { proxiedRequestLines ->
      val written = mutableListOf<TrailblazeLog>()
      val rule = TestLoggingRule(
        writeLogToDisk = { _, log -> written += log },
        logsBaseUrl = "http://localhost:1",
      )

      rule.endSession(session("device-local"), isSuccess = true)

      assertTrue(
        proxiedRequestLines.none { it.contains("//localhost:1/") },
        "a device-local log endpoint must not be routed through the proxy, saw $proxiedRequestLines",
      )
      assertEquals(1, written.size, "nothing is listening on port 1, so the log falls back to disk")
    }
  }

  @Test
  fun `a remote log endpoint is still reached through the device's proxy`() {
    // `trailblaze.logsEndpoint` may name a remote log server, and on a device that reaches the
    // outside world only through its proxy, that proxy is the route. A blanket bypass would send
    // every log for such a run to disk instead.
    withFakeProxyAsSystemDefault(answeringOnlyFor = "//logs.example.test:52526/") { proxiedRequestLines ->
      val written = mutableListOf<TrailblazeLog>()
      val rule = TestLoggingRule(
        writeLogToDisk = { _, log -> written += log },
        // A `.test` host never resolves, so the proxy is the only thing that can answer it.
        logsBaseUrl = "http://logs.example.test:52526",
      )

      rule.endSession(session("remote"), isSuccess = true)

      assertTrue(
        proxiedRequestLines.any { it.contains("//logs.example.test:52526/") },
        "a remote log endpoint must keep the proxy, saw $proxiedRequestLines",
      )
      assertTrue(written.isEmpty(), "the proxy accepted the log, so it must not also go to disk")
    }
  }

  // -- Fixtures --

  /**
   * Points the JVM's proxy selection at a fake proxy the way Android points it at the capture
   * proxy: through the `http.proxy*` system properties, which the default selector re-reads on
   * every lookup. Swapping the selector instance would not do — Ktor's OkHttp engine captures
   * `ProxySelector.getDefault()` once per JVM into a shared prototype, so the throwaway client
   * below forces that capture before this fixture changes anything.
   *
   * The block gets the request lines the proxy saw, so proxied-vs-direct is observable from either
   * side: a proxied request names its endpoint in one of them, a direct one leaves none.
   *
   * The proxy `200`s only requests for [answeringOnlyFor] and `502`s the rest. The properties are
   * JVM-wide and this module's tests share the JVM, so a blanket `200` would make some other test's
   * unreachable log server suddenly answer; a `502` leaves it with the same unusable channel a
   * refused connection gives it.
   */
  private fun withFakeProxyAsSystemDefault(
    answeringOnlyFor: String,
    block: (proxiedRequestLines: List<String>) -> Unit,
  ) {
    runBlocking { HttpClient(OkHttp).use { } }

    val requestLines = Collections.synchronizedList(mutableListOf<String>())
    val proxy = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    thread(isDaemon = true) {
      while (!proxy.isClosed) {
        val socket = try {
          proxy.accept()
        } catch (e: IOException) {
          break
        }
        thread(isDaemon = true) { serveAsProxy(socket, answeringOnlyFor, requestLines) }
      }
    }

    val previous = PROXY_PROPERTIES.associateWith { System.getProperty(it) }
    System.setProperty("http.proxyHost", "127.0.0.1")
    System.setProperty("http.proxyPort", proxy.localPort.toString())
    // The JDK exempts loopback from proxying by default; an empty list turns that exemption off, so
    // a loopback endpoint that did not bypass would really go through the proxy.
    System.setProperty("http.nonProxyHosts", "")
    try {
      block(requestLines)
    } finally {
      previous.forEach { (key, value) ->
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
      }
      proxy.close()
    }
  }

  /** Reads whole requests off [socket] — proxy-style absolute URIs included — and answers each. */
  private fun serveAsProxy(socket: Socket, answeringOnlyFor: String, requestLines: MutableList<String>) {
    socket.use {
      val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
      val out = socket.getOutputStream()
      while (true) {
        val requestLine = reader.readLine() ?: return
        if (requestLine.isBlank()) continue
        var contentLength = 0
        while (true) {
          val header = reader.readLine() ?: return
          if (header.isEmpty()) break
          if (header.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
          }
        }
        if (contentLength > 0) reader.read(CharArray(contentLength), 0, contentLength)
        requestLines += requestLine
        val status = if (requestLine.contains(answeringOnlyFor)) "200 OK" else "502 Bad Gateway"
        out.write("HTTP/1.1 $status\r\nContent-Length: 0\r\n\r\n".toByteArray())
        out.flush()
      }
    }
  }

  private fun TrailblazeLoggingRule.logLlmRequestOffering(session: TrailblazeSession, toolNames: List<String>) {
    logger.logLlmRequest(
      session = session,
      koogLlmRequestMessages = emptyList(),
      stepStatus = PromptStepStatus(
        promptStep = DirectionStep(step = "do the thing"),
        screenStateProvider = { FakeScreenState },
      ).also { it.prepareNextStep() },
      trailblazeLlmModel = TrailblazeLlmModel(
        trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
        modelId = "test-model",
        inputCostPerOneMillionTokens = 0.0,
        outputCostPerOneMillionTokens = 0.0,
        contextLength = 1_000,
        maxOutputTokens = 1_000,
        capabilityIds = emptyList(),
      ),
      response = Message.Assistant(parts = emptyList(), metaInfo = ResponseMetaInfo.create(KoogClock.System)),
      startTime = Clock.System.now(),
      traceId = TraceId.generate(TraceId.Companion.TraceOrigin.LLM),
      toolDescriptors = toolNames.map { ToolDescriptor(name = it, description = "does $it") },
      requestContext = TrailblazeLog.LlmRequestContext(
        agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
        llmCallStrategy = LlmCallStrategy.DIRECT,
        agentTier = AgentTier.OUTER,
      ),
    )
  }

  private fun endStatus(captured: List<TrailblazeLog>): SessionStatus =
    captured.filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .single()
      .sessionStatus

  /** Any Description will do — afterTestExecution reads the result, not the description. */
  private val DESCRIPTION: Description = Description.createTestDescription("Fake", "run")

  /** The properties the JDK's default proxy selector reads, saved and restored around a fixture. */
  private val PROXY_PROPERTIES = listOf("http.proxyHost", "http.proxyPort", "http.nonProxyHosts")

  private fun session(id: String = "test_session"): TrailblazeSession = TrailblazeSession(
    sessionId = SessionId(id),
    startTime = Clock.System.now(),
  )

  /**
   * Concrete [TrailblazeLoggingRule] with a fast-fail [logsBaseUrl] so the
   * `isServerAvailable` HTTP ping returns connection-refused immediately instead
   * of waiting for the 2 s timeout. Disk writes are no-ops by default. Callers
   * can inject an [additionalLogEmitter] to capture emitted logs.
   */
  private class TestLoggingRule(
    additionalLogEmitter: LogEmitter? = null,
    override val useDeviceClock: Boolean = false,
    writeLogToDisk: ((SessionId, TrailblazeLog) -> Unit) = { _, _ -> },
    writeTraceToDisk: ((SessionId, String) -> Unit) = { _, _ -> },
    noLogging: Boolean = false,
    logsBaseUrl: String = "http://127.0.0.1:1",
    override val logServerRetryAfterMs: Long = LogServerAvailability.DEFAULT_INITIAL_RETRY_AFTER_MS,
  ) : TrailblazeLoggingRule(
    logsBaseUrl = logsBaseUrl,
    additionalLogEmitter = additionalLogEmitter,
    writeLogToDisk = writeLogToDisk,
    writeTraceToDisk = writeTraceToDisk,
    noLogging = noLogging,
  ) {
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
  }

  private object FakeScreenState : ScreenState {
    // Non-null bytes are required: TrailblazeLogger.logScreenState short-circuits on
    // null screenshotBytes, which would suppress the TrailblazeSnapshotLog emission.
    // Use a PNG magic-number prefix so ImageFormatDetector.detectFormat returns PNG
    // and the generated filename is valid.
    override val screenshotBytes: ByteArray = byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode(
      nodeId = 1,
      className = "FrameLayout",
    )
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
  }
}
