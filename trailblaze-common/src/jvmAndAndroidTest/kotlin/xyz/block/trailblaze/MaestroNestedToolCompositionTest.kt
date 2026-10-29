package xyz.block.trailblaze

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.datetime.Clock
import kotlin.test.Test
import maestro.orchestra.Command
import maestro.orchestra.LaunchAppCommand
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
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
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.BooleanAssertionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.StringEvaluationTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.getIsRecordableFromAnnotation
import xyz.block.trailblaze.utils.ElementComparator

/**
 * Regression coverage for nested scripted-tool composition — the follow-up to PR #4506's
 * [xyz.block.trailblaze.toolcalls.ToolBatchScope] fix (see
 * `docs/devlog/2026-07-03-batched-tool-execution-scope.md`, "Deliberately out of
 * scope" section).
 *
 * #4506 fixed recorded replay building a fresh [TrailblazeToolExecutionContext] per tool, which
 * dropped cross-tool device state cached ON the context (the canary:
 * [AndroidDeviceCommandExecutor]'s in-process clipboard cache, since Android 10+ blocks
 * cross-process clipboard reads). The identical bug exists in nested scripted-tool composition —
 * a scripted tool calling `ctx.tools.setClipboard(...)` then `ctx.tools.pasteClipboard(...)`
 * inside one execute() — because `MaestroTrailblazeAgent.buildExecutionContext`'s
 * `nestedToolExecutor` closure re-entered `runTrailblazeTools(...)` fresh instead of dispatching
 * against the closure-captured outer context.
 *
 * This models the `mobile_setClipboard` -> `mobile_pasteClipboard` canary without touching real
 * ADB: a fake device-state cache keyed by the *context's* [AndroidDeviceCommandExecutor]
 * instance, mirroring that executor's real `lastSetClipboard` field. `AndroidDeviceCommandExecutor`
 * is a plain (non-data) class, so default reference identity is exactly what a `Map` key needs to
 * catch "nested calls silently got a different executor instance."
 */
class MaestroNestedToolCompositionTest {

  /** Minimal concrete [MaestroTrailblazeAgent] — mirrors `MaestroAgentContextChainTest.FixtureAgent`. */
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

  /**
   * Closure-carrying tool. Doesn't need `@Serializable`/`@TrailblazeToolClass` — it's constructed
   * directly in-test and dispatched via `nestedToolExecutor`, never through the repo/JSON decode
   * path a real scripted-tool composition would use (that path is already covered by
   * `SessionScopedHostBindingTest`; this test targets the agent's context-identity contract).
   */
  private class LambdaTool(
    val block: suspend (TrailblazeToolExecutionContext) -> TrailblazeToolResult,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = block(toolExecutionContext)
  }

  /**
   * Models a scripted tool composing two nested framework-tool calls in sequence — exactly what
   * `SessionScopedHostBinding.executeResolved` does for `ctx.tools.setClipboard(...)` followed by
   * `ctx.tools.pasteClipboard(...)` inside one bundle's execute(). The cache is keyed by the
   * *context's* `androidDeviceCommandExecutor` instance, so this fails the same way the real
   * clipboard round trip does if the two nested calls land on different contexts/executors.
   */
  private class SetThenPasteComposingTool(
    private val clipboardByExecutor: MutableMap<AndroidDeviceCommandExecutor, String>,
    private val text: String,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      val nested = toolExecutionContext.nestedToolExecutor
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "nestedToolExecutor not wired",
        )

      val setResult = nested.invoke(
        LambdaTool { ctx ->
          val executor = ctx.androidDeviceCommandExecutor
            ?: return@LambdaTool TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no executor")
          clipboardByExecutor[executor] = text
          TrailblazeToolResult.Success()
        },
      )
      if (setResult !is TrailblazeToolResult.Success) return setResult

