package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

/**
 * A host-path scripted tool that composes ITSELF by name (a nested `{ block_runIf: {...} }` entry
 * is exactly this) lands back on its own registration's QuickJS engine while the outer call still
 * holds it. One engine per registration keeps composition across different tools safe, but not
 * self-composition. It must fail with the binding's actionable same-bundle error — naming the fix —
 * not the engine's internal reentrancy tripwire, and never hang.
 */
class LazyYamlScriptedToolSelfCompositionTest {

  private val sessionId = SessionId.sanitized("self-composition-test")

  @Test
  fun `a scripted tool that composes itself fails with the same-bundle error`() = runBlocking {
    val bundleJs = """
      const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
      tools["selfCompose"] = {
        name: "selfCompose",
        spec: {},
        handler: async (args) => {
          if (args.nested) return { content: [{ type: "text", text: "inner-ran" }] };
          const env = JSON.parse(await globalThis.__trailblazeCall("selfCompose", JSON.stringify({ nested: true })));
          if (env.isError) return { isError: true, content: [{ type: "text", text: env.error }] };
          return { content: [{ type: "text", text: "outer-ran" }] };
        },
      };
    """.trimIndent()
    val dir = Files.createTempDirectory("self-composition-test").toFile()
    val repo = TrailblazeToolRepo.withDynamicToolSets()
    val registration = LazyYamlScriptedToolRegistration.create(
      toolConfig = InlineScriptToolConfig(script = "selfCompose.ts", name = "selfCompose"),
      bundlePath = dir.resolve("selfCompose.bundle.js").apply { writeText(bundleJs) },
      toolRepo = repo,
      sessionId = sessionId,
    )
    try {
      repo.addDynamicTools(listOf(registration))

      val result = withTimeout(30_000) {
        (registration.decodeToolCall("{}") as ExecutableTrailblazeTool).execute(buildContext())
      }

      assertIs<TrailblazeToolResult.Error>(result)
      assertTrue(
        result.errorMessage.contains("composing a tool from the same bundle is not supported"),
        "expected the actionable same-bundle error; got: ${result.errorMessage}",
      )
    } finally {
      runCatching { registration.dispose() }
      dir.deleteRecursively()
    }
  }

  private fun buildContext() = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = TraceId.generate(TraceOrigin.TOOL),
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "self-composition-test",
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
