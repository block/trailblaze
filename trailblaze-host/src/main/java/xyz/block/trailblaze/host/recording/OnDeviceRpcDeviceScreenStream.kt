package xyz.block.trailblaze.host.recording

import io.ktor.util.decodeBase64Bytes
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.ScreenStateProvider
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.fromTrailblazeTool

/**
 * [DeviceScreenStream] backed by the on-device RPC server. **Android recording path** —
 * distinct from [MaestroDeviceScreenStream] (iOS) and [PlaywrightDeviceScreenStream] (Web).
 *
 * Why this shape:
 * - Android's accessibility / instrumentation drivers run *on device*. The host doesn't have
 *   a Maestro driver and shouldn't try to spin one up — the on-device runner owns the
 *   accessibility tree and the screenshot pipeline. Going through the on-device RPC means
 *   we reuse the exact code path production tests use, which the user already trusts.
 *
 * **Frame loop.** [frames] polls [provider] at [frameIntervalMs] cadence. The provider's
 * cached response feeds [getViewHierarchy] / [getTrailblazeNodeTree] / [getScreenshot] so
 * the recorder doesn't fan out three RPCs per tap.
 *
 * **Input forwarding.** [tap] / [swipe] / [inputText] / [pressKey] dispatch a single-tool
 * trail YAML directly via [OnDeviceRpcClient.rpcCall] using a pre-built [RunYamlRequest]
 * template — same pattern as [xyz.block.trailblaze.host.HostOnDeviceRpcTrailblazeAgent] uses
 * for its per-tool dispatch in production runs.
 *
 * **Why direct RPC and not [xyz.block.trailblaze.ui.TrailblazeDeviceManager.runYaml]?** The
 * device-manager path stacks two polling waits (`awaitOnDeviceSessionCompletion` + the
 * outer `awaitSessionForDevice`) at 1s intervals each on top of the actual tool execution.
 * That added 2+ seconds of latency *per tap*, which made interactive recording unusable
 * (the user reported a 6-second lag during scroll-and-drag). Direct rpcCall with
 * `awaitCompletion = true` returns when the on-device handler finishes the tool — typical
 * round-trip is 50–200 ms, fast enough to feel like a live driver.
 *
 * @param runYamlRequestTemplate Pre-built per (device, target, driver, session) at connect
 *   time. Per-tap dispatch uses `template.copy(yaml = ...)` so we don't rebuild the
 *   LLM-model + config + driverType plumbing on every gesture.
 */
