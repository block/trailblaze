package xyz.block.trailblaze.quickjs.tools

import kotlin.test.Test
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
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
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * What a scripted (QuickJS) tool costs to dispatch, next to a Kotlin tool doing the same nothing.
 * The TypeScript tools are SDK-authored (`fixtures/dispatch-benchmark/bench.ts`) and bundled the
 * production way, so the SDK's per-call wrapper is included. Every body is empty, so each number is
 * dispatch overhead.
 *
 * Opt-in, and it asserts nothing about speed — timings belong in a devlog, not a CI gate:
 *
 *   ./gradlew :trailblaze-quickjs-tools:jvmTest --tests '*QuickJsToolDispatchBenchmark*' \
 *     -Dtrailblaze.benchmark=true -i
 */
class QuickJsToolDispatchBenchmark {

  @Serializable
  @TrailblazeToolClass("bench_noop")
  private data class NoopTool(val text: String = "") : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success(message = "ok")
  }

  @Test
  fun `scripted tool dispatch overhead`() = runBlocking {
    assumeTrue("pass -Dtrailblaze.benchmark=true to run", System.getProperty("trailblaze.benchmark") == "true")
    val sdkBundle = System.getProperty("trailblaze.test.dispatchBenchmarkBundle")?.let(::File)
    assumeTrue("run via Gradle so the SDK fixture is bundled", sdkBundle?.isFile == true)
    val sdkJs = sdkBundle!!.readText()

    val sessionId = SessionId("quickjs-dispatch-benchmark")
    val repo = TrailblazeToolRepo(
      trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "bench",
        toolClasses = setOf(NoopTool::class),
        yamlToolNames = emptySet(),
      ),
    )
    val binding = SessionScopedHostBinding(repo, sessionId)
    val ctx = context(sessionId)
    val results = linkedMapOf<String, List<Double>>()

    // The Kotlin baseline is a by-name dispatch: tool lookup, argument decode, execute, result
    // encode — what any dispatched Kotlin tool pays. A bare execute() is the floor under it.
    results["Kotlin tool, dispatched by name"] =
      time { binding.activeContext = ctx; binding.callFromBundle("bench_noop", "{}") }
    results["Kotlin tool, execute() called directly"] = time { NoopTool().execute(ctx) }

    // One engine alive per measurement, so retained engines can't skew the dispatch timings.
    suspend fun measure(bundleJs: String, rows: List<Pair<String, String>>) {
      val host = QuickJsToolHost.connect(bundleJs, hostBinding = binding)
      try {
        for ((label, name) in rows) {
          val tool = QuickJsTrailblazeTool(host, ToolName(name), buildJsonObject {}, binding)
          // A cheap error envelope would benchmark as fast as a real dispatch, so prove success first.
          val result = tool.execute(ctx)
          assertTrue("$name did not succeed: $result") { result is TrailblazeToolResult.Success }
          results[label] = time { tool.execute(ctx) }
        }
      } finally {
        host.shutdown()
      }
    }
    measure(
      sdkJs,
      listOf(
        "TypeScript tool (SDK) that returns immediately" to "bench_tsNoop",
        "TypeScript tool (SDK) calling 1 Kotlin tool" to "bench_tsCallsKotlin",
        "TypeScript tool (SDK) calling 10 Kotlin tools" to "bench_tsCallsKotlin10",
        "TypeScript tool (SDK) calling 10 TS helpers" to "bench_tsCallsHelper10",
      ),
    )
    measure(RAW_BUNDLE, listOf("Raw handler, no SDK wrapper, that returns immediately" to "tsNoop"))

    // Each scripted tool gets its own engine at session start, so this is a per-tool cost.
    val loadMs = List(CONNECTS) {
      val start = System.nanoTime()
      val host = QuickJsToolHost.connect(sdkJs, bundleFilename = sdkBundle.name, hostBinding = binding)
      val elapsed = (System.nanoTime() - start) / 1e6
      host.shutdown()
      elapsed
    }
    // A subprocess tool runtime pays this once when it starts, then an IPC round trip per call
    // (not measured here). QuickJS pays neither.
    val spawnMs = timeOnce(SPAWNS) { ProcessBuilder("/usr/bin/true").start().waitFor() }
    val nodeMs = runCatching { ProcessBuilder("node", "-e", "0").start().waitFor() }.getOrNull()?.let {
      timeOnce(NODE_SPAWNS) { ProcessBuilder("node", "-e", "0").start().waitFor() }
    }

    val report = buildString {
      appendLine("QuickJS tool dispatch (ms per call; $ITERATIONS calls after $WARMUP warm-up)")
      appendLine("%-62s %8s %8s %8s".format("", "median", "p90", "p99"))
      for ((label, samples) in results) appendLine(row(label, samples))
      appendLine(row("Load the SDK bundle (${sdkBundle.length() / 1024} KB) into a new engine, per tool", loadMs))
      appendLine(row("Spawn /usr/bin/true (once, for a subprocess runtime)", spawnMs))
      if (nodeMs != null) appendLine(row("Spawn `node -e 0` (once, for a subprocess runtime)", nodeMs))
      appendLine(
        "JVM ${System.getProperty("java.version")}, ${System.getProperty("os.name")} " +
          "${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores, " +
          "load average %.1f".format(ManagementFactory.getOperatingSystemMXBean().systemLoadAverage),
      )
    }
    println(report)
  }

  private suspend fun time(block: suspend () -> Unit): List<Double> {
    repeat(WARMUP) { block() }
    return List(ITERATIONS) {
      val start = System.nanoTime()
      block()
      (System.nanoTime() - start) / 1e6
    }
  }

  private fun timeOnce(count: Int, block: () -> Unit): List<Double> {
    repeat(10) { block() }
    return List(count) {
      val start = System.nanoTime()
      block()
      (System.nanoTime() - start) / 1e6
    }
  }

  private fun row(label: String, samples: List<Double>): String {
    val sorted = samples.sorted()
    fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
    return "%-62s %8.3f %8.3f %8.3f".format(label, at(0.5), at(0.9), at(0.99))
  }

  private fun context(sessionId: SessionId) = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "quickjs-dispatch-benchmark",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private companion object {
    const val WARMUP = 500
    const val ITERATIONS = 5_000
    const val CONNECTS = 20
    const val SPAWNS = 200
    const val NODE_SPAWNS = 50

    // A hand-registered handler with no SDK wrapper, to show what the wrapper itself costs.
    val RAW_BUNDLE = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["tsNoop"] = {
        name: "tsNoop",
        spec: {},
        handler: async () => ({ content: [{ type: "text", text: "ok" }] }),
      };
    """.trimIndent()
  }
}
