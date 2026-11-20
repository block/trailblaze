package xyz.block.trailblaze.host.lease

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.util.BufferedImageUtils
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl
import xyz.block.trailblaze.mcp.utils.RpcScreenStateAdapter
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailArgBinder
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * One thing a borrower asks a lender to do with a leased device. The lender does it the way it
 * would for its own CLI ([TrailblazeMcpBridge]), so every driver the lender has works the same
 * remotely, whatever the platform.
 */
@Serializable
data class RemoteDeviceCall(
  /** The device, as the lender names it ([TrailblazeDeviceId.toFullyQualifiedDeviceId]). */
  val device: String,
  val op: Op,
  /** For [Op.RUN_TOOL]: the tool as a one-tool YAML envelope (`- <toolName>: ...`). */
  val toolYaml: String? = null,
  /** The borrower's target id, so the lender offers and runs the tools that target declares. */
  val target: String? = null,
  /** For [Op.RUN_TOOL]: the borrower's memory as the tool runs against it. */
  val memory: Map<String, String> = emptyMap(),
  /** Keys of [memory] whose values are secret, so the lender's logs keep them redacted. */
  val sensitiveMemoryKeys: List<String> = emptyList(),
  /** The run's arguments, as `RunYamlRequest.argsSnapshot` carries them. */
  val args: Map<String, String> = emptyMap(),
  val sensitiveArgNames: List<String> = emptyList(),
  /** Every value the borrower's session treats as secret, masked in what the lender writes for the call. */
  val secrets: List<String> = emptyList(),
) {
  enum class Op {
    /** Run one tool on the device and return its result. */
    RUN_TOOL,

    /** Capture the device's screen state. */
    SCREEN_STATE,

    /** List the apps installed on the device. */
    INSTALLED_APPS,

    /** The borrower is done with the device: end its session and let go of its driver. */
    END,
  }
}

@Serializable
data class RemoteDeviceReply(
  val toolResult: RemoteToolResult? = null,
  val screenState: RemoteScreenState? = null,
  val installedAppIds: Set<String>? = null,
  /** For [RemoteDeviceCall.Op.RUN_TOOL]: memory after the tool, so what it remembered reaches the borrower. */
  val memory: Map<String, String>? = null,
  /** Keys the tool deleted, which [memory] alone can't tell from keys it never had. */
  val memoryDeletions: List<String> = emptyList(),
  val sensitiveMemoryKeys: List<String> = emptyList(),
)

/**
 * A tool's result on the wire. [TrailblazeToolResult]'s error cases carry the tool object itself,
 * which only decodes where its class is known, so the wire keeps what a caller reads: success with
 * its output, or failure with its message.
 */
@Serializable
data class RemoteToolResult(
  val success: Boolean,
  val message: String? = null,
  val structuredContent: JsonElement? = null,
  val fatal: Boolean = false,
  val stackTrace: String? = null,
) {
  fun toToolResult(): TrailblazeToolResult = when {
    success -> TrailblazeToolResult.Success(message = message, structuredContent = structuredContent)
    fatal -> TrailblazeToolResult.Error.FatalError(message ?: "Tool failed on the lending machine", stackTrace)
    else -> TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = message ?: "Tool failed on the lending machine",
      stackTrace = stackTrace,
    )
  }

  companion object {
    fun of(result: TrailblazeToolResult): RemoteToolResult = when (result) {
      is TrailblazeToolResult.Success -> RemoteToolResult(true, result.message, result.structuredContent)
      is TrailblazeToolResult.Error.FatalError ->
        RemoteToolResult(false, result.errorMessage, fatal = true, stackTrace = result.stackTraceString)
      is TrailblazeToolResult.Error.ExceptionThrown ->
        RemoteToolResult(false, result.errorMessage, stackTrace = result.stackTrace)
      is TrailblazeToolResult.Error -> RemoteToolResult(false, result.errorMessage)
    }

    fun failed(e: Throwable) = RemoteToolResult(false, e.message ?: e::class.java.simpleName, stackTrace = e.stackTraceToString())
  }
}

