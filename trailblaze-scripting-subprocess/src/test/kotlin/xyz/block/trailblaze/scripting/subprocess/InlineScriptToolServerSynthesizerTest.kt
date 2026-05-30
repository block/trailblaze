package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.bundle.SdkBundleResource
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test

class InlineScriptToolServerSynthesizerTest {
  private val tmpDir = Files.createTempDirectory("inline-script-tool-test").toFile()
  private val authorFile = File(tmpDir, "greet_user.js")
  private val helperFile = File(tmpDir, "shared.js")
  private val generatedDir = File(tmpDir, "generated")
  private val realConditionalExampleFile = locateExampleFile(
    "examples/playwright-native/trails/config/tools/playwrightSample_web_openFormIfNeeded.js",
  )

  private val baseContext = McpSpawnContext(
    platform = TrailblazeDevicePlatform.ANDROID,
    driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
    sessionId = SessionId("inline_script_tool_test"),
  )

  @AfterTest fun cleanup() {
    tmpDir.deleteRecursively()
  }

  @Test fun `wrapper script forwards inline tool meta into trailblaze tool registration`() {
    val wrapper = InlineScriptToolServerSynthesizer.renderWrapperScript(
      tool = InlineScriptToolConfig(
        script = authorFile.absolutePath,
        name = "metaForwardingTool",
        description = "Test tool.",
        meta = buildJsonObject {
          put("trailblaze/toolset", "web_core")
          put("trailblaze/requiresContext", true)
        },
      ),
      authorFile = authorFile,
    )

    assertThat(wrapper).contains("_meta:")
    assertThat(wrapper).contains("trailblaze/toolset")
  }

  @Test fun `requiresHost shortcut injects trailblaze requiresHost into wrapper meta`() {
    val wrapper = InlineScriptToolServerSynthesizer.renderWrapperScript(
      tool = InlineScriptToolConfig(
        script = authorFile.absolutePath,
        name = "hostOnlyTool",
        description = "Host-only tool.",
        requiresHost = true,
      ),
      authorFile = authorFile,
    )

    assertThat(wrapper).contains("\"trailblaze/requiresHost\":true")
  }

  @Test fun `requiresHost shortcut overrides explicit meta value`() {
    val wrapper = InlineScriptToolServerSynthesizer.renderWrapperScript(
      tool = InlineScriptToolConfig(
        script = authorFile.absolutePath,
        name = "hostOnlyTool",
        description = "Host-only tool with conflicting meta.",
        requiresHost = true,
        meta = buildJsonObject {
          put("trailblaze/requiresHost", false)
          put("trailblaze/toolset", "host_utilities")
        },
      ),
      authorFile = authorFile,
    )

    assertThat(wrapper).contains("\"trailblaze/requiresHost\":true")
    assertThat(wrapper).contains("\"trailblaze/toolset\":\"host_utilities\"")
  }

  @Test fun `normalize emits structuredContent passthrough for typed-overload returns`() {
    // Regression: typed-overload handlers (`trailblaze.tool<I, O>(handler)`) return a
    // structured TS value directly. Without populating `structuredContent` on the wire
    // envelope, the SDK client proxy on the consumer side falls back to text_content
    // and returns the JSON-stringified shape as a string cast to `O` — a silent type
    // lie (accessing `.field` returns undefined). The wrapper's `normalize` function
    // must populate `structuredContent: result` alongside the JSON-stringified text so
    // the proxy unwraps to the typed structured value as declared in TrailblazeToolMap.
    val wrapper = InlineScriptToolServerSynthesizer.renderWrapperScript(
      tool = InlineScriptToolConfig(
        script = authorFile.absolutePath,
        name = "typedReturnTool",
        description = "Tool that returns a structured TS value via the typed overload.",
      ),
      authorFile = authorFile,
    )

    // The non-string, non-MCP-envelope branch must populate `structuredContent`. Asserting
    // on the textual presence of the populator keeps this test independent of how the
    // wrapper layout evolves — what we care about is "structuredContent is carried
    // through," not the specific call shape.
    assertThat(wrapper).contains("structuredContent: result")
  }

  @Test fun `default requiresHost leaves explicit meta value untouched`() {
    val wrapper = InlineScriptToolServerSynthesizer.renderWrapperScript(
      tool = InlineScriptToolConfig(
        script = authorFile.absolutePath,
        name = "advancedHostTool",
        description = "Sets requiresHost via _meta only.",
        meta = buildJsonObject {
          put("trailblaze/requiresHost", true)
        },
      ),
      authorFile = authorFile,
    )

    assertThat(wrapper).contains("\"trailblaze/requiresHost\":true")
  }

