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
  val screenState: ScreenState?,
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
   * a target app's debug SharedPref gates between `clearAppData` and the first network call —
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
) {
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

  @Deprecated("Use maestroTrailblazeAgent, trailblazeLogger, or memory directly")
  val trailblazeAgent: MaestroTrailblazeAgent
    get() =
      maestroTrailblazeAgent
        ?: error("This tool requires MaestroTrailblazeAgent but running in Playwright-native context")
}
