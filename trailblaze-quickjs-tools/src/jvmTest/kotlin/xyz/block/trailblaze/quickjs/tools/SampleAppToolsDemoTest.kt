package xyz.block.trailblaze.quickjs.tools

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/**
 * End-to-end demo proving the three sample-app tool flavors compose correctly with the new
 * QuickJS-tool architecture:
 *
 *  1. **Pure JS** — `examples/android-sample-app/trails/config/quickjs-tools/pure.js`. No
 *     TypeScript, no SDK imports, no build step. Loaded into QuickJS as-is; populates
 *     `globalThis.__trailblazeTools` directly via plain JS.
 *  2. **On-device-compatible TS** — `examples/android-sample-app/trails/config/quickjs-tools/typed.ts`.
 *     Imports `@trailblaze/tools` (the tiny non-MCP SDK). Bundled by the
 *     `trailblaze.author-tool-bundle` Gradle plugin; the produced `.bundle.js` is read via
 *     the `trailblaze.test.sampleAppTypedBundle` system property and evaluated in QuickJS.
 *  3. **Host-only TS** — `examples/android-sample-app/trails/config/host-tools/tools.ts`. Uses
 *     `node:fs`, so it can't run in QuickJS. Documented as a source file; the future
 *     integration PR will exercise it via the existing subprocess infrastructure.
 *
 * The bundle test depends on the plugin task running first via Gradle; the
 * `trailblaze.test.sampleAppTypedBundle` system property is set in this module's
 * `build.gradle.kts` so the test reads the plugin's output rather than running esbuild
 * inline. Skip-with-`assumeTrue` if the property isn't set (e.g., test invoked outside Gradle).
 */
class SampleAppToolsDemoTest {

  // Resolve sample-app paths against whatever directory contains `sdks/typescript-tools/package.json`
  // directly. That marker lives at the repo root in the open-source release layout, and at
  // the framework-source ancestor in the internal-monorepo layout — same source code works
  // in either, no per-layout literal needed.
  private val frameworkRoot: File = locateFrameworkRoot()
  private val sampleAppQuickJsToolsDir: File get() =
    File(frameworkRoot, "examples/android-sample-app/trails/config/quickjs-tools")
  private val sampleAppHostToolsDir: File get() =
    File(frameworkRoot, "examples/android-sample-app/trails/config/host-tools")

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() = runBlocking {
    hosts.forEach { runCatching { it.shutdown() } }
    hosts.clear()
  }

  // ---------- Flavor 1: Pure JS (no build step) ----------

