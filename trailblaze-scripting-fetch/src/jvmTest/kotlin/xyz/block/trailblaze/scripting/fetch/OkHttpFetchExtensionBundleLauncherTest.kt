package xyz.block.trailblaze.scripting.fetch

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.BundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * Pins that [OkHttpFetchExtension] flows through [QuickJsToolBundleLauncher.launchAll] to the
 * launched bundles' engines — the path the on-device launchers (`AndroidTrailblazeRule`,
 * `OnDeviceScriptedToolBundleLauncher`) use to bind `fetch`, as opposed to the direct
 * [xyz.block.trailblaze.quickjs.tools.QuickJsToolHost.connect] wiring the host launchers use
 * (covered by [OkHttpFetchExtensionTest]). Asserts the observable contract only: what the tool's
 * handler sees back from `fetch`.
 */
class OkHttpFetchExtensionBundleLauncherTest {

  private val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
  private val sessionId = SessionId("fetch-bundle-launcher-test")
  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "fetch-bundle-launcher-test",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
    widthPixels = 1080,
    heightPixels = 1920,
    classifiers = listOf<TrailblazeDeviceClassifier>(),
  )

  private var launchedRuntime: LaunchedQuickJsToolRuntime? = null
  private var server: HttpServer? = null

  @AfterTest
  fun teardown() = runBlocking {
    launchedRuntime?.let { runCatching { it.shutdownAll() } }
    launchedRuntime = null
    server?.stop(0)
    server = null
  }

  private fun startServer(body: String): String {
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/") { exchange ->
      exchange.use {
        val bytes = body.toByteArray()
        it.sendResponseHeaders(200, bytes.size.toLong())
        it.responseBody.write(bytes)
      }
    }
    s.executor = null
    s.start()
    server = s
    return "http://127.0.0.1:${s.address.port}"
  }

  private suspend fun launch(extension: OkHttpFetchExtension?): LaunchedQuickJsToolRuntime =
    QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineJsBundleSource(FETCH_PROBE_BUNDLE) },
      engineExtension = extension,
    ).also { launchedRuntime = it }

  @Test
  fun `a bundle launched with the fetch extension can fetch from its tool handler`() = runBlocking {
    val baseUrl = startServer("""{"hello":"launcher"}""")

    val runtime = launch(OkHttpFetchExtension())

    val result = runtime.hosts.single().callTool("fetchProbe", buildJsonObject { put("url", "$baseUrl/data") })
    val probe = Json.parseToJsonElement(textContent(result)).jsonObject
    assertEquals(200, probe["status"]!!.jsonPrimitive.int)
    assertEquals(true, probe["ok"]!!.jsonPrimitive.boolean)
    assertTrue(
      probe["body"]!!.jsonPrimitive.content.contains("launcher"),
      "expected the response body to reach the handler; got: $probe",
    )
  }

  @Test
  fun `the extension is what binds fetch - absent it, the global is undefined`() = runBlocking {
    // Asserts the observable contract (is the global bound?) rather than an engine error message:
    // with the extension installed a request to a closed port ALSO throws a message containing
    // "fetch", so a substring match would pass in both worlds and pin nothing.
    val withoutExtension = launch(extension = null)
    assertEquals("undefined", fetchTypeof(withoutExtension))
    withoutExtension.shutdownAll()

    assertEquals("function", fetchTypeof(launch(OkHttpFetchExtension())))
  }

  private suspend fun fetchTypeof(runtime: LaunchedQuickJsToolRuntime): String {
    val result = runtime.hosts.single().callTool("fetchTypeof", buildJsonObject {})
    return textContent(result)
  }

  private fun textContent(result: JsonObject): String =
    ((result["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content

  /** Inline-JS [BundleSource] — the launcher's file/asset sources don't apply to a JVM test. */
  private class InlineJsBundleSource(private val source: String) : BundleSource {
    override val filename: String = "fetch-probe-bundle.js"
    override fun read(): String = source
  }

  companion object {
    /**
     * Adds a `fetchTypeof` tool to the shared probe bundle: reports whether the `fetch` global is
     * bound at all, which is the contract this test class cares about (the sibling
     * [OkHttpFetchExtensionTest] covers fetch's request/response behavior).
     */
    private val FETCH_PROBE_BUNDLE =
      FetchProbeBundle.SOURCE +
        """

      tools["fetchTypeof"] = {
        name: "fetchTypeof",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: typeof globalThis.fetch }] }),
      };
        """.trimIndent()
  }
}
