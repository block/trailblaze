package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.model.NodeSelectorMode
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
   * Working directory for resolving relative file paths in tools.
   * Set to the trail file's parent directory so that relative paths like
   * `../../../examples/sample-app/index.html` resolve correctly regardless of the JVM's CWD.
   */
  val workingDirectory: File? = null,
  /** Controls whether playback/recording uses nodeSelector or legacy Maestro path. */
  val nodeSelectorMode: NodeSelectorMode = NodeSelectorMode.DEFAULT,
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
