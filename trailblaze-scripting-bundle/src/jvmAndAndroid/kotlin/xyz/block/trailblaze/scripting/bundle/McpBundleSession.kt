package xyz.block.trailblaze.scripting.bundle

import com.dokar.quickjs.QuickJs
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One live on-device bundle — the QuickJS engine with the evaluated `.js`, the
 * in-process transport plumbing, and the MCP [Client] you talk to it with.
 *
 * ### Using this
 *
 * ```kotlin
 * val session = McpBundleSession.connect(BundleJsSource.FromFile("tools.bundle.js"))
 * try {
 *   val tools = session.client.listTools(ListToolsRequest()).tools
 *   val result = session.client.callTool(CallToolRequest(…))
 * } finally {
 *   session.shutdown()
 * }
 * ```
 *
 * Most callers don't touch this class directly — they set
 * `AndroidTrailblazeRule(mcpServers = listOf(McpServerConfig(script = "…")))`
 * and the rule's lifecycle drives [connect] + [shutdown] for them. Use the session
 * primitive directly when you're writing a test or integrating the bundle runtime
 * somewhere the rule doesn't reach.
 *
 * ### Lifecycle
 *
 * [connect] does the full startup in one call: evaluate the prelude, evaluate the
 * author's bundle, finish the MCP `initialize` handshake. By the time it returns
 * `tools/list` is safe to call.
 *
 * [shutdown] tears the session down: closes the MCP Client (which closes the transport
 * and fires the JS-side `onclose` hook), then frees the QuickJS native allocation. Runs
 * under `Dispatchers.IO` because `JS_FreeRuntime` can block on bundles with lots of
 * retained JS allocations.
 */
class McpBundleSession internal constructor(
  /**
   * The QuickJS engine hosting the bundle. Owned by this session; closed in [shutdown].
   * Exposed so tests can `evaluate<T>` into the bundle out-of-band — production code
   * should go through [client] instead.
   */
  val quickJs: QuickJs,
  val transport: InProcessMcpTransport,
  val client: Client,
  val bundleFilename: String,
) {

  /** Close the client + transport + QuickJS engine. Best-effort; safe to call multiple times. */
  suspend fun shutdown() {
    runCatching { client.close() }
    withContext(Dispatchers.IO) {
      runCatching { quickJs.close() }
    }
  }

  companion object {

    /** What this runtime advertises to bundles as the connecting MCP client. */
    val DEFAULT_CLIENT_INFO: Implementation = Implementation(
      name = "trailblaze-bundle",
      version = "0.1.0",
    )

    /**
     * Spin up a QuickJS engine, evaluate the bundle, and return a ready-to-use session
     * with the MCP handshake complete. Failures at any stage close the engine + rethrow.
     */
    suspend fun connect(
      bundleSource: BundleJsSource,
      clientInfo: Implementation = DEFAULT_CLIENT_INFO,
    ): McpBundleSession {
      // QuickJs.create is suspend because it loads the native library on first call.
      // Subsequent session starts are fast — the library stays loaded.
      val quickJs = QuickJs.create(Dispatchers.Default)
      try {
        // The order of the next six evaluations matters — don't reorder.
        //
        // MCP's handshake is symmetric: Client.connect() fires an `initialize` request
        // over the transport, and the JS Server's `server.connect(transport)` is what
        // installs the transport's `onmessage` handler. If we flip the last two steps,
        // the Client's initialize request lands on a transport with no handler and the
        // prelude rejects it. So: bridge → prelude (installs transport global and the
        // console shim) → SDK bundle (installs globalThis.trailblaze / globalThis.fromMeta
        // so plain-JS authors can skip the npm/bundler step) → author bundle (top-level
        // `await trailblaze.run()` picks up the pre-installed in-process transport and
        // wires its MCP Server onto it) → transport → Client.connect.
        val bridge = QuickJsBridge(quickJs)
        bridge.evaluate(BundleRuntimePrelude.SOURCE, "trailblaze-bundle-prelude.js")
        bridge.evaluate(BundleRuntimePrelude.SDK_BUNDLE_SOURCE, BundleRuntimePrelude.SDK_BUNDLE_FILENAME)

        // Read the author bundle here (not in the catch block below) so asset/filesystem
        // load errors surface with the bundle filename attached, not as a generic
        // QuickJS evaluation error.
        val bundleJs = bundleSource.read()
        bridge.evaluate(bundleJs, bundleSource.filename)

        val transport = InProcessMcpTransport(bridge)
        val client = Client(clientInfo, ClientOptions())
        client.connect(transport)

        return McpBundleSession(
          quickJs = quickJs,
          transport = transport,
          client = client,
          bundleFilename = bundleSource.filename,
        )
      } catch (t: Throwable) {
        // Fail-fast cleanup so a half-initialized session doesn't leak the native engine.
        // withContext(IO) because QuickJs.close runs a JNI free that can block.
        withContext(Dispatchers.IO) { runCatching { quickJs.close() } }
        throw t
      }
    }
  }
}
