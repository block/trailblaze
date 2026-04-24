package xyz.block.trailblaze.examples.sampleapp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.bundle.AndroidAssetBundleJsSource
import xyz.block.trailblaze.scripting.bundle.McpBundleSession
import xyz.block.trailblaze.scripting.bundle.fetchAndFilterTools

/**
 * Device-side counterpart to the JVM `InProcessBundleRoundTripTest`. The JVM test proves the Kotlin
 * plumbing (transport serialisation, bridge, handshake ordering) on a desktop JDK. This test runs
 * the same fixture bundle through QuickJS on a real Android device, so it additionally covers:
 * - `quickjs-kt`'s native library loading under the Android instrumentation runtime.
 * - The Android-side evaluate → async-binding → continuation flow under the instrumentation's
 *   coroutine dispatcher.
 * - **The [AndroidAssetBundleJsSource] asset-path resolver** — code review feedback flagged
 *   that earlier revisions of this test used `FromString` and so left the asset loader unverified
 *   on-device. Loading from an actual asset here means the CI device-farm shard also exercises the
 *   path production `AndroidTrailblazeRule.mcpServers` consumers hit when their target YAML's
 *   `script:` entry resolves to an APK-bundled `.js`.
 * - Sanity on the minSdk 26 surface that the bundle module targets.
 *
 * The fixture JS ships at `src/androidTest/assets/fixtures/bundle-roundtrip-fixture.js` — AGP
 * bundles `androidTest/assets/` into the test APK's `assets/` tree, where the instrumentation
 * context's AssetManager opens it via the path we pass to `AndroidAssetBundleJsSource`.
 *
 * This class + its test methods ship in `android-sample-app-uitests-debug-androidTest.apk`, which
 * CI hands to a cloud device farm on every PR. A regression in QuickJS loading, the transport
 * bridge, the requiresHost filter, or asset-path resolution will fail that required check and block
 * merge.
 *
 * If this test starts failing after a bump of `quickjs-kt`, check that the native .so for the
 * device's ABI is still published by the artifact; that's historically been the first regression
 * axis.
 */
class OnDeviceBundleRoundTripTest {

  /**
   * Loads the fixture bundle from the test APK's `assets/fixtures/bundle-roundtrip-fixture.js`.
   * `AndroidAssetBundleJsSource`'s no-arg form uses `InstrumentationRegistry`'s context, which
   * resolves to the androidTest APK (where our fixture ships), not the target-app APK. Production
   * `AndroidTrailblazeRule.mcpServers` callers hit the same resolver when their target YAML's
   * `script:` path references an asset.
   */
  private fun bundleSource(): AndroidAssetBundleJsSource =
    AndroidAssetBundleJsSource(assetPath = "fixtures/bundle-roundtrip-fixture.js")

  @Test
  fun quickjsLoadsOnDeviceAndBundleAdvertisesItsTools() = runBlocking {
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val advertised = session.client.listTools(ListToolsRequest()).tools.map { it.name }
      assertTrue("echoReverse must advertise: $advertised", "echoReverse" in advertised)
      assertTrue("hostOnlyTool must advertise: $advertised", "hostOnlyTool" in advertised)
    } finally {
      session.shutdown()
    }
  }

  @Test
  fun onDeviceRegistrationDropsRequiresHostTools() = runBlocking {
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val registered =
        session
          .fetchAndFilterTools(driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY)
          .map { it.advertisedName.toolName }
      // The requiresHost filter bites on-device — on-device passes preferHostAgent=false,
      // so the explicitly-host-only tool drops at registration and the LLM never sees it.
      assertTrue("echoReverse must register: $registered", "echoReverse" in registered)
      assertFalse("hostOnlyTool must be filtered out: $registered", "hostOnlyTool" in registered)
    } finally {
      session.shutdown()
    }
  }

  @Test
  fun roundTripToolsCallWorksThroughTheInProcessTransport() = runBlocking {
    val session = McpBundleSession.connect(bundleSource = bundleSource())
    try {
      val response =
        session.client.callTool(
          CallToolRequest(
            params =
              CallToolRequestParams(
                name = "echoReverse",
                arguments = buildJsonObject { put("text", "on-device") },
              )
          )
        )
      val textContent = response.content.firstOrNull() as? TextContent
      assertNotNull("expected TextContent, got $response", textContent)
      assertEquals("ecived-no", textContent!!.text)
    } finally {
      session.shutdown()
    }
  }
}
