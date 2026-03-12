package xyz.block.trailblaze.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.TwoTierAgentConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.util.Console

/**
 * Controls which tools are exposed to MCP clients.
 *
 * - [FULL]: All tools registered (device management, TestRail, Buildkite, primitive tools, etc.)
 * - [MINIMAL]: Only high-level tools: device, blaze, verify, ask, trail.
 *   Designed for external MCP clients (Claude Code, Goose) that should use
 *   Trailblaze as a black-box automation engine.
 */
enum class McpToolProfile {
  /** All tools — internal use, test authoring, full access. */
  FULL,

  /** Only device, blaze, verify, ask, trail — for external MCP clients. */
  MINIMAL;

  companion object {
    /** Tool names exposed in MINIMAL mode. */
    val MINIMAL_TOOL_NAMES = setOf("device", "blaze", "verify", "ask", "trail")
  }
}

/**
 * Session context for MCP tools.
 *
 * Maintains per-session state including:
 * - Operating mode (client_agent, trailblaze_agent, runner)
 * - Screenshot and view hierarchy configuration
 * - Progress notification tracking
 *
 * Configuration can be changed at runtime via MCP tools.
 */
class TrailblazeMcpSessionContext(
  @Volatile var mcpServerSession: ServerSession?, // Nullable and mutable to handle race condition during initialization
  val mcpSessionId: McpSessionId,
  /**
   * The MCP progress token from the client's request (_meta.progressToken).
   * Used to correlate notifications/progress back to the original request.
   * In Streamable HTTP, this is also used as the relatedRequestId to embed
   * notifications in the response (when client doesn't open SSE stream).
   * This is NOT related to Trailblaze session IDs.
   */
  var mcpProgressToken: ProgressToken? = null,

  // --- Session Configuration ---

  /**
   * Current operating mode. Determines which tools are available.
   * Can be changed at runtime via set_mode tool.
   */
  @Volatile var mode: TrailblazeMcpMode = TrailblazeMcpMode.TRAILBLAZE_AS_AGENT,

  /**
   * How screenshots are included in tool results.
   * - NONE: Screenshots not auto-included (use getScreenshot tool)
   * - IMAGE_CONTENT: Use MCP ImageContent type
   * - BASE64_TEXT: Include as base64-encoded string
   */
  var screenshotFormat: ScreenshotFormat = ScreenshotFormat.NONE,

  /**
   * Whether to automatically include screenshots after actions.
   * When true, device interaction tools (tap, swipe, etc.) include
   * a screenshot in the result. Defaults to false to minimize tokens.
   */
  var autoIncludeScreenshotAfterAction: Boolean = false,

  /**
   * Default verbosity level for view hierarchy responses.
   * Can be overridden per-call in view hierarchy tools.
   */
  var viewHierarchyVerbosity: ViewHierarchyVerbosity = ViewHierarchyVerbosity.MINIMAL,

  /**
   * Agent implementation to use in TRAILBLAZE_AS_AGENT mode.
   *
   * - DIRECT: New Koog-based agent with SamplingSource abstraction
   * - MCP_SAMPLING: Original SubagentOrchestrator (requires MCP client sampling)
   * - AUTO: Automatically select best available (recommended)
   *
   * Defaults to AUTO for gradual migration.
   */
  @Volatile var llmCallStrategy: LlmCallStrategy = LlmCallStrategy.DIRECT,

  /**
   * Whether to include primitive tools (tap, swipe, inputText, getScreenshot, etc.).
   *
   * - `false` (default): External MCP clients only see high-level tools like `runPrompt()`,
   *   `switchToolSet()`, `runTrail()`. This keeps their context window small.
   *
   * - `true`: Internal self-connection (recursive MCP) sees all primitive tools.
   *   The Trailblaze agent uses these to execute low-level UI interactions.
   *
   * External clients should NOT enable this - it defeats the purpose of Trailblaze
   * abstracting away UI state management.
   */
  @Volatile var includePrimitiveTools: Boolean = false,

  /**
   * Tool profile controlling which tools are exposed to the MCP client.
   *
   * - `FULL` (default): All tools registered (device management, TestRail, Buildkite, etc.)
   * - `MINIMAL`: Only device, blaze, verify, ask, trail. For external MCP clients.
   */
  var toolProfile: McpToolProfile = McpToolProfile.FULL,

  /**
   * Transport mode for internal agent tool execution.
   *
   * - `DIRECT` (default): In-process execution via DirectMcpToolExecutor. Fastest.
   * - `MCP_SSE`: Full MCP protocol via self-connection. Architectural purity.
   *
   * Both use the same MCP-compatible interface - difference is transport layer.
   */
  @Volatile var agentToolTransport: AgentToolTransport = AgentToolTransport.MCP_IN_PROCESS,

  /**
   * Agent implementation to use in TRAILBLAZE_AS_AGENT mode.
   *
   * - TRAILBLAZE_RUNNER: Stable YAML-based TrailblazeRunner.kt
   * - TWO_TIER_AGENT: Koog-based DirectMcpAgent with inner/outer agents
   *
   * Defaults to [AgentImplementation.DEFAULT]. Use setAgentImplementation(TWO_TIER_AGENT)
   * to opt into the two-tier architecture.
   */
  @Volatile var agentImplementation: AgentImplementation = AgentImplementation.DEFAULT,

  /**
   * Maximum iterations per objective for DirectMcpAgent.
   * Prevents runaway execution. Default is 50 (production), tests may use lower values like 10.
   */
  var maxIterationsPerObjective: Int = 50,

  /**
   * The device ID associated with this MCP session.
   * Set when connectToDevice is called, cleared on endSession.
   * Used for cancellation propagation when the MCP client disconnects.
   */
  var associatedDeviceId: TrailblazeDeviceId? = null,

  /**
   * The MCP client name from the initialize handshake.
   * Used for tracking which client (Goose, VS Code, Cursor, etc.) is making sampling requests.
   * Set during session initialization from clientInfo.name.
   */
  var mcpClientName: String? = null,

  /**
   * Configuration for the two-tier agent architecture.
   *
   * When enabled, uses separate LLM models for:
   * - Inner agent (cheap, fast vision model for screen analysis)
   * - Outer agent (expensive reasoning model for planning)
   *
   * When null or disabled, falls back to the single-agent DirectMcpAgent pattern.
   *
   * @see TwoTierAgentConfig
   */
  var twoTierAgentConfig: TwoTierAgentConfig? = null,
) {

  // ─────────────────────────────────────────────────────────────────────────────
  // Trail Recording State
  //
  // All recording state is guarded by [recordingLock] since multiple MCP tool
  // handlers (DeviceManagerToolSet, TrailTool, StepToolSet) can call into these
  // methods concurrently.
  // ─────────────────────────────────────────────────────────────────────────────

  private val recordingLock = Any()

  /**
   * Name of the current trail being recorded.
   * Null if recording hasn't been explicitly named yet (implicit recording from device()).
   */
  private var currentTrailName: String? = null

  /**
   * Whether recording is active. Always true after device() or trail(action=START).
   * Steps are captured to [recordedSteps] when this is true.
   */
  private var isRecording: Boolean = false

  /**
   * Steps recorded during this session.
   * Populated by blaze(), verify(), ask() calls.
   */
  private val recordedSteps: MutableList<RecordedStep> = mutableListOf()

  /**
   * Starts trail recording with an explicit name.
   * Called by trail(action=START, name="...").
   */
  fun startTrailRecording(name: String) = synchronized(recordingLock) {
    currentTrailName = name
    isRecording = true
    recordedSteps.clear()
    Console.log("[Recording] Started trail: $name")
  }

  /**
   * Starts implicit (unnamed) recording.
   * Called automatically by device() connection.
   * User can name and save later with trail(action=SAVE, name="...").
   */
  fun startImplicitRecording() = synchronized(recordingLock) {
    if (!isRecording) {
      isRecording = true
      recordedSteps.clear()
      Console.log("[Recording] Implicit recording started (save with trail(action=SAVE, name='...'))")
    }
  }

  /**
   * Records a step taken during the session.
   * Called by blaze(), verify(), ask() in StepTool.
   */
  fun recordStep(step: RecordedStep) = synchronized(recordingLock) {
    if (isRecording) {
      recordedSteps.add(step)
      Console.log("[Recording] Step ${recordedSteps.size}: ${step.type} - ${step.input.take(50)}...")
    }
  }

  /**
   * Returns all recorded steps (snapshot).
   */
  fun getRecordedSteps(): List<RecordedStep> = synchronized(recordingLock) {
    recordedSteps.toList()
  }

  /**
   * Returns the current trail name, or null if unnamed.
   */
  fun getCurrentTrailName(): String? = synchronized(recordingLock) { currentTrailName }

  /**
   * Returns whether recording is active.
   */
  fun isRecordingActive(): Boolean = synchronized(recordingLock) { isRecording }

  /**
   * Clears recording state without saving.
   * Called by trail(action=END) when discarding.
   */
  fun clearRecording() = synchronized(recordingLock) {
    currentTrailName = null
    isRecording = false
    recordedSteps.clear()
    Console.log("[Recording] Cleared")
  }

  /**
   * Cancels the progress notification scope.
   * Call when the session is being destroyed to prevent coroutine leaks.
   */
  fun close() {
    sendProgressNotificationsScope.cancel()
  }

  /**
   * Sets the trail name for saving (used when naming an implicit recording).
   */
  fun setTrailName(name: String) = synchronized(recordingLock) {
    currentTrailName = name
  }

  val sendProgressNotificationsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val progressCount = AtomicInteger(0)

  /**
   * Callback invoked when mode changes. Used to trigger tool re-registration.
   */
  var onModeChanged: ((TrailblazeMcpMode) -> Unit)? = null

  /**
   * Callback invoked when the MCP session is closed (client disconnected).
   * Used to cancel any running automation on the associated device.
   */
  var onSessionClosed: (() -> Unit)? = null
  
  /**
   * Custom SSE notification sender that bypasses the SDK's transport.
   * When set, notifications are ALSO sent through this channel in addition
   * to the SDK's session.notification(). This enables progress notifications
   * in Streamable HTTP mode where the SDK's transport doesn't support SSE GET.
   *
   * Uses AtomicReference for thread safety — set from SSE connection handler,
   * cleared on disconnect, invoked from progress dispatch coroutines.
   */
  private val _customSseNotificationSender = AtomicReference<((String) -> Unit)?>(null)
  var customSseNotificationSender: ((String) -> Unit)?
    get() = _customSseNotificationSender.get()
    set(value) { _customSseNotificationSender.set(value) }

  /**
   * Changes the operating mode and triggers tool re-registration.
   */
  fun setModeAndNotify(newMode: TrailblazeMcpMode) {
    if (mode != newMode) {
      mode = newMode
      onModeChanged?.invoke(newMode)
    }
  }

  /**
   * Associates a device with this session.
   * Called when connectToDevice succeeds.
   */
  fun setAssociatedDevice(deviceId: TrailblazeDeviceId) {
    associatedDeviceId = deviceId
  }

  /**
   * Clears the device association.
   * Called when endSession is called or when the session is closed.
   */
  fun clearAssociatedDevice() {
    associatedDeviceId = null
  }

  /**
   * Returns a human-readable description of the current session configuration.
   */
  fun describeConfiguration(): String = buildString {
    appendLine("Mode: ${mode.name}")
    appendLine("Screenshot format: ${screenshotFormat.name}")
    appendLine("Auto-include screenshot after action: $autoIncludeScreenshotAfterAction")
    appendLine("View hierarchy verbosity: ${viewHierarchyVerbosity.name}")
    appendLine("LLM call strategy: ${llmCallStrategy.name}")
    appendLine("Agent tool transport: ${agentToolTransport.name}")
    appendLine("Agent implementation: ${agentImplementation.name}")
    appendLine("Include primitive tools: $includePrimitiveTools")
    appendLine("Tool profile: ${toolProfile.name}")
    twoTierAgentConfig?.let { config ->
      appendLine("Two-tier agent: ${if (config.enabled) "ENABLED" else "disabled"}")
      if (config.enabled) {
        appendLine("  Inner model: ${config.innerModel.modelId}")
        appendLine("  Outer model: ${config.outerModel.modelId}")
      }
    } ?: appendLine("Two-tier agent: not configured")
  }

  /**
   * Flag to track if we've warned about missing progress token.
   * Prevents spamming logs with the same warning.
   */
  private var warnedAboutMissingProgressToken = false

  /**
   * Sends a DETERMINATE progress notification to the MCP client.
   * Use this when you know the total number of steps (shows progress bar).
   *
   * @param message Description of current progress
   * @param current Current step number (0-indexed or 1-indexed, client will display)
   * @param total Total number of steps
   */
  fun sendProgressMessage(message: String, current: Int, total: Int) {
    sendProgressInternal(message, progress = current, total = total)
  }
  
  /**
   * Sends an INDETERMINATE progress notification to the MCP client.
   * Use this when you don't know how long the operation will take (shows spinner).
   *
   * @param message Description of current progress
   */
  fun sendIndeterminateProgressMessage(message: String) {
    sendProgressInternal(message, progress = null, total = null)
  }
  
  private fun sendProgressInternal(message: String, progress: Int?, total: Int?) {
    val token = mcpProgressToken
    val session = mcpServerSession

    // Compute progress value once to ensure both notification channels get the same value
    val progressValue = progress ?: progressCount.getAndIncrement()

    // Always try to send through custom SSE channel first (bypasses SDK transport limitations)
    customSseNotificationSender?.let { sender ->
      try {
        // Build JSON-RPC notification for progress
        // Use JsonPrimitive to properly escape the message for JSON embedding
        // (handles quotes, newlines, tabs, backslashes, control chars, etc.)
        val escapedMessage =
          JsonPrimitive(message).toString().removeSurrounding("\"")

        // Extract the actual token value from ProgressToken (typealias for RequestId)
        // using sealed interface matching instead of brittle toString() parsing
        val tokenJsonValue = when (token) {
          is RequestId.StringId -> JsonPrimitive(token.value).toString()
          is RequestId.NumberId -> token.value.toString()
          null -> "\"\""
        }

        // Build params based on determinate vs indeterminate
        // MCP Spec: progress is required, total is optional (present = determinate, absent = indeterminate)
        val totalPart = if (total != null) ""","total":$total""" else ""

        // Standard JSON-RPC 2.0 notification format (no extra fields allowed at top level)
        // The progressToken in params is used by clients to correlate with the original request
        val jsonRpcNotification = if (token != null) {
          """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progressToken":$tokenJsonValue,"progress":$progressValue$totalPart,"message":"$escapedMessage"}}"""
        } else {
          """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info","data":"[progress] $escapedMessage","logger":"trailblaze"}}"""
        }
        sender(jsonRpcNotification)
        Console.log("[MCP Progress] Sent via custom SSE channel: $message (progress=$progressValue${if (total != null) "/$total" else ""})")
      } catch (e: Exception) {
        Console.error("[MCP Progress ERROR] Failed to send via custom SSE: ${e.javaClass.simpleName}: ${e.message}")
      }
    }

    // Also try SDK's notification (may work if client has SSE stream open)
    if (session == null) {
      if (customSseNotificationSender == null) {
        Console.log("[MCP Progress WARNING] mcpServerSession is null and no custom SSE - cannot send notification")
      }
      return
    }

    if (token != null) {
      sendProgressNotificationsScope.launch {
        try {
          session.notification(
            ProgressNotification(
              ProgressNotificationParams(
                progress = progressValue.toDouble(),
                progressToken = token,
                total = total?.toDouble(),  // null = indeterminate, value = determinate
                message = message,
              ),
            ),
          )
        } catch (e: Exception) {
          // Silently fail - custom SSE channel is primary delivery method
        }
      }
    } else {
      // Fall back to logging notification - doesn't require progressToken
      sendProgressNotificationsScope.launch {
        try {
          session.notification(
            LoggingMessageNotification(
              LoggingMessageNotificationParams(
                level = LoggingLevel.Info,
                data = JsonPrimitive("[progress] $message"),
                logger = "trailblaze",
              ),
            ),
          )
        } catch (e: Exception) {
          // Silently fail - custom SSE channel is primary delivery method
        }
      }
    }
  }

  /**
   * Sends a progress notification with explicit progress value.
   * 
   * @deprecated Use [sendProgressMessage] for determinate progress or 
   * [sendIndeterminateProgressMessage] for indeterminate progress.
   */
  @Deprecated(
    message = "Use sendProgressMessage(message, current, total) for determinate or sendIndeterminateProgressMessage(message) for indeterminate",
    replaceWith = ReplaceWith("sendProgressMessage(message, progress, total?.toInt() ?: progress)")
  )
  fun sendIndeterminateProgressMessage(progress: Int, message: String, total: Double? = null) {
    if (total != null) {
      sendProgressMessage(message, progress, total.toInt())
    } else {
      sendProgressInternal(message, progress = progress, total = null)
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Trail Recording Data Types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Type of recorded step.
 */
enum class RecordedStepType {
  /** An action step - "Tap the login button" */
  STEP,
  /** A verification step - "The welcome message is visible" */
  VERIFY,
  /** A question step - "What's the current balance?" */
  ASK,
}

/**
 * A recorded step in a trail.
 *
 * Captures the natural language input from the MCP client,
 * the tool calls made by the inner agent, and the result.
 */
@Serializable
data class RecordedStep(
  /** Type of step (STEP, VERIFY, ASK) */
  val type: RecordedStepType,

  /** The natural language input (goal/assertion/question) */
  val input: String,

  /** Tool calls made by the inner agent to achieve this step */
  val toolCalls: List<RecordedToolCall> = emptyList(),

  /** The result returned to the MCP client */
  val result: String,

  /** Whether this step succeeded */
  val success: Boolean,

  /** Timestamp when this step was executed */
  val timestamp: Instant = Clock.System.now(),
)

/**
 * A tool call made by the inner agent during step execution.
 */
@Serializable
data class RecordedToolCall(
  /** Name of the tool (e.g., "tapOnElementByNodeId") */
  val toolName: String,

  /** Arguments passed to the tool */
  val args: Map<String, String>,

  /** Result of the tool call */
  val result: String? = null,
)
