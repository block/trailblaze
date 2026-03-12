package xyz.block.trailblaze.toolcalls

import java.io.File
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.MaestroTrailblazeAgent
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.device.AndroidDeviceCommandExecutor
import xyz.block.trailblaze.devices.AdbDeviceDriver
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.TraceId

/**
 * Context for handling Trailblaze tools.
 * Provides access to session, agent, and device information needed for tool execution.
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
  /**
   * Optional ADB device driver for direct ADB command execution.
   * Used by benchmark tools (AndroidWorld) that need low-level ADB access
   * (tap, swipe, input text, etc.) without going through Maestro.
   */
  val adbDeviceDriver: AdbDeviceDriver? = null,
) {
  @Deprecated("Use maestroTrailblazeAgent, trailblazeLogger, or memory directly")
  val trailblazeAgent: MaestroTrailblazeAgent
    get() =
      maestroTrailblazeAgent
        ?: error("This tool requires MaestroTrailblazeAgent but running in Playwright-native context")
}