  @Test
  fun `pure JS file loads as-is and registers tools`() = runBlocking {
    val pureJs = File(sampleAppQuickJsToolsDir, "pure.js")
    assumeTrue("sample-app pure.js missing at ${pureJs.absolutePath}", pureJs.isFile)

    val host = QuickJsToolHost.connect(pureJs.readText(), bundleFilename = "pure.js")
      .also { hosts.add(it) }

    val registered = host.listTools().map { it.name }.toSet()
    assertTrue("expected sampleApp_reverseString in $registered") {
      "sampleApp_reverseString" in registered
    }
    assertTrue("expected sampleApp_addNumbers in $registered") {
      "sampleApp_addNumbers" in registered
    }

    val reversed = host.callTool("sampleApp_reverseString", buildJsonObject { put("text", "hello") })
    assertEquals(
      "olleh",
      ((reversed["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content,
    )

    val sum = host.callTool(
      "sampleApp_addNumbers",
      buildJsonObject {
        put("a", 2)
        put("b", 40)
      },
    )
    assertEquals(
      "42",
      ((sum["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content,
    )
  }

  // ---------- Flavor 2: On-device TS bundled via esbuild ----------

  @Test
  fun `on-device TS bundle from the trailblaze author-tool-bundle plugin runs in QuickJS`() = runBlocking {
    // The bundle is produced by `:trailblaze-quickjs-tools:bundleSampleAppTypedAuthorTool`
    // (the new `trailblaze.author-tool-bundle` Gradle plugin) and its absolute path is
    // injected via the `trailblaze.test.sampleAppTypedBundle` system property. This test
    // is the load-bearing proof that the plugin's output evaluates cleanly in the runtime —
    // a regression in either the plugin's flag set or the runtime's evaluator surfaces here.
    val bundlePathProperty = System.getProperty("trailblaze.test.sampleAppTypedBundle")
    assumeTrue(
      "trailblaze.test.sampleAppTypedBundle system property not set — run via Gradle " +
        "(`./gradlew :trailblaze-quickjs-tools:jvmTest`) so the bundling plugin produces the " +
        "input file before the test runs.",
      bundlePathProperty != null,
    )
    val bundleFile = File(bundlePathProperty!!)
    assumeTrue(
      "bundle file missing at ${bundleFile.absolutePath} — task " +
        "`bundleSampleAppTypedAuthorTool` should have produced it.",
      bundleFile.isFile,
    )

    val bundleJs = bundleFile.readText()
    // Negative size assertion — the new SDK is ~50 lines; if the bundle is huge, the alias
    // didn't bind and something else got inlined. 8 KB is generous headroom for the tool
    // definitions plus the inlined SDK source.
    assertTrue("bundle suspiciously large (${bundleJs.length} bytes) — alias may not have bound") {
      bundleJs.length < 8_000
    }

    val host =
      QuickJsToolHost.connect(bundleJs, bundleFilename = "typed.bundle.js").also { hosts.add(it) }

    val names = host.listTools().map { it.name }.toSet()
    // Anchor the contract that the plugin produced something *useful*, not just a non-empty
    // file. A bundle that's syntactically valid JS but registers zero tools — e.g. a refactor
    // that drops the `@trailblaze/tools` alias and tree-shakes the registrations away — would
    // still pass the size check above but fail this assertion with a clear "no tools registered"
    // message instead of the per-tool `Tool not registered` surprises that follow.
    assertTrue("plugin output should register at least one tool, got $names") { names.isNotEmpty() }
    assertTrue("expected sampleApp_uppercase in $names") { "sampleApp_uppercase" in names }
    assertTrue("expected sampleApp_jsonStringify in $names") { "sampleApp_jsonStringify" in names }

    val upper = host.callTool("sampleApp_uppercase", buildJsonObject { put("text", "hello") })
    assertEquals(
      "HELLO",
      ((upper["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content,
    )

    val pretty = host.callTool(
      "sampleApp_jsonStringify",
      buildJsonObject {
        put(
          "value",
          buildJsonObject {
            put("a", 1)
            put("b", "two")
          },
        )
      },
    )
    val prettyText =
      ((pretty["content"] as JsonArray).first().jsonObject["text"] as JsonPrimitive).content
    // The runtime's `JSON.stringify(value, null, 2)` emits 2-space indented JSON.
    assertTrue("expected 2-space indent in: $prettyText") { prettyText.contains("\n  \"a\": 1") }
  }

  // ---------- Flavor 3: Host-only TS source exists and is well-formed ----------

  @Test
  fun `host-only TS source uses the same @trailblaze tools SDK and is host-only because of node imports`() {
    // The unified-SDK move: host-only tools use the SAME `@trailblaze/tools` import as
    // on-device-compatible ones. What makes a tool host-only is reaching for `node:*`
    // modules inside the handler body, not picking a different SDK package. This test
    // pins that invariant — a regression that re-introduces a separate "host SDK" import
    // would fire here.
    val toolsTs = File(sampleAppHostToolsDir, "tools.ts")
    assumeTrue("sample-app host-tools/tools.ts missing at ${toolsTs.absolutePath}", toolsTs.isFile)
    val src = toolsTs.readText()
    assertTrue("host-only sample should import @trailblaze/tools (single SDK)") {
      src.contains("from \"@trailblaze/tools\"")
    }
    assertTrue("host-only sample should NOT import any other Trailblaze SDK package") {
      !src.contains("from \"@trailblaze/scripting\"")
    }
    assertTrue("host-only sample should import a `node:` module to justify host-only-ness") {
      src.contains("from \"node:")
    }
  }

  // ---------- Helpers ----------

  /**
   * Walk up from the test's working directory until we find a directory whose
   * `sdks/typescript-tools/package.json` exists. That directory is the framework-source
   * root. Works in any layout that places the SDK packages under `sdks/` at the framework
   * root; no layout-specific path prefix appears in this source code.
   */
  private fun locateFrameworkRoot(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
      if (File(dir, "sdks/typescript-tools/package.json").isFile) return dir
      dir = dir.parentFile
    }
    error(
      "could not locate framework root — no `sdks/typescript-tools/package.json` in any " +
        "ancestor of ${System.getProperty("user.dir")}",
    )
  }
}
