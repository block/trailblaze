package xyz.block.trailblaze.scripting

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.datetime.Clock
import org.junit.BeforeClass
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool

/**
 * End-to-end: JS source → YAML string → [xyz.block.trailblaze.yaml.TrailblazeYaml.Default]
 * decode → expanded list of [xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool].
 *
 * Exercises the full delegation path that `DelegatingTrailblazeTool` integrates with.
 */
class ScriptTrailblazeToolTest {

  companion object {
    @JvmStatic
    @BeforeClass
    fun initSerialization() {
      // Populates TrailblazeYaml.Default with every YAML-registered tool so the
      // script's returned YAML can decode built-ins like tapOnPoint and pasteClipboard.
      // Reading the lazy val triggers TrailblazeSerializationInitializer.buildAllTools()
      // (which is `internal` and not callable from here).
      xyz.block.trailblaze.yaml.TrailblazeYaml.Default
    }
  }

  @Test
  fun `script returning a two-tool YAML expands to two executable tools`() {
    val tool = ScriptTrailblazeTool(
      source = """
        return `
        - tapOnPoint:
            x: 100
            y: 200
        - pasteClipboard: {}
        `;
      """.trimIndent(),
    )

    val expanded = tool.toExecutableTrailblazeTools(createContext())

    assertThat(expanded).hasSize(2)
    assertThat(expanded[0]).isInstanceOf(TapOnPointTrailblazeTool::class)
    val tap = expanded[0] as TapOnPointTrailblazeTool
    assertThat(tap.x).isEqualTo(100)
    assertThat(tap.y).isEqualTo(200)
    assertThat(expanded[1]).isInstanceOf(PasteClipboardTrailblazeTool::class)
  }

  @Test
  fun `script reads memory and params from input and substitutes them into YAML`() {
    val ctx = createContext().apply {
      memory.variables["targetX"] = "500"
    }

    val tool = ScriptTrailblazeTool(
      source = """
        return `
        - tapOnPoint:
            x: ${'$'}{input.memory.targetX}
            y: ${'$'}{input.params.yCoord}
        `;
      """.trimIndent(),
      params = mapOf("yCoord" to "750"),
    )

    val expanded = tool.toExecutableTrailblazeTools(ctx)

    assertThat(expanded).hasSize(1)
    val tap = expanded[0] as TapOnPointTrailblazeTool
    assertThat(tap.x).isEqualTo(500)
    assertThat(tap.y).isEqualTo(750)
  }

  @Test
  fun `script with conditional branches returns different tool lists based on memory`() {
    // Memory is empty → script takes the "empty" branch.
    val source = """
      if (input.memory.loggedIn === 'true') {
        return '- pasteClipboard: {}';
      } else {
        return `
        - tapOnPoint:
            x: 1
            y: 2
        `;
      }
    """.trimIndent()

    val emptyMemory = ScriptTrailblazeTool(source).toExecutableTrailblazeTools(createContext())
    assertThat(emptyMemory).hasSize(1)
    assertThat(emptyMemory[0]).isInstanceOf(TapOnPointTrailblazeTool::class)

    val loggedInCtx = createContext().apply {
      memory.variables["loggedIn"] = "true"
    }
    val loggedIn = ScriptTrailblazeTool(source).toExecutableTrailblazeTools(loggedInCtx)
    assertThat(loggedIn).hasSize(1)
    assertThat(loggedIn[0]).isInstanceOf(PasteClipboardTrailblazeTool::class)
  }

  @Test
  fun `script returning a YAML-defined tool expands recursively into its primitives`() {
    // pressBack is a YAML-defined tool (tools: composition) — it's a DelegatingTrailblazeTool,
    // not an ExecutableTrailblazeTool. A script author writing `return "- pressBack: {}"` should
    // still get a runnable list: the expansion must walk into the YAML tool's primitives.
    // Regression guard for the pressBack class-to-YAML migration.
    val tool = ScriptTrailblazeTool(
      source = """
        return `
        - pressBack: {}
        `;
      """.trimIndent(),
    )

    val expanded = tool.toExecutableTrailblazeTools(createContext())

    assertThat(expanded).hasSize(1)
    assertThat(expanded[0]).isInstanceOf(MaestroTrailblazeTool::class)
  }

  @Test
  fun `script returning YAML for a non-executable tool throws a clear error`() {
    // `rememberText` is a real tool but not ExecutableTrailblazeTool — it implements memory
    // semantics via a different interface. Silently dropping it would leave the test author
    // believing their script worked.
    val tool = ScriptTrailblazeTool(
      source = """
        return `
        - rememberText:
            prompt: some prompt
            variable: varName
        `;
      """.trimIndent(),
    )

    assertFailure { tool.toExecutableTrailblazeTools(createContext()) }
      .transform { it::class.simpleName ?: "" }.isEqualTo("ScriptEvaluationException")
  }

  @Test
  fun `script returning empty YAML yields no tools so imperative-only scripts can opt out of return-expansion`() {
    // A script that does all its work through trailblaze.execute() and has nothing to
    // return afterward should be able to return "" (or return nothing) without
    // triggering a YAML decode error.
    val emptyReturn = ScriptTrailblazeTool(source = "return '';")
    assertThat(emptyReturn.toExecutableTrailblazeTools(createContext())).hasSize(0)

    val whitespaceReturn = ScriptTrailblazeTool(source = "return '   \\n  ';")
    assertThat(whitespaceReturn.toExecutableTrailblazeTools(createContext())).hasSize(0)
  }

  @Test
  fun `trailblaze execute call records a TrailblazeToolLog entry`() {
    // PR A2's load-bearing recording invariant: every trailblaze.execute() call must
    // produce a TrailblazeToolLog via logToolExecution, so replay on a JVM that never
    // loaded the script can rerun the primitive sequence. We verify this with a
    // capturing LogEmitter + a real primitive name. The tool's execute() throws against
    // the fake context (no maestro agent), which is exactly what makes this a good
    // regression guard: the log must fire on the exception path too.
    val captured = mutableListOf<TrailblazeLog>()
    val ctx = createContext(
      TrailblazeLogger(
        logEmitter = LogEmitter { captured.add(it) },
        screenStateLogger = ScreenStateLogger { "" },
      ),
    )

    val tool = ScriptTrailblazeTool(
      source = """
        const r = trailblaze.execute("pasteClipboard", {});
        return "";
      """.trimIndent(),
    )

    // Returns no tools (empty YAML return). The interesting assertion is on the logger.
    assertThat(tool.toExecutableTrailblazeTools(ctx)).hasSize(0)

    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs[0].toolName).isEqualTo("pasteClipboard")
    // pasteClipboard requires a Maestro agent in context; without one it throws, which the
    // dispatcher maps to an Error.ExceptionThrown result — still recorded.
    assertThat(toolLogs[0].successful).isEqualTo(false)
  }

  @Test
  fun `script returning malformed YAML surfaces a ScriptEvaluationException with the returned text`() {
    val tool = ScriptTrailblazeTool(
      source = """
        return 'this: is: not: valid: yaml: [';
      """.trimIndent(),
    )

    assertFailure { tool.toExecutableTrailblazeTools(createContext()) }
      .transform { it.message ?: "" }.apply {
        contains("could not be decoded")
        contains("returned YAML")
      }
  }

  private fun createContext(
    trailblazeLogger: TrailblazeLogger = TrailblazeLogger.createNoOp(),
  ): TrailblazeToolExecutionContext = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
    },
    trailblazeLogger = trailblazeLogger,
    memory = AgentMemory(),
  )
}
