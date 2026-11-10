package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.callback.JsScriptingCallbackArgumentValidator
import xyz.block.trailblaze.util.TrailblazeDispatchers

/**
 * Undo calls that tools registered for a session (`ctx.session.addCleanup`), kept as tool calls,
 * not closures. A tool registers one right after it changes the device, built from the path it
 * actually took and the values it actually saw, so the undo is exact without anyone predicting it.
 *
 * Being data, an undo works the same from QuickJS, subprocess and Kotlin tools, and runs through
 * normal tool dispatch, so it logs like any other step. The runner runs them when the trail ends,
 * newest first, whether the trail passed, failed or was cancelled; a session that never runs a
 * trail runs them when it ends.
 */
object SessionCleanups {

  /**
   * One undo. [tool] is resolved when it is registered, so a bad name or arguments fail at the
   * step that registered it. [runDetached] runs it outside a trail, through the dispatch that
   * registered it, for a session that ends without one.
   */
  class Cleanup(
    val toolName: String,
    val args: JsonObject,
    val tool: TrailblazeTool,
    val runDetached: suspend () -> TrailblazeToolResult,
  )

  // One lock for every list, so an `add` can't land on a list `drain` just took.
  private val lock = Any()
  private val bySession = HashMap<SessionId, MutableList<Cleanup>>()

  /**
   * Adds [cleanup] as the newest undo and returns whether it was new. The same call already
   * pending keeps its place instead of running twice: the earlier one restores the state from
   * before every change since, so it must still run after the undos registered in between.
   */
  fun add(sessionId: SessionId, cleanup: Cleanup): Boolean = addAll(sessionId, listOf(cleanup)).single()

  /**
   * Adds [cleanups] in one step, to run in the order given ahead of everything already pending.
   * Returns, for each, whether it was new; one already pending keeps its place. A call repeated
   * within [cleanups] keeps its last place, which is likewise the last point it would have run.
   */
  fun addAll(sessionId: SessionId, cleanups: List<Cleanup>): List<Boolean> = synchronized(lock) {
    // Oldest first, so the list's first call, which runs first, goes in last.
    val pending = bySession.getOrPut(sessionId) { mutableListOf() }
    cleanups.asReversed().map { cleanup ->
      pending.none { it.sameCall(cleanup) }.also { isNew -> if (isNew) pending.add(cleanup) }
    }.asReversed()
  }

  /** Removes and returns [sessionId]'s cleanups, newest first. */
  fun drain(sessionId: SessionId): List<Cleanup> = synchronized(lock) {
    bySession.remove(sessionId)?.reversed().orEmpty()
  }

  private fun Cleanup.sameCall(other: Cleanup) = toolName == other.toolName && args == other.args

  /**
   * Runs every one of [cleanups] in order with [run], continuing past failures, and returns one
   * line per failed call. Each call gets at most [perCall], then is cancelled and given [grace] to
   * stop. A call still running after that is stuck where cancellation can't reach it (blocked in
   * I/O, adb gone after a cancelled trail): the rest are reported as not run rather than started
   * alongside it, since they would go through the same stuck dispatch and race it on the device.
   * A cancellation thrown by a call is that call's failure; only the caller's own cancellation
   * stops the run.
   */
  suspend fun runAll(
    cleanups: List<Cleanup>,
    perCall: Duration = PER_CALL_TIMEOUT,
    grace: Duration = CANCEL_GRACE,
    run: suspend (Cleanup) -> TrailblazeToolResult,
  ): List<String> {
    // The caller's context (logging, session elements) without its job, so a call left behind
    // doesn't keep the caller waiting.
    val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob() + TrailblazeDispatchers.IO)
    val failures = mutableListOf<String>()
    for ((index, cleanup) in cleanups.withIndex()) {
      // Its own Job as the one a host-local dispatch links to, so cancelling it at the limit
      // reaches the dispatch's inner runBlocking (a script's evaluation) and not only this await.
      val call = scope.async { withContext(DispatchCancellation.element(coroutineContext.job)) { run(cleanup) } }
      val failure = try {
        val result = withTimeoutOrNull(perCall) { call.await() }
        if (result == null) {
          call.cancel()
          if (withTimeoutOrNull(grace) { call.join() } == null) {
            failures += "${cleanup.toolName}: timed out after $perCall and is still running"
            cleanups.drop(index + 1).forEach { failures += "${it.toolName}: not run, ${cleanup.toolName} is still running" }
            return failures
          }
          "timed out after $perCall"
        } else {
          (result as? TrailblazeToolResult.Error)?.errorMessage
        }
      } catch (e: CancellationException) {
        call.cancel()
        currentCoroutineContext().ensureActive()
        // The call's own cancellation (a timeout inside it, a tool that threw one), not ours.
        if (e is TimeoutCancellationException) "timed out: ${e.message}" else "cancelled: ${e.message}"
      } catch (e: Exception) {
        e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
      } catch (e: VirtualMachineError) {
        throw e
      } catch (e: Throwable) {
        // Any other Error (an assertion Compose dispatch lets through, a native library that
        // failed to load) is this undo's failure; it must not skip the rest or the teardown.
        e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
      }
      failure?.let { failures += "${cleanup.toolName}: $it" }
    }
    return failures
  }

  /** The step text of the objective that wraps the trail-end undos in the session log. */
  const val OBJECTIVE_STEP: String = "Undo at end"

  val PER_CALL_TIMEOUT: Duration = 20.seconds

  /** How long a timed-out undo has to stop once cancelled before the rest are given up on. */
  val CANCEL_GRACE: Duration = 5.seconds
}

