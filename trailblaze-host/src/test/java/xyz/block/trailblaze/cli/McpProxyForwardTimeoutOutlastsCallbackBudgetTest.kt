package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isGreaterThanOrEqualTo
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestBuilder
import org.junit.Test
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.logs.server.endpoints.ScriptingCallbackEndpoint
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher

/**
 * The outermost deadline over one tool call has to outlast the longest thing that call can
 * legitimately do, because it is the last hop before the answer reaches whoever asked.
 *
 * A scripted tool that composes device work holds the daemon for the whole nested-callback budget,
 * and the subprocess's fetch timeout and the daemon's outer `tools/call` each sit a buffer above
 * that. If the outer hop gives up first the caller gets a transport error while the daemon keeps
 * working — losing the structured "the tool took too long" answer those inner deadlines exist to
 * produce, and pointing the user at the wrong layer.
 *
 * So the whole chain is asserted here, innermost deadline first: the nested-callback budget, the
 * daemon's own per-call cap above it, and then the outermost hop above that. Two callers hold that
 * outermost hop and each is checked separately — an external agent through [McpProxy], a CLI
 * command through [CliMcpClient]. They share one budget today, but asserting them apart means
 * splitting them later cannot leave one behind.
 *
 * The constants live in different modules — the deadlines in `:trailblaze-host`, the callback
 * budget and the daemon cap in `:trailblaze-server` — so nothing but this test notices when one
 * moves.
 */
class McpProxyForwardTimeoutOutlastsCallbackBudgetTest {

  @Test
  fun `the proxy outlasts the nested callback budget and the buffers stacked above it`() {
    assertThat(McpProxy.DAEMON_REQUEST_TIMEOUT_MS)
      .isGreaterThanOrEqualTo(
        ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS + LADDER_BUFFERS_ABOVE_CALLBACK_MS,
      )
  }

  @Test
  fun `a CLI command's default deadline outlasts that same budget`() {
    // Without this, every case in `CliMcpClientTimeoutTest` compares the resolved timeout to the
    // default symbolically and passes for any number — including a return to a default below the
    // callback budget, which is exactly the bug of the CLI failing a step the daemon then finishes.
    assertThat(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS)
      .isGreaterThanOrEqualTo(
        ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS + LADDER_BUFFERS_ABOVE_CALLBACK_MS,
      )
  }

  @Test
  fun `the daemon's per-call cap sits between the callback ladder and the hop above it`() {
    // The daemon cancels any MCP tool call that outlives this cap, so a cap below the callback
    // ladder makes the longer budget unreachable over MCP — and it expires as a bare coroutine
    // cancellation, which tells the caller its run was cancelled instead of which tool was slow.
    // Above the ladder, below the outermost hop: the innermost deadline is the one that answers.
    assertThat(TrailblazeMcpServer.MCP_TOOL_EXECUTION_TIMEOUT_MS)
      .isGreaterThan(ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS)
    assertThat(McpProxy.DAEMON_REQUEST_TIMEOUT_MS)
      .isGreaterThan(TrailblazeMcpServer.MCP_TOOL_EXECUTION_TIMEOUT_MS)
    assertThat(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS)
      .isGreaterThan(TrailblazeMcpServer.MCP_TOOL_EXECUTION_TIMEOUT_MS)
  }

