package xyz.block.trailblaze.quickjs.tools

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * On-device scripted-tool SMOKE GATE — the middle tier between `bun test` (SDK mock) and a full
 * device run.
 *
 * ### The gap this closes
 * A scripted tool that called `ctx.memory.interpolate(...)` passed `bun test`, `tsc`, and every
 * module unit suite, then crashed at replay on the real on-device QuickJS runtime with
 * `TypeError: ... is not a function` — because on-device, `ctx.memory` arrives as a raw
 * `Record<string,string>` snapshot with no methods, whereas the SDK's test mock hands out a fully
 * working memory. The mock was more capable than reality, and no PR gate ever ran the *actual
 * bundle* on the *real engine*. Only a slow on-demand device build caught it.
 *
 * ### What this test does that the sibling suites don't
 * `QuickJsTrailblazeToolTest` / `QuickJsToolBundleLauncherTest` load **hand-written inline JS**
 * bundles — they register a handler straight onto `globalThis.__trailblazeTools` and therefore
 * never exercise the SDK's `defineTypedTool` context wrapping, which is exactly where the memory
 * surface is (or isn't) reconstituted on-device. This test instead loads a **real
 * `trailblaze.tool()`-authored `.ts`** bundled the production way (esbuild against the SLIM
 * `@trailblaze/scripting` in-process profile + the synthesized wrapper template — same flags as
 * the daemon's `DaemonScriptedToolBundler` and the on-device `BundleAuthorToolsTask`), then
 * dispatches it through [QuickJsTrailblazeTool.execute] with a production-shaped context
 * (`buildCtxEnvelope` → raw memory record → SDK re-wrap). If the on-device memory surface ever
 * regresses to a bare record, the probe's first line throws and `execute` maps it to
 * [TrailblazeToolResult.Error] — this test goes red with the reproduction in hand, on the JVM, in
 * seconds, no emulator.
 *
 * As of the SDK's `defineTypedTool` `createMemory` re-wrap (`tool-core.ts`), the on-device memory
 * surface IS reconstituted, so this test PASSES today — green is the healthy state, and a red here
 * means the wrap (or the memory plumbing through `buildCtxEnvelope`) regressed.
 *
 * The bundle is produced by `:trailblaze-quickjs-tools:bundleOnDeviceMemoryProbeAuthorTool` and its
 * path injected via the `trailblaze.test.onDeviceMemoryProbeBundle` system property (see this
 * module's `build.gradle.kts`); the bundle task depends on the SDK-install task, so esbuild is
 * always present when the gate runs under Gradle. `assumeTrue` skips only when the test is invoked
 * outside Gradle (e.g. a bare IDE run) where the property isn't set.
 */
class QuickJsOnDeviceMemorySmokeTest {

  private val hosts = mutableListOf<QuickJsToolHost>()

  @AfterTest
  fun teardown() {
    runBlocking { hosts.forEach { runCatching { it.shutdown() } } }
    hosts.clear()
  }

  @Test
  fun `real slim-SDK bundle can use the full ctx memory surface on the QuickJS engine`() = runBlocking {
    val bundleFile = requireProbeBundle()

    val host = QuickJsToolHost.connect(bundleFile.readText(), bundleFilename = "on-device-memory-probe.bundle.js")
      .also { hosts.add(it) }

    // Sanity: the real bundle registered the fixture's exported tool.
    val names = host.listTools().map { it.name }.toSet()
    assertTrue("expected smoke_memoryProbe registered, got $names") { "smoke_memoryProbe" in names }

    // Production-shaped memory: a non-sensitive var the probe resolves via interpolate/get/has/keys,
    // plus a key the probe deletes so the write-flush assertions below exercise both set and delete.
    val memory = AgentMemory().apply {
      remember("firstName", "Ada")
      remember("smoke_probe_seeded_to_delete", "bye")
    }
    // The ONLY arg is the plain key. The fixture builds the `{{firstName}}`/`${firstName}` tokens
    // itself, inside QuickJS — deliberately NOT passed as an arg. `execute` pre-interpolates recorded
    // args against memory before the bundle runs, so a token arg would arrive already resolved and a
    // regressed `interpolate` that just returns its input would still pass. Building the token
    // in-handler keeps the on-device `ctx.memory.interpolate` the only thing that can resolve it.
    val tool = QuickJsTrailblazeTool(
      host,
      ToolName("smoke_memoryProbe"),
      buildJsonObject { put("lookupKey", "firstName") },
    )

    val result = tool.execute(buildContext(memory = memory))

    // The load-bearing assertion: the handler completed instead of throwing
    // `TypeError: ctx.memory.interpolate is not a function`. A regression of the on-device memory
    // wrap surfaces here as an Error carrying the JS stack.
    assertTrue("expected Success — a memory-surface regression maps to Error here. Got: $result") {
      result is TrailblazeToolResult.Success
    }
    val message = (result as TrailblazeToolResult.Success).message.orEmpty()

    // ...and it returned the RIGHT answers. This catches a memory surface that exists but silently
    // no-ops (e.g. interpolate returning the template unresolved, or get always undefined) — a
    // failure mode a "did it throw?" check alone would sail past.
    assertTrue("interpolate should resolve {{firstName}} and \${firstName}; got: $message") {
      message.contains("interpolated=Hi Ada / Ada")
    }
    assertTrue("get(firstName) should return the remembered value; got: $message") {
      message.contains("got=Ada")
    }
    assertTrue("has(firstName) should be true; got: $message") { message.contains("has=true") }
    assertTrue("keys() should include firstName; got: $message") { message.contains("keys=firstName") }

    // Write-flush contract — the regression this fix closes. A `ctx.memory.set(...)` on the
    // on-device QuickJS path buffers on the JS side and must be flushed back into the host
    // AgentMemory (via `_meta.trailblaze.memoryDelta`, applied by `QuickJsTrailblazeTool.execute`)
    // so the NEXT tool's `ctx.memory.get(...)` sees it. Before the fix this write was silently
    // dropped — the exact failure behind the write-then-read hand-off between two scripted tools.
    assertTrue(
      "ctx.memory.set should flush to host memory; smoke_probe_wrote=${memory.variables["smoke_probe_wrote"]}",
    ) {
      memory.variables["smoke_probe_wrote"] == "Ada"
    }
    // ...and a `ctx.memory.delete(...)` of a host-seeded key must flush the deletion too.
    assertTrue("ctx.memory.delete should flush to host memory") {
      !memory.has("smoke_probe_seeded_to_delete")
    }
  }

  /**
   * Resolves the fixture bundle produced by the Gradle bundling task. Never returns null: it either
   * skips (via [assumeTrue], which throws) when the property is absent, or throws on a missing file,
   * or returns the bundle.
   *
   * Two intentionally-different behaviors for the two "no bundle" cases:
   *  - **Property absent** → [assumeTrue] SKIP. This only happens when the test is run outside Gradle
   *    (a bare IDE run), where the system property was never injected. Skipping is right — there's
   *    nothing to test and nothing is broken.
   *  - **Property set but file missing** → hard [assertTrue] FAIL, deliberately NOT a skip. Under
   *    Gradle the property is always set and the jvmTest task depends on the bundle task (which
   *    depends on the SDK install), so a missing file here means the bundling task silently failed —
   *    exactly the kind of build breakage a gate must surface loudly, not paper over. This diverges
   *    on purpose from `SampleAppToolsDemoTest`, which skips this case too; fail-loud is the correct
   *    behavior for a regression gate (don't "fix" this to match the weaker sibling).
   */
  private fun requireProbeBundle(): File {
    val path = System.getProperty("trailblaze.test.onDeviceMemoryProbeBundle")
    assumeTrue(
      "trailblaze.test.onDeviceMemoryProbeBundle not set — run via Gradle " +
        "(`./gradlew :trailblaze-quickjs-tools:jvmTest`) so the bundling plugin produces the fixture " +
        "before the test runs.",
      path != null,
    )
    val file = File(path!!)
    assertTrue(
      "fixture bundle missing at ${file.absolutePath} — bundleOnDeviceMemoryProbeAuthorTool should " +
        "have produced it.",
    ) { file.isFile }
    return file
  }

  private fun buildContext(
    memory: AgentMemory = AgentMemory(),
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "quickjs-on-device-memory-smoke",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("quickjs-on-device-memory-smoke"), startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = memory,
  )
}