/**
 * Stores a tool call to run when the trail ends. Called by `ctx.session.addCleanup`;
 * never offered to the LLM or recorded. Host-local: it runs in the runner's own process (the host
 * on a host-driven lane, the device on an on-device one), which is where the trail-end run reads
 * the list.
 */
@Serializable
@TrailblazeToolClass(
  name = RegisterCleanupTrailblazeTool.TOOL_NAME,
  surfaceToLlm = false,
  isRecordable = false,
)
@LLMDescription("Internal. Runs a tool call when the trail ends; use `ctx.session.addCleanup`.")
data class RegisterCleanupTrailblazeTool(
  @param:LLMDescription("The tool to run when the trail ends.")
  val tool: String = "",
  @param:LLMDescription("That tool's arguments.")
  val args: JsonObject = JsonObject(emptyMap()),
  @param:LLMDescription("Instead of `tool`: several calls, run in this order.")
  val calls: List<Call>? = null,
) : HostLocalExecutableTrailblazeTool, SensitiveArgsTrailblazeTool {

  /** One call of [calls]. */
  @Serializable
  data class Call(val tool: String, val args: JsonObject = JsonObject(emptyMap()))

  override val advertisedToolName: String get() = TOOL_NAME

  // An undo carries what the tool read off the device, so its args stay out of persisted logs.
  // The undo's own run logs like a direct call to that tool, with that tool's redaction.
  override val sensitiveArgNames: Set<String> get() = setOf("args", "calls")

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val requested = when {
      calls == null -> listOf(Call(tool, args))
      tool.isNotEmpty() -> return error("pass `tool` or `calls`, not both.")
      calls.isEmpty() -> return error("`calls` is empty.")
      else -> calls
    }
    // Every call resolves before any is added, so a bad one leaves none of its list registered.
    val cleanups = requested.map { call -> resolve(call, toolExecutionContext).getOrElse { return error(it.message.orEmpty()) } }
    val added = SessionCleanups.addAll(toolExecutionContext.sessionProvider.invoke().sessionId, cleanups)
    val names = requested.joinToString { "`${it.tool}`" }
    return TrailblazeToolResult.Success(
      message = if (added.any { it }) "$names will run when the trail ends." else "$names was already set to run when the trail ends.",
    )
  }

  private fun resolve(call: Call, context: TrailblazeToolExecutionContext): Result<SessionCleanups.Cleanup> {
    val name = call.tool
    // The dispatch boundary resolved this call's `{{tokens}}`, so a rememberSensitive value is here
    // in the clear. Put its token back, so the undo resolves it when it runs, through the boundary
    // that keeps it out of the log.
    val callArgs = scrubSensitivePayload(call.args, context.memory) as JsonObject
    if (name.isBlank() || name == TOOL_NAME) return Result.failure(IllegalArgumentException("`$name` can't be a cleanup."))
    val repo = context.toolRepo
    val resolved: TrailblazeTool = if (repo == null) {
      // Resolved by the agent when it runs instead.
      OtherTrailblazeTool(name, callArgs)
    } else {
      // The same unknown-key check `ctx.tools` applies, so a misspelled key fails here and not
      // at trail end, where the decoder would drop it and the undo would run without it.
      JsScriptingCallbackArgumentValidator.validate(repo, name, callArgs.toString())
        ?.let { return Result.failure(IllegalArgumentException(it)) }
      try {
        repo.toolCallToTrailblazeToolUnfiltered(name, callArgs.toString())
      } catch (e: CancellationException) {
        throw e
      } catch (e: SerializationException) {
        return Result.failure(IllegalArgumentException("bad arguments for `$name`: ${toolDecodeFailureReason(e, callArgs)}"))
      } ?: return Result.failure(IllegalArgumentException("no tool named `$name`."))
    }
    return Result.success(
      SessionCleanups.Cleanup(name, callArgs, resolved) {
        context.nestedToolExecutor?.invoke(resolved)
          ?: (resolved as? ExecutableTrailblazeTool)?.execute(context)
          ?: TrailblazeToolResult.Error.UnknownTrailblazeTool(command = resolved)
      },
    )
  }

  private fun error(message: String) = TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "$TOOL_NAME: $message")

  companion object {
    const val TOOL_NAME: String = "trailblaze_registerCleanup"
  }
}
