package xyz.block.trailblaze.scripting.bundle

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test

/**
 * End-to-end coverage of the in-process bundle transport — the PR A5 analog to the
 * subprocess-runtime's `SampleAppMcpSdkToolsTest`.
 *
 * Uses a hand-crafted JS fixture that speaks the MCP wire protocol directly against
 * `globalThis.__trailblazeInProcessTransport` — no real MCP JS SDK required, so the test
 * exercises the Kotlin-side transport + launcher + registration plumbing without a bundled
 * SDK dependency. That's deliberate: the on-device production path bundles the real SDK
 * (`@modelcontextprotocol/sdk` + `zod`) via esbuild, but those deps are huge (~300 KB of
 * bundled JS) and their semantics are tested in the author's own bundle build. What the
 * Trailblaze runtime is responsible for — the transport bridge, `tools/list` registration,
 * `tools/call` round-trip, the `_meta.trailblaze/requiresHost` filter — is all exercised
 * here in milliseconds.
 *
 * The fixture JS lives at `src/jvmTest/resources/fixtures/bundle-roundtrip-fixture.js`
 * so it's syntax-highlightable / lintable as a standalone `.js` file rather than an
 * inline Kotlin string. Code review feedback drove the externalization.
 *
 * ### What the fixture bundle does
 *
 * Installs a tiny MCP-like Server directly on `globalThis.__trailblazeInProcessTransport`
 * that responds to:
 *
 *  - `initialize` → echoes back `{ protocolVersion, capabilities: { tools: {} }, serverInfo }`.
 *    Required by the Client's handshake.
 *  - `notifications/initialized` → silently acknowledged (notifications have no response).
 *  - `tools/list` → returns three tools:
 *      * `echoReverse` — no `_meta`, always registers.
 *      * `hostOnlyTool` — `_meta: { "trailblaze/requiresHost": true }`, skipped on-device.
 *      * `instrumentationOnlyTool` — driver-scoped via `_meta.supportedDrivers`.
 *  - `tools/call` for `echoReverse` → returns `content: [{ type: "text", text: reversed }]`.
 *
 * ### What the test asserts
 *
 *  1. [`tools/list` drops `requiresHost: true` tools] — on-device registration filters
 *     correctly.
 *  2. [`tools/list` respects `supportedDrivers`] — driver-specific tools only register
 *     for their driver.
 *  3. [round-trip tool call flows correctly through the bridge] — message serializes to
 *     JS, JS dispatches, response deserializes on Kotlin side, Client sees the result.
 */
class InProcessBundleRoundTripTest {

  private val fixtureBundle: String by lazy {
    javaClass.classLoader
      .getResourceAsStream("fixtures/bundle-roundtrip-fixture.js")
      ?.use { it.readBytes().decodeToString() }
      ?: error(
        "Missing fixture resource: fixtures/bundle-roundtrip-fixture.js. " +
          "Is src/jvmTest/resources/ wired in the build?",
      )
  }

  @Test fun `tools list drops requiresHost tools on-device`() = runBlocking {
    val session = McpBundleSession.connect(
      bundleSource = BundleJsSource.FromString(fixtureBundle, "bundle-roundtrip-fixture.js"),
    )
    try {
      val advertised = session.client.listTools(ListToolsRequest()).tools.map { it.name }
      // Sanity: all three tools advertise over MCP — filter hasn't run yet.
      assertThat(advertised).containsExactlyInAnyOrder(
        "echoReverse",
        "hostOnlyTool",
        "instrumentationOnlyTool",
      )

      // Now run them through the on-device registration filter. On-device is always
      // preferHostAgent = false, so requiresHost=true drops.
      val filteredAccessibility = session.fetchAndFilterTools(
        driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ).map { it.advertisedName.toolName }

      assertThat(filteredAccessibility).contains("echoReverse")
      assertThat(filteredAccessibility).doesNotContain("hostOnlyTool")
      // instrumentationOnlyTool declares supportedDrivers=[android-ondevice-instrumentation],
      // so it should NOT register on the accessibility driver.
      assertThat(filteredAccessibility).doesNotContain("instrumentationOnlyTool")
    } finally {
      session.shutdown()
    }
  }

  @Test fun `tools list respects supportedDrivers filter`() = runBlocking {
    val session = McpBundleSession.connect(
      bundleSource = BundleJsSource.FromString(fixtureBundle, "bundle-roundtrip-fixture.js"),
    )
    try {
      val filteredInstrumentation = session.fetchAndFilterTools(
        driver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ).map { it.advertisedName.toolName }

      // instrumentationOnlyTool registers for its matching driver.
      assertThat(filteredInstrumentation).contains("echoReverse")
      assertThat(filteredInstrumentation).contains("instrumentationOnlyTool")
      // requiresHost still drops.
      assertThat(filteredInstrumentation).doesNotContain("hostOnlyTool")
    } finally {
      session.shutdown()
    }
  }

  @Test fun `round-trip tool call flows through the bridge`() = runBlocking {
    val session = McpBundleSession.connect(
      bundleSource = BundleJsSource.FromString(fixtureBundle, "bundle-roundtrip-fixture.js"),
    )
    try {
      val response = session.client.callTool(
        CallToolRequest(
          params = CallToolRequestParams(
            name = "echoReverse",
            arguments = buildJsonObject { put("text", "hello") },
          ),
        ),
      )

      val textContent = response.content.firstOrNull() as? TextContent
      assertThat(textContent).isNotNull()
      assertThat(textContent!!.text).isEqualTo("olleh")
    } finally {
      session.shutdown()
    }
  }
}
