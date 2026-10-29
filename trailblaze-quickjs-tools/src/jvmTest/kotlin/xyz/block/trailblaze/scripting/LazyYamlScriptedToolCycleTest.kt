package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A cycle across two scripted tools (A calls B, B calls A back) lands on A's engine while A's outer
 * call still holds it. Each tool has its own engine, so neither the same-bundle guard nor the
 * engine's same-thread tripwire sees it. It must fail with a cycle error marked as a call that
 * never ran, never hang.
 */
class LazyYamlScriptedToolCycleTest {

  private val sessionId = SessionId.sanitized("scripted-tool-cycle-test")

  @Test
  fun `a cycle across two scripted tools fails fast instead of hanging`() = runBlocking {
    // A calls B. B calls A back and reports the envelope it got, dispatchFailed mark included.
    val bundleA = composerBundle(name = "cycleA", calls = "cycleB")
    val bundleB = composerBundle(name = "cycleB", calls = "cycleA")
    val dir = Files.createTempDirectory("scripted-tool-cycle-test").toFile()
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val registrations = listOf("cycleA" to bundleA, "cycleB" to bundleB).map { (name, js) ->
      LazyYamlScriptedToolRegistration.create(
        toolConfig = InlineScriptToolConfig(script = "$name.ts", name = name),
        bundlePath = dir.resolve("$name.bundle.js").apply { writeText(js) },
        toolRepo = repo,
        sessionId = sessionId,
      )
    }
    try {
      repo.addDynamicTools(registrations)

      // A daemon thread with a hard deadline: a deadlocked cycle blocks engine threads, which
      // coroutine cancellation cannot interrupt, so `withTimeout` would hang here instead of failing.
      val executor = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
      val result = try {
        executor.submit<TrailblazeToolResult> {
          runBlocking {
            (registrations.first().decodeToolCall("{}") as ExecutableTrailblazeTool).execute(buildContext())
          }
        }.get(30, TimeUnit.SECONDS)
      } finally {
        executor.shutdownNow()
      }

      assertIs<TrailblazeToolResult.Error>(result)
      assertTrue(
        result.errorMessage.contains("already running further up this call path"),
        "expected the cycle error; got: ${result.errorMessage}",
      )
      assertTrue(
        result.errorMessage.contains("dispatchFailed=true"),
        "expected the cycle to be marked as a call that never ran; got: ${result.errorMessage}",
      )
    } finally {
      // Disposing waits for each engine, so after a deadlock it would hang too; bound it.
      Thread { runBlocking { registrations.forEach { runCatching { it.dispose() } } } }
        .apply { isDaemon = true }
        .apply { start() }
        .join(5_000)
      dir.deleteRecursively()
    }
  }

  /** A tool that calls [calls] and fails with the envelope it got back when that call fails. */
  private fun composerBundle(name: String, calls: String) = """
    const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
    tools["$name"] = {
      name: "$name",
      spec: {},
      handler: async () => {
        const env = JSON.parse(await globalThis.__trailblazeCall("$calls", "{}"));
        if (env.isError) {
          const text = (env.error || env.errorMessage || "") + " dispatchFailed=" + (env.dispatchFailed === true);
          return { isError: true, content: [{ type: "text", text }] };
        }
        return { content: [{ type: "text", text: "$name-ran" }] };
      },
    };
  """.trimIndent()

  private fun buildContext() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = TraceId.generate(TraceOrigin.TOOL),
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "scripted-tool-cycle-test",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
      widthPixels = 1080,
      heightPixels = 1920,
      classifiers = listOf<TrailblazeDeviceClassifier>(),
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )
}
