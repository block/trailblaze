package xyz.block.trailblaze.android.test

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.BaseTrailblazeAgent
import xyz.block.trailblaze.logToolExecution
import xyz.block.trailblaze.android.test.tools.AndroidTestExecutableTool
import xyz.block.trailblaze.android.test.tools.CanonicalToolAdapters
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.mobile.tools.ClearAppDataTrailblazeTool
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.interpolateMemoryInTool
import xyz.block.trailblaze.tracing.TrailblazeTracer

/** Shared with `:trailblaze-android`'s accessibility driver so both drivers group as one category. */
internal const val DRIVER_TRACE_CAT = "driver"

/** Dispatch into Espresso or Compose. Contains `resolveSelector`; see `executeMeasured`. */
private const val DISPATCH_SPAN_NATIVE = "nativeDispatch"

/** Dispatch into a tool's own Kotlin body — no Android backend is involved. */
private const val DISPATCH_SPAN_GENERIC = "genericDispatch"

/**
 * Trailblaze agent that dispatches directly into an Android instrumentation test.
 *
 * It intentionally accepts the existing app test target rather than creating Espresso or Compose
 * rules itself. That lets an application keep its established idling resources, Compose clock,
 * activity lifecycle, fixtures, and cleanup while Trailblaze replaces the hand-authored test body.
 *
 * Tool-log serialization is optional because this thin driver is designed to embed into arbitrary
 * app test harnesses, whose pinned kotlinx.serialization runtime may be older than Trailblaze's.
 * The default keeps the hot replay path dependency-light and benchmarkable; applications that align
 * serialization versions can opt into normal Trailblaze tool logging later.
 */
