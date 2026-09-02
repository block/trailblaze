package xyz.block.trailblaze.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.types.LoggingLevel
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.ProgressToken
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.TwoTierAgentConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.models.McpSessionId
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.util.Console

/**
 * The MCP `clientInfo.name` value the Trailblaze CLI sends on its initialize
 * handshake. Constant lives here (next to [TrailblazeMcpSessionContext.mcpClientName]
 * which it filters against) rather than in the `host` module so server-side
 * code can reference it without a `server -> host` dependency. The CLI's own
 * `CliMcpClient.CLIENT_NAME` is held to match this value via a unit-test
 * assertion that would fail on drift.
 *
 * `mcpClientName != TRAILBLAZE_CLI_CLIENT_NAME` is the discriminator
 * [xyz.block.trailblaze.logs.server.TrailblazeMcpServer.pinMostRecentUnboundMcpSession]
 * uses to pick "real" MCP clients (Claude Desktop, Cursor, Goose) over the
 * CLI's own short-lived one-shot sessions.
 */
const val TRAILBLAZE_CLI_CLIENT_NAME: String = "TrailblazeCLI"

/** Default bound on how long a device move waits for another one to finish. */
private const val DEVICE_MOVE_WAIT_MS = 30_000L

/**
 * Wire names of the MCP session tools, referenced by the @Tool annotations in
 * their respective ToolSets and by the registration logic in
 * `TrailblazeMcpServer.registerTools`.
 *
 * Every MCP session gets one uniform tool surface: these session tools plus the
 * current target's driver-filtered TrailblazeTools (re-registered with a
 * `tools/list_changed` notification whenever the device or target changes).
 * There is no per-session tool profile — the former FULL/MINIMAL
 * `McpToolProfile` split was removed in favor of target-scoped registration.
 */
object McpToolNames {
  const val TOOL_DEVICE = "device"
  const val TOOL_STEP = "step"
  const val TOOL_ASK = "ask"
  const val TOOL_TRAIL = "trail"
  const val TOOL_TRAIL_EDIT = "trailEdit"
  const val TOOL_CONFIG = "config"
  const val TOOL_SESSION = "session"
  const val TOOL_TOOLS = "toolbox"
  const val TOOL_LOGCAT = "logcat"
  // Per-device target override — paired with TOOL_DEVICE in the device-binding
  // flow. The CLI's `device connect --target` / `device rebind --target` paths
  // call this tool to scope a target to the bound device. Removing it breaks
  // those commands with "Tool setSessionTargetForBoundDevice not found".
  const val TOOL_SET_SESSION_TARGET = "setSessionTargetForBoundDevice"
  // Paired with TOOL_DEVICE in the device-binding flow. The CLI's
  // `device connect` calls this tool right after binding so a co-resident
  // real-MCP-client session (Claude Desktop / Cursor / etc.) gets pinned
  // to the same device. Removing it breaks that call with
  // "Tool pinMostRecentUnboundMcpSession not found".
  const val TOOL_PIN_MCP_SESSION = "pinMostRecentUnboundMcpSession"
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
   * - MULTI_AGENT_V3: Koog-based multi-agent runner with inner/outer agents
   *
   * Defaults to [AgentImplementation.DEFAULT]. Use setAgentImplementation(MULTI_AGENT_V3)
   * to opt into the modern architecture.
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
   *
   * In a session with named bindings (see [namedDeviceBindings]) this is the ACTIVE device — the
   * one `switchDevice` last handed the session to. It stays the single value tool dispatch reads,
   * so the roster never introduces a second routing path.
   *
   * Note: a CLI-side workaround in `CliMcpClient.ensureDevice` re-issues
   * `device(action=PLATFORM, deviceId=…)` on every session reuse to defend
   * against this field drifting out of sync with `mcpBridge.selectedDeviceId`.
   * If the underlying drift is rooted in daemon-side bookkeeping rather than
   * a CLI-only concern, fixing it here (e.g. repopulating from the bridge in
   * the `addTool` handler when null but `selectedDeviceId` is non-null) would
   * let the CLI drop the rebind.
   *
   * `@Volatile` for cross-thread visibility: writes can come from the per-
   * session request handler (normal `device()` flow) AND from the daemon's
   * `pinMostRecentUnboundMcpSession` running on a different connect-command
   * coroutine, while reads happen on yet another thread when the pinned
   * client's next tool call enters `addTool`. Without volatility a freshly
   * pinned value can sit invisible in another core's cache.
   */
  @Volatile var associatedDeviceId: TrailblazeDeviceId? = null,

