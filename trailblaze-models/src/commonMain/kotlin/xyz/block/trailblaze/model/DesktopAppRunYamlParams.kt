package xyz.block.trailblaze.model

import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.llm.RunYamlRequest

/**
 * Result of a trail execution.
 *
 * Not `@Serializable`: this is an in-process runtime result handed to a completion callback
 * (`DesktopAppRunYamlParams.onComplete`), never persisted or sent over the wire — which is why
 * [Success] can hold a raw [JsonElement] without the serialization contract `RunYamlResponse`
 * (the wire type carrying the equivalent payload) requires.
 */
sealed class TrailExecutionResult {
  /**
   * Trail completed successfully.
   *
   * [toolMessage] / [toolStructuredContent] mirror the last successfully-executed tool's
   * [xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success] payload — the same "last
   * success wins" semantics as `RunYamlResponse.toolMessage` / `toolStructuredContent`. They
   * let the host/Maestro `trailblaze tool <read-tool>` path surface the tool's real return
   * value instead of a generic "Executed …" acknowledgement, matching the on-device-RPC,
   * host-local web, and iOS-AXE branches. Both are null for action-style tools (tap/swipe) that
   * produce no payload and for runs where no tool produced a `Success`.
   */
  data class Success(
    val toolMessage: String? = null,
    val toolStructuredContent: JsonElement? = null,
  ) : TrailExecutionResult()

  /** Trail failed with an error. */
  data class Failed(val errorMessage: String?) : TrailExecutionResult()

  /** Trail was cancelled. */
  data object Cancelled : TrailExecutionResult()
}

class DesktopAppRunYamlParams(
  val forceStopTargetApp: Boolean,
  val runYamlRequest: RunYamlRequest,
  val targetTestApp: TrailblazeHostAppTarget?,
  val onProgressMessage: (String) -> Unit,
  val onConnectionStatus: (DeviceConnectionStatus) -> Unit,
  val additionalInstrumentationArgs: Map<String, String>,
  /** Called when the trail execution completes (success, failure, or cancellation). */
  val onComplete: ((TrailExecutionResult) -> Unit)? = null,
  /** RPC port for Compose driver connections. */
  val composeRpcPort: Int = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT,
  /** When true, uses a no-op logger so no session files are written to disk. */
  val noLogging: Boolean = false,
  /** Override capture video setting (null = use app config default). */
  val captureVideo: Boolean? = null,
  /** Override capture Android logcat setting (null = use app config default). */
  val captureLogcat: Boolean? = null,
  /** Override capture iOS Simulator system logs setting (null = use app config default). */
  val captureIosLogs: Boolean? = null,
)
