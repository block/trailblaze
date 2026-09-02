package xyz.block.trailblaze.android

import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.quickjs.tools.QuickJsToolHost
import xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.SleepTrailblazeTool

/**
 * Proves a scripted tool can **wait on a device** by composing the framework's `sleep` tool.
 *
 * QuickJS has no `setTimeout`, so a `.ts` tool cannot sleep in JS. Waiting is what a polling
 * assertion needs (watch a growing file, retry until an expected thing shows up), and whether that
 * is expressible in TypeScript decides whether such tools can be written in TypeScript at all or
 * have to be compiled into the binary as Kotlin. The answer turns on `sleep` being reachable and
 * genuinely blocking **inside the instrumentation process**, which no JVM test can establish:
 *
 * 1. **Resolution is asset-backed.** `sleep` reaches a scripted caller through
 *    `toolCallToTrailblazeToolUnfiltered`'s global-registry tier, which is built by discovering
 *    `*.tool.yaml` — on Android from the `AssetManager`, not the classpath. If `sleep.tool.yaml`
 *    stops being merged into the APK's assets, resolution returns null and every composed wait
 *    degrades to an error envelope the JS side is free to ignore.
 * 2. **`HostLocalExecutableTrailblazeTool` means in-process, not on-host.** The name reads like
 *    "host machine only", and that misreading is on record as having driven a wait loop into
 *    Kotlin unnecessarily. The refusal it suggests belongs to a host-side RPC branch that
 *    host-local tools fork away from before reaching. On-device the tool simply runs here.
 *
 * The first two tests assert the two layers separately — direct resolution/execution, then the
 * full QuickJS dispatch — so a failure says which one broke. The third is the power check: it
 * proves an unresolvable name really does come back as an error envelope, without which the
 * second test's "no error" assertion would pass for a binding that never resolved anything.
 *
 * Durations are asserted as lower bounds only. A loaded emulator can always take longer; the
 * defect worth catching is a wait that returns *early* (or not at all).
 */
class OnDeviceSleepBindingTest {

  private val sessionId = SessionId("on-device-sleep-binding-test")

  private val hosts = mutableListOf<QuickJsToolHost>()

  @After
  fun teardown() = runBlocking { hosts.forEach { runCatching { it.shutdown() } } }

  @Test
  fun theSleepToolResolvesFromTheApkAndBlocksInTheInstrumentationProcess() = runBlocking {
    // Deliberately an EMPTY session toolset: resolution can only come from the global registry
    // built out of the APK's assets, which is the tier a scripted `ctx.tools.sleep` uses.
    val resolved = newRepo().toolCallToTrailblazeToolUnfiltered("sleep", """{"durationMs":$SLEEP_MS}""")

    assertEquals(
      "a scripted caller reaches `sleep` only through the global registry, which on Android is " +
        "built from *.tool.yaml in the APK's assets. Resolving to anything else means " +
        "sleep.tool.yaml is not being merged into this APK, and every TypeScript tool that " +
        "waits would silently get an error envelope instead of a wait.",
      SleepTrailblazeTool(durationMs = SLEEP_MS),
      resolved,
    )

    val mark = TimeSource.Monotonic.markNow()
    val result = (resolved as SleepTrailblazeTool).execute(executionContext())
    val elapsedMs = mark.elapsedNow().inWholeMilliseconds

    assertTrue(
      "sleep must run in-process on-device, not be refused as host-only. Got: $result",
      result is TrailblazeToolResult.Success,
    )
    assertTrue(
      "sleep must consume the full duration in the instrumentation process; waited ${elapsedMs}ms " +
        "of a requested ${SLEEP_MS}ms",
      elapsedMs >= SLEEP_MS,
    )
  }

