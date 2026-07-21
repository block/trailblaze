package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.ScreenStateLogger
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.memory.RememberTextTrailblazeTool
import xyz.block.trailblaze.utils.ElementComparator

/**
 * End-to-end coverage of the dispatch-boundary memory interpolation through a real agent loop
 * ([BaseTrailblazeAgent.runTrailblazeTools] → `dispatchTools` → `handleExecutableTool`), pinning
 * the three observable surfaces the boundary exists for:
 *
 * 1. **The driver sees resolved values** — a `{{var}}` in a tool arg reaches the Maestro command
 *    with the remembered value substituted, with no self-interpolation inside the tool.
 * 2. **The emitted `TrailblazeToolLog` carries BOTH forms** — `trailblazeTool` is the log-safe
 *    resolved payload, `rawTrailblazeTool` the authored token-bearing payload (elided when they
 *    match), which is what lets the recording generator keep tokens.
 * 3. **`rememberSensitive` values never enter the log or failure metadata** — the resolved
 *    payload keeps the sensitive token literal, and a failing tool's `command` is swapped back
 *    to the authored instance before it can render into LLM-facing error content.
 *
 * The helper-level contracts (per-type pass-throughs, scrub semantics, identity swap) are unit
 * tested in [xyz.block.trailblaze.toolcalls.ToolMemoryInterpolationTest].
 */
class ToolDispatchMemoryBoundaryTest {

  /** Minimal concrete [MaestroTrailblazeAgent] that records the commands the "driver" received. */
  private class FixtureAgent(
    logger: TrailblazeLogger = TrailblazeLogger.createNoOp(),
  ) : MaestroTrailblazeAgent(
    trailblazeLogger = logger,
    trailblazeDeviceInfoProvider = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "fixture-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    },
    sessionProvider = TrailblazeSessionProvider {
      TrailblazeSession(sessionId = SessionId("fixture-session"), startTime = Clock.System.now())
    },
  ) {
    val executedCommands = mutableListOf<Command>()

    override suspend fun executeMaestroCommands(
      commands: List<Command>,
      traceId: TraceId?,
    ): TrailblazeToolResult {
      executedCommands += commands
      return TrailblazeToolResult.Success()
    }
  }

  private val noOpComparator = object : ElementComparator {
    override fun getElementValue(prompt: String): String? = null
    override fun evaluateBoolean(statement: String) =
      BooleanAssertionTrailblazeTool(reason = statement, result = true)
    override fun evaluateString(query: String) =
      StringEvaluationTrailblazeTool(reason = query, result = "")
    override fun extractNumberFromString(input: String): Double? = null
  }

  private fun capturingLogger(captured: MutableList<TrailblazeLog>) = TrailblazeLogger(
    logEmitter = LogEmitter { captured += it },
    screenStateLogger = ScreenStateLogger { "" },
  )

