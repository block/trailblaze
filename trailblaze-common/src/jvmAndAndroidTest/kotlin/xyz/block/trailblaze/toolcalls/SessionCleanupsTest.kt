package xyz.block.trailblaze.toolcalls

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool

class SessionCleanupsTest {

  private val session = TrailblazeSession(sessionId = SessionId("cleanup-test"), startTime = Clock.System.now())
  private val dispatched = mutableListOf<TrailblazeTool>()

  private val context = TrailblazeToolExecutionContext(
    screenState = null,
    traceId = null,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 1080,
      heightPixels = 1920,
    ),
    sessionProvider = TrailblazeSessionProvider { session },
    trailblazeLogger = TrailblazeLogger.createNoOp(),
    memory = AgentMemory(),
    nestedToolExecutor = { tool -> dispatched += tool; TrailblazeToolResult.Success() },
  )

  private fun args(script: String) = JsonObject(mapOf("command" to JsonPrimitive(script)))

  private fun register(tool: String, args: JsonObject) =
    runBlocking { RegisterCleanupTrailblazeTool(tool, args).execute(context) }

  private fun registerAll(vararg calls: RegisterCleanupTrailblazeTool.Call) =
    runBlocking { RegisterCleanupTrailblazeTool(calls = calls.toList()).execute(context) }

  @AfterTest
  fun drain() {
    SessionCleanups.drain(session.sessionId)
  }

  @Test
  fun `registers each distinct call once and drains newest first`() {
    register("android_adbShell", args("restore radios"))
    register("android_adbShell", args("restore location"))
    val repeat = register("android_adbShell", args("restore radios"))

    assertTrue((repeat as TrailblazeToolResult.Success).message.orEmpty().contains("already"))
    // The repeat kept its place: the radios restore still runs last.
    val drained = SessionCleanups.drain(session.sessionId)
    assertEquals(listOf(args("restore location"), args("restore radios")), drained.map { it.args })
    assertTrue(SessionCleanups.drain(session.sessionId).isEmpty())
  }

  @Test
  fun `a restore registered again still leaves the original state`() {
    // Wi-Fi starts on, then goes off, on and off: each step registers a restore of what it saw.
    register("android_adbShell", args("wifi on"))
    register("android_adbShell", args("wifi off"))
    register("android_adbShell", args("wifi on"))

    val ran = SessionCleanups.drain(session.sessionId).map { it.args }
    assertEquals(listOf(args("wifi off"), args("wifi on")), ran)
  }

  @Test
  fun `with no tool repo the call is kept for the agent to resolve`() {
    register("android_adbShell", args("x"))

    val tool = SessionCleanups.drain(session.sessionId).single().tool
    assertIs<OtherTrailblazeTool>(tool)
    assertEquals("android_adbShell", tool.toolName)
  }

  @Test
  fun `refuses a blank name and itself`() {
    assertIs<TrailblazeToolResult.Error>(register(" ", args("x")))
    assertIs<TrailblazeToolResult.Error>(register(RegisterCleanupTrailblazeTool.TOOL_NAME, args("x")))
    assertTrue(SessionCleanups.drain(session.sessionId).isEmpty())
  }

  @Test
  fun `a detached run goes through the dispatch that registered it`() = runBlocking {
    register("android_adbShell", args("x"))

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { it.runDetached() }

    assertEquals(emptyList(), failures)
    assertEquals(listOf("android_adbShell"), dispatched.map { (it as OtherTrailblazeTool).toolName })
  }

  @Test
  fun `a list registers as one, runs in the order written, and ahead of older undos`() {
    register("android_adbShell", args("older"))
    registerAll(
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("first")),
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("second")),
    )

    assertEquals(listOf(args("first"), args("second"), args("older")), SessionCleanups.drain(session.sessionId).map { it.args })
  }

  @Test
  fun `a call repeated in one list runs at its last place`() {
    registerAll(
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("wifi on")),
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("bluetooth off")),
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("wifi on")),
    )

    assertEquals(listOf(args("bluetooth off"), args("wifi on")), SessionCleanups.drain(session.sessionId).map { it.args })
  }

  @Test
  fun `a sensitive value is stored as its token, so the undo resolves it where the log masks it`() {
    context.memory.rememberSensitive("pin", "4321")
    context.memory.remember("note", "hello")

    register("android_adbShell", JsonObject(mapOf("command" to JsonPrimitive("input text 4321 hello"))))

    assertEquals(
      JsonObject(mapOf("command" to JsonPrimitive("input text {{pin}} hello"))),
      SessionCleanups.drain(session.sessionId).single().args,
    )
  }

  @Test
  fun `a misspelled argument is refused when the undo is added`() {
    val repoContext = TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = context.trailblazeDeviceInfo,
      sessionProvider = TrailblazeSessionProvider { session },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      toolRepo = TrailblazeToolRepo(
        TrailblazeToolSet.DynamicTrailblazeToolSet(name = "cleanup-test", toolClasses = setOf(InputTextTrailblazeTool::class)),
      ),
    )
    fun add(key: String) = runBlocking {
      RegisterCleanupTrailblazeTool("inputText", JsonObject(mapOf(key to JsonPrimitive("x")))).execute(repoContext)
    }

    val refused = assertIs<TrailblazeToolResult.Error>(add("txet"))
    assertTrue("txet" in refused.errorMessage, refused.errorMessage)
    assertTrue(SessionCleanups.drain(session.sessionId).isEmpty())
    assertIs<TrailblazeToolResult.Success>(add("text"))
  }

  @Test
  fun `a bad call in a list registers none of it`() {
    val result = registerAll(
      RegisterCleanupTrailblazeTool.Call("android_adbShell", args("fine")),
      RegisterCleanupTrailblazeTool.Call(" ", args("bad")),
    )

    assertIs<TrailblazeToolResult.Error>(result)
    assertTrue(SessionCleanups.drain(session.sessionId).isEmpty())
    assertIs<TrailblazeToolResult.Error>(runBlocking { RegisterCleanupTrailblazeTool(calls = emptyList()).execute(context) })
    assertIs<TrailblazeToolResult.Error>(
      runBlocking { RegisterCleanupTrailblazeTool("x", calls = listOf(RegisterCleanupTrailblazeTool.Call("y"))).execute(context) },
    )
  }

  @Test
  fun `runAll runs every call, continuing past failures`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { cleanup ->
      if (cleanup.toolName == "second") error("adb went away") else TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "exit 1")
    }

    assertEquals(listOf("second: adb went away", "first: exit 1"), failures)
  }

  @Test
  fun `a hung call times out and the next one still runs`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId), perCall = 100.milliseconds) { cleanup ->
      if (cleanup.toolName == "second") awaitCancellation()
      ran += cleanup.toolName
      TrailblazeToolResult.Success()
    }

    assertEquals(listOf("first"), ran)
    assertTrue(failures.single().startsWith("second: timed out"), failures.single())
  }

  @Test
  fun `a call that ignores cancellation stops the run instead of racing the next one`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()
    val release = CountDownLatch(1)

    val ranWhileStuck = mutableListOf<String>()
    val failures = try {
      SessionCleanups.runAll(SessionCleanups.drain(session.sessionId), perCall = 100.milliseconds, grace = 100.milliseconds) { cleanup ->
        // Blocking I/O, as with adb gone: cancellation can't reach it.
        if (cleanup.toolName == "second") release.await()
        synchronized(ran) { ran += cleanup.toolName }
        TrailblazeToolResult.Success()
      }.also {
        // Read before releasing: once released, the leaked call finishes and records itself.
        ranWhileStuck += synchronized(ran) { ran.toList() }
      }
    } finally {
      release.countDown()
    }

    assertEquals(emptyList(), ranWhileStuck)
    assertEquals(
      listOf("second: timed out after 100ms and is still running", "first: not run, second is still running"),
      failures,
    )
  }

  @Test
  fun `a call that stops once cancelled lets the rest run`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId), perCall = 100.milliseconds) { cleanup ->
      if (cleanup.toolName == "second") runInterruptible { Thread.sleep(60_000) }
      ran += cleanup.toolName
      TrailblazeToolResult.Success()
    }

    assertEquals(listOf("first"), ran)
    assertEquals(listOf("second: timed out after 100ms"), failures)
  }

  @Test
  fun `an assertion failure is that call's failure, not the end of the run`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { cleanup ->
      if (cleanup.toolName == "second") throw AssertionError("node not found")
      ran += cleanup.toolName
      TrailblazeToolResult.Success()
    }

    assertEquals(listOf("first"), ran)
    assertEquals(listOf("second: node not found"), failures)
  }

  @Test
  fun `any other Error is that call's failure, not the end of the run`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { cleanup ->
      if (cleanup.toolName == "second") throw UnsatisfiedLinkError("no libquickjs")
      ran += cleanup.toolName
      TrailblazeToolResult.Success()
    }

    assertEquals(listOf("first"), ran)
    assertEquals(listOf("second: no libquickjs"), failures)
  }

  @Test
  fun `a cancellation thrown by a call is its failure, not the end of the run`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { cleanup ->
      if (cleanup.toolName == "second") throw CancellationException("tool gave up")
      ran += cleanup.toolName
      TrailblazeToolResult.Success()
    }

    assertEquals(listOf("first"), ran)
    assertEquals(listOf("second: cancelled: tool gave up"), failures)
  }

  @Test
  fun `the caller's own cancellation still stops the run`() = runBlocking {
    register("first", args("a"))
    register("second", args("b"))
    val ran = mutableListOf<String>()

    val job = launch {
      SessionCleanups.runAll(SessionCleanups.drain(session.sessionId)) { cleanup ->
        if (cleanup.toolName == "second") awaitCancellation()
        ran += cleanup.toolName
        TrailblazeToolResult.Success()
      }
    }
    delay(100)
    job.cancelAndJoin()

    assertEquals(emptyList(), ran)
  }

  @Test
  fun `a timeout cancels a host-local dispatch's own runBlocking`() = runBlocking {
    register("stuck", args("a"))
    val cancelled = CountDownLatch(1)

    val failures = SessionCleanups.runAll(SessionCleanups.drain(session.sessionId), perCall = 100.milliseconds) {
      // How BaseTrailblazeAgent dispatches a host-local tool: a fresh runBlocking linked to the caller.
      runBlocking {
        DispatchCancellation.linked(this) {
          try {
            awaitCancellation()
          } finally {
            cancelled.countDown()
          }
        }
      }
    }

    assertTrue(failures.single().startsWith("stuck: timed out"), failures.single())
    assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the dispatch was never cancelled")
  }

  @Test
  fun `registration logs mask the undo's args`() {
    assertEquals(setOf("args", "calls"), RegisterCleanupTrailblazeTool("android_adbShell", args("secret")).sensitiveArgNames)
  }

  @Test
  fun `an undo's tool names never become session secrets, its credentials do`() {
    val undo = RegisterCleanupTrailblazeTool(
      calls = listOf(
        RegisterCleanupTrailblazeTool.Call("android_adbShell", args("pm clear com.example.app")),
        RegisterCleanupTrailblazeTool.Call(
          "fixture_signOut",
          JsonObject(mapOf("password" to JsonPrimitive("Staging-Pw-0451!"))),
        ),
      ),
    )

    assertEquals(listOf("Staging-Pw-0451!"), undo.credentialArgValues())
  }
}