  /**
   * Regression: prior to this test the default SDK-bundle resolver walked up from `user.dir`
   * looking for the bundle's source path on disk. That worked when running tests from inside
   * the repo (source tree visible) but failed when the host daemon ran from an installed uber
   * jar with a working directory outside the repo — exactly the CI shape after
   * `scripts/install-trailblaze-from-source.sh`. The fix routes resolution through
   * [SdkBundleResource.extractToFile] in `:trailblaze-scripting-bundle`, which loads the
   * bundle from the classpath resource and writes it to a process-scoped temp file.
   *
   * This test pins the integration contract: `synthesize()` produces a wrapper whose embedded
   * SDK-bundle path is a real readable file. We additionally point `user.dir` at a foreign
   * temp dir to demonstrate the resolution no longer cares about the working directory — a
   * regression that re-introduced cwd-relative resolution would still pass the basic shape
   * check but would break here.
   */
  @Test fun `synthesize resolves default SDK bundle independent of user dir`() {
    authorFile.writeText("export function noopTool() {}\n")
    val previousUserDir = System.getProperty("user.dir")
    val foreignCwd = Files.createTempDirectory("inline-script-tool-foreign-cwd").toFile()
    try {
      System.setProperty("user.dir", foreignCwd.absolutePath)
      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "noopTool",
            description = "noop",
          ),
        ),
        outputDir = generatedDir,
      )
      val wrapperContent = File(generated.single().script).readText()
      // Wrapper does `await import(pathToFileURL("/abs/path/to/<bundle>").href)`. The first
      // pathToFileURL(...) in the wrapper is the SDK bundle import (subsequent ones resolve
      // the author's own module). Use the canonical filename constant from the bundle module
      // so a future rename of the extracted file breaks here at compile time, not at runtime.
      val bundleFileName = SdkBundleResource.FILE_NAME
      val sdkPath = Regex("""pathToFileURL\("([^"]+${Regex.escape(bundleFileName)})"\)""")
        .find(wrapperContent)
        ?.groupValues
        ?.get(1)
        ?: error("Wrapper did not embed an SDK bundle path:\n$wrapperContent")
      assertThat(File(sdkPath).isFile).isEqualTo(true)
    } finally {
      System.setProperty("user.dir", previousUserDir)
      foreignCwd.deleteRecursively()
    }
  }

  @Test fun `synthesized inline tool advertises schema and can call back through the SDK client`() {
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )

      authorFile.writeText(
        """
        import { joinGreeting } from "./shared.js";

        export async function greetUserInline(args, ctx, client) {
          const inner = await client.callTool("echoFromCallback", { greeting: args.greeting });
          return joinGreeting(
            args.greeting,
            ctx?.sessionId ?? "no-session",
            inner.textContent,
          );
        }
        """.trimIndent() + "\n",
      )
      helperFile.writeText(
        """
        export function joinGreeting(greeting, sessionId, innerText) {
          return `${'$'}{greeting}|${'$'}{sessionId}|${'$'}{innerText}`;
        }
        """.trimIndent() + "\n",
      )

      val capturedRequestBody = AtomicReference<String>()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/scripting/callback") { exchange ->
          try {
            capturedRequestBody.set(exchange.requestBody.bufferedReader().readText())
            val responseBody =
              """
              {
                "result": {
                  "type": "call_tool_result",
                  "success": true,
                  "text_content": "callback-ok",
                  "error_message": ""
                }
              }
              """.trimIndent()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
          } finally {
            exchange.close()
          }
        }
        start()
      }

      val baseUrl = "http://127.0.0.1:${server.address.port}"
      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "greetUserInline",
            description = "Greets a user via an inline script.",
            inputSchema = buildJsonObject {
              put("type", "object")
              putJsonObject("properties") {
                putJsonObject("greeting") {
                  put("type", "string")
                }
              }
              put("required", Json.parseToJsonElement("""["greeting"]""").jsonArray)
            },
          ),
        ),
        outputDir = generatedDir,
      )

      val spawned = McpSubprocessSpawner.spawn(
        config = generated.single(),
        context = baseContext.copy(baseUrl = baseUrl),
      )
      val stderrLog = File(tmpDir, "inline-script-tool.stderr.log")
      val session = runCatching {
        McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = StderrCapture(stderrLog),
        )
      }.getOrElse { t ->
        val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
        throw AssertionError("Inline tool subprocess failed during connect. stderr:\n$stderr", t)
      }
      try {
        runCatching {
          val listed = session.client.listTools(ListToolsRequest()).tools
          assertThat(listed.map { it.name }).containsExactlyInAnyOrder("greetUserInline")
          val schema = listed.single().inputSchema
          assertThat(schema.toString()).contains("type")
          assertThat(schema.toString()).contains("greeting")

          val requestMeta = RequestMeta(
            json = buildJsonObject {
              putJsonObject("trailblaze") {
                put("baseUrl", baseUrl)
                put("sessionId", baseContext.sessionId.value)
                put("invocationId", "outer-invocation-id")
                putJsonObject("device") {
                  put("platform", "android")
                  put("widthPixels", 1080)
                  put("heightPixels", 2400)
                  put("driverType", "android-ondevice-accessibility")
                }
                putJsonObject("memory") {}
              }
            }
          )
          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = "greetUserInline",
                arguments = buildJsonObject { put("greeting", "hello") },
                meta = requestMeta,
              ),
            ),
          )

          val text = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
            "greetUserInline returned no text content — response: $response"
          }
          assertThat(text).isEqualTo("hello|inline_script_tool_test|callback-ok")

          val callbackRequest = Json.parseToJsonElement(checkNotNull(capturedRequestBody.get())).jsonObject
          assertThat(callbackRequest["session_id"]?.jsonPrimitive?.content).isEqualTo(baseContext.sessionId.value)
          assertThat(callbackRequest["invocation_id"]?.jsonPrimitive?.content).isEqualTo("outer-invocation-id")
          val action = callbackRequest["action"]!!.jsonObject
          assertThat(action["tool_name"]?.jsonPrimitive?.content).isEqualTo("echoFromCallback")
        }.getOrElse { t ->
          val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
          throw AssertionError("Inline tool subprocess failed. stderr:\n$stderr", t)
        }
      } finally {
        server.stop(0)
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

  @Test fun `two tool entries sharing a module get one wrapper that advertises both tools`() {
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )

      authorFile.writeText(
        """
        export async function firstInlineTool() {
          return "first";
        }

        export async function secondInlineTool() {
          return "second";
        }
        """.trimIndent() + "\n",
      )

      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "firstInlineTool",
            description = "First tool from a shared module.",
          ),
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "secondInlineTool",
            description = "Second tool from a shared module.",
          ),
        ),
        outputDir = generatedDir,
      )

      // Dedup contract: both tools share `script:`, so the synthesizer emits exactly ONE
      // wrapper subprocess that registers BOTH tools. Without this, every group of N tools
      // sharing a module would fork N subprocesses each loading the same JS — the
      // developer-facing "scripted tools, not MCP servers" promise leaks the multiplicity.
      assertThat(generated.size).isEqualTo(1)

      val spawned = McpSubprocessSpawner.spawn(config = generated.single(), context = baseContext)
      val stderrLog = File(tmpDir, "group.stderr.log")
      val session = McpSubprocessSession.connect(
        spawnedProcess = spawned,
        stderrCapture = StderrCapture(stderrLog),
      )
      try {
        val advertised = session.client.listTools(ListToolsRequest()).tools.map { it.name }
        assertThat(advertised).containsExactlyInAnyOrder("firstInlineTool", "secondInlineTool")

        // Call both tools through the same subprocess to confirm each named export routes
        // through to the corresponding registered handler. If the synthesizer's per-tool
        // dispatcher leaked across the two registrations (e.g. shared a `handler` reference
        // that got overwritten in the loop), one of these would return the other's text.
        val responses = advertised.map { toolName ->
          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = toolName,
                arguments = buildJsonObject {},
              ),
            ),
          )
          toolName to checkNotNull((response.content.firstOrNull() as? TextContent)?.text)
        }.toMap()
        assertThat(responses["firstInlineTool"]).isEqualTo("first")
        assertThat(responses["secondInlineTool"]).isEqualTo("second")
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

  @Test fun `typed-overload return populates structuredContent on the MCP wire envelope`() {
    // End-to-end pin for the typed-result chain: when a scripted-tool author returns a
    // structured TS value directly (the canonical typed-overload shape), the synthesized
    // wrapper's `normalizeInlineToolResult` must populate BOTH the text-encoded content
    // (for raw-MCP consumers that only read `content[].text`) AND `structuredContent` (for
    // the trailblaze-SDK client proxy that unwraps it as the typed `result` declared in
    // `TrailblazeToolMap`). Without `structuredContent`, the proxy on the consumer side
    // falls back to text and returns the JSON STRING cast as `O` — a silent type lie
    // (matching the `m.bounds.width` shape of bug earlier in this PR's history).
    //
    // The author file uses the equivalent of a typed-overload's adapter: a 3-arg async
    // function that returns the structured value DIRECTLY (no MCP envelope, no
    // JSON.stringify by hand). The synthesizer's wrapper feeds the return through
    // `normalizeInlineToolResult`, which is the code path under test.
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )

      authorFile.writeText(
        """
        export async function typedStructuredTool(args, _ctx, _client) {
          return {
            found: true,
            count: 5,
            label: args.label ?? "default",
          };
        }
        """.trimIndent() + "\n",
      )

      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "typedStructuredTool",
            description = "Returns a structured TS value (typed-overload shape).",
            inputSchema = buildJsonObject {
              put("type", "object")
              putJsonObject("properties") {
                putJsonObject("label") { put("type", "string") }
              }
            },
          ),
        ),
        outputDir = generatedDir,
      )

      val spawned = McpSubprocessSpawner.spawn(config = generated.single(), context = baseContext)
      val stderrLog = File(tmpDir, "typed-structured.stderr.log")
      val session = runCatching {
        McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = StderrCapture(stderrLog),
        )
      }.getOrElse { t ->
        val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
        throw AssertionError("Typed-structured tool subprocess failed during connect. stderr:\n$stderr", t)
      }
      try {
        runCatching {
          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = "typedStructuredTool",
                arguments = buildJsonObject { put("label", "explicit") },
              ),
            ),
          )

          // Text content half — JSON-stringified payload so raw-MCP consumers (the agent
          // toolbox path, any non-trailblaze-SDK client) still see a serializable result.
          val text = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
            "typedStructuredTool returned no text content — response: $response"
          }
          val parsedText = Json.parseToJsonElement(text).jsonObject
          assertThat(parsedText["found"]?.jsonPrimitive?.content).isEqualTo("true")
          assertThat(parsedText["count"]?.jsonPrimitive?.content).isEqualTo("5")
          assertThat(parsedText["label"]?.jsonPrimitive?.content).isEqualTo("explicit")

          // Structured content half — the load-bearing assertion. THIS is what the SDK
          // client proxy unwraps into the typed `result` on the consumer side. Without it,
          // the proxy falls back to text and the consumer's `result.found === undefined`.
          val structured = checkNotNull(response.structuredContent) {
            "typedStructuredTool DID NOT populate structuredContent on the wire envelope. " +
              "The synthesizer's `normalizeInlineToolResult` must emit structuredContent " +
              "alongside text content for non-string, non-MCP-envelope returns. Without " +
              "this, the trailblaze-SDK client proxy on the caller side returns the JSON " +
              "STRING cast as the declared typed `result` — a silent type lie. response=$response"
          }.jsonObject
          assertThat(structured["found"]?.jsonPrimitive?.content).isEqualTo("true")
          assertThat(structured["count"]?.jsonPrimitive?.content).isEqualTo("5")
          assertThat(structured["label"]?.jsonPrimitive?.content).isEqualTo("explicit")

          // isError must not be `true` for the happy path so the proxy unwraps rather than
          // throws. MCP spec models `isError` as optional, so absence on a success envelope
          // is null (not literal false) — both shapes are valid "no error" signals.
          // Coerce null → false so the assertion accepts both shapes uniformly.
          assertThat(response.isError ?: false).isEqualTo(false)
        }.getOrElse { t ->
          val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
          throw AssertionError("typedStructuredTool e2e assertion failed. stderr:\n$stderr", t)
        }
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

  @Test fun `script-overload web_evaluate serializes fn and args into the wire script payload`() {
    // End-to-end pin for the script-overload proxy specialization on `web_evaluate`. The
    // Playwright-style ergonomic shape lets a trailmap author write
    //
    //     await client.tools.web_evaluate((path) => path.toUpperCase(), "/hello");
    //
    // …instead of hand-stringifying the JS expression. The SDK proxy detects the function
    // form, calls `Function.prototype.toString()`, JSON-encodes the args, and emits
    // `(<fnSrc>).apply(null, <argsJson>)` as the `script` field — that's the wire shape
    // the Kotlin tool's `page.evaluate(String)` expects.
    //
    // **Why this test exists.** The proxy-side translation is unit-tested in `client.test.ts`
    // via `createMockClient` (the mock proxy mirrors production behavior in `testing.ts`).
    // That covers the runtime logic. This test covers the orthogonal property: the SDK
    // bundle shipped with the synthesizer (`SdkBundleResource`) actually carries the
    // script-overload code. A tree-shake or bundler refactor that drops the
    // `SCRIPT_OVERLOAD_TOOLS` set or the `buildScriptOverloadArgs` helper from the bundled
    // IIFE would silently regress to passing the function through as the args object — the
    // callback request would carry no `script` field and the test below catches it.
    //
    // The callback server here doesn't actually run the script — it just captures the wire
    // payload. The script's *behavior* in the page context is the Kotlin tool's contract
    // (covered by the playwright-driver path), not this synthesizer's.
    //
    // **Coverage scope.** `web_evaluate` is the only script-overload tool today. Sibling
    // `web_<Page-method>` tools (e.g. `web_addInitScript`, `web_setExtraHTTPHeaders`) will
    // arrive via the Playwright-tool-shim codegen — see the
    // `2026-05-26-playwright-tool-shim-codegen.md` devlog. The function-overload test
    // surface here doesn't need to enumerate those once they land; the bundle-shipping
    // property is the same for every tool in `SCRIPT_OVERLOAD_TOOLS`.
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )

      // Author writes a tool that uses `web_evaluate` in the function-form (canonical
      // typed-overload shape: `(fn, ...args)`). The wire payload must be the `apply`-form
      // string with JSON-encoded args.
      authorFile.writeText(
        """
        export async function exerciseScriptOverloads(args, _ctx, client) {
          // Function-form: (fn, ...args). Wire payload should be
          // `((path) => path.toUpperCase()).apply(null, ["/hello"])`.
          await client.tools.web_evaluate((path) => path.toUpperCase(), args.path);
          return "exercised";
        }
        """.trimIndent() + "\n",
      )

      // Capture every callback request. Each entry is `{tool_name, args}` parsed from the
      // wire payload — assertions below pin both calls in order.
      data class Captured(val toolName: String, val args: JsonObject)
      val captured = mutableListOf<Captured>()
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/scripting/callback") { exchange ->
          try {
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val action = request["action"]!!.jsonObject
            val toolName = action["tool_name"]!!.jsonPrimitive.content
            val args =
              Json.parseToJsonElement(action["arguments_json"]!!.jsonPrimitive.content).jsonObject
            synchronized(captured) {
              captured += Captured(toolName, args)
            }
            val responseBody = callbackSuccess("captured")
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
          } finally {
            exchange.close()
          }
        }
        start()
      }

      val baseUrl = "http://127.0.0.1:${server.address.port}"
      // Nested try/finally so a failure during `spawn` or `connect` doesn't leak the
      // HttpServer thread or the subprocess. Each resource is teared down in the
      // reverse order of acquisition; the innermost block holds the assertion body.
      try {
        val generated = InlineScriptToolServerSynthesizer.synthesize(
          tools = listOf(
            InlineScriptToolConfig(
              script = authorFile.absolutePath,
              name = "exerciseScriptOverloads",
              description = "Exercises both script-overload entries via the SDK proxy.",
              inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                  putJsonObject("path") { put("type", "string") }
                }
              },
            ),
          ),
          outputDir = generatedDir,
        )

        val spawned = McpSubprocessSpawner.spawn(
          config = generated.single(),
          context = baseContext.copy(
            platform = TrailblazeDevicePlatform.WEB,
            driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
            baseUrl = baseUrl,
          ),
        )
        try {
          val stderrLog = File(tmpDir, "script-overload.stderr.log")
          val session = runCatching {
            McpSubprocessSession.connect(
              spawnedProcess = spawned,
              stderrCapture = StderrCapture(stderrLog),
            )
          }.getOrElse { t ->
            val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
            throw AssertionError(
              "script-overload subprocess failed during connect. stderr:\n$stderr",
              t,
            )
          }
          try {
            runCatching {
              val requestMeta = RequestMeta(
                json = buildJsonObject {
                  putJsonObject("trailblaze") {
                    put("baseUrl", baseUrl)
                    put("sessionId", baseContext.sessionId.value)
                    put("invocationId", "script-overload-invocation")
                    putJsonObject("device") {
                      put("platform", "web")
                      put("widthPixels", 1440)
                      put("heightPixels", 900)
                      put("driverType", "playwright-native")
                    }
                    putJsonObject("memory") {}
                  }
                }
              )
              val response = session.client.callTool(
                CallToolRequest(
                  params = CallToolRequestParams(
                    name = "exerciseScriptOverloads",
                    arguments = buildJsonObject { put("path", "/hello") },
                    meta = requestMeta,
                  ),
                ),
              )
              val text = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
                "exerciseScriptOverloads returned no text content — response: $response"
              }
              assertThat(text).isEqualTo("exercised")

              val calls = synchronized(captured) { captured.toList() }
              // One call: web_evaluate in the function-form.
              assertThat(calls.map { it.toolName }).isEqualTo(listOf("web_evaluate"))
              // Function-form: the script field is `(<fnSrc>).apply(null, <argsJson>)`. Pinning
              // the `.apply(null, [...])` shape catches accidental refactors (e.g. a switch to
              // `(fn)(<arg>)` that wouldn't survive multi-arg cases).
              val evaluateScript = calls[0].args["script"]?.jsonPrimitive?.content
              checkNotNull(evaluateScript) {
                "web_evaluate call carries no `script` field — proxy didn't translate the " +
                  "function-form overload. args=${calls[0].args}"
              }
              assertThat(evaluateScript).contains(".apply(null, [\"/hello\"])")
              assertThat(evaluateScript).contains("path.toUpperCase()")
            }.getOrElse { t ->
              val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
              throw AssertionError("script-overload e2e assertion failed. stderr:\n$stderr", t)
            }
          } finally {
            session.shutdown()
          }
        } finally {
          val exited = spawned.process.waitFor(10, TimeUnit.SECONDS)
          if (!exited) {
            spawned.process.destroyForcibly()
            spawned.process.waitFor(5, TimeUnit.SECONDS)
          }
          assertThat(exited).isEqualTo(true)
        }
      } finally {
        server.stop(0)
      }
    }
  }

  @Test fun `handler throw surfaces as isError envelope carrying error message and name`() {
    // End-to-end pin for the error path: when a scripted-tool author's handler throws, the
    // synthesized wrapper's MCP layer (`registerPendingTools`' try/catch in `tool.ts`)
    // catches it and surfaces an `isError: true` envelope whose text content includes the
    // original error's name, message, AND stack. On the trailblaze-SDK consumer side, the
    // proxy then re-throws an `Error` whose message contains the inner error's message —
    // see `testing.test.ts::stub with errorMessage throws with production wording` for the
    // proxy-side pin.
    //
    // **Known forwarding gap (worth fixing as a follow-up):** the consumer-side proxy
    // throws a plain `Error` with a concatenated string — the original Error subclass
    // (`name`) and `stack` are flattened into the text payload, not preserved as
    // re-throwable structured fields. So a caller can `try/catch` and read the message but
    // cannot `instanceof` the original Error subclass or read the inner `.stack` from a
    // structured field. The information IS in the text envelope; it's just not parsed back
    // into structured Error fields on the consumer side. Sufficient for diagnostics; less
    // good for callers that want to programmatically branch on Error subclass.
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )

      authorFile.writeText(
        """
        export async function throwingTool(args, _ctx, _client) {
          throw new Error("intentional failure from throwingTool: " + (args.reason ?? "no-reason"));
        }
        """.trimIndent() + "\n",
      )

      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = authorFile.absolutePath,
            name = "throwingTool",
            description = "Always throws; used to verify error-envelope forwarding.",
            inputSchema = buildJsonObject {
              put("type", "object")
              putJsonObject("properties") {
                putJsonObject("reason") { put("type", "string") }
              }
            },
          ),
        ),
        outputDir = generatedDir,
      )

      val spawned = McpSubprocessSpawner.spawn(config = generated.single(), context = baseContext)
      val stderrLog = File(tmpDir, "throwing-tool.stderr.log")
      val session = runCatching {
        McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = StderrCapture(stderrLog),
        )
      }.getOrElse { t ->
        val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
        throw AssertionError("throwingTool subprocess failed during connect. stderr:\n$stderr", t)
      }
      try {
        runCatching {
          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = "throwingTool",
                arguments = buildJsonObject { put("reason", "test-trigger") },
              ),
            ),
          )

          // The throw must surface as `isError: true` — this is the wire signal that lets
          // both the trailblaze-SDK proxy and raw-MCP consumers branch into their error
          // path rather than treating the envelope as a success.
          assertThat(response.isError).isEqualTo(true)

          // Error envelope's text content must carry the original Error's `message`
          // verbatim so a caller can diagnose. The producer-side catch in
          // `registerPendingTools` (tool.ts) builds this from `error.name`, `error.message`,
          // and `error.stack`; we assert on the message because it's the load-bearing
          // diagnostic surface.
          val text = checkNotNull((response.content.firstOrNull() as? TextContent)?.text) {
            "throwingTool isError envelope carries no text content — response: $response"
          }
          assertThat(text).contains("intentional failure from throwingTool: test-trigger")

          // Error envelope must NOT carry structuredContent — error responses are
          // diagnostic-text-only by convention. The `CallToolResultMapper` Kotlin side
          // already drops structuredContent on isError, and the wire layer mirrors that.
          assertThat(response.structuredContent).isEqualTo(null)
        }.getOrElse { t ->
          val stderr = if (stderrLog.isFile) stderrLog.readText() else "(no stderr captured)"
          throw AssertionError("throwingTool e2e assertion failed. stderr:\n$stderr", t)
        }
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

  @Test fun `registered conditional example can branch when an inner tool probe fails`() {
    runBlocking {
      assumeTrue(
        "bun must be on PATH to exercise the synthesized inline tool host path",
        runtimeAvailable(),
      )
      assumeTrue(
        "Playwright conditional example must exist in the repo",
        realConditionalExampleFile.isFile,
      )

      val requestedTools = mutableListOf<String>()
      val formVisible = AtomicReference(false)
      val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/scripting/callback") { exchange ->
          try {
            val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
            val action = request["action"]!!.jsonObject
            val toolName = action["tool_name"]!!.jsonPrimitive.content
            val args =
              Json.parseToJsonElement(action["arguments_json"]!!.jsonPrimitive.content).jsonObject
            synchronized(requestedTools) {
              requestedTools += toolName
            }

            val responseBody = when (toolName) {
              "web_navigate" -> {
                val url = args["url"]?.jsonPrimitive?.content ?: ""
                formVisible.set(url.contains("#form"))
                callbackSuccess("navigated")
              }
              "web_verifyTextVisible" -> {
                if (formVisible.get()) callbackSuccess("visible")
                else callbackFailure("Assertion failed: text is not visible")
              }
              "web_click" -> {
                if (args["ref"]?.jsonPrimitive?.content == "css=a[href='#form']") {
                  formVisible.set(true)
                }
                callbackSuccess("clicked")
              }
              else -> callbackFailure("Unexpected tool: $toolName")
            }

            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
          } finally {
            exchange.close()
          }
        }
        start()
      }

      val baseUrl = "http://127.0.0.1:${server.address.port}"
      val generated = InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(
            script = realConditionalExampleFile.absolutePath,
            name = "playwrightSample_web_openFormIfNeeded",
            description = "Conditional example tool.",
            inputSchema = buildJsonObject {
              put("type", "object")
              putJsonObject("properties") {
                putJsonObject("relativePath") {
                  put("type", "string")
                }
              }
            },
          ),
        ),
        outputDir = generatedDir,
      )

      val spawned = McpSubprocessSpawner.spawn(
        config = generated.single(),
        context = baseContext.copy(
          platform = TrailblazeDevicePlatform.WEB,
          driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          baseUrl = baseUrl,
        ),
      )
      val stderrLog = File(tmpDir, "inline-script-tool-conditional.stderr.log")
      val session = McpSubprocessSession.connect(
        spawnedProcess = spawned,
        stderrCapture = StderrCapture(stderrLog),
      )

      try {
        val requestMeta = RequestMeta(
          json = buildJsonObject {
            putJsonObject("trailblaze") {
              put("baseUrl", baseUrl)
              put("sessionId", baseContext.sessionId.value)
              put("invocationId", "conditional-example-id")
              putJsonObject("device") {
                put("platform", "web")
                put("widthPixels", 1440)
                put("heightPixels", 900)
                put("driverType", "playwright-native")
              }
              putJsonObject("memory") {}
            }
          }
        )

        val openedResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "playwrightSample_web_openFormIfNeeded",
              arguments = buildJsonObject {
                put("relativePath", "./examples/playwright-native/sample-app/index.html")
              },
              meta = requestMeta,
            ),
          ),
        )
        val openedText = checkNotNull((openedResponse.content.firstOrNull() as? TextContent)?.text)
        assertThat(openedText).contains("Opened the form section")
        assertThat(synchronized(requestedTools) { requestedTools.toList() }).isEqualTo(
          listOf(
            "web_navigate",
            "web_verifyTextVisible",
            "web_click",
            "web_verifyTextVisible",
          ),
        )

        synchronized(requestedTools) {
          requestedTools.clear()
        }

        val alreadyVisibleResponse = session.client.callTool(
          CallToolRequest(
            params = CallToolRequestParams(
              name = "playwrightSample_web_openFormIfNeeded",
              arguments = buildJsonObject {
                put("relativePath", "./examples/playwright-native/sample-app/index.html#form")
              },
              meta = requestMeta,
            ),
          ),
        )
        val alreadyVisibleText = checkNotNull(
          (alreadyVisibleResponse.content.firstOrNull() as? TextContent)?.text,
        )
        assertThat(alreadyVisibleText).contains("already visible")
        assertThat(synchronized(requestedTools) { requestedTools.toList() }).isEqualTo(
          listOf(
            "web_navigate",
            "web_verifyTextVisible",
          ),
        )
      } finally {
        server.stop(0)
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

  private fun runtimeAvailable(): Boolean = try {
    BunRuntimeDetector.cached
    true
  } catch (_: NoBunRuntimeException) {
    false
  }

  private fun locateExampleFile(relative: String): File {
    // Walk up from the JVM working directory looking for the example file. Also peeks
    // one level into immediate child directories so the test still resolves when run
    // from a parent workspace root that nests this checkout one level deeper.
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
      val direct = File(dir, relative)
      if (direct.isFile) return direct
      dir.listFiles { f -> f.isDirectory }?.forEach { child ->
        val nested = File(child, relative)
        if (nested.isFile) return nested
      }
      dir = dir.parentFile
    }
    return File(System.getProperty("user.dir"), relative)
  }

  private fun callbackSuccess(textContent: String): String =
    """
    {
      "result": {
        "type": "call_tool_result",
        "success": true,
        "text_content": ${Json.encodeToString(String.serializer(), textContent)},
        "error_message": ""
      }
    }
    """.trimIndent()

  private fun callbackFailure(errorMessage: String): String =
    """
    {
      "result": {
        "type": "call_tool_result",
        "success": false,
        "text_content": "",
        "error_message": ${Json.encodeToString(String.serializer(), errorMessage)}
      }
    }
    """.trimIndent()

  // --------------------------------------------------------------------------------------------
  // Multi-tool shape — one author module, N tools, one synthesized wrapper.
  // Pins the dedup contract that makes the developer-facing "scripted tool, not MCP server"
  // promise hold: a group of 8 tools costs ONE wrapper file (and therefore one subprocess at
  // runtime), not 8. Without dedup, every group of M tools sharing a script would spawn M
  // subprocesses each loading the same module — surfacing as load-time waste and broken
  // module-level state sharing if the author relied on it (e.g. a per-process cache).
  // --------------------------------------------------------------------------------------------

  @Test fun `synthesize groups configs sharing the same script into one wrapper`() {
    authorFile.writeText(
      """
      export function group_alpha() { return "alpha"; }
      export function group_beta() { return "beta"; }
      """.trimIndent(),
    )
    val generated = InlineScriptToolServerSynthesizer.synthesize(
      tools = listOf(
        InlineScriptToolConfig(
          script = authorFile.absolutePath,
          name = "group_alpha",
          description = "Alpha entry.",
        ),
        InlineScriptToolConfig(
          script = authorFile.absolutePath,
          name = "group_beta",
          description = "Beta entry.",
        ),
      ),
      outputDir = generatedDir,
    )

    assertThat(generated.size).isEqualTo(1)
    val wrapper = File(generated.single().script).readText()
    // Both tools register on the SAME wrapper. Confirm via `trailblaze.tool(` lookups: there
    // should be exactly two — one per entry — even though the author module is imported only
    // once (one `pathToFileURL($authorModulePath).href` line).
    val toolRegistrations = Regex("trailblaze\\.tool\\(").findAll(wrapper).count()
    assertThat(toolRegistrations).isEqualTo(2)
    val authorImports = Regex("const authorModule = await import").findAll(wrapper).count()
    assertThat(authorImports).isEqualTo(1)
    assertThat(wrapper).contains("\"group_alpha\"")
    assertThat(wrapper).contains("\"group_beta\"")
  }

  @Test fun `synthesize keeps separate wrappers for distinct script paths`() {
    // Two unrelated tools backed by different author files — dedup must NOT collapse them.
    // Regression guard against an over-eager grouping key (e.g. on tool name alone) that
    // would silently merge unrelated groups and break tool resolution at registration time.
    authorFile.writeText("export function tool_in_first_file() {}")
    helperFile.writeText("export function tool_in_second_file() {}")
    val generated = InlineScriptToolServerSynthesizer.synthesize(
      tools = listOf(
        InlineScriptToolConfig(script = authorFile.absolutePath, name = "tool_in_first_file"),
        InlineScriptToolConfig(script = helperFile.absolutePath, name = "tool_in_second_file"),
      ),
      outputDir = generatedDir,
    )
    assertThat(generated.size).isEqualTo(2)
  }

  @Test fun `synthesize rejects a multi-tool group with duplicate tool names`() {
    authorFile.writeText("export function dup_tool() {}")
    val ex = kotlin.runCatching {
      InlineScriptToolServerSynthesizer.synthesize(
        tools = listOf(
          InlineScriptToolConfig(script = authorFile.absolutePath, name = "dup_tool"),
          InlineScriptToolConfig(script = authorFile.absolutePath, name = "dup_tool"),
        ),
        outputDir = generatedDir,
      )
    }.exceptionOrNull()
    val message = (ex ?: error("expected an exception")).message ?: ""
    assertThat(message).contains("duplicate tool name 'dup_tool'")
  }
}
