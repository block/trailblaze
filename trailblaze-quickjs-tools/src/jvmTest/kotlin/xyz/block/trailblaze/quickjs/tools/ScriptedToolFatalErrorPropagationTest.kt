package xyz.block.trailblaze.quickjs.tools

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.McpServerConfig
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
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet

/**
 * A Kotlin tool that fails fatally, called from a scripted tool through `ctx.tools`, must keep the
 * scripted tool's own failure fatal when the script lets the error propagate: a recorded step skips
 * self-heal on a fatal result, and a scripted tool that flattened it into a recoverable error
 * sent self-heal into a retry loop. A script that catches the error and carries on is unaffected.
 *
 * Drives the REAL in-process wrapper template (`sdks/typescript/tools/in-process-wrapper-template.mjs`)
 * with the import line swapped for an inline module, so the `client.tools` shim, the host binding,
 * the QuickJS catch and the result mapper are all production code.
 */
class ScriptedToolFatalErrorPropagationTest {

  @Serializable
  @TrailblazeToolClass("test_fatal")
  private data class FatalTool(val unused: String = "") : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Error.FatalError(errorMessage = "device disconnected")
  }

  @Serializable
  @TrailblazeToolClass("test_recoverable")
  private data class RecoverableTool(val unused: String = "") : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
      TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "button missing")
  }

  private val sessionId = SessionId("fatal-propagation-test")
  private val toolRepo = TrailblazeToolRepo(
    TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "fatal-propagation-test-set",
      toolClasses = setOf(FatalTool::class, RecoverableTool::class),
    ),
  )
  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "fatal-propagation-test",
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
    runBlocking { launchedRuntime?.let { runCatching { it.shutdownAll() } } }
  }

  @Test
  fun `a fatal nested failure the script does not catch fails the scripted tool fatally`() = runBlocking<Unit> {
    val result = run("propagates")
    assertIs<TrailblazeToolResult.Error.FatalError>(result)
    assertEquals(true, "device disconnected" in result.errorMessage, result.errorMessage)
  }

  @Test
  fun `a fatal nested failure the script rethrows fails the scripted tool fatally`() = runBlocking<Unit> {
    assertIs<TrailblazeToolResult.Error.FatalError>(run("rethrows"))
  }

  @Test
  fun `a fatal nested failure the script catches lets the scripted tool succeed`() = runBlocking<Unit> {
    val result = run("catches")
    assertIs<TrailblazeToolResult.Success>(result)
    assertEquals("handled", result.message)
  }

  @Test
  fun `a recoverable nested failure the script does not catch stays recoverable`() = runBlocking<Unit> {
    val result = run("recoverable")
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(result)
  }

  private suspend fun run(toolName: String): TrailblazeToolResult {
    launchedRuntime = QuickJsToolBundleLauncher.launchAll(
      bundles = listOf(McpServerConfig(script = "ignored.js")),
      deviceInfo = deviceInfo,
      sessionId = sessionId,
      toolRepo = toolRepo,
      bundleSourceResolver = { InlineBundleSource(renderWrapper()) },
    )
    val tool = toolRepo.toolCallToTrailblazeToolUnfiltered(toolName, "{}")
    assertNotNull(tool, "expected the launcher to register '$toolName'")
    return (tool as QuickJsTrailblazeTool).execute(buildContext())
  }

  private fun renderWrapper(): String {
    val template = File(locateFrameworkRoot(), "sdks/typescript/tools/in-process-wrapper-template.mjs").readText()
    val importLine = "import * as __userModule from \"__TRAILBLAZE_IMPORT_SOURCE__\";"
    check(importLine in template) { "wrapper template import line changed; update this test" }
    return template.replace(importLine, USER_MODULE)
  }

  private fun buildContext(): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = TraceId.generate(TraceOrigin.TOOL),
    trailblazeDeviceInfo = deviceInfo,
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = sessionId, startTime = Clock.System.now())
    },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
  )

  private fun locateFrameworkRoot(): File {
    var dir: File? = File(System.getProperty("user.dir"))
    while (dir != null) {
      if (File(dir, "sdks/typescript/package.json").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate framework root from ${System.getProperty("user.dir")}")
  }

  private companion object {
    const val USER_MODULE = """
      const __userModule = {
        propagates: async (args, ctx, client) => {
          await client.tools.test_fatal({});
          return "unreachable";
        },
        rethrows: async (args, ctx, client) => {
          try {
            await client.tools.test_fatal({});
          } catch (e) {
            throw e;
          }
          return "unreachable";
        },
        catches: async (args, ctx, client) => {
          try {
            await client.tools.test_fatal({});
          } catch (e) {
            return "handled";
          }
          return "unreachable";
        },
        recoverable: async (args, ctx, client) => {
          await client.tools.test_recoverable({});
          return "unreachable";
        },
      };
    """
  }
}
