package xyz.block.trailblaze.llm

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.TraceId
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
  /**
   * Optional trace identifier to reuse for every nested tool execution spawned by this run.
   * When null, the downstream runner may generate a new tool-scoped trace.
   */
  val traceId: TraceId? = null,
  val driverType: TrailblazeDriverType? = null,

  /**
   * Which agent architecture to use for processing prompts.
   *
   * - [AgentImplementation.TRAILBLAZE_RUNNER]: Legacy YAML-based TrailblazeRunner
   * - [AgentImplementation.MULTI_AGENT_V3]: Mobile-Agent-v3 inspired implementation
   */
  val agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,

  /**
   * Whether the on-device handler should block the RPC response until execution finishes and
   * return the terminal result inline on [RunYamlResponse.success] /
   * [RunYamlResponse.errorMessage].
   *
   * Default is `true` — matches every other on-device RPC handler (e.g. `GetScreenState`),
   * which return when their work is done. This eliminates the need for callers to poll
   * [xyz.block.trailblaze.mcp.android.ondevice.rpc.GetExecutionStatusRequest]; the RPC
   * response itself is the completion signal.
   *
   * "Completion" here means the handler waits for the launched job to reach a terminal
   * state (success, failure, or cancellation) and for the pre-tool UI-settle + tool
   * execution to return — but only up to [OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS]. If
   * the handler hits that await cap, it returns a structured timeout response
   * ([RunYamlResponse.success] = false, [RunYamlResponse.errorMessage] describing the
   * timeout) before `job.join()` completes, and cleanup (cancelling the job, emitting the
   * terminal progress event, ending the session as Cancelled) continues asynchronously.
   * It also does NOT guarantee a final post-tool UI settle — callers that need a settled
   * screen should make a separate `GetScreenStateRequest`, which settles on entry. See
   * [xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler] for the exact sequence.
   *
   * Callers that want async-kickoff semantics (dispatch a long-running trail and observe
   * progress out-of-band via [xyz.block.trailblaze.mcp.handlers.SubscribeToProgressHandler])
   * can set this to `false`. No in-repo caller currently uses `false`; the flag is preserved
   * to support that dispatch mode when a concrete use case lands.
   */
  val awaitCompletion: Boolean = true,

  /**
   * Snapshot of the host's `AgentMemory` at dispatch time. The on-device
   * `RunYamlRequestHandler` populates the per-request agent's memory from this map BEFORE
   * any tool's `execute()` runs, so on-device tools (Kotlin or scripted) read the same
   * memory state the host had — including values written by earlier tools in the same
   * trail.
   *
   * The host's pre-resolution of `{{var}}` / `${var}` tokens still happens at the RPC
   * boundary (see `RpcToolMemoryInterpolation`), so already-resolved string scalars reach
   * the device. This snapshot covers the OTHER access path: tools that read memory keys
   * directly via `context.memory.variables[...]` or that write via
   * `context.memory.remember(...)` — most relevant to user-contributed TypeScript tools
   * running in the on-device runtime.
   *
   * Must be `emptyMap()` when [awaitCompletion] is `false`: memory sync requires a
   * round-trip, and fire-and-forget has no completion event to attach a return snapshot
   * to. See `init` block.
   */
  val memorySnapshot: Map<String, String> = emptyMap(),

  /**
   * Per-objective cap on LLM calls issued by the legacy [AgentImplementation.TRAILBLAZE_RUNNER]
   * inner loop. When non-null, overrides the runner's built-in default
   * (`xyz.block.trailblaze.agent.TrailblazeRunner.DEFAULT_MAX_STEPS`). Surfaced as
   * `--max-llm-calls` on the CLI so users on metered or expensive providers can cut off a
   * stuck self-heal loop before it racks up many round trips.
   *
   * The cap is per [xyz.block.trailblaze.yaml.PromptStep]/objective, not per trail — a trail
   * with three prompt blocks runs the inner loop up to 3 × maxLlmCalls times in the worst
   * case. The runner's existing cycle/stuck detection still trips earlier when the LLM
   * spins on the same tool call.
   *
   * Not honored by [AgentImplementation.MULTI_AGENT_V3], which manages its own iteration
   * budget internally. The `init` block rejects the combination at construction time rather
   * than silently dropping the cap, so direct RPC callers and tests can't bypass the
   * invariant the CLI also enforces.
   */
  val maxLlmCalls: Int? = null,

  /**
   * CLI-supplied memory seeds — entries passed via `--memory KEY=VAL` flags. Applied
   * AFTER the trail YAML's `config.memory:` block (so CLI overrides YAML on the same key)
   * and BEFORE any tool runs. Empty by default. Distinct from [memorySnapshot], which
   * carries already-populated host memory through to a sub-trail RPC dispatch.
   *
   * Values are logged in cleartext via [xyz.block.trailblaze.AgentMemory.remember] and
   * persist into the [xyz.block.trailblaze.logs.model.SessionStatus.Started] snapshot.
   * For passwords / tokens / API keys, use [initialMemorySensitiveSeeds] (CLI `--secret`)
   * which routes through `rememberSensitive` and is excluded from the session-start
   * snapshot.
   *
   * Appended after [maxLlmCalls] (rather than inserted alongside [memorySnapshot]) so
   * existing positional `componentN` accessors and binary-compatibility baselines for
   * earlier fields stay stable.
   */
  val initialMemorySeeds: Map<String, String> = emptyMap(),

  /**
   * CLI-supplied sensitive memory seeds — entries passed via `--secret KEY=VAL` flags.
   * Same composition order as [initialMemorySeeds] (applied after the trail YAML's
   * `config.memory:` defaults and after [initialMemorySeeds]), but routed through
   * [xyz.block.trailblaze.AgentMemory.rememberSensitive] so values are redacted in
   * `Console.log`, excluded from the scripting envelope, and omitted from the
   * [xyz.block.trailblaze.logs.model.SessionStatus.Started.resolvedInitialMemory]
   * snapshot persisted to session logs. Keys still appear in
   * [xyz.block.trailblaze.logs.model.SessionStatus.Started.sensitiveMemoryKeys] so
   * replay knows to expect them.
   */
  val initialMemorySensitiveSeeds: Map<String, String> = emptyMap(),
) : RpcRequest<RunYamlResponse> {
  init {
    require(awaitCompletion || memorySnapshot.isEmpty()) {
      "RunYamlRequest with awaitCompletion=false cannot carry a memorySnapshot — memory " +
        "sync requires a round-trip. Either set awaitCompletion=true or send empty memory."
    }
    require(maxLlmCalls == null || maxLlmCalls > 0) {
      "maxLlmCalls must be a positive integer when set, was $maxLlmCalls."
    }
    require(maxLlmCalls == null || agentImplementation != AgentImplementation.MULTI_AGENT_V3) {
      "maxLlmCalls is not honored by AgentImplementation.MULTI_AGENT_V3; pass null or use " +
        "AgentImplementation.TRAILBLAZE_RUNNER."
    }
  }

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