      return nested.invoke(
        LambdaTool { ctx ->
          val executor = ctx.androidDeviceCommandExecutor
            ?: return@LambdaTool TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "no executor")
          val cached = clipboardByExecutor[executor]
          if (cached == null) {
            TrailblazeToolResult.Error.ExceptionThrown(
              errorMessage = "mobile_pasteClipboard: device clipboard is empty.",
            )
          } else {
            TrailblazeToolResult.Success(message = "pasted:$cached")
          }
        },
      )
    }
  }

  /**
   * Models a scripted tool whose first nested call fails, composing a second nested call that
   * records whether it ran. Pins that a failing nested call short-circuits the composing tool's
   * `execute()` — the second `ctx.tools.X()` call must never fire — the same early-return
   * contract `SessionScopedHostBinding.executeResolved` relies on for a `DelegatingTrailblazeTool`'s
   * expanded sub-tools.
   */
  private class FailThenRecordComposingTool(
    private val secondCallInvoked: MutableList<Boolean>,
  ) : ExecutableTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult {
      val nested = toolExecutionContext.nestedToolExecutor
        ?: return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "nestedToolExecutor not wired",
        )

      val firstResult = nested.invoke(
        LambdaTool {
          TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "first nested call failed")
        },
      )
      if (firstResult !is TrailblazeToolResult.Success) return firstResult

      return nested.invoke(
        LambdaTool {
          secondCallInvoked += true
          TrailblazeToolResult.Success()
        },
      )
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

  @Test
  fun `a scripted tool's two sequential nested framework-tool calls share one context`() = runBlocking {
    val agent = FixtureAgent()
    val cache = mutableMapOf<AndroidDeviceCommandExecutor, String>()

    val result = agent.runTrailblazeTools(
      tools = listOf(SetThenPasteComposingTool(cache, text = "HELLO")),
      elementComparator = noOpComparator,
    )

    // Before the fix, each `nestedToolExecutor.invoke(...)` call rebuilt a fresh
    // TrailblazeToolExecutionContext (and therefore a fresh AndroidDeviceCommandExecutor) via
    // `runTrailblazeTools`, so the "paste" half read an empty cache — exactly the "device
    // clipboard is empty" symptom from the real mobile_setClipboard -> mobile_pasteClipboard
    // round trip. After the fix, both nested calls dispatch against the SAME closure-captured
    // context, so the write is visible to the read.
    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    assertThat((result.result as TrailblazeToolResult.Success).message).isEqualTo("pasted:HELLO")
  }

  @Test
  fun `a failing nested call short-circuits and the next nested call never runs`() = runBlocking {
    val agent = FixtureAgent()
    val secondCallInvoked = mutableListOf<Boolean>()

    val result = agent.runTrailblazeTools(
      tools = listOf(FailThenRecordComposingTool(secondCallInvoked)),
      elementComparator = noOpComparator,
    )

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Error::class)
    assertThat(secondCallInvoked).isEqualTo(emptyList())
  }

  /**
   * Models the recording-generation regression: regenerating a recordable
   * launch/sign-in orchestrator emitted BOTH the parent call AND its under-the-hood
   * `mobile_maestro` / `tapOn` / `inputText` internals. The internals are dispatched as nested
   * `ctx.tools.X()` calls, and each such dispatch must log `isRecordable = false` so the recording
   * generator's `isRecordable` filter drops it — deterministically, independent of how the parent's
   * logged execution span happens to line up with its children's (the fragile signal the old
   * span-containment heuristic relied on, which drifts across drivers and the shared-execution batch).
   *
   * Depth-2 nesting (`parent` → `nested1` → `nested2`) mirrors the real shape
   * (`launchAppSignedIn` → `clearLaunchAndSignIn` → `mobile_maestro`) and proves the depth counter
   * (not a boolean) restores the outer level correctly on unwind. All three tools are the same
   * un-annotated class — recordable-by-default at the annotation — so the ONLY thing flipping the
   * two nested logs to non-recordable is the nested-dispatch depth, while the top-level parent stays
   * recordable.
   */
  @Test
  fun `nested ctx-dot-tools dispatches log as non-recordable while the top-level call stays recordable`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(
      logger = TrailblazeLogger(
        logEmitter = LogEmitter { captured += it },
        screenStateLogger = ScreenStateLogger { "" },
      ),
    )

    val nested2 = LambdaTool { TrailblazeToolResult.Success() }
    val nested1 = LambdaTool { ctx -> ctx.nestedToolExecutor!!.invoke(nested2) }
    val parent = LambdaTool { ctx -> ctx.nestedToolExecutor!!.invoke(nested1) }

    // Sanity: the class is recordable-by-default at the annotation, so any non-recordable log below
    // is the nested-dispatch stamp doing its job — not an inherently non-recordable tool.
    assertThat(nested2.getIsRecordableFromAnnotation()).isEqualTo(true)

    agent.runTrailblazeTools(tools = listOf(parent), elementComparator = noOpComparator)

    // Emission order: a nested call's log lands while its parent's execute() is still running, so
    // the deepest completes first and the top-level parent logs last.
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(3)
    assertThat(toolLogs[0].isRecordable).isEqualTo(false) // nested2 (depth 2)
    assertThat(toolLogs[1].isRecordable).isEqualTo(false) // nested1 (depth 1)
    assertThat(toolLogs[2].isRecordable).isEqualTo(true) // parent  (depth 0, top-level)
  }

  @Test
  fun `recorded launches retain authored memory references and exclude resolved credentials`() = runBlocking {
    for (reference in listOf("{{oauth2Token}}", "\${oauth2Token}", "{{ oauth2Token }}", "\${ oauth2Token }", "{{session-token}}", "prefix-{{oauth2Token}}")) {
      val captured = mutableListOf<TrailblazeLog>()
      val agent = FixtureAgent(logger = TrailblazeLogger(
        logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
      ))
      agent.memory.remember("oauth2Token", "fixture-session-secret")
      agent.memory.remember("session-token", "fixture-session-secret")
      agent.memory.remember("apiVersion", "2")
      val launch = MaestroTrailblazeTool(
        """- launchApp: {appId: com.example.app, arguments: {credential: "$reference", apiVersion: "{{apiVersion}}"}}""",
      )

      val result = agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

      assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
      val delivered = agent.executedCommands.filterIsInstance<LaunchAppCommand>().single()
      kotlin.test.assertContains(delivered.launchArguments?.get("credential").toString(), "fixture-session-secret")
      val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
      assertThat(log.isRecordable).isEqualTo(true)
      if (reference.startsWith("prefix-")) {
        kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains(reference))
        kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "<redacted>")
      } else {
        kotlin.test.assertContains(log.rawTrailblazeTool.toString(), reference)
      }
      kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{apiVersion}}")
      kotlin.test.assertFalse(log.toString().contains("fixture-session-secret"))
    }
  }

  @Test
  fun `reference shaped resolved credential is masked but its authored reference remains recordable`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.remember("oauth2Token", "prefix-{{literal}}-suffix")
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "{{oauth2Token}}"}}""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.isRecordable).isEqualTo(true)
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{oauth2Token}}")
    kotlin.test.assertFalse(log.toString().contains("prefix-{{literal}}-suffix"))
  }

  @Test
  fun `resolved launch value does not poison authored app id or unrelated text`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("host", JsonPrimitive("example"))
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {host: "{{args.host}}"}}
- inputText: "Connected to example"""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "com.example.app")
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "Connected to example")
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{args.host}}")
    kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains("<redacted>"))
    kotlin.test.assertFalse(log.trailblazeTool.toString().contains("Connected to example"))
  }

  @Test
  fun `mixed launch template masks its literal suffix when reused in authored text`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("user", JsonPrimitive("alice"))
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "{{args.user}}:FAKESECRET"}}
- inputText: FAKESECRET""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains("FAKESECRET"))
    kotlin.test.assertFalse(log.trailblazeTool.toString().contains("FAKESECRET"))
  }

  @Test
  fun `mixed launch prefix masks its full resolved value reused in authored text`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("key", JsonPrimitive("47391"))
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "sk-{{args.key}}"}}
- inputText: sk-47391""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains("sk-47391"))
    kotlin.test.assertFalse(log.trailblazeTool.toString().contains("sk-47391"))
  }

  @Test
  fun `numeric launch argument is masked after dispatch while its reference remains recordable`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("pin", JsonPrimitive(47391))
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "{{args.pin}}"}}""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.isRecordable).isEqualTo(true)
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{args.pin}}")
    kotlin.test.assertFalse(log.toString().contains("47391"))
  }

  @Test
  fun `a launch credential reused in input text is masked outside launch arguments`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.remember("token", "fixture-session-secret")
    val tool = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "{{token}}"}}
