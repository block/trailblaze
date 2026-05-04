package xyz.block.trailblaze.examples.sampleapp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.AndroidAssetBundleSource
import xyz.block.trailblaze.quickjs.tools.LaunchedQuickJsToolRuntime
import xyz.block.trailblaze.quickjs.tools.QuickJsToolBundleLauncher
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo

/**
 * On-device counterpart to `:trailblaze-quickjs-tools`'s JVM `SampleAppToolsDemoTest`. Proves
 * `QuickJsToolHost` + the new `QuickJsToolBundleLauncher` plumbing wire up correctly under the
 * Android instrumentation runtime, mirroring what the legacy `OnDeviceBundleRoundTripTest` does for
 * the MCP-shaped runtime.
 *
 * Loads two bundles staged into the test APK assets:
 * 1. `config/quickjs-tools/pure.js` — the pure-JS sample, copied via `stageTrailAssets`.
 * 2. `fixtures/quickjs/typed.bundle.js` — the typed `@trailblaze/tools` bundle produced by
 *    `:trailblaze-quickjs-tools:bundleSampleAppTypedAuthorTool` and staged via
 *    `stageQuickjsBundles`.
 *
 * Asserts:
 * - `quickjs-kt`'s native library loads under the Android instrumentation runtime.
 * - The launcher registers all four tools (two per bundle) into a `TrailblazeToolRepo` — the
 *   contract the LLM-side dispatch layer depends on.
 * - `QuickJsToolHost.callTool(...)` round-trips against a real bundle on-device, dispatching one
 *   tool from each flavor. This stands in for the issue's "dispatches one via
 *   `client.callTool(...)`" requirement; the equivalent dispatch through `TrailblazeToolRepo`
 *   requires a full `TrailblazeToolExecutionContext` (screen state, agent, etc.) which is exercised
 *   by the rule-driven trail tests, not this transport-shape proof.
 *
 * Ships in `android-sample-app-uitests-debug-androidTest.apk`. A regression in QuickJS loading, the
 * asset-path resolver, or the launcher's registration filter will fail this test on the cloud
 * device farm and block merge.
 */
class QuickJsToolBundleOnDeviceTest {

  private val hosts = mutableListOf<QuickJsToolHost>()
  private var launchedRuntime: LaunchedQuickJsToolRuntime? = null

