package xyz.block.trailblaze.capture

import java.io.File
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * A single capture stream (video, logcat, etc.) that records during a session.
 *
 * Implementations manage background processes (e.g., `adb screenrecord`, `adb logcat`) and produce
 * a [CaptureArtifact] when stopped.
 */
interface CaptureStream {
  val type: CaptureType

  /**
   * Starts capturing. The output file is written to [sessionDir].
   *
   * @param sessionDir Directory where session logs are stored
   * @param deviceId Device identifier (e.g., "emulator-5554")
   * @param appId Package name of the app under test, if known
   */
  fun start(sessionDir: File, deviceId: String, appId: String?)

  /**
   * Stops capturing and returns the artifact, or null if capture failed.
   *
   * Implementations should gracefully handle cases where the process already exited.
   *
   * @param options The session's capture options, for streams whose finalize step depends on them.
   */
  fun stop(options: CaptureOptions = CaptureOptions.NONE): CaptureArtifact?
}

/** Implemented by capture streams that can prove their output is restricted to the target app. */
internal interface AppScopedCaptureStream {
  val isAppScoped: Boolean
}

/** Which side of a tool call a [ToolCallAwareCaptureStream] is being told about. */
enum class ToolCallPhase {
  BEFORE,
  AFTER,
}

/**
 * Implemented by capture streams that want to know about every tool-call boundary — the moment
 * right before the agent runs a tool and the moment right after it returns — in addition to
 * whatever they record on their own clock. The host agent loop calls [onToolCall] for each
 * top-level tool of a session on the dispatching thread, so an implementation that does real
 * work here delays the tool by that much; nested dispatches (a tool that runs other tools) are not
 * reported separately.
 */
interface ToolCallAwareCaptureStream {
  /**
   * [traceId] is the trace the dispatch's own session logs carry, so a row a stream writes here can
   * be joined to them. It identifies the dispatch rather than the single tool — a batch run in one
   * agent call shares one trace — and is null on a path that did not resolve one.
   */
  fun onToolCall(phase: ToolCallPhase, toolName: String, traceId: String? = null)
}