  @Test
  fun `dispatch resolves tokens for the driver and logs raw plus resolved`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = capturingLogger(captured))
    agent.memory.remember("account_email", "owner@example.com")

    val result = agent.runTrailblazeTools(
      tools = listOf(InputTextTrailblazeTool(text = "{{account_email}}")),
      elementComparator = noOpComparator,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    // The driver received the resolved value — no self-interpolation inside the tool needed.
    val typed = agent.executedCommands.filterIsInstance<InputTextCommand>().single()
    assertThat(typed.text).isEqualTo("owner@example.com")
    // The log carries the resolved form as `trailblazeTool` and the authored form as
    // `rawTrailblazeTool` — the split the recording generator emits tokens from.
    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.toolName).isEqualTo("inputText")
    assertThat(log.trailblazeTool.raw["text"]).isEqualTo(JsonPrimitive("owner@example.com"))
    assertThat(log.rawTrailblazeTool!!.raw["text"]).isEqualTo(JsonPrimitive("{{account_email}}"))
  }

  @Test
  fun `token-free dispatch logs no raw payload`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = capturingLogger(captured))
    agent.memory.remember("account_email", "owner@example.com")

    agent.runTrailblazeTools(
      tools = listOf(InputTextTrailblazeTool(text = "plain text")),
      elementComparator = noOpComparator,
    )

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.trailblazeTool.raw["text"]).isEqualTo(JsonPrimitive("plain text"))
    // Interpolation was a no-op → no raw/resolved split to record.
    assertThat(log.rawTrailblazeTool).isNull()
  }

  @Test
  fun `a sensitive value reaches the driver but never the persisted log`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = capturingLogger(captured))
    agent.memory.remember("user", "sam")
    // The canary must stay non-numeric: the whole-log doesNotContain assertions scan the
    // serialized entry, and a digits-only value collides with timestamp microseconds /
    // durationMs (a bare "9999" matched `…50.599998Z`).
    agent.memory.rememberSensitive("pin", "9999zq")

    val result = agent.runTrailblazeTools(
      tools = listOf(InputTextTrailblazeTool(text = "{{user}}:{{pin}}")),
      elementComparator = noOpComparator,
    )

    // The driver got the real secret — that's the whole point of the pass-through.
    val typed = agent.executedCommands.filterIsInstance<InputTextCommand>().single()
    assertThat(typed.text).isEqualTo("sam:9999zq")
    // The executed-tools ledger (feeds LLM chat history) keeps the authored token form.
    val executed = result.executedTools.single() as InputTextTrailblazeTool
    assertThat(executed.text).isEqualTo("{{user}}:{{pin}}")
    // The log's resolved payload resolves the non-sensitive token but keeps the sensitive one
    // literal; the raw payload is present because raw and scrubbed-resolved still differ.
    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.trailblazeTool.raw["text"]).isEqualTo(JsonPrimitive("sam:{{pin}}"))
    assertThat(log.rawTrailblazeTool!!.raw["text"]).isEqualTo(JsonPrimitive("{{user}}:{{pin}}"))
    // The whole persisted log entry — payloads, messages, everything — is secret-free.
    assertThat(TrailblazeJsonInstance.encodeToString(log)).doesNotContain("9999zq")
  }

  @Test
  fun `a fully sensitive arg elides the raw payload and logs only the token`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = capturingLogger(captured))
    agent.memory.rememberSensitive("pin", "9999zq")

    agent.runTrailblazeTools(
      tools = listOf(InputTextTrailblazeTool(text = "{{pin}}")),
      elementComparator = noOpComparator,
    )

    assertThat(agent.executedCommands.filterIsInstance<InputTextCommand>().single().text)
      .isEqualTo("9999zq")
    // Scrubbing put the token back, making resolved == raw — so the split is elided and the
    // single payload is the token-bearing form.
    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.trailblazeTool.raw["text"]).isEqualTo(JsonPrimitive("{{pin}}"))
    assertThat(log.rawTrailblazeTool).isNull()
    assertThat(TrailblazeJsonInstance.encodeToString(log)).doesNotContain("9999zq")
  }

  @Test
  fun `failure metadata carries the authored tool identity`() {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = capturingLogger(captured))
    agent.memory.rememberSensitive("pin", "9999zq")
    val authored = FailingEchoTool(secret = "{{pin}}")

    val result = agent.runTrailblazeTools(
      tools = listOf(authored),
      elementComparator = noOpComparator,
    )

    // The tool stamped `command = this` with the RESOLVED instance; the boundary swapped it back
    // to the authored one before the result left the dispatch loop — the command renders into
    // LLM-facing error content, which must never carry a rememberSensitive value.
    val error = result.result as TrailblazeToolResult.Error.ExceptionThrown
    assertThat(error.command).isSameInstanceAs(authored)
    // Ledger keeps the authored instance too.
    assertThat(result.executedTools.single()).isSameInstanceAs(authored)
    // And the emitted log is secret-free — including the exception message, which is logged from
    // the RAW result (before the loop's return-value scrub) and so is scrubbed at the log boundary.
    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.exceptionMessage).isEqualTo("deliberate failure on {{pin}}")
    assertThat(TrailblazeJsonInstance.encodeToString(log)).doesNotContain("9999zq")
  }

  @Test
  fun `a remember-tool failure scrubs the resolved secret from its error message`() {
    val agent = FixtureAgent()
    agent.memory.rememberSensitive("pin", "9999zq")

    // The prompt carries a sensitive token; the boundary resolves it before execute() runs, and
    // the comparator always misses (getElementValue → null), so the tool throws
    // "Failed to find element for prompt: <resolved prompt>". That exception's message is rendered
    // into LLM-facing error content, so the resolved secret must not survive in it.
    val thrown = assertFailsWith<TrailblazeToolExecutionException> {
      agent.runTrailblazeTools(
        tools = listOf(RememberTextTrailblazeTool(prompt = "the field showing {{pin}}", variable = "captured")),
        elementComparator = noOpComparator,
      )
    }

    val error = thrown.trailblazeToolResult as TrailblazeToolResult.Error.ExceptionThrown
    // Command identity is swapped back to the authored, token-bearing instance.
    assertThat((error.command as RememberTextTrailblazeTool).prompt).isEqualTo("the field showing {{pin}}")
    // The free-form error message no longer carries the resolved secret — it's back to the token.
    assertThat(error.errorMessage).doesNotContain("9999zq")
    assertThat(error.errorMessage).contains("{{pin}}")
  }
}

/**
 * Fails with `command = this` — the pattern real tools use — so the test can observe whether the
 * dispatch loop swapped the failure metadata back to the authored instance. Top-level (not nested
 * in the test class) so its `@Serializable` round-trip through the boundary works.
 */
@Serializable
@TrailblazeToolClass("failingEcho")
private data class FailingEchoTool(val secret: String) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = TrailblazeToolResult.Error.ExceptionThrown(
    // Splice the RESOLVED arg into the message — the pattern a real tool follows when it reports
    // what it was operating on. Exercises the message scrub on the LOGGED result, which the
    // dispatch loop's return-value scrub doesn't reach.
    errorMessage = "deliberate failure on $secret",
    command = this,
  )
}
