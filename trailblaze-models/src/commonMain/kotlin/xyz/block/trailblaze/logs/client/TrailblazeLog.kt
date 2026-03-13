package xyz.block.trailblaze.logs.client

import ai.koog.prompt.message.Message
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.llm.LlmRequestUsageAndCost
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.logs.model.HasAgentTaskStatus
import xyz.block.trailblaze.logs.model.HasDuration
import xyz.block.trailblaze.logs.model.HasPromptStep
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.HasTraceId
import xyz.block.trailblaze.logs.model.HasTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.logs.model.TrailblazeLlmMessage
import xyz.block.trailblaze.agent.AgentTier
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.AgentToolTransport
import xyz.block.trailblaze.mcp.LlmCallStrategy
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.PromptStep

@Serializable
sealed interface TrailblazeLog {
  val session: SessionId
  val timestamp: Instant

  @Serializable
  data class TrailblazeAgentTaskStatusChangeLog(
    override val agentTaskStatus: AgentTaskStatus,
    override val durationMs: Long = agentTaskStatus.statusData.totalDurationMs,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasDuration

  @Serializable
  data class TrailblazeSessionStatusChangeLog(
    val sessionStatus: SessionStatus,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog

  /**
   * Context about how an LLM request was made.
   *
   * Groups together metadata about the agent architecture and execution strategy
   * for better observability into the AI pipeline.
   */
  @Serializable
  data class LlmRequestContext(
    /** Which agent architecture was used (TRAILBLAZE_RUNNER or TWO_TIER_AGENT) */
    val agentImplementation: AgentImplementation,
    /** How the LLM was called (DIRECT or MCP_SAMPLING) */
    val llmCallStrategy: LlmCallStrategy,
    /** Which tier of the two-tier agent made this request (INNER for screen analysis, OUTER for planning) */
    val agentTier: AgentTier? = null,
  )

  @Serializable
  data class TrailblazeLlmRequestLog(
    override val agentTaskStatus: AgentTaskStatus,
    val viewHierarchy: ViewHierarchyTreeNode,
    val viewHierarchyFiltered: ViewHierarchyTreeNode? = null,
    val trailblazeNodeTree: TrailblazeNode? = null,
    val instructions: String,
    val trailblazeLlmModel: TrailblazeLlmModel,
    val llmMessages: List<TrailblazeLlmMessage>,
    val llmResponse: List<Message.Response>,
    val actions: List<Action>,
    val toolOptions: List<TrailblazeToolDescriptor>,
    val llmRequestUsageAndCost: LlmRequestUsageAndCost? = null,
    override val screenshotFile: String?,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    /**
     * Context about how this LLM request was made.
     *
     * Includes agent architecture, LLM call strategy, and tier info.
     */
    val requestContext: LlmRequestContext? = null,
    /**
     * Human-readable label identifying which agent/component made this LLM request.
     *
     * Displayed in the log viewer card title (e.g., "LLM Request: Planner" instead of
     * just "LLM Request"). Examples: "Planner", "Screen Analyzer", "Subtask Progress",
     * "Reflection", "Trail Executor".
     *
     * Null for backward compatibility with older logs.
     */
    val llmRequestLabel: String? = null,
  ) : TrailblazeLog,
    HasAgentTaskStatus,
    HasTraceId,
    HasScreenshot,
    HasDuration {

    @Serializable
    data class Action(
      val name: String,
      val args: JsonObject,
    )
  }

  @Serializable
  data class MaestroCommandLog(
    val maestroCommandJsonObj: JsonObject,
    override val traceId: TraceId?,
    val successful: Boolean,
    val trailblazeToolResult: TrailblazeToolResult,
    override val session: SessionId,
    override val timestamp: Instant,
    override val durationMs: Long,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  /**
   * Log entry for an accessibility action executed through `AccessibilityDeviceManager`,
   * completely independent of Maestro. This is the accessibility equivalent of
   * [MaestroCommandLog] — used for tracing and debugging trail playback.
   *
   * The [actionJsonObj] contains the kotlinx-serialized representation of the
   * `AccessibilityAction`, providing the same level of insight as Maestro command
   * logging without the Maestro dependency.
   *
   * @deprecated Replaced by [AgentDriverLog] which provides unified logging with screenshot
   * overlays. Kept for backward-compatible deserialization of old log files.
   */
  @Deprecated("Use AgentDriverLog instead for new log entries")
  @Serializable
  data class AccessibilityActionLog(
    val actionJsonObj: JsonObject,
    val actionDescription: String,
    override val traceId: TraceId?,
    val successful: Boolean,
    val trailblazeToolResult: TrailblazeToolResult,
    override val session: SessionId,
    override val timestamp: Instant,
    override val durationMs: Long,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  @Serializable
  @SerialName("xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroDriverLog")
  data class AgentDriverLog(
    val viewHierarchy: ViewHierarchyTreeNode?,
    val trailblazeNodeTree: TrailblazeNode? = null,
    override val screenshotFile: String?,
    val action: AgentDriverAction,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    override val traceId: TraceId? = null,
  ) : TrailblazeLog,
    HasScreenshot,
    HasDuration,
    HasTraceId

  @Serializable
  data class DelegatingTrailblazeToolLog(
    val toolName: String,
    override val trailblazeTool: TrailblazeTool,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId?,
    val executableTools: List<TrailblazeTool>,
  ) : TrailblazeLog,
    HasTraceId,
    HasTrailblazeTool

  @Serializable
  data class TrailblazeToolLog(
    override val trailblazeTool: TrailblazeTool,
    val toolName: String,
    val successful: Boolean,
    override val traceId: TraceId?,
    val exceptionMessage: String? = null,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    /** Whether this tool is recordable for YAML session recordings. Defaults to true for backward compatibility. */
    val isRecordable: Boolean = true,
  ) : TrailblazeLog,
    HasTrailblazeTool,
    HasTraceId,
    HasDuration

  @Serializable
  data class ObjectiveStartLog(
    override val promptStep: PromptStep,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasPromptStep

  @Serializable
  data class ObjectiveCompleteLog(
    override val promptStep: PromptStep,
    val objectiveResult: AgentTaskStatus,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasPromptStep

  @Serializable
  data class AttemptAiFallbackLog(
    override val promptStep: PromptStep,
    override val session: SessionId,
    override val timestamp: Instant,
    val recordingResult: PromptRecordingResult.Failure,
  ) : TrailblazeLog,
    HasPromptStep

  /**
   * Log entry for a snapshot captured via TakeSnapshotTool.
   *
   * Snapshots are user-initiated screen captures intended for documentation,
   * debugging, or compliance purposes. They contain clean (unannotated) screenshots
   * along with the view hierarchy at the time of capture.
   */
  @Serializable
  data class TrailblazeSnapshotLog(
    /**
     * Optional user-provided display name for this snapshot.
     *
     * This is a human-readable label shown in the snapshot viewer UI (e.g., "login_screen",
     * "payment_confirmation"). It does NOT affect the actual filename on disk.
     *
     * If null, the snapshot viewer will use the [screenshotFile] name as the display label.
     */
    val displayName: String?,

    /**
     * The filename of the screenshot file on disk.
     *
     * Auto-generated with format: `{sessionId}_{timestamp}.{extension}`
     * This is the actual file path used to locate the PNG in the logs directory.
     */
    override val screenshotFile: String,

    val viewHierarchy: ViewHierarchyTreeNode,
    val trailblazeNodeTree: TrailblazeNode? = null,
    override val deviceWidth: Int,
    override val deviceHeight: Int,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId? = null,
  ) : TrailblazeLog,
    HasScreenshot,
    HasTraceId

  // region MCP Agent Logs

  /**
   * Log entry for an MCP agent run (when Trailblaze acts as agent).
   *
   * Captures the full lifecycle of an agent run including objective, transport mode,
   * LLM strategy, iterations, and final result.
   */
  @Serializable
  data class McpAgentRunLog(
    /** The objective/prompt given to the agent */
    val objective: String,

    /** Transport mode used for tool execution */
    val transportMode: AgentToolTransport,

    /** Strategy used for LLM completions */
    val llmStrategy: LlmCallStrategy,

    /** Number of agent iterations (LLM calls) */
    val iterationCount: Int,

    /** Total number of tools executed */
    val toolCallCount: Int,

    /** Whether the agent run succeeded */
    val successful: Boolean,

    /** Summary or error message from the agent */
    val resultMessage: String,

    /** Names of actions taken (e.g., "tapOnPoint", "inputText") */
    val actionsTaken: List<String>,

    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  /**
   * Log entry for an individual MCP agent iteration (one LLM call + optional tool execution).
   */
  @Serializable
  data class McpAgentIterationLog(
    /** 1-based iteration number within the agent run */
    val iterationNumber: Int,

    /** Transport mode used for tool execution */
    val transportMode: AgentToolTransport,

    /** The tool called (null if completion/failure response) */
    val toolName: String?,

    /** Arguments passed to the tool (null if no tool called) */
    val toolArgs: JsonObject?,

    /** Whether the tool execution succeeded (null if no tool called) */
    val toolSucceeded: Boolean?,

    /** LLM completion text (truncated if long) */
    val llmCompletion: String?,

    /** Type of response: "tool_call", "complete", "failed", "invalid" */
    val responseType: String,

    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  /**
   * Log entry for an MCP sampling request (LLM completion via MCP or direct).
   *
   * This is emitted for each LLM call made during agent execution.
   */
  @Serializable
  data class McpSamplingLog(
    /** Strategy used for this sampling request */
    val llmStrategy: LlmCallStrategy,

    /** The system prompt sent to the LLM */
    val systemPrompt: String,

    /** The user message sent to the LLM (may be truncated) */
    val userMessage: String,

    /** The LLM completion received */
    val completion: String,

    /** Whether a screenshot was included in the request */
    val includedScreenshot: Boolean,

    /** Token usage if available */
    val usageAndCost: LlmRequestUsageAndCost?,

    /** Model used for completion (if known) */
    val modelName: String?,

    /** Whether the sampling succeeded */
    val successful: Boolean,

    /** Error message if failed */
    val errorMessage: String?,

    // --- Screen Context (optional, for debugging) ---

    /** View hierarchy at time of sampling (null if not available) */
    val viewHierarchy: ViewHierarchyTreeNode? = null,

    /** Filtered view hierarchy (interactable elements only) */
    val viewHierarchyFiltered: ViewHierarchyTreeNode? = null,

    /** Device screen width in pixels (0 if not available) */
    override val deviceWidth: Int = 0,

    /** Device screen height in pixels (0 if not available) */
    override val deviceHeight: Int = 0,

    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration,
    HasScreenshot {
    /** McpSamplingLog doesn't save screenshot to a file, but implements HasScreenshot for UI consistency */
    override val screenshotFile: String? = null
  }

  /**
   * Log entry for MCP tool execution during agent runs.
   *
   * This is emitted for each tool executed by the MCP agent (DirectMcpAgent or KoogMcpAgent).
   */
  @Serializable
  data class McpAgentToolLog(
    /** Transport mode used for this tool call */
    val transportMode: AgentToolTransport,

    /** Name of the tool executed */
    val toolName: String,

    /** Arguments passed to the tool */
    val toolArgs: JsonObject,

    /** Whether the tool execution succeeded */
    val successful: Boolean,

    /** Tool output or error message */
    val resultOutput: String,

    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
    override val traceId: TraceId?,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  // endregion

  // region MCP Client ↔ Server Conversation Logs
  //
  // These logs capture the conversation between an MCP client (outer agent like Goose)
  // and Trailblaze's MCP server. They are logged SEPARATELY so you can see:
  //
  // 1. McpToolCallRequestLog  - What the outer agent asked us to do
  // 2. (Inner loop activity)  - LLM calls, screen analysis, etc.
  // 3. McpToolCallResponseLog - What we sent back to the outer agent
  //
  // This separation is critical for debugging TWO_TIER_AGENT mode where
  // the inner loop's decisions directly influence the response.

  /**
   * Log entry for an INCOMING MCP tool call request from an external MCP client.
   *
   * Logged IMMEDIATELY when a request arrives, BEFORE any processing happens.
   * This captures what the outer agent (Goose, Claude Desktop, etc.) is asking us to do.
   *
   * Pair with [McpToolCallResponseLog] to see the full request/response cycle.
   */
  @Serializable
  data class McpToolCallRequestLog(
    /** Name of the tool being invoked by the MCP client */
    val toolName: String,

    /** Arguments passed by the MCP client (full JSON) */
    val toolArgs: JsonObject,

    /** MCP session ID for the calling client */
    val mcpSessionId: String,

    /** Trace ID to correlate request with response and inner loop activity */
    override val traceId: TraceId,

    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTraceId

  /**
   * Log entry for an OUTGOING MCP tool call response back to an external MCP client.
   *
   * Logged AFTER processing completes, capturing what we sent back to the outer agent.
   * The [traceId] correlates this with the original [McpToolCallRequestLog] and any
   * inner loop activity (LLM calls, screen analysis) that happened in between.
   */
  @Serializable
  data class McpToolCallResponseLog(
    /** Name of the tool that was invoked */
    val toolName: String,

    /** MCP session ID for the calling client */
    val mcpSessionId: String,

    /** Whether the tool execution succeeded */
    val successful: Boolean,

    /** Tool result or error message as JSON (for better readability in logs) */
    val resultSummary: JsonElement,

    /** Full error message if failed */
    val errorMessage: String? = null,

    /** Trace ID to correlate response with request and inner loop activity */
    override val traceId: TraceId,

    /** How long the tool execution took */
    override val durationMs: Long,

    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  /**
   * Log entry for an `ask()` call — the outer agent asking a question about the screen.
   *
   * Ask calls are for situational awareness (not actions or verifications) and are
   * excluded from trail file generation. They appear in the session viewer for
   * debugging and understanding agent reasoning.
   */
  @Serializable
  data class McpAskLog(
    /** The question asked by the outer agent */
    val question: String,

    /** The answer derived from screen analysis */
    val answer: String?,

    /** Summary of what's on screen */
    val screenSummary: String?,

    /** Error message if the ask failed */
    val errorMessage: String? = null,

    override val traceId: TraceId?,
    override val durationMs: Long,
    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasTraceId,
    HasDuration

  // endregion

  // region Progress Reporting Logs (Phase 6)
  //
  // These logs capture progress events from the Multi-Agent V3 implementation.
  // They provide real-time visibility into:
  // - Execution lifecycle (start, complete)
  // - Step progress (started, completed)
  // - Subtask progress (from task decomposition)
  // - Reflection and self-correction
  // - Exception handling (popups, ads, errors)
  // - Memory operations (facts stored/recalled)

  /**
   * Log entry for a progress event during execution.
   *
   * This wraps [xyz.block.trailblaze.agent.TrailblazeProgressEvent] for logging purposes,
   * capturing all progress-related events in the session log stream.
   *
   * ## Event Types Logged
   *
   * - **Execution lifecycle**: Start/complete of the overall execution
   * - **Step progress**: Individual step start/complete with timing
   * - **Subtask progress**: Task decomposition progress (Phase 3)
   * - **Reflection**: Self-assessment and course corrections (Phase 2)
   * - **Exceptions**: Recovery from popups, ads, errors (Phase 1)
   * - **Memory**: Facts stored/recalled for cross-app workflows (Phase 4)
   */
  @Serializable
  data class TrailblazeProgressLog(
    /** The type of progress event (e.g., "ExecutionStarted", "StepCompleted") */
    val eventType: String,

    /** Human-readable description of the event */
    val description: String,

    /** Device ID for telemetry correlation (SessionId → TrailblazeDeviceId → TraceId hierarchy) */
    val deviceId: TrailblazeDeviceId? = null,

    /** Whether this event indicates success (null if not applicable) */
    val success: Boolean? = null,

    /** Associated step or subtask index (null if not applicable) */
    val stepIndex: Int? = null,

    /** Total steps or subtasks (null if not applicable) */
    val totalSteps: Int? = null,

    /** Progress percentage (0-100, null if not applicable) */
    val progressPercent: Int? = null,

    /** Duration in milliseconds (for completion events) */
    override val durationMs: Long = 0L,

    /** Additional event-specific data as JSON */
    val eventData: JsonObject? = null,

    override val session: SessionId,
    override val timestamp: Instant,
  ) : TrailblazeLog,
    HasDuration

  // endregion
}