class OnDeviceRpcDeviceScreenStream(
  private val rpc: OnDeviceRpcClient,
  private val provider: ScreenStateProvider,
  private val runYamlRequestTemplate: RunYamlRequest,
  initialDeviceWidth: Int,
  initialDeviceHeight: Int,
  private val frameIntervalMs: Long = 200,
) : DeviceScreenStream {

  override val deviceWidth: Int = initialDeviceWidth
  override val deviceHeight: Int = initialDeviceHeight

  // Reuse the shared default — recording dispatches the same built-in tool set every
  // existing trail YAML uses, so building a private instance per recorder would just re-scan
  // the classpath for no reason.
  private val trailblazeYaml = TrailblazeYaml.Default

  // Last successful screen-state response. Cached so [getViewHierarchy] /
  // [getTrailblazeNodeTree] / [getScreenshot] don't each fire their own RPC — those calls
  // happen back-to-back from the recorder for every tap, and re-fetching three times in a
  // row would just triple the round-trip cost for data the frame loop already has.
  @Volatile private var lastResponse: GetScreenStateResponse? = null

  override fun frames(): Flow<ByteArray> = flow {
    while (currentCoroutineContext().isActive) {
      val bytes = captureFrame()
      if (bytes != null) emit(bytes)
      delay(frameIntervalMs)
    }
  }

  private suspend fun captureFrame(): ByteArray? {
    val response = provider.getScreenState(includeScreenshot = true) ?: return null
    lastResponse = response
    return response.screenshotBase64?.decodeBase64Bytes()
  }

  override suspend fun tap(x: Int, y: Int) {
    dispatchTool(TapOnPointTrailblazeTool(x = x, y = y))
  }

  override suspend fun longPress(x: Int, y: Int) {
    dispatchTool(TapOnPointTrailblazeTool(x = x, y = y, longPress = true))
  }

  override suspend fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long?) {
    val startXPct = ((startX.toFloat() / deviceWidth) * 100).toInt()
    val startYPct = ((startY.toFloat() / deviceHeight) * 100).toInt()
    val endXPct = ((endX.toFloat() / deviceWidth) * 100).toInt()
    val endYPct = ((endY.toFloat() / deviceHeight) * 100).toInt()
    @Suppress("DEPRECATION")
    dispatchTool(
      SwipeWithRelativeCoordinatesTool(
        startRelative = "$startXPct%, $startYPct%",
        endRelative = "$endXPct%, $endYPct%",
        durationMs = durationMs,
      ),
    )
  }

  override suspend fun inputText(text: String) {
    dispatchTool(InputTextTrailblazeTool(text = text))
  }

  override suspend fun pressKey(key: String) {
    val keyCode = when (key) {
      "Back" -> PressKeyTrailblazeTool.PressKeyCode.BACK
      "Enter" -> PressKeyTrailblazeTool.PressKeyCode.ENTER
      "Home" -> PressKeyTrailblazeTool.PressKeyCode.HOME
      else -> return
    }
    dispatchTool(PressKeyTrailblazeTool(keyCode = keyCode))
  }

  override suspend fun getViewHierarchy(): ViewHierarchyTreeNode {
    val cached = lastResponse?.viewHierarchy
    if (cached != null) return cached
    return refreshScreenState()?.viewHierarchy ?: ViewHierarchyTreeNode(nodeId = 0)
  }

  override suspend fun getTrailblazeNodeTree(): TrailblazeNode? {
    val cached = lastResponse?.trailblazeNodeTree
    if (cached != null) return cached
    return refreshScreenState()?.trailblazeNodeTree
  }

  override suspend fun getScreenshot(): ByteArray = captureFrame() ?: ByteArray(0)

  private suspend fun refreshScreenState(): GetScreenStateResponse? {
    return provider.getScreenState(includeScreenshot = false)?.also { lastResponse = it }
  }

  /**
   * Public fast dispatch for arbitrary trail YAML — used by the recording UI's per-card
   * Replay button so a manual replay feels as immediate as the user's own tap on the
   * device. Same direct rpc path the live tap dispatch uses (no `deviceManager.runYaml`
   * session bookkeeping, no awaitCompletion polling); the on-device handler accepts the
   * request and the next frame poll picks up the result.
   */
  suspend fun dispatchYaml(yaml: String) {
    val request = runYamlRequestTemplate.copy(
      yaml = yaml,
      agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
      awaitCompletion = false,
    )
    when (val result: RpcResult<RunYamlResponse> = rpc.rpcCall(request)) {
      is RpcResult.Success -> Unit
      is RpcResult.Failure -> {
        Console.log(
          "[OnDeviceRpcDeviceScreenStream] dispatchYaml failed (${result.errorType}): " +
            "${result.message}" + (result.details?.let { " | $it" } ?: ""),
        )
      }
    }
  }

  private suspend fun dispatchTool(tool: TrailblazeTool) {
    val toolItems = listOf(TrailYamlItem.ToolTrailItem(listOf(fromTrailblazeTool(tool))))
    val yaml = trailblazeYaml.encodeToString(toolItems)
    val request = runYamlRequestTemplate.copy(
      yaml = yaml,
      // TRAILBLAZE_RUNNER dispatches a fixed tool list directly — no LLM call, no agent
      // loop. Right shape for "I have one tool, just run it".
      agentImplementation = AgentImplementation.TRAILBLAZE_RUNNER,
      // Fire-and-forget. The screen-state poll loop is the source of truth for what the
      // user sees, and it ticks every [frameIntervalMs] regardless of dispatch state.
      // Blocking the tap on `awaitCompletion = true` adds the on-device pre-tool UI-settle
      // + tool-execution + post-settle wait to every gesture — which is what was felt as
      // scroll lag. With false, the dispatch returns as soon as the on-device handler
      // accepts the request and the next frame poll picks up the result.
      awaitCompletion = false,
    )
    val name = tool::class.simpleName ?: "tool"
    when (val result: RpcResult<RunYamlResponse> = rpc.rpcCall(request)) {
      is RpcResult.Success -> Unit
      is RpcResult.Failure -> {
        Console.log(
          "[OnDeviceRpcDeviceScreenStream] dispatch $name failed (${result.errorType}): " +
            "${result.message}" + (result.details?.let { " | $it" } ?: ""),
        )
      }
    }
  }
}