  /**
   * The MCP client name from the initialize handshake.
   * Used for tracking which client (Goose, VS Code, Cursor, etc.) is making sampling requests.
   * Set during session initialization from clientInfo.name.
   */
  var mcpClientName: String? = null,

  /**
   * Free-form description of where this session came from, captured from the
   * `X-Trailblaze-Origin` header on the first POST. The CLI passes its argv
   * (e.g. `snapshot -d android`); the desktop UI passes a fixed label like
   * `desktop-ui`. Surfaced in the device-busy error so users can see exactly
   * which command is currently driving the device.
   *
   * Null for clients that don't set the header (Goose, Claude Code, etc.) —
   * `mcpClientName` is enough identification for those.
   */
  var origin: String? = null,

  /**
   * Wall-clock timestamp of the most recent inbound HTTP touch for this
   * session — refreshed on every POST /mcp and SSE GET /mcp handler entry.
   *
   * Used by [xyz.block.trailblaze.logs.server.TrailblazeMcpServer.pinMostRecentUnboundMcpSession]
   * to pick the "most-recent unbound real-MCP-client session" when a shell
   * `trailblaze device connect` wants to also pin a co-resident MCP client
   * (Claude Desktop / Cursor / etc.) that hasn't called `device()` yet.
   *
   * Initialized to the construction timestamp so a session that hasn't yet
   * processed a request (race against the very first POST) still has a
   * comparable value. `@Volatile` for visibility under reads from a different
   * thread than the request handler — see TrailblazeMcpServer where pinning
   * may run on the connect-command coroutine while the request loop on
   * another thread is also writing.
   */
  @Volatile var lastActive: Instant = Clock.System.now(),

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
  // Session Capture State
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Callback to stop the active capture session (video + device logs).
   * Returns list of artifact descriptions (name, type, sizeBytes).
   * Set by session(action=START), cleared by session(action=STOP) or close().
   */
  var stopCaptureCallback: (() -> List<CaptureArtifactInfo>)? = null

  /** Simple artifact info returned by capture stop, avoiding dependency on trailblaze-capture. */
  data class CaptureArtifactInfo(val name: String, val type: String, val sizeBytes: Long)

