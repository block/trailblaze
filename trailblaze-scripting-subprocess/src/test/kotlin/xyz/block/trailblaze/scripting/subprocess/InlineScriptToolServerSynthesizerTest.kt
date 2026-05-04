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
        "bun or tsx must be on PATH to exercise the synthesized inline tool host path",
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

  @Test fun `two tool entries can reference the same module when their names match named exports`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the synthesized inline tool host path",
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

      val responses = generated.map { config ->
        val spawned = McpSubprocessSpawner.spawn(config = config, context = baseContext)
        val stderrLog = File(tmpDir, "${config.script.hashCode()}.stderr.log")
        val session = McpSubprocessSession.connect(
          spawnedProcess = spawned,
          stderrCapture = StderrCapture(stderrLog),
        )
        try {
          val toolName = session.client.listTools(ListToolsRequest()).tools.single().name
          val response = session.client.callTool(
            CallToolRequest(
              params = CallToolRequestParams(
                name = toolName,
                arguments = buildJsonObject {},
              ),
            ),
          )
          checkNotNull((response.content.firstOrNull() as? TextContent)?.text)
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

      assertThat(responses).containsExactlyInAnyOrder("first", "second")
    }
  }

  @Test fun `registered conditional example can branch when an inner tool probe fails`() {
    runBlocking {
      assumeTrue(
        "bun or tsx must be on PATH to exercise the synthesized inline tool host path",
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
              "web_verify_text_visible" -> {
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
            "web_verify_text_visible",
            "web_click",
            "web_verify_text_visible",
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
            "web_verify_text_visible",
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
    NodeRuntimeDetector.cached
    true
  } catch (_: NoCompatibleTsRuntimeException) {
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
}