- inputText: "{{token}}"""",
    )

    agent.runTrailblazeTools(tools = listOf(tool), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertFalse(log.toString().contains("fixture-session-secret"))
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{token}}")
  }

  @Test
  fun `mixed literal and reference launch argument masks the literal part of the raw form`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("token", JsonPrimitive("fixture-session-secret"))
    agent.memory.putArg("suffix", JsonPrimitive("v2"))
    val tool = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {a: "{{args.token}}", b: "fixture-session-secret-{{args.suffix}}"}}""",
    )

    agent.runTrailblazeTools(tools = listOf(tool), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertFalse(log.toString().contains("fixture-session-secret"))
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "{{args.token}}")
    kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains("<redacted>-{{args.suffix}}"))
    kotlin.test.assertContains(log.rawTrailblazeTool.toString(), "<redacted>")
  }

  @Test
  fun `mixed launch template never retains its literal prefix without another credential argument`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    agent.memory.putArg("suffix", JsonPrimitive("v2"))
    val tool = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: "fixture-session-secret-{{args.suffix}}"}}""",
    )

    agent.runTrailblazeTools(tools = listOf(tool), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    kotlin.test.assertFalse(log.toString().contains("fixture-session-secret"))
    kotlin.test.assertFalse(log.rawTrailblazeTool.toString().contains("{{args.suffix}}"))
    kotlin.test.assertContains(log.trailblazeTool.toString(), "<redacted>")
  }

  @Test
  fun `literal launch credential is masked before the recording generator inspects it`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(logger = TrailblazeLogger(
      logEmitter = LogEmitter { captured += it }, screenStateLogger = ScreenStateLogger { "" },
    ))
    val launch = MaestroTrailblazeTool(
      """- launchApp: {appId: com.example.app, arguments: {credential: fixture-session-secret}}""",
    )

    agent.runTrailblazeTools(tools = listOf(launch), elementComparator = noOpComparator)

    val log = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>().single()
    assertThat(log.isRecordable).isEqualTo(true)
    kotlin.test.assertFalse(log.toString().contains("fixture-session-secret"))
  }

  @Test
  fun `nested launch passes real arguments to the driver but excludes them from tool logs`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(
      logger = TrailblazeLogger(
        logEmitter = LogEmitter { captured += it },
        screenStateLogger = ScreenStateLogger { "" },
      ),
    )
    val launch = MaestroTrailblazeTool(
      """
      - launchApp:
          appId: com.example.app
          arguments:
            appCredential: fixture-app-secret
            sessionCredential: fixture-session-secret
      """.trimIndent(),
    )
    val parent = LambdaTool { ctx -> ctx.nestedToolExecutor!!.invoke(launch) }

    val result = agent.runTrailblazeTools(tools = listOf(parent), elementComparator = noOpComparator)

    assertThat(result.result).isInstanceOf(TrailblazeToolResult.Success::class)
    val delivered = agent.executedCommands.filterIsInstance<LaunchAppCommand>().single()
    assertThat(delivered.launchArguments?.get("appCredential")).isEqualTo("fixture-app-secret")
    assertThat(delivered.launchArguments?.get("sessionCredential")).isEqualTo("fixture-session-secret")
    val logs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    val launchLog = logs.single { it.toolName == "mobile_maestro" }
    assertThat(launchLog.isRecordable).isEqualTo(false)
    assertThat(logs.last().isRecordable).isEqualTo(true)
    kotlin.test.assertContains(launchLog.trailblazeTool.raw.toString(), "com.example.app")
    for (secret in listOf("fixture-app-secret", "fixture-session-secret")) {
      kotlin.test.assertFalse(launchLog.toString().contains(secret))
    }
  }

  /**
   * Concurrency guard for the nested-dispatch depth counter. A single composite tool can fan its
   * nested `ctx.tools.*` calls out in PARALLEL — a scripted tool doing
   * `Promise.all([ctx.tools.a(), ctx.tools.b(), ...])`, whose callbacks arrive as concurrent
   * `/scripting/callback` dispatches all resolving the same invocation's `executionContext`. Every
   * one of those parallel callbacks bumps/restores the SAME context's `nestedDispatchDepth`, so the
   * counter must be atomic: a plain `Int` `++`/`--` can lose an update across threads (two callbacks
   * both read 0 and write 1; the first to finish decrements to 0 while the second is still running),
   * which would mis-stamp a still-in-flight nested call's log as top-level and leak it back into the
   * recording. This dispatches many nested calls concurrently on a multi-threaded dispatcher and
   * pins the observable contract: EVERY nested log stays non-recordable, and only the single
   * top-level parent is recordable — regardless of how the concurrent inc/dec interleave.
   */
  @Test
  fun `parallel nested ctx-dot-tools callbacks all record as non-recordable`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(
      logger = TrailblazeLogger(
        // Nested logs are emitted from the concurrent dispatcher threads — guard the shared list.
        logEmitter = LogEmitter { log -> synchronized(captured) { captured += log } },
        screenStateLogger = ScreenStateLogger { "" },
      ),
    )

    val fanOut = 16
    val parent = LambdaTool { ctx ->
      val nested = ctx.nestedToolExecutor!!
      coroutineScope {
        (1..fanOut).map {
          async(Dispatchers.Default) { nested.invoke(LambdaTool { TrailblazeToolResult.Success() }) }
        }.awaitAll()
      }
      TrailblazeToolResult.Success()
    }

    agent.runTrailblazeTools(tools = listOf(parent), elementComparator = noOpComparator)

    val toolLogs = synchronized(captured) { captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>() }
    assertThat(toolLogs).hasSize(fanOut + 1)
    // Exactly one recordable log — the top-level parent (emitted after execute() returns, once every
    // nested call has unwound depth back to 0). All `fanOut` nested calls stay non-recordable.
    assertThat(toolLogs.count { it.isRecordable }).isEqualTo(1)
  }

  /**
   * Pins the exception-unwind contract of `nestedToolExecutorFor`'s `try { … } finally { decrement }`.
   * A nested `ctx.tools.*` call that THROWS propagates out of `dispatchTools` uncaught (the executable
   * dispatch path has no try/catch around `execute()`), so the depth counter is only restored by the
   * `finally`. If that were a trailing decrement instead, depth would leak past the throw and the
   * parent's own top-level log — emitted after the composite swallows the error and returns — would be
   * wrongly stamped `isRecordable = false` and vanish from the recording. This asserts the parent stays
   * recordable, which fails if the `finally` is ever weakened to a trailing decrement.
   */
  @Test
  fun `depth is restored after a nested call throws so the parent stays recordable`() = runBlocking {
    val captured = mutableListOf<TrailblazeLog>()
    val agent = FixtureAgent(
      logger = TrailblazeLogger(
        logEmitter = LogEmitter { captured += it },
        screenStateLogger = ScreenStateLogger { "" },
      ),
    )

    val parent = LambdaTool { ctx ->
      try {
        ctx.nestedToolExecutor!!.invoke(LambdaTool { throw IllegalStateException("nested boom") })
      } catch (e: IllegalStateException) {
        // Composite swallows the nested failure and returns normally.
      }
      TrailblazeToolResult.Success()
    }

    agent.runTrailblazeTools(tools = listOf(parent), elementComparator = noOpComparator)

    // The throwing nested tool emits no log (the throw bypasses `logToolExecution`), so the sole
    // tool log is the top-level parent — and it must be recordable, proving depth unwound to 0.
    val toolLogs = captured.filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
    assertThat(toolLogs).hasSize(1)
    assertThat(toolLogs.single().isRecordable).isEqualTo(true)
  }
}