/** A [ScreenState] captured on the lending machine, as it travels to the borrower. */
@Serializable
data class RemoteScreenState(
  val platform: TrailblazeDevicePlatform,
  val deviceWidth: Int,
  val deviceHeight: Int,
  val viewHierarchy: ViewHierarchyTreeNode,
  val screenshotBase64: String? = null,
  val annotatedScreenshotBase64: String? = null,
  val viewHierarchyText: String? = null,
  val pageContextSummary: String? = null,
  val classifiers: List<String> = emptyList(),
  val trailblazeNodeTree: TrailblazeNode? = null,
  val droppedNodeFetches: Int? = null,
) {
  fun toScreenState(): ScreenState = Received(this)

  private class Received(private val wire: RemoteScreenState) : ScreenState {
    override val screenshotBytes: ByteArray? by lazy { wire.screenshotBase64?.let(Base64.getDecoder()::decode) }
    override val annotatedScreenshotBytes: ByteArray? by lazy {
      wire.annotatedScreenshotBase64?.let(Base64.getDecoder()::decode) ?: screenshotBytes
    }
    override val deviceWidth: Int get() = wire.deviceWidth
    override val deviceHeight: Int get() = wire.deviceHeight
    override val viewHierarchy: ViewHierarchyTreeNode get() = wire.viewHierarchy
    override val viewHierarchyTextRepresentation: String? get() = wire.viewHierarchyText
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform get() = wire.platform
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> =
      wire.classifiers.map(::TrailblazeDeviceClassifier)
    override val trailblazeNodeTree: TrailblazeNode? get() = wire.trailblazeNodeTree
    override val droppedNodeFetches: Int? get() = wire.droppedNodeFetches
    override val pageContextSummary: String? get() = wire.pageContextSummary
  }

  companion object {
    fun of(state: ScreenState): RemoteScreenState {
      val encoder = Base64.getEncoder()
      val screenshot = state.screenshotBytes
      val annotated = state.annotatedScreenshotBytes?.takeIf { it.isNotEmpty() && it !== screenshot }
      return RemoteScreenState(
        platform = state.trailblazeDevicePlatform,
        deviceWidth = state.deviceWidth,
        deviceHeight = state.deviceHeight,
        viewHierarchy = state.viewHierarchy,
        screenshotBase64 = screenshot?.let(encoder::encodeToString),
        annotatedScreenshotBase64 = annotated?.let(encoder::encodeToString),
        viewHierarchyText = state.viewHierarchyTextRepresentation,
        pageContextSummary = state.pageContextSummary,
        classifiers = state.deviceClassifiers.map { it.classifier },
        trailblazeNodeTree = state.trailblazeNodeTree,
        droppedNodeFetches = state.droppedNodeFetches,
      )
    }
  }
}

/** Does what a borrower asks with a device this daemon lent it. */
fun interface LeasedDeviceCalls {
  suspend fun call(device: TrailblazeDeviceId, call: RemoteDeviceCall): RemoteDeviceReply
}

/**
 * Carries out a borrower's calls through this daemon's own [TrailblazeMcpBridge], bound to the
 * leased device for each call, so a tool runs exactly as `trailblaze tool --device <it>` would run
 * it here: same driver, same dispatch, any platform.
 */