  @Test
  fun aBundledToolWaitsOnDeviceByComposingSleepThroughTheHostBinding() = runBlocking {
    val binding = SessionScopedHostBinding(newRepo(), sessionId)
    val host = connect(binding)

    val result = host.callTool("sleepProbe", buildJsonObject { put("durationMs", SLEEP_MS) })

    // `error` is set by the probe itself from the envelope the binding returned, so an
    // unresolved or refused `sleep` surfaces here as a message rather than as a fast pass.
    assertNull(
      "composing `sleep` from a bundle must dispatch, not return an error envelope",
      result.stringField("error"),
    )
    val elapsedMs = requireNotNull(result.stringField("elapsedMs")).toLong()
    assertTrue(
      "the bundle's own clock must show the full wait — a dispatch that returns immediately " +
        "means the tool never executed. Waited ${elapsedMs}ms of a requested ${SLEEP_MS}ms",
      elapsedMs >= SLEEP_MS,
    )
  }

  @Test
  fun anUnresolvableToolNameComesBackAsAnErrorEnvelopeToTheBundle() = runBlocking {
    // Without this, the test above would pass just as happily against a binding that resolved
    // nothing and reported nothing — its `error == null` assertion has to be able to fail.
    val binding = SessionScopedHostBinding(newRepo(), sessionId)
    val host = connect(binding)

    val result = host.callTool("callByName", buildJsonObject { put("toolName", "not_a_registered_tool") })

    val error = result.stringField("error")
    assertTrue(
      "an unknown tool name must reach the bundle as an error envelope naming the tool; got $error",
      error != null && error.contains("not_a_registered_tool"),
    )
  }

  private suspend fun connect(binding: SessionScopedHostBinding): QuickJsToolHost =
    QuickJsToolHost.connect(
      bundleJs = SLEEP_PROBE_BUNDLE,
      bundleFilename = "on-device-sleep-probe.js",
      hostBinding = binding,
    ).also {
      hosts.add(it)
      // The path the in-process scripted-tool dispatch uses. Set on the binding rather than via
      // the thread-local so the engine thread sees it regardless of dispatcher hops.
      binding.activeContext = executionContext()
    }

  /** Empty session toolset on purpose — see the resolution test. */
  private fun newRepo() = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "on-device-sleep-binding-test",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
    ),
  )

  /** No driver and no session dir, exactly as the on-device agent builds it for a host-local tool. */
  private fun executionContext() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "on-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  /** Unwraps the tool envelope's first text block, which the probe fills with its own JSON. */
  private fun JsonObject.stringField(name: String): String? {
    val content = this["content"] as? JsonArray ?: return null
    val text = (content.firstOrNull()?.jsonObject?.get("text") as? JsonPrimitive)?.content ?: return null
    return (Json.parseToJsonElement(text).jsonObject[name] as? JsonPrimitive)?.content
  }

  companion object {
    /** Comfortably above `sleep`'s 100ms floor and short enough not to drag the suite. */
    private const val SLEEP_MS = 400L

    /**
     * Stands in for the compiled output of a `.ts` tool: it reaches the framework through the same
     * `__trailblazeCall(name, argsJson)` binding the generated wrapper uses, and times the call
     * with the only clock QuickJS has.
     */
    private val SLEEP_PROBE_BUNDLE =
      """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      const report = (obj) => ({ content: [{ type: "text", text: JSON.stringify(obj) }] });
      const callHost = (name, args) => JSON.parse(__trailblazeCall(name, JSON.stringify(args)));

      tools["sleepProbe"] = {
        name: "sleepProbe",
        spec: {},
        handler: async (args) => {
          const startedAt = Date.now();
          const envelope = callHost("sleep", { durationMs: args.durationMs });
          if (envelope && envelope.isError) return report({ error: String(envelope.error) });
          return report({ elapsedMs: String(Date.now() - startedAt) });
        },
      };

      tools["callByName"] = {
        name: "callByName",
        spec: {},
        handler: async (args) => {
          const envelope = callHost(args.toolName, {});
          return report(envelope && envelope.isError ? { error: String(envelope.error) } : {});
        },
      };
      """.trimIndent()
  }
}