  @After
  fun teardown() = runBlocking {
    launchedRuntime?.let { runCatching { it.shutdownAll() } }
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  /**
   * Smallest possible smoke check: load `pure.js` from the staged assets, list, and dispatch. If
   * `quickjs-kt` can't load its native lib under instrumentation, this fails immediately with a
   * `UnsatisfiedLinkError`. If the asset-path resolver mis-normalizes the path, the
   * `AssetManager.open` raises `FileNotFoundException` here.
   */
  @Test
  fun pureJsBundleLoadsAndDispatchesOnDevice() = runBlocking {
    val source = AndroidAssetBundleSource(assetPath = "config/quickjs-tools/pure.js")
    val host = QuickJsToolHost.connect(bundleJs = source.read(), bundleFilename = source.filename)
    hosts.add(host)

    val toolNames = host.listTools().map { it.name }.toSet()
    assertTrue(
      "expected sampleApp_reverseString in $toolNames",
      "sampleApp_reverseString" in toolNames,
    )
    assertTrue("expected sampleApp_addNumbers in $toolNames", "sampleApp_addNumbers" in toolNames)

    val reversed =
      host.callTool("sampleApp_reverseString", buildJsonObject { put("text", "android") })
    assertEquals("diordna", reversed.firstTextContent())
  }

  /**
   * The typed `@trailblaze/tools` bundle path — proves the esbuild output evaluates cleanly under
   * Android-side QuickJS, not just the JVM target.
   */
  @Test
  fun typedAuthorBundleLoadsAndDispatchesOnDevice() = runBlocking {
    val source = AndroidAssetBundleSource(assetPath = "fixtures/quickjs/typed.bundle.js")
    val host = QuickJsToolHost.connect(bundleJs = source.read(), bundleFilename = source.filename)
    hosts.add(host)

    val toolNames = host.listTools().map { it.name }.toSet()
    assertTrue("expected sampleApp_uppercase in $toolNames", "sampleApp_uppercase" in toolNames)
    assertTrue(
      "expected sampleApp_jsonStringify in $toolNames",
      "sampleApp_jsonStringify" in toolNames,
    )

    val upper = host.callTool("sampleApp_uppercase", buildJsonObject { put("text", "android") })
    assertEquals("ANDROID", upper.firstTextContent())
  }

  /**
   * Launcher path — registers the advertised tools from both bundles into a real
   * `TrailblazeToolRepo` through the same code path `AndroidTrailblazeRule.runSuspend` will use for
   * `quickjsToolBundles`. Asserts all four sample tools end up in the repo and that a teardown
   * round-trips cleanly (no leaked dynamic registrations).
   */
  @Test
  fun launcherRegistersAllFourSampleToolsAndShutdownClearsTheRepo() = runBlocking {
    val toolRepo = TrailblazeToolRepo.withDynamicToolSets()
    val deviceInfo =
      TrailblazeDeviceInfo(
        trailblazeDeviceId =
          TrailblazeDeviceId(
            instanceId = "quickjs-test-device",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
        trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    val sessionId = SessionId("quickjs-on-device-test-${System.currentTimeMillis()}")

    val bundles =
      listOf(
        McpServerConfig(script = "config/quickjs-tools/pure.js"),
        McpServerConfig(script = "fixtures/quickjs/typed.bundle.js"),
      )
    val runtime =
      QuickJsToolBundleLauncher.launchAll(
        bundles = bundles,
        deviceInfo = deviceInfo,
        sessionId = sessionId,
        toolRepo = toolRepo,
        // Resolver maps `script:` paths onto Android assets — same shape the production
        // `AndroidTrailblazeRule.quickjsBundleSourceResolver` uses.
        bundleSourceResolver = { entry ->
          AndroidAssetBundleSource(assetPath = requireNotNull(entry.script))
        },
      )
    launchedRuntime = runtime

    val registered = toolRepo.getRegisteredDynamicTools().keys.map { it.toolName }.toSet()
    assertEquals(
      "expected all four sample tools registered, got $registered",
      setOf(
        "sampleApp_reverseString",
        "sampleApp_addNumbers",
        "sampleApp_uppercase",
        "sampleApp_jsonStringify",
      ),
      registered,
    )

    // Shutdown must remove the dynamic tools so a follow-up session starts with a clean repo.
    runtime.shutdownAll()
    launchedRuntime = null
    assertTrue(
      "expected no dynamic tools after shutdown, got ${toolRepo.getRegisteredDynamicTools().keys}",
      toolRepo.getRegisteredDynamicTools().isEmpty(),
    )
  }

  /**
   * Pins the path-traversal validation. The asset path originates in target YAML config
   * (consumer-controlled), so a `..`-bearing path must fail at `AndroidAssetBundleSource`
   * construction with a clear error rather than relying on `AssetManager.open` to refuse traversal
   * at I/O time.
   */
  @Test
  fun assetPathContainingDotDotIsRejectedAtConstruction() {
    val attempts =
      listOf("../secret.js", "./../secret.js", "fixtures/quickjs/../typed.bundle.js", "..")
    attempts.forEach { badPath ->
      val err = runCatching { AndroidAssetBundleSource(assetPath = badPath) }.exceptionOrNull()
      assertNotNull("expected '$badPath' to be rejected", err)
      assertTrue(
        "expected the rejection message to name the bad path, got: ${err!!.message}",
        err.message.orEmpty().contains(badPath) || err.message.orEmpty().contains(".."),
      )
    }
  }

  private fun JsonObject.firstTextContent(): String {
    val content = this["content"] as JsonArray
    val first = content.first().jsonObject
    return (first["text"] as JsonPrimitive).content
  }
}