class BridgeLeasedDeviceCalls(
  /** How long a first call waits for this machine to finish setting up the device's driver. */
  private val readyTimeout: Duration = READY_TIMEOUT,
  private val bridge: () -> TrailblazeMcpBridge,
) : LeasedDeviceCalls {

  /** Devices the bridge has been pointed at, so its driver is set up once per borrowing. */
  private val selected = ConcurrentHashMap.newKeySet<TrailblazeDeviceId>()

  /** The target each device's session was last set to for its borrower. */
  private val targets = ConcurrentHashMap<TrailblazeDeviceId, String>()

  override suspend fun call(device: TrailblazeDeviceId, call: RemoteDeviceCall): RemoteDeviceReply =
    // The borrower's use of its device, which this machine's busy rules must not refuse.
    RemoteDevicePools.asBorrowersCall(device) { onDevice(device, call) }

  private suspend fun onDevice(device: TrailblazeDeviceId, call: RemoteDeviceCall): RemoteDeviceReply =
    // Binds this call to the device without touching which device the daemon's own CLI selected.
    withContext(McpDeviceContext.currentDeviceId.asContextElement(device)) {
      val bridge = bridge()
      if (call.op == RemoteDeviceCall.Op.END) {
        selected.remove(device)
        targets.remove(device)
        runCatching { if (bridge.getActiveSessionId() != null) bridge.endSession() }
        runCatching { bridge.releasePersistentDeviceConnection(device) }
        return@withContext RemoteDeviceReply()
      }
      if (device !in selected) {
        bridge.selectDevice(device)
        awaitDriverReady(bridge, device)
        selected += device
      }
      val target = call.target
      if (target != null && targets[device] != target) {
        // Unknown here: the lender's own default target applies, and a tool it lacks fails by name.
        if (bridge.setSessionTargetForDevice(device, target) == null) {
          Console.log("[LeasedDevice] ${call.device}: target '$target' is not known on this machine")
        }
        targets[device] = target
      }
      when (call.op) {
        RemoteDeviceCall.Op.RUN_TOOL -> {
          val tool = decodeTool(call.toolYaml)
          refusalFor(tool)?.let { reason ->
            Console.log("[LeasedDevice] ${call.device}: refused: $reason")
            return@withContext RemoteDeviceReply(toolResult = RemoteToolResult(success = false, message = reason))
          }
          val leased = LeasedCall(memoryOf(call), call.secrets)
          val result = try {
            LeasedCall.running(leased) {
              RemoteToolResult.of(bridge.executeTrailblazeToolForResult(tool, blocking = true, traceId = null))
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Console.log("[LeasedDevice] ${call.device}: tool failed: ${e.message}")
            RemoteToolResult.failed(e)
          }
          val memory = leased.memory
          RemoteDeviceReply(
            toolResult = result,
            memory = memory.variables.toMap(),
            memoryDeletions = memory.deletedKeys.toList(),
            sensitiveMemoryKeys = memory.sensitiveKeys.toList(),
          )
        }
        RemoteDeviceCall.Op.SCREEN_STATE ->
          RemoteDeviceReply(screenState = freshScreenState(bridge)?.let(RemoteScreenState::of))
        RemoteDeviceCall.Op.INSTALLED_APPS -> RemoteDeviceReply(installedAppIds = bridge.getInstalledAppIds())
        RemoteDeviceCall.Op.END -> error("unreachable")
      }
    }

  /**
   * Waits while this machine is still setting [device]'s driver up (a browser downloading and
   * starting, say), which selecting the device starts but doesn't wait for. Only a status saying
   * setup is under way is waited on; any other is left to the first tool, as for this daemon's own
   * calls. Throws with the reason when setup ends without the driver ready, or outlasts
   * [readyTimeout]; the device stays unselected, so the borrower's next call waits again.
   */
  private suspend fun awaitDriverReady(bridge: TrailblazeMcpBridge, device: TrailblazeDeviceId) {
    var status = bridge.getDriverConnectionStatus(device)
    if (status == null || !isSettingUp(status)) return
    val settled = withTimeoutOrNull(readyTimeout) {
      while (status?.let(::isSettingUp) == true) {
        delay(READY_POLL_INTERVAL)
        status = bridge.getDriverConnectionStatus(device)
      }
    }
    val last = status ?: return
    error(if (settled == null) "${device.toFullyQualifiedDeviceId()} is still starting on the lender: $last" else "${device.toFullyQualifiedDeviceId()} failed to start on the lender: $last")
  }

  /** The wording [TrailblazeMcpBridge.getDriverConnectionStatus] uses while a driver is set up. */
  private fun isSettingUp(status: String): Boolean =
    status.contains("installing", ignoreCase = true) || status.contains("still initializing", ignoreCase = true)

  /**
   * The screen as it is now, never the bridge's snapshot from the last tool: a borrower polls this
   * while it waits for a screen to appear (`assertWaypoint`), and a cached one would never change.
   * Same sources, in the same order, as the bridge's own polling tools.
   */
  private suspend fun freshScreenState(bridge: TrailblazeMcpBridge): ScreenState? {
    bridge.getDirectScreenStateProvider(skipScreenshot = false)?.let { capture ->
      return withContext(Dispatchers.IO) { capture(EffectiveScreenshotScalingConfig.effective) }
    }
    if (bridge.isOnDeviceInstrumentation()) {
      return bridge.getScreenStateViaRpc()?.let { RpcScreenStateAdapter.from(it, BufferedImageUtils::encodeLikeCaptures) }
    }
    return bridge.getCurrentScreenState()
  }

  /** The borrower's memory, secrets marked before their values land, as the borrower holds it. */
  private fun memoryOf(call: RemoteDeviceCall): AgentMemory = AgentMemory().apply {
    call.sensitiveMemoryKeys.forEach(::markSensitive)
    variables.putAll(call.memory)
    // Already bound and resolved by the borrower, so rehydrated verbatim (as the on-device runner does).
    TrailArgBinder.decodeProvided(call.args).forEach { (name, value) -> putArg(name, value) }
    call.sensitiveArgNames.forEach(::markArgSensitive)
  }

  private fun decodeTool(yaml: String?): TrailblazeTool {
    requireNotNull(yaml) { "a tool call needs its tool" }
    return createTrailblazeYaml().decodeTools(yaml).singleOrNull()?.trailblazeTool
      ?: throw IllegalArgumentException("a tool call carries exactly one tool")
  }

  companion object {
    /**
     * Why this machine won't run [tool] for a borrower, or null when it will: only device actions
     * run. A borrower runs host tools on its own machine and sends only what acts on the device, so
     * a host tool here came from something that isn't a borrower, and it would run shell commands
     * or scripts on this machine for whoever reached the port. A name this machine doesn't know
     * could resolve to one of its own target's host tools, so it is refused too.
     */
    internal fun refusalFor(tool: TrailblazeTool): String? = when {
      tool is OtherTrailblazeTool ->
        "'${tool.toolName}' is not a device action this machine lends; run it from a machine whose target declares it"
      TrailblazeMcpBridgeImpl.resolveToolDispatchRoute(tool) != TrailblazeMcpBridgeImpl.ToolDispatchRoute.DEVICE ->
        "${tool::class.simpleName} runs on a host, not a device; a lending machine runs only device actions"
      else -> null
    }

    /** Under the borrower's own wait for a call, so it hears why rather than timing out. */
    val READY_TIMEOUT: Duration = 4.minutes
    private val READY_POLL_INTERVAL: Duration = 500.milliseconds

    /** [tool] as [RemoteDeviceCall.toolYaml]. */
    fun encodeTool(tool: TrailblazeTool, customToolClasses: Set<kotlin.reflect.KClass<out TrailblazeTool>> = emptySet()): String =
      createTrailblazeYaml(customToolClasses).encodeTools(listOf(fromTrailblazeTool(tool)))
  }
}
