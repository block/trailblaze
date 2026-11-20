package xyz.block.trailblaze.scripting

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.history.ToolCallHistory
import xyz.block.trailblaze.history.recordExecution
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TraceId.Companion.TraceOrigin
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RegisterCleanupTrailblazeTool
import xyz.block.trailblaze.toolcalls.SessionCleanups
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.commands.ExecTrailblazeTool
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.toolcalls.invokeFrameworkTool
import java.io.File
import java.nio.file.Files

/**
 * `exec` runs only when a tool's own code names it. A generic forwarder such as `runIf` passes on
 * tool names its caller wrote, so when that caller is outside any tool (a trail step, `trailblaze
 * tool`, MCP `step`, the agent), a forwarded tool-only name meets the refusal the caller would
 * have met. Runs the shipped `runIf` bundle; `exec` itself is intercepted and never starts a process.
 */
class ForwardedToolOnlyRefusalTest {

  private val sessionId = SessionId.sanitized("forwarded-tool-only-test")
  private val dir: File = Files.createTempDirectory("forwarded-tool-only-test").toFile()
  private val registrations = mutableListOf<LazyYamlScriptedToolRegistration>()

  /** A condition `runIf` can evaluate without a device. */
  @Serializable
  @TrailblazeToolClass("test_pass")
  private class PassTool : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) = TrailblazeToolResult.Success()
  }

  /** A Kotlin tool that forwards the tool name it is handed, as a generic forwarder would. */
  @Serializable
  @TrailblazeToolClass("test_forward")
  private data class ForwardTool(val tool: String) : ExecutableTrailblazeTool {
    override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext) =
      toolExecutionContext.invokeFrameworkTool(tool, """{"argv":["printf","hi"]}""")
  }

  private val repo = TrailblazeToolRepo(TrailblazeToolSet.DynamicTrailblazeToolSet("test-set", setOf(PassTool::class)))

  /** The `exec` calls that reached dispatch. */
  private val execs = mutableListOf<ExecTrailblazeTool>()

  @AfterTest
  fun cleanup() {
    runBlocking { registrations.forEach { runCatching { it.dispose() } } }
    SessionCleanups.drain(sessionId)
    dir.deleteRecursively()
  }

  @Test
  fun `runIf can't forward exec from a caller outside any tool`() = runBlocking {
    val runIf = register("runIf", shippedBundle("runIf"))
    val context = buildContext()

    val result = callFromOutside(
      runIf,
      """{"condition":{"tool":{"test_pass":{}}},"then":[{"exec":{"argv":["printf","hi"]}}]}""",
      context,
    )

    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue("`exec` can only be called from another tool's own code" in result.errorMessage, result.errorMessage)
    assertEquals(emptyList(), execs)
  }

  @Test
  fun `runIf can't forward registerCleanup, which would run exec at trail end`() = runBlocking {
    val runIf = register("runIf", shippedBundle("runIf"))
    val context = buildContext()

    val result = callFromOutside(
      runIf,
      """{"condition":{"tool":{"test_pass":{}}},"then":[""" +
        """{"${RegisterCleanupTrailblazeTool.TOOL_NAME}":{"tool":"exec","args":{"argv":["printf","hi"]}}}]}""",
      context,
    )

    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue("`${RegisterCleanupTrailblazeTool.TOOL_NAME}` can only be called" in result.errorMessage, result.errorMessage)
    assertEquals(emptyList(), SessionCleanups.drain(sessionId))
  }

  @Test
  fun `a Kotlin forwarder can't be handed exec through a memory token`() = runBlocking {
    val context = buildContext()
    context.memory.variables["t"] = "exec"
    val authored = ForwardTool(tool = "{{t}}")
    // The dispatch boundary resolves tokens before a Kotlin tool runs.
    val resolved = interpolateMemoryInTool(authored, context.memory, context.trailblazeDeviceInfo) as ExecutableTrailblazeTool

    val result = withTimeout(30_000) {
      ToolCallHistory.recordExecution(context, authored) { resolved.execute(context) }
    }

    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue("`exec` can only be called from another tool's own code" in result.errorMessage, result.errorMessage)
    assertEquals(emptyList(), execs)
  }

  @Test
  fun `a caller passing exec doesn't block a later tool's own exec`() = runBlocking {
    val ownExec = register("ownExec", inlineBundle("ownExec", OWN_EXEC_BODY))
    val context = buildContext()

    val forwarded = callFromOutside(ForwardTool(tool = "exec"), context)
    val own = callFromOutside(ownExec, "{}", context)

    assertIs<TrailblazeToolResult.Error>(forwarded)
    assertIs<TrailblazeToolResult.Success>(own, (own as? TrailblazeToolResult.Error)?.errorMessage)
    assertEquals(listOf(ExecTrailblazeTool(argv = listOf("printf", "hi"))), execs)
  }

  @Test
  fun `a tool's own code still calls exec and registers an exec cleanup`() = runBlocking {
    val ownExec = register(
      "ownExec",
      inlineBundle("ownExec", OWN_EXEC_BODY),
    )
    val context = buildContext()

    val result = callFromOutside(ownExec, "{}", context)

    assertIs<TrailblazeToolResult.Success>(result, (result as? TrailblazeToolResult.Error)?.errorMessage)
    assertEquals(listOf(ExecTrailblazeTool(argv = listOf("printf", "hi"))), execs)
    assertEquals(listOf("exec"), SessionCleanups.drain(sessionId).map { it.toolName })
  }

  /** Runs [tool] the way a trail step or the daemon's direct call does: recorded on [context], then executed. */
  private suspend fun callFromOutside(
    tool: LazyYamlScriptedToolRegistration,
    argsJson: String,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val decoded = tool.decodeToolCall(argsJson) as ExecutableTrailblazeTool
    return callFromOutside(decoded, context)
  }

  private suspend fun callFromOutside(tool: ExecutableTrailblazeTool, context: TrailblazeToolExecutionContext): TrailblazeToolResult =
    withTimeout(30_000) { ToolCallHistory.recordExecution(context, tool) { tool.execute(context) } }

  private suspend fun register(name: String, bundle: File): LazyYamlScriptedToolRegistration =
    LazyYamlScriptedToolRegistration.create(
      toolConfig = InlineScriptToolConfig(script = "$name.ts", name = name),
      bundlePath = bundle,
      toolRepo = repo,
      sessionId = sessionId,
    ).also {
      registrations += it
      repo.addDynamicTools(listOf(it))
    }

  private fun shippedBundle(name: String): File {
    val resource = "trails/config/trailmaps/trailblaze/tools/$name.bundle.js"
    val js = checkNotNull(javaClass.classLoader.getResource(resource)) { "missing $resource" }.readText()
    return dir.resolve("$name.bundle.js").apply { writeText(js) }
  }

  private fun inlineBundle(name: String, handlerBody: String): File = dir.resolve("$name.bundle.js").apply {
    writeText(
      """
        const tools = (globalThis.__trailblazeTools = globalThis.__trailblazeTools || {});
        tools["$name"] = { name: "$name", spec: {}, handler: async (args) => { $handlerBody } };
      """.trimIndent(),
    )
  }

  /** Nested calls dispatch as an agent's would; `exec` is recorded instead of run. */
  private fun buildContext(): TrailblazeToolExecutionContext {
    lateinit var context: TrailblazeToolExecutionContext
    context = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = TraceId.generate(TraceOrigin.TOOL),
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "forwarded-tool-only-test",
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
      toolRepo = repo,
      nestedToolExecutor = { tool: TrailblazeTool ->
        if (tool is ExecTrailblazeTool) {
          execs += tool
          TrailblazeToolResult.Success()
        } else {
          ToolCallHistory.recordExecution(context, tool) { (tool as ExecutableTrailblazeTool).execute(context) }
        }
      },
    )
    return context
  }

  private companion object {
    /** A handler whose own code runs `exec` and registers an `exec` cleanup. */
    val OWN_EXEC_BODY = """
          for (const [name, args] of [
            ["exec", { argv: ["printf", "hi"] }],
            ["${RegisterCleanupTrailblazeTool.TOOL_NAME}", { tool: "exec", args: { argv: ["printf", "bye"] } }],
          ]) {
            const env = JSON.parse(await globalThis.__trailblazeCall(name, JSON.stringify(args)));
            if (env.isError) return { isError: true, content: [{ type: "text", text: env.error }] };
          }
          return { content: [{ type: "text", text: "ran" }] };
    """
  }
}
