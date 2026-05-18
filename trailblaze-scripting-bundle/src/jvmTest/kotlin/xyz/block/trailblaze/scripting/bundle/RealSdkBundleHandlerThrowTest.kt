package xyz.block.trailblaze.scripting.bundle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isInstanceOf
import assertk.assertions.startsWith
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.scripting.mcp.toTrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import kotlin.test.Test

/**
 * End-to-end coverage for the README claim under `sdks/typescript/README.md`
 * § "Error handling — how failures become session-log entries": a TS author's handler that
 * throws produces a `TrailblazeToolResult.Error.ExceptionThrown` whose `errorMessage`
 * carries both the JS error message AND a stack frame referencing the source file.
 *
 * The sibling tests cover each layer in isolation:
 *
 *  - `sdks/typescript/src/tool.test.ts` proves the SDK shim's catch builds the
 *    `{ isError: true, content: [{ text: "Error: msg\n  at ..." }] }` envelope.
 *  - `QuickJsToolHostTest` proves the host runtime's `callTool` maps the envelope.
 *  - The on-device wire-up that bundles the real `trailblaze-sdk-bundle.js` with an
 *    author's `.js` and dispatches through it had no end-to-end check — if the committed
 *    bundle ever drifts from `sdks/typescript/src/tool.ts` (a missed `bundleTrailblazeSdk`
 *    regen, an SDK refactor that breaks the catch), no test in the suite caught it. This
 *    test closes that gap.
 *
 * ### Why the in-process bundle path is the closest harness available
 *
 * The truly on-device runtime is Android-only (QuickJS via the AAR, evaluated by an
 * instrumented app). `McpBundleSession` is the same evaluator + transport plumbing used
 * on-device, just hosted on the JVM — which is exactly what `InProcessBundleRoundTripTest`
 * already pins for the happy-path tools/list + tools/call surface. Pinning the throw path
 * here uses the **real** committed `trailblaze-sdk-bundle.js` (the production bundle
 * `BundleRuntimePrelude.SDK_BUNDLE_SOURCE` loads on-device) rather than the hand-rolled
 * MCP-like fixture that `InProcessBundleRoundTripTest` uses for its driver-filter assertions.
 * That's the upgrade in coverage — a regression in the SDK shim's `try/catch` block will
 * show up here.
 */
class RealSdkBundleHandlerThrowTest {

  /**
   * Tiny author-side bundle that the runtime evaluates after `trailblaze-sdk-bundle.js`.
   * Calls `trailblaze.tool(name, spec, handler)` with a handler that synchronously throws —
   * the SDK's `registerPendingTools` wraps every handler in the try/catch that builds the
   * stack-bearing `isError` envelope. `await trailblaze.run()` picks up the prelude's
   * pre-installed `__trailblazeInProcessTransport` and connects the MCP server onto it.
   *
   * The throw lives on a clearly-named source line so the assertion on the JS stack
   * frame's bundle filename is unambiguous in failure output.
   */
  private val authorBundle: String = """
    trailblaze.tool("e2e_thrower", {}, async () => {
      throw new Error("e2e boom");
    });
    await trailblaze.run();
  """.trimIndent()

  @Test fun `real SDK bundle round-trips a handler throw with name message and stack`() {
    runBlocking {
      val session = McpBundleSession.connect(
        bundleSource = BundleJsSource.FromString(authorBundle, "e2e-thrower-author-bundle.js"),
      )
      try {
        val response = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(name = "e2e_thrower", arguments = null),
          ),
        )
        val mapped = response.toTrailblazeToolResult()
        assertThat(mapped).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
        val message = (mapped as TrailblazeToolResult.Error.ExceptionThrown).errorMessage
        // The SDK shim builds the envelope text from `error.stack`, which in V8/QuickJS
        // starts with `Error: <message>\n` — so the message text begins with the JS
        // error's `name: message` header. A regression in the catch block (e.g. forgetting
        // to include `error.message`, or stripping the prefix) trips here.
        assertThat(message).startsWith("Error: ")
        assertThat(message).contains("e2e boom")
        // The stack frame must reference the bundled SDK source — QuickJS attributes
        // stack frames to the filename passed to `quickJs.evaluate`, which for the SDK is
        // `BundleRuntimePrelude.SDK_BUNDLE_FILENAME` (`trailblaze-sdk-bundle.js`). Without
        // the SDK shim's catch, the throw escapes the MCP SDK boundary and the stack is
        // lost — this assertion is the canary for that regression.
        assertThat(message).contains(BundleRuntimePrelude.SDK_BUNDLE_FILENAME)
      } finally {
        session.shutdown()
      }
    }
  }
}
