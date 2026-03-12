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
 * - [AgentImplementation.TWO_TIER_AGENT]: Modern two-tier architecture (inner/outer agents)
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
   * - [AgentImplementation.TWO_TIER_AGENT]: Modern two-tier with separate inner/outer agents
   * - [AgentImplementation.MULTI_AGENT_V3]: Mobile-Agent-v3 inspired implementation
   */
  val agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,

  /**
   * Runtime configuration for [AgentImplementation.TWO_TIER_AGENT].
   * Ignored when using [AgentImplementation.TRAILBLAZE_RUNNER].
   */
  val directAgentConfig: DirectAgentConfig = DirectAgentConfig(),
) : RpcRequest<RunYamlResponse>
