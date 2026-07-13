package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.network.InflightRequestTracker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Context for handling Trailblaze tools.
 * Provides access to session, agent, and device information needed for tool execution.
 *
 * ## Thread-safety
 * Not thread-safe. A single instance is typically built once per `runTrailblazeTools(...)`
 * batch and reused across every tool in that batch — sequentially. The mutable
 * [recordedToolOverride] field relies on that sequencing: each tool's `execute()` may
 * set it, and `logToolExecution` clears it after reading. Concurrent dispatch on a
 * shared context would race on that field and mis-record tools.
 */
class TrailblazeToolExecutionContext(
  screenState: ScreenState?,
  val traceId: TraceId?,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo,
  val sessionProvider: TrailblazeSessionProvider,
  /**
   * Optional provider to capture a fresh screen state on demand.
   * Used by tools like TakeSnapshotTool that need to capture the current device state
   * at the moment the tool is executed, rather than using the cached screenState.
   */
  val screenStateProvider: (() -> ScreenState)? = null,
  /**
   * Executor for running commands on the Android device.
   * Available when running on JVM/Android platforms with a connected Android device.
   */
  val androidDeviceCommandExecutor: AndroidDeviceCommandExecutor? = null,
  /** Logger for recording tool execution and snapshots. */
  val trailblazeLogger: TrailblazeLogger,
  /** Agent memory for variable interpolation and storage. */
  val memory: AgentMemory,
  /**
   * The Maestro-based agent, if running in Maestro mode.
   * Null when running in Playwright-native mode.
   */
  val maestroTrailblazeAgent: MaestroTrailblazeAgent? = null,
  /**
   * Optional executor for nested tool calls issued through the scripting callback channel.
   *
   * When absent, callback dispatch falls back to calling `tool.execute(context)` directly.
   * Agent-backed drivers like Playwright override this so nested tools run through the same
   * driver-specific execution path as top-level tool calls.
   */
  val nestedToolExecutor: (suspend (TrailblazeTool) -> TrailblazeToolResult)? = null,
  /**
   * Working directory for resolving relative file paths in tools.
   * Set to the trail file's parent directory so that relative paths like
   * `../../../examples/sample-app/index.html` resolve correctly regardless of the JVM's CWD.
   */
  val workingDirectory: File? = null,
  /** Controls whether playback/recording uses nodeSelector or legacy Maestro path. */
  val nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
  /**
   * Resolves the on-disk directory where session artifacts (logs, screenshots,
   * network capture, etc.) are written. Null when the host has no logs repo —
   * tools that write artifacts should fail with a clear message in that case.
   *
   * Wired by host runners from `LogsRepo::getSessionDir`. The returned directory
   * is created on demand and shared across all writers for the session.
   */
  val sessionDirProvider: ((SessionId) -> File)? = null,
  /**
   * Engine-agnostic in-flight request tracker. Network capture engines update
   * this on every request start / end so cross-platform idling tools
   * (`wait_for_network_idle`, `wait_for_request`) can observe network
   * settling without needing engine-specific hooks. Null when the host doesn't
   * wire one (e.g. drivers without network observation).
   */
  val inflightRequestTracker: InflightRequestTracker? = null,
  /**
   * Mirrors `TrailblazeConfig.captureNetworkTraffic`. Tools that need to do capture-aware setup
   * that the host bridge can't reach into — most notably Android launch tools that have to flip
   * a target app's debug SharedPref gates between `mobile_clearAppData` and the first network call —
   * read this flag to decide whether to flip those gates.
   *
   * ### Producer contract — host runners MUST populate this from the request
   *
   * Every host-side dispatcher that constructs a [MaestroTrailblazeAgent] (and therefore a
   * context) for a session-scoped tool execution **must** copy
   * `RunYamlRequest.config.captureNetworkTraffic` into the agent's constructor. The default-
   * `false` here is for unit-test fixtures and other test-only callers where the field is
   * irrelevant — a production dispatcher that hits the default is almost certainly a wiring
   * bug, and the symptom is silent: capture-aware launch tools skip their seeding step, the
   * host bridge times out at discovery, and the session ends with empty `network.ndjson`. This
   * is exactly the shape that bit Android sessions on the host-agent paths before all three
   * branches of `DesktopYamlRunner.runYaml` were wired through.
   *
   * If you're adding a new dispatcher path, thread this through explicitly — the compiler
   * won't catch a missed wiring because the default is silent.
   */
  val captureNetworkTraffic: Boolean = false,
  /**
   * The session's active app target paired with its device — closes the deferred wiring
   * note from `ResolvedTarget`'s kdoc (#2699). Null when the session has no target (web
   * sessions, scratch tools, unit-test fixtures). Pre-computed by host runners at session
   * start so per-tool-call envelope-building doesn't re-resolve on every dispatch.
   *
   * Consumers (notably `QuickJsTrailblazeTool.buildCtxEnvelope`) read both this and
   * [appId] to surface `ctx.target.{id, appIds, appId}` to scripted tools.
   * The two fields are intentionally separate: [resolvedTarget] holds the raw declared
   * candidates (`appIds` getter returns the unfiltered list), while [appId] holds
   * the device-resolved one. Both can be informative.
   */
  val resolvedTarget: xyz.block.trailblaze.model.ResolvedTarget? = null,
  /**
   * The actually-installed app id picked from [resolvedTarget]'s declared candidates by
   * intersecting against the device's installed-apps list. Null if [resolvedTarget] is
   * null OR if none of the target's declared candidates are installed on the device — in
   * the latter case scripted tools should fall back to `ctx.target.appIds[0]` (the first
   * declared) and let the launch fail downstream with a clear "app not installed" error.
   *
   * Pre-computed by host runners at session start (one ADB / `simctl listapps` roundtrip
   * per session, not per tool) via `MobileDeviceUtils.findInstalledAppIdForTarget` or its
   * non-throwing equivalent. Tests leave this null.
   */
  val appId: String? = null,
  /**
   * Session's tool repo. When populated, a Kotlin tool's `execute(...)` can compose other
   * framework tools by name via the [invokeFrameworkTool] extension — the bridge that lets
   * the same `@TrailblazeToolClass`-registered Kotlin tools be invoked from both TS (via
   * `ctx.tools.<name>(args)`) and from Kotlin (via `ctx.invokeFrameworkTool("<name>", args)`).
   *
   * ## Producer contract — host runners SHOULD populate this
   *
   * Every host-side dispatcher that already constructs a [TrailblazeToolRepo] for the session
   * (to build the Koog tool registry / dispatch the LLM's tool calls) should pass the same
   * instance here so Kotlin tools composing framework tools see the registered set. Tests
   * that don't exercise cross-tool composition leave the default `null` — `invokeFrameworkTool`
   * throws a clear "toolRepo not wired" error if reached, distinct from "tool not registered".
   *
   * Nullable rather than required so existing producers don't break when this field is added;
   * the runtime cost of forgetting to wire it is a clear error on the first composing tool
   * that runs, not a silent miss.
   */
  val toolRepo: TrailblazeToolRepo? = null,
) {
  /**
   * The screen state tools act against. Reading this always yields a *current* state:
   *
   * - `var`, not `val`: a shared tool-batch context (see
   *   [xyz.block.trailblaze.toolcalls.ToolBatchScope]) is built once for a whole recording but
   *   must still hand each dispatched tool a *current* screen state — tools like
   *   [xyz.block.trailblaze.toolcalls.commands.TapOnTrailblazeTool] and
   *   [xyz.block.trailblaze.toolcalls.commands.ClearTextTrailblazeTool] read this field directly
   *   rather than re-capturing via [screenStateProvider], so a frozen-at-first-tool value would
   *   make every later tool in the batch act on stale (pre-action) UI.
   *   [xyz.block.trailblaze.BaseTrailblazeAgent.runTrailblazeTools] reassigns this before each
   *   dispatch into a shared batch, while reusing the same context instance (and therefore the
   *   same [androidDeviceCommandExecutor]) for the rest of the batch's lifetime.
   * - **Lazy when unset**: when the field is null and a [screenStateProvider] is wired, the
   *   getter captures on first read and caches until the next reassignment. This lets
   *   dispatchers skip the up-front capture for tools that never read the field (`launchApp`,
   *   `pressKey`, `inputText`, every RPC-dispatched tool that resolves against the device's own
   *   live tree) — on the host→on-device RPC path that capture was a full screenshot RPC per
   *   recorded tool (https://github.com/block/trailblaze/issues/210). Assigning null re-arms lazy capture, so "refresh
   *   for the next dispatch" and "don't capture unless consumed" compose.
   * - **With a [screenStateProvider] wired, reads never observe null** — a null read captures
   *   instead. A consumer that treats a null `screenState` as "no state available" keeps that
   *   meaning only on contexts constructed without a provider.
   * - The capture is single-flight (synchronized): parallel nested callbacks share one context
   *   (see [nestedDispatchDepth]), so an unguarded first read would let two of them race into
   *   duplicate device captures.
   */
  var screenState: ScreenState? = screenState
    get() = field ?: synchronized(screenStateCaptureLock) {
      field ?: screenStateProvider?.invoke()?.also { field = it }
    }

  private val screenStateCaptureLock = Any()

  /**
   * Set by a tool during [ExecutableTrailblazeTool.execute] to replace the invoked tool
   * with a different representation in the recorded trail based on what was discovered at
   * execution time. For example, a recorded
   * [xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool] gets replaced with
   * [xyz.block.trailblaze.toolcalls.commands.TapOnTrailblazeTool] carrying the selector and
   * relative point resolved at execute time — the raw coordinate tap still fires in-session,
   * only the recorded YAML changes so replays survive screen reflow.
   *
   * Cleared immediately after each log emit by [xyz.block.trailblaze.logToolExecution] so
   * the override can't bleed into the next tool executed on the same shared context.
   * Null when the invoked tool form should be recorded as-is.
   */
  var recordedToolOverride: TrailblazeTool? = null

  /**
   * Depth of the current `ctx.tools.X()` nested-dispatch stack. Zero while a top-level tool runs;
   * `> 0` while a composite (scripted) tool's under-the-hood sub-calls run, because
   * [xyz.block.trailblaze.BaseTrailblazeAgent.nestedToolExecutorFor] bumps it around each nested
   * [xyz.block.trailblaze.BaseTrailblazeAgent] dispatch and restores it afterward.
   *
   * A tool's log is stamped `isRecordable = false` whenever this is `> 0` (see
   * [xyz.block.trailblaze.logToolExecution]), so a composite's internals never enter a recording:
   * a one-call trailhead like `myapp_launchAppSignedIn` records as that single call, not as the
   * `mobile_maestro` / `tapOn` / `inputText` internals it happens to run — deterministically,
   * regardless of how the parent's logged span lines up with its children's (the fragile signal the
   * generator's span-containment heuristic relied on, which drifts across drivers and the
   * shared-execution batch).
   *
   * ### Scope — which drivers this covers
   * Only drivers that route nested dispatch through
   * [xyz.block.trailblaze.BaseTrailblazeAgent.nestedToolExecutorFor] bump this counter: Maestro
   * (Android on-device instrumentation/accessibility, iOS host) and Playwright. Two paths do NOT
   * and still rely on the generator's `dropNestedToolCalls` span heuristic as the fallback, so that
   * heuristic is **load-bearing, not redundant**: (1) Compose RPC — `ComposeRpcTrailblazeAgent`
   * deliberately wires its own `nestedToolExecutor` (to keep its post-batch screenshot) that never
   * touches this field, and logs via the [TraceId]-only `logToolExecution` overload, which has no
   * context to read; (2) device-routed nesting on `HostOnDeviceRpcTrailblazeAgent` — this counter is
   * in-process and can't cross the RPC boundary. Extending the deterministic filter to those paths
   * (thread depth into the [TraceId] overload / propagate a non-recordable flag over RPC) is a
   * follow-up.
   *
   * A depth counter, not a boolean: nesting is N-deep (e.g. `launchAppSignedIn` →
   * `clearLaunchAndSignIn` → `mobile_maestro`), so unwinding one level must not un-mark the level
   * still running above it.
   *
   * [AtomicInteger], not a plain `Int`: a single composite tool can issue **parallel** nested
   * callbacks that share this one context — e.g. a scripted tool doing
   * `Promise.all([ctx.tools.a(), ctx.tools.b()])`, whose callbacks arrive as concurrent
   * `/scripting/callback` dispatches all resolving the same invocation's `executionContext`. A plain
   * `++`/`--` would lose updates across those (both read 0, both write 1; the first to finish
   * decrements to 0 while the second is still running, so its nested log would mis-record as
   * top-level). Atomic inc/dec keeps the invariant "depth > 0 while any nested dispatch is in
   * flight", so every nested log emitted during that window sees `> 0`. Note the sibling
   * [recordedToolOverride] stays a plain `var` and DOES race on this same concurrent path — that's
   * benign here because a nested dispatch's log is dropped from the recording regardless of which
   * override it happened to read; the class-level "not thread-safe" caveat still governs it.
   *
   * Precondition: assumes composites **await** their nested calls before returning from `execute()`
   * (the callback dispatcher does — it awaits each `/scripting/callback` round-trip). A
   * fire-and-forget nested call still in flight when the parent's own log emits would read `> 0` and
   * wrongly stamp the real top-level step non-recordable; that's a pathological authoring shape, not
   * a supported one.
   */
  val nestedDispatchDepth: AtomicInteger = AtomicInteger(0)

  @Deprecated("Use maestroTrailblazeAgent, trailblazeLogger, or memory directly")
  val trailblazeAgent: MaestroTrailblazeAgent
    get() =
      maestroTrailblazeAgent
        ?: error("This tool requires MaestroTrailblazeAgent but running in Playwright-native context")
}