  @Test
  fun `raising the callback budget moves the daemon's per-call cap with it`() {
    // The cap is the innermost of the three deadlines, so a cap that ignores the override cancels
    // the call before the budget the operator asked for — making a raised
    // `trailblaze.callback.timeoutMs` unreachable over MCP, which is what the property's own
    // documentation promises it is not.
    val raised = ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS + 100_000L
    val previous = System.getProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    try {
      System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, raised.toString())
      assertThat(TrailblazeMcpServer.resolveMcpToolExecutionTimeoutMs()).isGreaterThan(raised)
    } finally {
      if (previous == null) {
        System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
      } else {
        System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, previous)
      }
    }
  }

  @Test
  fun `the per-call cap falls back to the default budget when nothing overrides it`() {
    // The resolved cap and the constant the ordering assertions above are written against have to
    // be the same number at the defaults, or those assertions stop describing what runs.
    val previous = System.getProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    try {
      System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
      assertThat(TrailblazeMcpServer.resolveMcpToolExecutionTimeoutMs())
        .isEqualTo(TrailblazeMcpServer.MCP_TOOL_EXECUTION_TIMEOUT_MS)
    } finally {
      if (previous != null) {
        System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, previous)
      }
    }
  }

  @Test
  fun `a raised callback budget is followable at the proxy hop, not just the daemon's cap`() {
    // The rung the ladder was missing. The daemon's cap follows `trailblaze.callback.timeoutMs` at
    // runtime, so raising that property lifts the cap ABOVE the proxy's default deadline and the
    // proxy becomes the first hop to expire. Every other caller has a lever for that; the proxy
    // pinned the compile-time default, so an external agent had none at any value, and the one
    // failure this whole file exists to prevent survived on the path that cannot opt out.
    val raised = ScriptingCallbackEndpoint.DEFAULT_CALLBACK_TIMEOUT_MS + 100_000L
    val previous = System.getProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
    try {
      System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, raised.toString())
      val cap = TrailblazeMcpServer.resolveMcpToolExecutionTimeoutMs()
      assertThat(cap).isGreaterThan(McpProxy.DAEMON_REQUEST_TIMEOUT_MS)

      // Constructed, not resolved in isolation: the wiring is the finding. A resolver nobody
      // installs would pass a pure assertion and still leave the agent on the old deadline.
      val followed = cap + LADDER_BUFFERS_ABOVE_CALLBACK_MS
      CliCallerContext.withCallerEnv(mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to followed.toString())) {
        assertThat(McpProxy().daemonRequestTimeoutMs).isEqualTo(followed)
      }
    } finally {
      if (previous == null) {
        System.clearProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY)
      } else {
        System.setProperty(JsScriptingCallbackDispatcher.CALLBACK_TIMEOUT_MS_PROPERTY, previous)
      }
    }
  }

  @Test
  fun `the proxy keeps its default deadline when nothing overrides it`() {
    // The ordering assertions above are written against the constant, so the deadline that is
    // actually installed has to equal it at the defaults or they stop describing what runs.
    CliCallerContext.withCallerEnv(emptyMap()) {
      assertThat(McpProxy().daemonRequestTimeoutMs).isEqualTo(McpProxy.DAEMON_REQUEST_TIMEOUT_MS)
    }
  }

  @Test
  fun `raising the callback budget to the proxy's own deadline is not enough`() {
    // The fit assertion above passes for a proxy deadline equal to the callback budget plus the
    // buffers, which would leave the proxy expiring in the same instant as the hop it encloses.
    // Requiring real slack keeps the order of expiry deterministic under a loaded machine.
    assertThat(LADDER_BUFFERS_ABOVE_CALLBACK_MS).isGreaterThanOrEqualTo(MIN_MEANINGFUL_SLACK_MS)
  }

  @Test
  fun `a lowered request deadline pulls the proxy's connect timeout under it`() {
    // The connect budget has to stay inside the deadline that encloses it. Unclamped, a 2s
    // override left a 5s connect attempt outliving the deadline, so the request timer fired
    // while TCP setup was still going -- and the in-flight classifier reads a request timeout as
    // "the daemon may still be running this". A POST that never arrived was reported as in
    // flight, which is the one verdict that suppresses both the retry and the daemon start that
    // a connection going nowhere calls for.
    CliCallerContext.withCallerEnv(mapOf(CliMcpClient.REQUEST_TIMEOUT_ENV to "2000")) {
      val proxy = McpProxy()
      assertThat(proxy.daemonConnectTimeoutMs).isLessThan(proxy.daemonRequestTimeoutMs)
    }
  }

  @Test
  fun `the proxy keeps its full connect ceiling at the default deadline`() {
    // The clamp must not quietly shorten the normal case: at the default deadline the ceiling is
    // far below half the budget, so the value installed is the ceiling itself.
    CliCallerContext.withCallerEnv(emptyMap()) {
      assertThat(McpProxy().daemonConnectTimeoutMs).isEqualTo(McpProxy.PROXY_CONNECT_TIMEOUT_MS)
    }
  }

  @Test
  fun `the ping probe installs a connect budget under its request budget, not equal to it`() {
    // The same race at the reachability probe, where the two budgets were equal and the winner was
    // a coin flip. The request side of that flip reports HELD_UNRESPONSIVE, claiming the port has
    // an owner that went quiet -- which a connect that never completed does not establish, and
    // which costs the CLI a full startup wait when it is wrong.
    //
    // Read off the request the probe configures, not off the two constants: the connect constant is
    // DEFINED as half the request one, so comparing them holds no matter what the probe does with
    // them. What can regress is the wiring -- the connect field handed the request budget -- and
    // only the installed config shows that.
    val installed = HttpRequestBuilder()
      .apply { McpProxy.installPingProbeTimeouts(this) }
      .getCapabilityOrNull(HttpTimeoutCapability)
      ?: error("the probe has to install a timeout config, or neither budget applies")
    val connect = installed.connectTimeoutMillis ?: error("no connect budget installed")
    val request = installed.requestTimeoutMillis ?: error("no request budget installed")

    assertThat(connect).isLessThan(request)
    assertThat(connect).isGreaterThan(0L)
    assertThat(request).isEqualTo(McpProxy.PING_PROBE_TIMEOUT_MS)
  }

  private companion object {
    /**
     * What the callback budget accumulates before it reaches the proxy: the subprocess's client
     * fetch timeout and the daemon's outer `tools/call` each add a 2s buffer
     * (`McpSubprocessSpawner`), plus slack for transport and for the daemon to serialize its
     * answer. Deliberately larger than the sum so the proxy is never the first to expire.
     */
    const val LADDER_BUFFERS_ABOVE_CALLBACK_MS = 20_000L

    /** Two buffered hops plus transport need more than a tick. */
    const val MIN_MEANINGFUL_SLACK_MS = 10_000L
  }
}
