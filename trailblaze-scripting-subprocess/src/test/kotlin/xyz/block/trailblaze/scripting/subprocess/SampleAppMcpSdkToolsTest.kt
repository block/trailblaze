package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.endsWith
import assertk.assertions.isBetween
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * CI coverage for the `@trailblaze/scripting`-authored sample-app reference tools.ts
 * (`examples/android-sample-app/trailblaze-config/mcp-sdk/tools.ts`).
 *
 * Sister to [SampleAppMcpToolsTest] — same spawn → handshake → `tools/list` → `tools/call`
 * shape, but hits the SDK-authored file instead of the raw-MCP one. Both tests run so CI
 * notices the moment either authoring surface regresses.
 *
 * Gating mirrors [SampleAppMcpToolsTest]: skip when the runtime (bun/tsx) isn't on PATH or
 * when `node_modules/` hasn't been installed. The Gradle `installSampleAppMcpSdkTools` task
 * runs `bun install` / `npm install` in the SDK-example directory (dependencies include a
 * `file:` link back to the Trailblaze scripting SDK package at `sdks/typescript/`), so CI
 * with either runtime installed runs end-to-end.
 */
class SampleAppMcpSdkToolsTest {

  private val sampleAppSdkToolsTs: File by lazy {
    val path = checkNotNull(System.getProperty("trailblaze.sampleApp.mcpSdk.toolsTs")) {
      "trailblaze.sampleApp.mcpSdk.toolsTs system property not set — this test must run via " +
        "the Gradle `test` task (which configures the property). If you're running from an " +
        "IDE, point the run-configuration's working directory at the module's projectDir, " +
        "or invoke via Gradle instead."
    }
    File(path)
  }