class AndroidTestTrailblazeAgent(
  val target: AndroidTestTarget,
  override val trailblazeLogger: TrailblazeLogger,
  override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo,
  override val sessionProvider: TrailblazeSessionProvider,
  override val trailblazeToolRepo: TrailblazeToolRepo? = null,
  /**
   * The trailmap-manifest target this run is bound to, threaded into every execution context so a
   * QuickJS scripted tool's `ctx.target` (`id`, `appIds`, `appId`) is populated — the same
   * envelope fields `MaestroTrailblazeAgent.buildExecutionContext` supplies. Null keeps the
   * documented "no target → `ctx.target === undefined`" shape.
   */
  private val resolvedTarget: ResolvedTarget? = null,
  /**
   * The app id scripted tools see as `ctx.target.appId`. On this driver the app under test is by
   * definition the instrumented target package, so callers pass that rather than probing the
   * device's installed packages the way the on-device rule does.
   */
  private val appId: String? = null,
  private val screenStateProviderOverride: (() -> ScreenState)? = null,
  private val metricsSink: AndroidTestMetricsSink = AndroidTestMetricsSink.NONE,
  /**
   * Emit a [xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog] per dispatched tool.
   *
   * These logs ARE the Trailblaze report: with none of them a run leaves behind a session with no
   * steps in it, and a failing gate leaves nothing to read. Off by default because serializing them
   * needs a kotlinx.serialization runtime at least as new as Trailblaze's, and this driver is built
   * to embed into app harnesses that pin their own — see the class docs.
   * [AndroidTestTrailblazeRule] turns it on, which is what makes the trail-file path reportable.
   */
  private val logToolCalls: Boolean = false,
) : BaseTrailblazeAgent() {

  /**
   * Per-request external memory, swapped in by [AndroidTestTrailblazeRule.runRpcEnvelope] for the
   * duration of one RPC dispatch. The on-device RPC handler owns that memory's lifecycle — it
   * pre-populates it from the host's `memorySnapshot` and serializes it back onto the response —
   * so writes from tools must land in the handler's instance, not this agent's own. A `var` is
   * safe because on-device RPC runs are serialized; the farm trail-file path never sets it.
   */
  internal var externalMemory: AgentMemory? = null

  private val ownMemory = AgentMemory()

  override val memory: AgentMemory
    get() = externalMemory ?: ownMemory

  val screenStateProvider: () -> ScreenState =
    screenStateProviderOverride ?: { AndroidTestScreenState(target) }

  override fun buildExecutionContext(
    traceId: TraceId,
    screenState: ScreenState?,
    screenStateProvider: (() -> ScreenState)?,
  ): TrailblazeToolExecutionContext {
    val deviceInfo = trailblazeDeviceInfoProvider()
    lateinit var context: TrailblazeToolExecutionContext
    context =
      TrailblazeToolExecutionContext(
        screenState = screenState,
        traceId = traceId,
        trailblazeDeviceInfo = deviceInfo,
        sessionProvider = sessionProvider,
        screenStateProvider = screenStateProvider ?: this.screenStateProvider,
        trailblazeLogger = trailblazeLogger,
        memory = memory,
        toolRepo = trailblazeToolRepo,
        resolvedTarget = resolvedTarget,
        appId = appId,
        nestedToolExecutor = nestedToolExecutorFor { context },
        // Without this every dual-mode primitive — `android_sendBroadcast` and its siblings —
        // fails with "AndroidDeviceCommandExecutor is not provided", whatever the trail says.
        // They need no host here: the Android implementation goes through `InstrumentationRegistry`
        // and `UiAutomation`, both of which this driver already runs inside. That is how a harness
        // puts the app under test into a starting state without driving its UI to get there.
        androidDeviceCommandExecutor =
          AndroidDeviceCommandExecutor(deviceInfo.trailblazeDeviceId),
      )
    return context
  }

  override fun executeTool(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
    toolsExecuted: MutableList<TrailblazeTool>,
  ): TrailblazeToolResult =
    when (tool) {
      is AndroidTestExecutableTool -> {
        toolsExecuted.add(tool)
        executeNativeTool(tool, context)
      }
      is ExecutableTrailblazeTool -> {
        toolsExecuted.add(tool)
        executeGenericTool(tool, context)
      }
      is DelegatingTrailblazeTool ->
        executeDelegatingTool(tool, context, toolsExecuted) { expanded ->
          when (expanded) {
            is AndroidTestExecutableTool -> executeNativeTool(expanded, context)
            else -> executeGenericTool(expanded, context)
          }
        }
      else ->
        throw TrailblazeException(
          "Unhandled Android test tool ${tool::class.simpleName}. " +
            "Register an AndroidTestExecutableTool or a generic ExecutableTrailblazeTool."
        )
    }

  private fun executeNativeTool(
    tool: AndroidTestExecutableTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    executeMeasured(tool, context, DISPATCH_SPAN_NATIVE) { resolved ->
      resolved.executeWithAndroidTest(target, context)
    }

  private fun executeGenericTool(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult =
    refuseIfSelfDestructive(tool)
      ?: executeCanonicalTool(tool, context)
      ?: refuseIfMaestroBound(tool)
      ?: executeMeasured(tool, context, DISPATCH_SPAN_GENERIC) { it.execute(context) }

  /**
   * Refuses a tool whose only body is Maestro dispatch, or null to let it through. This driver
   * carries no Maestro agent, so letting one through fails anyway — but as
   * "MaestroTrailblazeTool requires MaestroTrailblazeAgent", which names an implementation detail
   * instead of the actual situation. Reached only after the canonical adapters (which lower the
   * common Maestro-era recorded tools onto this driver's own backends — raw `mobile_maestro`
   * command lists included, per-command, via
   * [xyz.block.trailblaze.android.test.tools.MaestroCommandAdapters]). So in practice this names
   * the remaining [MapsToMaestroCommands] tool classes nothing upstream claimed; a raw
   * `MaestroTrailblazeTool` never reaches it.
   */
  private fun refuseIfMaestroBound(tool: ExecutableTrailblazeTool): TrailblazeToolResult? {
    if (tool !is MaestroTrailblazeTool && tool !is MapsToMaestroCommands) return null
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "${tool::class.simpleName} dispatches through Maestro, which the in-process " +
        "ANDROID_TEST driver does not carry. Either replay this trail on a driver that does, or " +
        "fork the scripted tool that authors it on `ctx.device.driverType` in its own trailmap " +
        "and compose in-process primitives there instead.",
      command = tool,
    )
  }

  /**
   * Runs a canonical recorded-trail tool (`tapOnElementBySelector` and family) on this driver's
   * own backends, or null to let the tool through to its generic body.
   *
   * Intercepted rather than executed generically because those tools' generic bodies dispatch
   * through Maestro-era agent surfaces this driver does not carry — see [CanonicalToolAdapters].
   * Dispatched as `nativeDispatch`: the adapter runs the same selector-resolve + Espresso/Compose
   * path the `androidTest_*` tools do, so its cost belongs on the timeline as backend time. The
   * tool logged and measured is the CANONICAL tool the trail authored, not the native tool it
   * lowers to — the report should read like the trail.
   */
  private fun executeCanonicalTool(
    tool: ExecutableTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult? {
    CanonicalToolAdapters.adapt(tool) ?: return null
    return executeMeasured(tool, context, DISPATCH_SPAN_NATIVE) { resolved ->
      // Re-adapted after memory interpolation; the resolved tool is the same class, so the
      // second adapt cannot return null.
      CanonicalToolAdapters.adapt(resolved)!!.execute(target, context)
    }
  }

  /**
   * Refuses a generic tool that would destroy the process running it, or null to let it through.
   *
   * Wiring an executor in gives this driver the same device primitives every other Android driver
   * has, and one of them does not survive the trip: `mobile_clearAppData` is `pm clear`, and this
   * instrumentation runs INSIDE the app it would be clearing. Elsewhere that call resets the app
   * under test; here it kills the test, and the run ends as a process death with no result and no
   * report row rather than as a failed step naming what happened.
   *
   * Refused rather than made to work, because there is no version of it that works: nothing this
   * driver can do would let the app be cleared and the test survive to report on it. A trail that
   * needs a fresh install needs a lane that installs; a scripted chain that needs a session reset
   * forks on `ctx.device.driverType` in its own trailmap and composes an app-specific reset (a
   * sign-out broadcast, a debug intent) on this driver instead.
   */
  private fun refuseIfSelfDestructive(tool: ExecutableTrailblazeTool): TrailblazeToolResult? {
    if (tool !is ClearAppDataTrailblazeTool) return null
    val instrumentedPackage =
      InstrumentationRegistry.getInstrumentation().targetContext.packageName
    if (tool.appId != instrumentedPackage) return null
    return TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "mobile_clearAppData cannot target '$instrumentedPackage' on the ANDROID_TEST " +
        "driver: this instrumentation runs inside that process, so clearing it would kill the test " +
        "rather than reset the app. Clearing a DIFFERENT package is still allowed; a scripted " +
        "chain that needs a session reset forks on ctx.device.driverType in its trailmap and " +
        "composes an app-specific reset on this driver instead.",
      command = tool,
    )
  }

  private fun <T> executeMeasured(
    authoredTool: T,
    context: TrailblazeToolExecutionContext,
    /**
     * What to call the dispatch span, since the two branches dispatch to different places.
     *
     * Required rather than defaulted: a generic tool runs its own Kotlin body with no Espresso or
     * Compose involved, so naming it `nativeDispatch` would put a custom tool's cost on the
     * timeline as backend time. Making the caller choose means a third branch cannot inherit the
     * wrong name by omission.
     */
    dispatchSpanName: String,
    execute: suspend (T) -> TrailblazeToolResult,
  ): TrailblazeToolResult where T : TrailblazeTool, T : ExecutableTrailblazeTool =
    runBlocking(EmptyCoroutineContext) {
      // The spans and [metricsSink] report the same phases for different readers: the sink is the
      // numeric surface the in-process benchmark publishes, the spans put that split on the
      // timeline next to every other driver's. The sink's arithmetic is untouched.
      //
      // The published benchmark numbers are unaffected AT THE DEFAULT LEVEL, where each
      // `traceDetail*` below is a field read and nothing more. At VERBOSE they do move: every timer
      // starts outside the span it now encloses, so each phase's reported ms absorbs that span's
      // own open/close cost. That is the right way round — a benchmark is run at the default level,
      // and a profiling run is not the run you quote a number from — but "additive" means additive
      // to the timeline, not free.
      //
      // The two surfaces name the same split rather than a different one. A native tool resolves
      // its own selector, so the hierarchy walk and the resolve poll happen INSIDE the dispatch
      // below; the sink subtracts that from `nativeExecutionMs` via [AndroidTestPhaseAttribution],
      // and the timeline shows it as a `resolveSelector` span nested in the dispatch span. Hence
      // `nativeDispatch` rather than `nativeExecution`: the backend's own cost is the part of the
      // dispatch that `resolveSelector` does not cover, which is exactly the sink's
      // `nativeExecutionMs`. A span named for the sink's field while measuring more than the field
      // does would put selector polling on the timeline as Espresso time.
      //
      // This method adds no span around the dispatch as a whole, because two already cover it:
      // `BaseTrailblazeAgent` opens one per tool call (named by `traceSpanName()`, which is why the
      // phases below land two levels under it), and `TrailblazeToolLog` records the same call as
      // the durable timing. A third would only restate them.
      val orchestrationTimer = AndroidTestStopwatch()
      @Suppress("UNCHECKED_CAST")
      val resolvedTool =
        TrailblazeTracer.traceDetail("interpolateMemory", cat = DRIVER_TRACE_CAT) {
          interpolateMemoryInTool(authoredTool, memory) as T
        }
      val interpolationMs = orchestrationTimer.elapsedMs()
      AndroidTestPhaseAttribution.reset()
      val nativeTimer = AndroidTestStopwatch()
      val timeBeforeExecution = Clock.System.now()
      val result =
        try {
          // Inside the try, so a thrown tool still closes its span and still maps to an error
          // result exactly as before.
          TrailblazeTracer.traceDetailSuspend(dispatchSpanName, cat = DRIVER_TRACE_CAT) {
            execute(resolvedTool)
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(e, authoredTool)
        }
      val measuredMs = nativeTimer.elapsedMs()
      val inToolOrchestrationMs = AndroidTestPhaseAttribution.takeOrchestrationMs()
      val loggingTimer = AndroidTestStopwatch()
      if (logToolCalls) {
        TrailblazeTracer.traceDetail("logToolExecution", cat = DRIVER_TRACE_CAT) {
          logToolExecution(
            tool = resolvedTool,
            timeBeforeExecution = timeBeforeExecution,
            context = context,
            result = result,
            // Only on an actual rewrite, so the log's raw/resolved split stays meaningful: an
            // identical pair would render as a `{{var}}` step that resolved to itself.
            rawTool = authoredTool.takeIf { it !== resolvedTool },
          )
        }
      }
      metricsSink.record(
        AndroidTestTiming(
          toolName = authoredTool::class.simpleName ?: "unknown",
          orchestrationMs = interpolationMs + inToolOrchestrationMs,
          nativeExecutionMs = (measuredMs - inToolOrchestrationMs).coerceAtLeast(0.0),
          loggingMs = loggingTimer.elapsedMs(),
        )
      )
      result
    }
}