  /**
   * Human-readable title for the current session.
   * Set by session(action=START, title="..."), used as default trail name when saving.
   */
  var sessionTitle: String? = null

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
   *
   * Consecutive tool calls with the same objective (input) are grouped into a single
   * recorded step with accumulated tool calls. This enables external agents to use
   * `blaze tool -o "Enter login credentials"` multiple times and have them produce
   * a single trail objective with a multi-tool recording.
   */
  fun recordStep(step: RecordedStep) = synchronized(recordingLock) {
    if (isRecording) {
      val lastStep = recordedSteps.lastOrNull()
      // Group consecutive STEP entries with the same objective input
      if (lastStep != null &&
        lastStep.type == RecordedStepType.STEP &&
        step.type == RecordedStepType.STEP &&
        lastStep.input == step.input &&
        step.toolCalls.isNotEmpty()
      ) {
        // Merge tool calls into the existing step
        val merged = lastStep.copy(
          toolCalls = lastStep.toolCalls + step.toolCalls,
          result = "Executed ${lastStep.toolCalls.size + step.toolCalls.size} tools",
          success = lastStep.success && step.success,
        )
        recordedSteps[recordedSteps.lastIndex] = merged
        Console.log("[Recording] Step ${recordedSteps.size} (grouped): ${step.type} - ${step.input.take(50)}... (${merged.toolCalls.size} tools)")
      } else {
        recordedSteps.add(step)
        Console.log("[Recording] Step ${recordedSteps.size}: ${step.type} - ${step.input.take(50)}...")
      }
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

  // ─────────────────────────────────────────────────────────────────────────────
  // In-flight tool call tracking
  //
  // Set by the MCP request dispatcher around each tool execution and cleared
  // in a finally block. The device-claim registry reads this to decide whether
  // a competing claim should yield (no in-flight call → take over silently)
  // or fail with a rich busy error (in-flight call → tell the user what's
  // running so they can wait for it).
  // ─────────────────────────────────────────────────────────────────────────────

  private val _currentToolCall = AtomicReference<InFlightToolCall?>(null)

  /** Currently-running tool call for this session, or null if idle. */
  val currentToolCall: InFlightToolCall? get() = _currentToolCall.get()

  /** Set by the request dispatcher when a tool starts executing. */
  fun beginToolCall(call: InFlightToolCall) {
    _currentToolCall.set(call)
  }

  /** Cleared by the request dispatcher in a `finally` after a tool finishes. */
  fun endToolCall() {
    _currentToolCall.set(null)
  }

  /**
   * Cancels the progress notification scope.
   * Call when the session is being destroyed to prevent coroutine leaks.
   */
  fun close() {
    try {
      stopCaptureCallback?.invoke()
    } catch (e: Exception) {
      Console.error("[SessionContext] Failed to stop capture on close: ${e.message}")
    }
    stopCaptureCallback = null
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
    clearNamedDeviceBindings()
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Named device bindings (interactive multi-device)
  //
  // A session accumulates devices under names — `device(action=BIND, name=buyer, …)` — and
  // `switchDevice` hands the session between them. [associatedDeviceId] above stays the ACTIVE
  // device and remains the only value tool dispatch reads (it becomes the per-call
  // `McpDeviceContext.currentDeviceId`); the roster here is state beside it, and switching is its
  // only additional writer. A session that never binds a name keeps an empty roster and behaves
  // exactly as it did before named bindings existed.
  //
  // The roster is held as a [SessionDeviceBindings] — the same type the replay path builds and
  // `SwitchDeviceTrailblazeTool` reads — rather than a parallel registry, so an interactive
  // handover and a recorded one address devices through one model. That type is immutable and
  // non-empty by construction, which is the right contract for a replay whose cast is fixed at
  // session start, so an additive bind rebuilds it and carries the active name across.
  // ─────────────────────────────────────────────────────────────────────────────

  private val namedBindingsLock = Any()

  /**
   * Serializes any operation that moves the session's device — a handover, a bind, an unbind, a
   * replacing connect — which [namedBindingsLock] cannot: each moves the active name, then suspends
   * on the bridge, then commits [associatedDeviceId], and a lock can't be held across a suspension
   * point.
   *
   * Two of them interleaved in that window commit different halves — one owns the active name while
   * the other sets the routing id — and the session then drives one device while reporting the
   * other, which is the exact failure the roster exists to prevent. MCP dispatches each `tools/call`
   * independently, so overlapping calls in one session are the client's to make.
   */
  private val deviceHandoverMutex = Mutex()

  /** What holds [deviceHandoverMutex], for the error a caller that can't get a turn is given. */
  @Volatile
  private var deviceMoveInFlight: String? = null

  /** How long a device move waits for the one in flight. Shortened by tests. */
  internal var deviceMoveWaitMs: Long = DEVICE_MOVE_WAIT_MS

  /**
   * Runs [block] as the session's only in-flight device move, or reports through [onBusy] rather
   * than queueing behind one indefinitely.
   *
   * The wait is bounded because a move can hold the mutex across a cold connect — the WEB path
   * downloads a browser — and an MCP client whose call sits there gets no signal at all. [label]
   * names this move so the refused caller is told what it is waiting on.
   */
  suspend fun <T> runDeviceMove(
    label: String,
    onBusy: (inFlight: String?) -> T,
    block: suspend () -> T,
  ): T {
    // `lock()` doesn't acquire when it is cancelled while suspended, so a timed-out wait can't
    // leave the mutex held by a caller that already gave up.
    val acquired = withTimeoutOrNull(deviceMoveWaitMs) { deviceHandoverMutex.lock() }
    if (acquired == null) return onBusy(deviceMoveInFlight)
    deviceMoveInFlight = label
    return try {
      block()
    } finally {
      deviceMoveInFlight = null
      deviceHandoverMutex.unlock()
    }
  }

  /** Names in bind order — the first bound name is where the session starts. */
  private val namedDevices = LinkedHashMap<String, SessionDeviceBindings.BoundDevice>()

  /**
   * The session's named devices with one active, or null while no name has been bound.
   *
   * `@Volatile` for the same reason [associatedDeviceId] is: written from the request handler
   * running a `device()` / `switchDevice` call, read from another thread's tool dispatch.
   */
  @Volatile
  var namedDeviceBindings: SessionDeviceBindings? = null
    private set

  /** Bound names in bind order. Empty for an ordinary single-device session. */
  fun boundDeviceNames(): List<String> = synchronized(namedBindingsLock) { namedDevices.keys.toList() }

  /** The bound device for [name], or null when nothing is bound under it. */
  fun boundDevice(name: String): SessionDeviceBindings.BoundDevice? =
    synchronized(namedBindingsLock) { namedDevices[name] }

  /** The active name, or null when no name has been bound. */
  fun activeDeviceName(): String? = namedDeviceBindings?.activeName

  /**
   * Binds [device] under [name], keeping every already-bound name. Rebinding an existing name
   * replaces that entry in place (its position in bind order is kept, so the start device stays the
   * start device).
   *
   * @return true when this bind made [name] the active device — the first bind of the session, or a
   * rebind of the already-active name. The caller owns the device-side consequences (claiming the
   * device, pointing the bridge at it, re-registering the tool surface).
   */
  fun bindNamedDevice(name: String, device: SessionDeviceBindings.BoundDevice): Boolean =
    synchronized(namedBindingsLock) {
      val previousActive = namedDeviceBindings?.activeName
      namedDevices[name] = device
      rebuildNamedBindings(activeName = previousActive ?: name)
      val becameActive = namedDeviceBindings?.activeName == name
      Console.log(
        "[MCP Bindings] Bound '$name' -> ${device.trailblazeDeviceId.toFullyQualifiedDeviceId()} " +
          "(roster: ${namedDevices.keys.joinToString()}, active: ${namedDeviceBindings?.activeName})",
      )
      becameActive
    }

  /**
   * Removes the binding for [name].
   *
   * @return the outcome — [UnbindResult.NotBound] when nothing was bound under that name,
   * [UnbindResult.LastRemaining] when [name] is the only bound device (refused: a session with an
   * empty roster and a still-connected device is what `session(action=STOP)` is for), else
   * [UnbindResult.Unbound] carrying the name that is active afterwards. When the unbound name WAS
   * active, the first remaining name takes over and the caller must point the bridge at it.
   */
  fun unbindNamedDevice(name: String): UnbindResult = synchronized(namedBindingsLock) {
    val removed = namedDevices[name] ?: return UnbindResult.NotBound
    if (namedDevices.size == 1) return UnbindResult.LastRemaining
    val wasActive = namedDeviceBindings?.activeName == name
    namedDevices.remove(name)
    val nextActive = if (wasActive) namedDevices.keys.first() else namedDeviceBindings?.activeName
    rebuildNamedBindings(activeName = nextActive)
    Console.log(
      "[MCP Bindings] Unbound '$name' (${removed.trailblazeDeviceId.instanceId}); " +
        "active: ${namedDeviceBindings?.activeName}",
    )
    UnbindResult.Unbound(
      unbound = removed,
      activeName = namedDeviceBindings?.activeName,
      activeChanged = wasActive,
    )
  }

  /**
   * Moves the binding at [from] onto [to], keeping the device, its place in bind order, and its
   * active status.
   *
   * Renaming in place is the only way to correct a name: a device holds one name, so re-binding it
   * under another would be a duplicate, and unbinding the old name first is refused when it is the
   * session's last binding.
   *
   * @return the moved device, or null when [from] isn't bound or [to] already is — reassigning a
   * name that another device holds would silently unbind that device.
   */
  fun renameNamedDevice(from: String, to: String): SessionDeviceBindings.BoundDevice? =
    synchronized(namedBindingsLock) {
      val device = namedDevices[from] ?: return null
      if (to in namedDevices) return null
      val wasActive = namedDeviceBindings?.activeName == from
      // Rebuilt rather than remove-then-put: bind order marks the start device, and re-adding under
      // the new name would move it to the end of the roster.
      val renamed = LinkedHashMap<String, SessionDeviceBindings.BoundDevice>(namedDevices.size)
      namedDevices.forEach { (boundName, bound) ->
        renamed[if (boundName == from) to else boundName] = bound
      }
      namedDevices.clear()
      namedDevices.putAll(renamed)
      rebuildNamedBindings(activeName = if (wasActive) to else namedDeviceBindings?.activeName)
      Console.log(
        "[MCP Bindings] Renamed '$from' to '$to' (${device.trailblazeDeviceId.instanceId}); " +
          "roster: ${namedDevices.keys.joinToString()}, active: ${namedDeviceBindings?.activeName}",
      )
      device
    }

  /**
   * Makes [name] the active device.
   *
   * @return the newly-active device, or null when [name] isn't bound. Only the session-side switch;
   * the caller does the device-side work (bridge selection, [associatedDeviceId], tool re-register).
   */
  fun switchActiveNamedDevice(name: String): SessionDeviceBindings.BoundDevice? =
    synchronized(namedBindingsLock) {
      val bindings = namedDeviceBindings ?: return null
      if (bindings.deviceFor(name) == null) return null
      bindings.switchTo(name)
    }

  /**
   * Every device this session addresses: the active/associated device plus each named binding.
   *
   * Session teardown iterates this rather than [associatedDeviceId] alone — a bound device gets its
   * driver warmed at bind time, so cleaning up only the active one leaves the rest connected.
   */
  fun addressedDeviceIds(): List<TrailblazeDeviceId> = synchronized(namedBindingsLock) {
    (listOfNotNull(associatedDeviceId) + namedDevices.values.map { it.trailblazeDeviceId }).distinct()
  }

  /** Drops every named binding. Called when the session's device association is cleared. */
  fun clearNamedDeviceBindings() = synchronized(namedBindingsLock) {
    if (namedDevices.isEmpty()) return
    namedDevices.clear()
    namedDeviceBindings = null
    Console.log("[MCP Bindings] Cleared all named bindings")
  }

  /**
   * Rebuilds [namedDeviceBindings] from [namedDevices], restoring [activeName] when it is still
   * bound. A [SessionDeviceBindings] always starts on its first entry, so the active name has to be
   * re-applied — otherwise adding a second device would silently hand the session back to the first.
   */
  private fun rebuildNamedBindings(activeName: String?) {
    namedDeviceBindings = if (namedDevices.isEmpty()) {
      null
    } else {
      SessionDeviceBindings(LinkedHashMap(namedDevices)).also { rebuilt ->
        activeName?.takeIf { it in namedDevices }?.let { rebuilt.switchTo(it) }
      }
    }
  }

  /** Outcome of [unbindNamedDevice]. */
  sealed interface UnbindResult {
    /** No device was bound under the requested name. */
    data object NotBound : UnbindResult

    /** The requested name is the session's only bound device, so it was kept. */
    data object LastRemaining : UnbindResult

    /** The binding was removed. [activeChanged] is true when the unbound device was the active one. */
    data class Unbound(
      val unbound: SessionDeviceBindings.BoundDevice,
      val activeName: String?,
      val activeChanged: Boolean,
    ) : UnbindResult
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
  
  /**
   * Sends `notifications/tools/list_changed` on both notification channels, mirroring
   * [sendProgressInternal]:
   *
   *  - Custom SSE channel (the daemon's `sse("/mcp")` endpoint) — the path that reaches
   *    StreamableHttp clients. The SDK's own dispatch is unreachable there: with
   *    `enableJsonResponse = true` the transport routes server-initiated notifications to
   *    its standalone GET stream, which the daemon never registers (the custom SSE endpoint
   *    replaces the SDK's `handleGetRequest`).
   *  - SDK session notification — the path that reaches direct-STDIO clients
   *    (`trailblaze mcp --direct`), whose transport is the stdout pipe and who never open
   *    the custom SSE stream. Without this leg a spec-compliant STDIO client that caches
   *    `tools/list` never learns the surface changed after a device connect / target switch.
   *
   * No-ops when neither channel is available — such a client sees the new surface on its
   * next `tools/list` instead.
   */
  fun sendToolListChangedNotification() {
    customSseNotificationSender?.let { sender ->
      try {
        sender("""{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}""")
        Console.log("[MCP SESSION] Sent tools/list_changed via custom SSE channel")
      } catch (e: Exception) {
        Console.error("[MCP SESSION] Failed to send tools/list_changed via custom SSE: ${e.message}")
      }
    }
    mcpServerSession?.let { session ->
      sendProgressNotificationsScope.launch {
        try {
          session.notification(ToolListChangedNotification())
          Console.log("[MCP SESSION] Sent tools/list_changed via SDK session")
        } catch (e: Exception) {
          // Tolerated: on StreamableHttp the SDK leg has no reachable stream (custom SSE
          // above is the real delivery); only direct-STDIO clients depend on this leg.
        }
      }
    }
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
// In-flight tool call
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Snapshot of a tool call currently executing on a session.
 *
 * Carried by [TrailblazeMcpSessionContext.currentToolCall] for the duration of
 * the call. Read by [DeviceClaimRegistry] when another session tries to claim
 * the same device — its presence is what flips the registry from "yield" to
 * "fail with details so the user knows what's actually running."
 *
 * [argsSummary] is a short, human-readable description (the `objective` for
 * `step`/`ask`, the trail name for `trail`, etc.). It's surfaced verbatim in
 * the busy-error message, so keep it concise.
 */
data class InFlightToolCall(
  val toolName: String,
  val argsSummary: String?,
  val traceId: String,
  val startedAtMs: Long = System.currentTimeMillis(),
)

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

  /** The natural language input (objective/assertion/question) */
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