  private val context = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("sample_app_mcp_sdk_tools_test"),
  )

  @Test fun `sdk-authored sample-app tools dot ts spawns and advertises its tool set`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the sample-app SDK MCP tool e2e path",
        runtimeAvailable(),
      )
      val depsInstalled = sampleAppDepsInstalled()
      if (ciRequiresDepsInstalled()) {
        assertTrue(
          "CI requires sample-app SDK MCP tool deps to be installed. The " +
            "`installSampleAppMcpSdkTools` Gradle task should have done this — check the " +
            "Gradle log for warnings. Expected node_modules at: " +
            "${File(sampleAppSdkToolsTs.parentFile, "node_modules").absolutePath}",
          depsInstalled,
        )
      } else {
        assumeTrue(
          "Sample-app SDK MCP tool deps not installed. Run from the repo root:\n" +
            "  cd examples/android-sample-app/trailblaze-config/mcp-sdk && bun install\n" +
            "(or `npm install`). CI installs these automatically via the " +
            "`installSampleAppMcpSdkTools` Gradle task.",
          depsInstalled,
        )
      }

      val spawned = McpSubprocessSpawner.spawn(
        config = McpServerConfig(script = sampleAppSdkToolsTs.absolutePath),
        context = context,
        anchor = sampleAppSdkToolsTs.parentFile,
      )
      val session = McpSubprocessSession.connect(spawnedProcess = spawned)
      try {
        // The tools the SDK-authored example advertises. Distinct from the raw-SDK file's
        // names so both sources can register side-by-side in the same session without colliding.
        // `trailblazeContextSdk` is the envelope-read proof tool — covered further down.
        // `signUpNewUserSdk` is the callback round-trip proof tool — covered in a dedicated test
        // (`client dot callTool round-trips through the callback endpoint`).
        val listResult = session.client.listTools(ListToolsRequest())
        assertThat(listResult.tools.map { it.name })
          .containsExactlyInAnyOrder(
            "generateTestUserSdk",
            "currentEpochMillisSdk",
            "trailblazeContextSdk",
            "signUpNewUserSdk",
          )

        // generateTestUserSdk round-trip — proves the SDK's `trailblaze.tool(...)` wrapper
        // actually registers the handler and forwards args (and eventually, ctx) the same way
        // the raw MCP path does.
        val userResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "generateTestUserSdk",
              arguments = JsonObject(emptyMap()),
            ),
          ),
        )
        val userText = checkNotNull((userResponse.content.firstOrNull() as? TextContent)?.text) {
          "generateTestUserSdk returned no text content — response: $userResponse"
        }
        val userJson = Json.parseToJsonElement(userText).jsonObject
        val name = userJson["name"]?.jsonPrimitive?.content
        val email = userJson["email"]?.jsonPrimitive?.content
        assertThat(name).isNotNull()
        assertThat(email).isNotNull().endsWith("@example.com")

        // currentEpochMillisSdk round-trip — minimum sanity on the timestamp path.
        val timeResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "currentEpochMillisSdk",
              arguments = JsonObject(emptyMap()),
            ),
          ),
        )
        val timeText = checkNotNull((timeResponse.content.firstOrNull() as? TextContent)?.text) {
          "currentEpochMillisSdk returned no text content — response: $timeResponse"
        }
        val millis = checkNotNull(timeText.toLongOrNull()) {
          "currentEpochMillisSdk text didn't parse as a long: $timeText"
        }
        val lowerBound = 1_577_836_800_000L // 2020-01-01T00:00:00Z
        val upperBound = System.currentTimeMillis() + 3_600_000L
        assertThat(millis).isBetween(lowerBound, upperBound)

        // Envelope round-trip: send `_meta.trailblaze` matching what the Kotlin runtime
        // injects in production, assert the handler reads it back via the SDK's fromMeta ctx
        // argument and echoes it as JSON. Proves the entire envelope pipeline — Kotlin
        // envelope build → MCP wire (`_meta` on CallToolRequestParams) → TS SDK's
        // `extractMeta` → `fromMeta` parsing → handler's `ctx` — works end-to-end in one
        // test. Regressions in any link fail this assertion loud.
        val envelope = buildJsonObject {
          putJsonObject("trailblaze") {
            put("baseUrl", "http://localhost:52525")
            put("sessionId", context.sessionId.value)
            put("invocationId", "test-invocation-id")
            putJsonObject("device") {
              put("platform", "android")
              put("widthPixels", 1080)
              put("heightPixels", 2400)
              put("driverType", "android-ondevice-accessibility")
            }
            putJsonObject("memory") {
              put("scratch", "hello")
            }
          }
        }
        val envelopeResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "trailblazeContextSdk",
              arguments = JsonObject(emptyMap()),
              meta = RequestMeta(json = envelope),
            ),
          ),
        )
        val envelopeText = checkNotNull((envelopeResponse.content.firstOrNull() as? TextContent)?.text) {
          "trailblazeContextSdk returned no text content — response: $envelopeResponse"
        }
        val envelopeJson = Json.parseToJsonElement(envelopeText).jsonObject
        // Sentinel fields the tool returns when ctx is defined — if any are missing, the
        // SDK's fromMeta rejected the envelope (strict mode) or the wiring is broken.
        assertThat(envelopeJson["sessionId"]?.jsonPrimitive?.content).isEqualTo(context.sessionId.value)
        assertThat(envelopeJson["invocationId"]?.jsonPrimitive?.content).isEqualTo("test-invocation-id")
        assertThat(envelopeJson["baseUrl"]?.jsonPrimitive?.content).isEqualTo("http://localhost:52525")
        val device = envelopeJson["device"]!!.jsonObject
        assertThat(device["platform"]?.jsonPrimitive?.content).isEqualTo("android")
        assertThat(device["driverType"]?.jsonPrimitive?.content).isEqualTo("android-ondevice-accessibility")
        val memory = envelopeJson["memory"]!!.jsonObject
        assertThat(memory["scratch"]?.jsonPrimitive?.content).isEqualTo("hello")

        // Strictness check: invocation WITHOUT `_meta` — the SDK's fromMeta must return
        // undefined, and the tool returns the `status: "no-envelope"` sentinel. If the SDK
        // ever regresses to silent defaults (returning a fabricated ctx instead of undefined),
        // this assertion fails.
        val noEnvelopeResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "trailblazeContextSdk",
              arguments = JsonObject(emptyMap()),
            ),
          ),
        )
        val noEnvelopeText = checkNotNull((noEnvelopeResponse.content.firstOrNull() as? TextContent)?.text) {
          "trailblazeContextSdk returned no text content — response: $noEnvelopeResponse"
        }
        assertThat(noEnvelopeText).contains("no-envelope")
      } finally {
        session.shutdown()
        val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
        if (!exited) {
          spawned.process.destroyForcibly()
          spawned.process.waitFor(5, TimeUnit.SECONDS)
        }
        assertThat(exited).isEqualTo(true)
      }
    }
  }

  @Test fun `client dot callTool round-trips through the callback endpoint`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the sample-app SDK MCP tool e2e path",
        runtimeAvailable(),
      )
      val depsInstalled = sampleAppDepsInstalled()
      if (ciRequiresDepsInstalled()) {
        assertTrue(
          "CI requires sample-app SDK MCP tool deps to be installed. The " +
            "`installSampleAppMcpSdkTools` Gradle task should have done this — check the " +
            "Gradle log for warnings.",
          depsInstalled,
        )
      } else {
        assumeTrue(
          "Sample-app SDK MCP tool deps not installed. Run `bun install` in the " +
            "mcp-sdk/ directory.",
          depsInstalled,
        )
      }

      // Stub callback server. Captures the POST body so the test can assert the exact wire
      // shape the TS client sent, then replies with a canned JsScriptingCallbackResult.CallToolResult
      // mimicking what the real ScriptingCallbackEndpoint would return for generateTestUserSdk.
      // Kept in-test rather than spinning up the real endpoint so this suite stays free of a
      // full `:trailblaze-server` dep — the real endpoint is covered exhaustively by
      // ScriptingCallbackEndpointTest; this test's value is proving the TS-side HTTP client
      // constructs the right JsScriptingCallbackRequest and parses the right JsScriptingCallbackResponse.
      val capturedRequestBody = AtomicReference<String>()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/scripting/callback") { exchange: HttpExchange ->
          try {
            val body = exchange.requestBody.use { it.readBytes() }.toString(Charsets.UTF_8)
            capturedRequestBody.set(body)
            // Respond with a successful CallToolResult whose textContent matches the shape
            // `generateTestUserSdk` would have produced, so the outer `signUpNewUserSdk`'s
            // JSON.parse succeeds and the composed output is deterministic.
            val responseBody = """
              {"result":{"type":"call_tool_result","success":true,"text_content":"{\"name\":\"Callback User\",\"email\":\"callback@example.com\"}","error_message":""}}
            """.trimIndent()
            val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
          } catch (e: Exception) {
            exchange.sendResponseHeaders(500, -1)
          }
        }
        start()
      }
      val stubBaseUrl = "http://127.0.0.1:${server.address.port}"

      try {
        val spawned = McpSubprocessSpawner.spawn(
          config = McpServerConfig(script = sampleAppSdkToolsTs.absolutePath),
          context = context,
          anchor = sampleAppSdkToolsTs.parentFile,
        )
        val session = McpSubprocessSession.connect(spawnedProcess = spawned)
        try {
          // Envelope points the subprocess at the stub server — the SDK's TrailblazeClient
          // reads `_meta.trailblaze.baseUrl` and issues its POST there.
          val envelope = buildJsonObject {
            putJsonObject("trailblaze") {
              put("baseUrl", stubBaseUrl)
              put("sessionId", context.sessionId.value)
              put("invocationId", "round-trip-invocation-id")
              putJsonObject("device") {
                put("platform", "android")
                put("widthPixels", 1080)
                put("heightPixels", 2400)
                put("driverType", "android-ondevice-accessibility")
              }
              putJsonObject("memory") {}
            }
          }

          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = "signUpNewUserSdk",
                arguments = JsonObject(emptyMap()),
                meta = RequestMeta(json = envelope),
              ),
            ),
          )
          val responseText = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
            "signUpNewUserSdk returned no text content — response: $response"
          }
          val responseJson = Json.parseToJsonElement(responseText).jsonObject
          // The composed tool wraps the dispatched result with a `composedFrom` marker; both
          // sides are asserted so a regression in either the callback parse path or the
          // handler wrapper surfaces independently.
          assertThat(responseJson["composedFrom"]?.jsonPrimitive?.content)
            .isEqualTo("generateTestUserSdk")
          assertThat(responseJson["name"]?.jsonPrimitive?.content).isEqualTo("Callback User")
          assertThat(responseJson["email"]?.jsonPrimitive?.content).isEqualTo("callback@example.com")

          // Assert the TS client built the JsScriptingCallbackRequest per wire contract — version 1,
          // snake_case keys, `call_tool` action discriminator, arguments_json as a JSON
          // *string* (not a nested object). Regressions here would silently break the Kotlin
          // endpoint's deserialization in prod, so the test pins the shape explicitly.
          val requestJson = Json.parseToJsonElement(
            checkNotNull(capturedRequestBody.get()) {
              "stub callback server never received a request — did signUpNewUserSdk short-circuit?"
            },
          ).jsonObject
          assertThat(requestJson["version"]?.jsonPrimitive?.content).isEqualTo("1")
          assertThat(requestJson["session_id"]?.jsonPrimitive?.content).isEqualTo(context.sessionId.value)
          assertThat(requestJson["invocation_id"]?.jsonPrimitive?.content).isEqualTo("round-trip-invocation-id")
          val action = requestJson["action"]!!.jsonObject
          assertThat(action["type"]?.jsonPrimitive?.content).isEqualTo("call_tool")
          assertThat(action["tool_name"]?.jsonPrimitive?.content).isEqualTo("generateTestUserSdk")
          // arguments_json is a JSON *string* per the wire contract (D2 in the envelope-
          // migration devlog). Parsed here to verify it decodes to an object.
          val argumentsJson = action["arguments_json"]?.jsonPrimitive?.content
          assertThat(argumentsJson).isNotNull()
          Json.parseToJsonElement(argumentsJson!!).jsonObject // must parse
        } finally {
          session.shutdown()
          val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
          if (!exited) {
            spawned.process.destroyForcibly()
            spawned.process.waitFor(5, TimeUnit.SECONDS)
          }
        }
      } finally {
        // `stop(0)` forces an immediate shutdown without waiting for in-flight exchanges —
        // the test owns the stub's lifecycle, so no other caller is waiting on it.
        server.stop(0)
      }
    }
  }

  @Test fun `client dot callTool surfaces daemon error shapes with tool-name context`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the sample-app SDK MCP tool e2e path",
        runtimeAvailable(),
      )
      val depsInstalled = sampleAppDepsInstalled()
      if (ciRequiresDepsInstalled()) {
        assertTrue(
          "CI requires sample-app SDK MCP tool deps to be installed.",
          depsInstalled,
        )
      } else {
        assumeTrue(
          "Sample-app SDK MCP tool deps not installed. Run `bun install` in the mcp-sdk/ directory.",
          depsInstalled,
        )
      }

      // Stub callback server whose response SHAPE is selected per sub-case via a mutable mode
      // flag. Lets us spawn the subprocess once and cycle through every error branch the TS
      // client should surface — otherwise each branch would want its own spawn (slow).
      //
      // Modes cover the branches flagged in the lead-dev review of client.ts:
      //  - "403"                 → HTTP 403 non-OK response (protocol-level framing error)
      //  - "malformed-json"      → HTTP 200 with a body that isn't valid JSON
      //  - "missing-result"      → HTTP 200 with valid JSON but missing the `result` field
      //  - "non-json-text"       → HTTP 200 with a successful CallToolResult whose text_content
      //                            is NOT valid JSON, which hits signUpNewUserSdk's inner
      //                            JSON.parse try/catch
      //
      // The fetch-abort / timeout branch is not covered here because triggering it from the
      // stub requires either a >32 s response delay (slow) or an env var override plumbed
      // through the subprocess spawner (scope creep). Tracked as follow-up — separate issue
      // is the right venue for that branch.
      val responseMode = AtomicReference<String>("ok")
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/scripting/callback") { exchange: HttpExchange ->
          try {
            exchange.requestBody.use { it.readBytes() } // drain body so client doesn't block
            when (responseMode.get()) {
              "403" -> {
                val msg = "Forbidden by stub".toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(403, msg.size.toLong())
                exchange.responseBody.use { it.write(msg) }
              }
              "malformed-json" -> {
                val body = "{ not valid json }".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
              }
              "missing-result" -> {
                // Valid JSON but missing the `result` field — the TS client's guard should
                // catch this with a clear protocol error rather than a downstream TypeError.
                val body = "{\"unexpected\":true}".toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
              }
              "non-json-text" -> {
                // Successful CallToolResult but text_content is not JSON — signUpNewUserSdk
                // tries to JSON.parse it and should re-throw with a tool-scoped message.
                val body = """
                  {"result":{"type":"call_tool_result","success":true,"text_content":"not json","error_message":""}}
                """.trimIndent().toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
              }
              else -> {
                exchange.sendResponseHeaders(500, -1)
              }
            }
          } catch (e: Exception) {
            exchange.sendResponseHeaders(500, -1)
          }
        }
        start()
      }
      val stubBaseUrl = "http://127.0.0.1:${server.address.port}"

      try {
        val spawned = McpSubprocessSpawner.spawn(
          config = McpServerConfig(script = sampleAppSdkToolsTs.absolutePath),
          context = context,
          anchor = sampleAppSdkToolsTs.parentFile,
        )
        val session = McpSubprocessSession.connect(spawnedProcess = spawned)
        try {
          // Envelope points the subprocess at the stub server.
          val envelope = buildJsonObject {
            putJsonObject("trailblaze") {
              put("baseUrl", stubBaseUrl)
              put("sessionId", context.sessionId.value)
              put("invocationId", "error-branch-invocation-id")
              putJsonObject("device") {
                put("platform", "android")
                put("widthPixels", 1080)
                put("heightPixels", 2400)
                put("driverType", "android-ondevice-accessibility")
              }
              putJsonObject("memory") {}
            }
          }

          // Each sub-case: flip the stub's response mode, dispatch signUpNewUserSdk, assert the
          // MCP result surfaces an error whose message contains the expected substring. The
          // TS MCP SDK maps a thrown handler error to an `isError: true` response with the
          // error message in text content — that's the public failure surface authors see.
          val cases = listOf(
            "403" to "HTTP 403",
            "malformed-json" to "failed to parse response body as JSON",
            "missing-result" to "missing the \"result\" field",
            "non-json-text" to "returned non-JSON textContent",
          )
          for ((mode, expectedSubstring) in cases) {
            responseMode.set(mode)
            val response = session.client.callTool(
              CallToolRequest(
                params = CallToolRequestParams(
                  name = "signUpNewUserSdk",
                  arguments = JsonObject(emptyMap()),
                  meta = RequestMeta(json = envelope),
                ),
              ),
            )
            assertThat(response.isError).isEqualTo(true)
            val errorText = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
              "Expected error text content for mode '$mode', got: $response"
            }
            assertThat(errorText).contains(expectedSubstring)
          }
        } finally {
          session.shutdown()
          val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
          if (!exited) {
            spawned.process.destroyForcibly()
            spawned.process.waitFor(5, TimeUnit.SECONDS)
          }
        }
      } finally {
        server.stop(0)
      }
    }
  }

  private fun runtimeAvailable(): Boolean = try {
    NodeRuntimeDetector.cached
    true
  } catch (_: NoCompatibleTsRuntimeException) {
    false
  }

  private fun sampleAppDepsInstalled(): Boolean =
    File(sampleAppSdkToolsTs.parentFile, "node_modules").isDirectory

  /**
   * Same gating semantics as the raw-SDK test: `CI=true` upgrades skip to hard assertion,
   * `TRAILBLAZE_SAMPLE_APP_MCP_TEST=skip` is an escape hatch. Documented on
   * [SampleAppMcpToolsTest.ciRequiresDepsInstalled].
   */
  private fun ciRequiresDepsInstalled(): Boolean {
    if (System.getenv("TRAILBLAZE_SAMPLE_APP_MCP_TEST")?.equals("skip", ignoreCase = true) == true) {
      return false
    }
    return System.getenv("CI")?.equals("true", ignoreCase = true) == true
  }
}
