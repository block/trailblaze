package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Request to execute Trailblaze automation on a device.
 *
 * The [yaml] field contains serialized [xyz.block.trailblaze.yaml.TrailYamlItem] items,
 * which can represent:
 * - Natural language prompts ([xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem])
 * - Static tool sequences ([xyz.block.trailblaze.yaml.TrailYamlItem.ToolTrailItem])
 * - Configuration items ([xyz.block.trailblaze.yaml.TrailYamlItem.ConfigTrailItem])
 *
 * The [agentImplementation] controls which architecture processes the request:
 * - [AgentImplementation.TRAILBLAZE_RUNNER]: Legacy YAML-based TrailblazeRunner
 * - [AgentImplementation.MULTI_AGENT_V3]: Mobile-Agent-v3 inspired implementation
 */
@Serializable
data class RunYamlRequest(
  /** Test identifier for logging/reporting */
  val testName: String,

  /** Serialized TrailYamlItem content (prompts, tools, or maestro commands) */
  val yaml: String,

  /** Optional path to the .trail.yaml file (for recording/playback) */
  val trailFilePath: String?,

  /** Target application name (for app-specific context) */
  val targetAppName: String?,

  /** Whether to use recorded tool sequences instead of LLM inference */
  val useRecordedSteps: Boolean,

  /** Device to execute on */
  val trailblazeDeviceId: TrailblazeDeviceId,

  /** LLM configuration for agent inference */
  val trailblazeLlmModel: TrailblazeLlmModel,

  /** Execution configuration (timeouts, logging, etc.) */
  val config: TrailblazeConfig,

  /** Source of the request (MCP, desktop UI, etc.) */
  val referrer: TrailblazeReferrer,
  val driverType: TrailblazeDriverType? = null,

  /**
   * Which agent architecture to use for processing prompts.
   *
   * - [AgentImplementation.TRAILBLAZE_RUNNER]: Legacy YAML-based TrailblazeRunner
   * - [AgentImplementation.MULTI_AGENT_V3]: Mobile-Agent-v3 inspired implementation
   */
  val agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,

  /**
   * Whether the on-device handler should block the RPC response until execution finishes
   * (including post-action UI-settle) and return the terminal result inline on
   * [RunYamlResponse.success] / [RunYamlResponse.errorMessage].
   *
   * Default is `true` — matches every other on-device RPC handler (e.g. `GetScreenState`),
   * which return when their work is done. This eliminates the need for callers to poll
   * [xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest]; the RPC
   * response itself is the completion signal.
   *
   * Callers that want async-kickoff semantics (dispatch a long-running trail and observe
   * progress out-of-band via [xyz.block.trailblaze.mcp.handlers.SubscribeToProgressHandler])
   * can set this to `false`. No in-repo caller currently uses `false`; the flag is preserved
   * to support that dispatch mode when a concrete use case lands.
   */
  val awaitCompletion: Boolean = true,
) : RpcRequest<RunYamlResponse> {
  /**
   * Sync dispatches can run for minutes (cold app launches, agentic AI reflection loops,
   * whole-trail runs). Uses [OnDeviceRpcTimeouts.HTTP_REQUEST_CAP_MS], which is defined as
   * [OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS] plus a fixed buffer — so the socket stays
   * open strictly longer than the on-device handler's own await cap, guaranteeing the
   * handler's structured timeout response lands before the socket itself closes.
   *
   * Falls back to the HttpClient default when the request is fire-and-forget
   * (`awaitCompletion = false`), since that dispatch returns immediately anyway.
   */
  override val requestTimeoutMs: Long?
    get() = if (awaitCompletion) OnDeviceRpcTimeouts.HTTP_REQUEST_CAP_MS else null
}
