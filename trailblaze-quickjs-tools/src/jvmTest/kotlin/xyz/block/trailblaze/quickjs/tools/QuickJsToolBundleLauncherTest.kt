package xyz.block.trailblaze.quickjs.tools

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * Direct coverage for [QuickJsToolBundleLauncher] using inline JS bundle fixtures via
 * [InlineBundleSource]. Exercises the launch → register → shutdown round-trip without
 * touching disk or the Android instrumentation runtime — the on-device counterpart in
 * `:examples:android-sample-app-uitests` proves the asset-loader and instrumentation
 * runtime side.
 */
class QuickJsToolBundleLauncherTest {

  private val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
  private val sessionId = SessionId("quickjs-launcher-test")
  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "quickjs-launcher-test",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
    widthPixels = 1080,
    heightPixels = 1920,
    classifiers = listOf<TrailblazeDeviceClassifier>(),
  )

  private var launchedRuntime: LaunchedQuickJsToolRuntime? = null

  @AfterTest
  fun teardown() {
    runBlocking {
      launchedRuntime?.let { runCatching { it.shutdownAll() } }
    }
  }

  @Test
  fun `launchAll registers each tool advertised by the bundle into the repo`() = runBlocking {
    val bundleJs = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["alpha"] = {
        name: "alpha",
        spec: { description: "Alpha tool", inputSchema: { x: { type: "string" } } },
        handler: async (args) => ({ content: [{ type: "text", text: "alpha:" + args.x }] }),
      };
      tools["beta"] = {
        name: "beta",
        spec: { description: "Beta tool" },
        handler: async () => ({ content: [{ type: "text", text: "beta" }] }),
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs, filename = "test-bundle.js") },
    )
    launchedRuntime = runtime
    val registered = toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet()
    assertEquals(setOf("alpha", "beta"), registered)
  }

  @Test
  fun `launchAll keeps tools tagged trailblaze requiresHost when preferHostAgent is true`() = runBlocking {
    // Counterpart to the on-device test below. A host CLI / desktop daemon resolving
    // bundles from the local filesystem should be able to opt into registering host-only
    // tools by passing `preferHostAgent = true`. Without this the launcher would silently
    // drop those tools regardless of session shape, which would silently break host runners
    // once they migrate to the QuickJS path.
    val bundleJs =
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["onDeviceOk"] = {
        name: "onDeviceOk",
        spec: { description: "Runs anywhere" },
        handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
      };
      tools["hostOnly"] = {
        name: "hostOnly",
        spec: { description: "Host-only", _meta: { "trailblaze/requiresHost": true } },
        handler: async () => ({ content: [{ type: "text", text: "host" }] }),
      };
    """
        .trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      preferHostAgent = true,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime
    val registered = toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet()
    assertEquals(
      setOf("onDeviceOk", "hostOnly"),
      registered,
      "preferHostAgent=true must surface requiresHost tools to host sessions",
    )
  }

  @Test
  fun `launchAll drops tools tagged trailblaze requiresHost on-device`() = runBlocking {
    // `_meta.trailblaze/requiresHost: true` must skip the tool at registration time when
    // the launcher passes preferHostAgent=false (the on-device default). Without the
    // filter, the LLM would pick a tool it can't actually execute and the trail would
    // fail mid-run instead of at registration.
    val bundleJs = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["onDeviceOk"] = {
        name: "onDeviceOk",
        spec: { description: "Runs anywhere" },
        handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
      };
      tools["hostOnly"] = {
        name: "hostOnly",
        spec: { description: "Host-only", _meta: { "trailblaze/requiresHost": true } },
        handler: async () => ({ content: [{ type: "text", text: "host" }] }),
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime
    val registered = toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet()
    assertEquals(setOf("onDeviceOk"), registered, "hostOnly must be filtered on-device")
  }

  @Test
  fun `shutdownAll removes every dynamic registration the launch added`() = runBlocking {
    val bundleJs = """
      globalThis.__trailblazeTools = {
        gamma: {
          name: "gamma",
          spec: {},
          handler: async () => ({ content: [{ type: "text", text: "g" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    assertEquals(setOf("gamma"), toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet())
    runtime.shutdownAll()
    assertTrue("expected dynamic tools to be cleared, got ${toolRepo.getRegisteredDynamicTools().keys}") {
      toolRepo.getRegisteredDynamicTools().isEmpty()
    }
  }

  @Test
  fun `launchAll skips command-only entries with a log and continues`() = runBlocking {
    val bundleJs = """
      globalThis.__trailblazeTools = {
        delta: {
          name: "delta",
          spec: {},
          handler: async () => ({ content: [{ type: "text", text: "d" }] }),
        },
      };
    """.trimIndent()
    // First entry is command-only (host-only by definition); second is a script-backed
    // bundle. Launcher must skip the first and register the second's tool.
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(
        McpServerConfig(command = "/bin/true"),
        McpServerConfig(script = "ignored.js"),
      ),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime
    assertEquals(setOf("delta"), toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet())
  }

  @Test
  fun `decodeToolCall produces a QuickJsTrailblazeTool that round-trips through the host`() = runBlocking {
    val bundleJs = """
      globalThis.__trailblazeTools = {
        replay: {
          name: "replay",
          spec: { description: "Replay back the text arg" },
          handler: async (args) => ({ content: [{ type: "text", text: "replay:" + args.text }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val registration = toolRepo.getRegisteredDynamicTools().values.single()
    val tool = registration.decodeToolCall("""{"text":"hi"}""") as QuickJsTrailblazeTool

    val response = tool.host.callTool(tool.advertisedName.toolName, tool.args, ctx = null)
    val text = ((response["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
    assertEquals("replay:hi", text)
  }

  @Test
  fun `descriptor reflects the flat author input schema with all params required`() = runBlocking {
    val bundleJs = """
      globalThis.__trailblazeTools = {
        twoArgs: {
          name: "twoArgs",
          spec: {
            description: "Takes two args",
            inputSchema: {
              first: { type: "string", description: "the first" },
              second: { type: "number" },
            },
          },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals("Takes two args", descriptor.description)
    val paramNames = descriptor.requiredParameters.map { it.name }.toSet()
    assertEquals(setOf("first", "second"), paramNames)
    assertEquals(0, descriptor.optionalParameters.size)
    val first = descriptor.requiredParameters.single { it.name == "first" }
    assertEquals("string", first.type)
    assertEquals("the first", first.description)
  }

  @Test
  fun `descriptor reflects the JSON Schema input shape including required partition`() = runBlocking {
    val bundleJs = """
      globalThis.__trailblazeTools = {
        nested: {
          name: "nested",
          spec: {
            description: "JSON Schema-shape inputSchema",
            inputSchema: {
              type: "object",
              properties: {
                must: { type: "string" },
                opt: { type: "boolean" },
              },
              required: ["must"],
            },
          },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals(listOf("must"), descriptor.requiredParameters.map { it.name })
    assertEquals(listOf("opt"), descriptor.optionalParameters.map { it.name })
  }

  @Test
  fun `descriptor flat-shape detection is not fooled by a parameter literally named properties`() = runBlocking {
    // Regression test for a subtle bug a reviewer flagged: the JSON-Schema-vs-flat
    // detector used to key only on the presence of a `properties` JsonObject. An author
    // who legitimately declared a flat parameter named `properties` (whose schema is an
    // object) would be misclassified as the nested JSON Schema shape — the descriptor
    // would surface schema keys (`type`, `description`) as parameter names instead of
    // the actual one (`properties`). Tightening the detector to require BOTH
    // `type: "object"` and a `properties` object closes that hole.
    val bundleJs = """
      globalThis.__trailblazeTools = {
        rename: {
          name: "rename",
          spec: {
            description: "Renames a property",
            inputSchema: {
              properties: { type: "string", description: "Property names to rename" },
            },
          },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals(
      listOf("properties"),
      descriptor.requiredParameters.map { it.name },
      "expected the flat parameter named `properties` to round-trip — without the type-co-condition fix, the descriptor would surface `type`/`description` here instead",
    )
    assertEquals("string", descriptor.requiredParameters.single().type)
  }

  @Test
  fun `default host binding returns a structured not-yet-wired error envelope`() = runBlocking {
    // Pins the placeholder host-binding contract: the launcher-installed binding always
    // returns a well-formed TrailblazeToolResult with isError=true rather than
    // deadlocking, throwing, or returning malformed JSON. A future PR replaces this with
    // a real cross-tool dispatch path; this test should fail then and be rewritten.
    val bundleJs = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["caller"] = {
        name: "caller",
        spec: {},
        handler: async () => {
          const json = await globalThis.__trailblazeCall("nope", JSON.stringify({}));
          const env = JSON.parse(json);
          return { content: [{ type: "text", text: env.isError ? "got-error:" + env.content[0].text : "ok" }] };
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val response = runtime.hosts.single().callTool("caller", buildJsonObject { put("x", 1) })
    val text = ((response["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
    assertTrue("expected the not-yet-wired envelope, got: $text") {
      text.startsWith("got-error:") && "not yet wired" in text
    }
  }

  @Test
  fun `failing bundle startup tears down already-started hosts and rethrows`() = runBlocking {
    val firstBundleJs = """
      globalThis.__trailblazeTools = {
        first: { name: "first", spec: {}, handler: async () => ({ content: [] }) },
      };
    """.trimIndent()
    val secondBundleJs = "this is not valid JS ((("
    val err = runCatching {
      QuickJsToolBundleLauncher.launchAll(
        bundles = listOf(
          McpServerConfig(script = "first.js"),
          McpServerConfig(script = "broken.js"),
        ),
        deviceInfo = deviceInfo,
        sessionId = sessionId,
        toolRepo = toolRepo,
        bundleSourceResolver = { entry ->
          when (entry.script) {
            "first.js" -> InlineBundleSource(firstBundleJs)
            else -> InlineBundleSource(secondBundleJs, filename = "broken.js")
          }
        },
      )
    }.exceptionOrNull()
    assertNotNull(err, "expected the malformed second bundle to abort the launch")
    // First bundle's tools never landed in the repo because addDynamicTools is only
    // called after every bundle started cleanly.
    assertTrue(
      "no dynamic tools should be registered after a failed launch, got ${toolRepo.getRegisteredDynamicTools().keys}",
    ) {
      toolRepo.getRegisteredDynamicTools().isEmpty()
    }
  }

  @Test
  fun `descriptor with description-only spec has no parameters`() = runBlocking {
    // The description-only path (no `inputSchema`) is an early-return branch in the
    // descriptor parser. Pin it so a refactor can't accidentally drop the description or
    // surface phantom parameters.
    val bundleJs =
      """
      globalThis.__trailblazeTools = {
        zeroArgs: {
          name: "zeroArgs",
          spec: { description: "Takes no arguments" },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals("Takes no arguments", descriptor.description)
    assertEquals(emptyList(), descriptor.requiredParameters)
    assertEquals(emptyList(), descriptor.optionalParameters)
  }

  @Test
  fun `descriptor silently drops parameter whose schema is a primitive`() = runBlocking {
    // A parameter whose schema is a JsonPrimitive (e.g. an author who wrote
    // `text: "string"` instead of `text: { type: "string" }`) is silently dropped. Pin
    // the current behavior so the silent-drop is at least observable in tests; if we
    // later promote this to a registration-time warning, this test flips from "asserts
    // dropped" to "asserts a warning fires".
    val bundleJs =
      """
      globalThis.__trailblazeTools = {
        oneOk: {
          name: "oneOk",
          spec: {
            description: "Has one good and one malformed param",
            inputSchema: {
              ok: { type: "string" },
              malformed: "string",
            },
          },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals(
      listOf("ok"),
      descriptor.requiredParameters.map { it.name },
      "the malformed `malformed: \"string\"` param must drop without crashing registration",
    )
  }

  @Test
  fun `descriptor handles empty inputSchema as zero-parameter tool`() = runBlocking {
    val bundleJs =
      """
      globalThis.__trailblazeTools = {
        emptySchema: {
          name: "emptySchema",
          spec: { description: "Empty inputSchema", inputSchema: {} },
          handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
        },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(bundleJs) },
    )
    launchedRuntime = runtime

    val descriptor = toolRepo.getRegisteredDynamicTools().values.single().trailblazeDescriptor
    assertEquals(emptyList(), descriptor.requiredParameters)
    assertEquals(emptyList(), descriptor.optionalParameters)
  }

  @Test
  fun `shutdownAll continues and logs the bundle filename when a host throws`() = runBlocking {
    // Pins the `shutdownAll` invariant: partial shutdown still tears down the rest,
    // with each failure observable in logs. Force the FIRST host's shutdown to throw
    // by closing its engine out from under the runtime, then assert the second host
    // still shuts down. (Direct log capture isn't trivial without a Console redirect,
    // so this test only pins loop semantics; the log line shape is exercised by the
    // structural invariant that bundleFilenames lines up with hosts.)
    val firstBundle =
      """
      globalThis.__trailblazeTools = {
        first: { name: "first", spec: {}, handler: async () => ({ content: [] }) },
      };
    """.trimIndent()
    val secondBundle =
      """
      globalThis.__trailblazeTools = {
        second: { name: "second", spec: {}, handler: async () => ({ content: [] }) },
      };
    """.trimIndent()
    val runtime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(
        McpServerConfig(script = "first.js"),
        McpServerConfig(script = "second.js"),
      ),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { entry ->
        when (entry.script) {
          "first.js" -> InlineBundleSource(firstBundle, filename = "first.js")
          else -> InlineBundleSource(secondBundle, filename = "second.js")
        }
      },
    )
    launchedRuntime = runtime
    assertEquals(2, runtime.hosts.size)

    // Force the FIRST host's shutdown to throw by closing it out from under the
    // runtime — `shutdown()` then catches an `IllegalStateException` from the closed
    // engine. The runtime's `runCatching` must swallow it, log, and continue to the
    // second host (which still shuts down cleanly because we didn't pre-close it).
    runtime.hosts[0].quickJs.close()

    runtime.shutdownAll()
    launchedRuntime = null

    // The second host's `shutdown()` ran (the runtime's loop didn't bail on the first
    // host's failure). Repeating shutdown on it must succeed (idempotent) — proves the
    // engine wasn't leaked.
    val secondShutdownAgain = runCatching { runtime.hosts[1].shutdown() }
    assertTrue("second host must remain shut-downable") { secondShutdownAgain.isSuccess }

    // The repo was cleared regardless of host shutdown failures.
    assertTrue("expected dynamic tools to be cleared after partial-shutdown teardown") {
      toolRepo.getRegisteredDynamicTools().isEmpty()
    }
  }
}
