package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.recording.RecordedInteraction
import xyz.block.trailblaze.recording.RecordingYamlCodec
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ConnectionState
import xyz.block.trailblaze.ui.recording.RecordingDeviceConnection
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Interactive recording endpoints — the "drive the device from Trail Runner, every interaction
 * becomes an editable step" feature. Unlike `/api/draft/record` (which hands the blaze steps to the
 * agent to re-derive on the device), these routes let the author tap/type/swipe the live device
 * directly and capture each gesture as a deterministic Trailblaze tool.
 *
 * It reuses the same connection + recording primitives the Compose desktop recording tab uses:
 *  - [TrailblazeDeviceManager.connectionService] opens a live [xyz.block.trailblaze.recording.DeviceScreenStream]
 *    and the matching per-platform [xyz.block.trailblaze.recording.InteractionToolFactory] (wrapped in a
 *    [RecordingDeviceConnection]).
 *  - the factory + [xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator] turn a tap coordinate
 *    into a stable `tapOnElementBySelector` (falling back to `tapOnPoint`), exactly like the desktop
 *    recorder.
 *  - [RecordingYamlCodec] serializes each captured tool to a runnable trail item the UI shows as a card.
 *
 * The web UI assembles those cards (plus any free-text prompt steps) into a `<platform>.trail.yaml`
 * and saves it into a draft folder via the existing draft endpoints — so a recording lands in the
 * same Blaze/draft flow as a proposed blaze.
 *
 * Routes (all POST; the device id rides in the JSON body since [TrailblazeDeviceId] is structured):
 *   /api/record/connect    — open a live connection, hold the stream for subsequent calls
 *   /api/record/screen     — poll one mirror frame (base64) for the live view
 *   /api/record/gesture    — dispatch a tap/longPress/swipe/inputText/pressKey AND record it as a tool
 *   /api/record/disconnect — release the connection
 */
internal fun Route.recordRoutes(deps: TrailRunnerDeps) {
  // One service per daemon (registered once): it holds the live connections keyed by device so
  // screen/gesture calls reach the already-open stream without reconnecting. Null when the daemon
  // runs without a deviceManager (e.g. the integration test harness) — every route then 503s.
  val service = deps.deviceManager?.let { TrailRunnerRecordingService(it) }

  post("$PATH_BASE/api/record/connect") {
    if (service == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordConnectResponse(ok = false, error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordDeviceRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, RecordConnectResponse(ok = false, error = "trailblazeDeviceId is required"))
      return@post
    }
    val resp = service.connect(body.trailblazeDeviceId)
    call.respondText(
      text = JSON.encodeToString(RecordConnectResponse.serializer(), resp),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/screen") {
    if (service == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordScreenResponse(ok = false, error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordDeviceRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, RecordScreenResponse(ok = false, error = "trailblazeDeviceId is required"))
      return@post
    }
    val resp = service.screen(body.trailblazeDeviceId)
    call.respondText(
      text = JSON.encodeToString(RecordScreenResponse.serializer(), resp),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/gesture") {
    if (service == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordGestureResponse(ok = false, error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordGestureRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, RecordGestureResponse(ok = false, error = "malformed gesture request"))
      return@post
    }
    val resp = service.gesture(body)
    call.respondText(
      text = JSON.encodeToString(RecordGestureResponse.serializer(), resp),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/tree") {
    if (service == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordTreeResponse(ok = false, error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordDeviceRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, RecordTreeResponse(ok = false, error = "trailblazeDeviceId is required"))
      return@post
    }
    val resp = service.tree(body.trailblazeDeviceId)
    call.respondText(
      text = JSON.encodeToString(RecordTreeResponse.serializer(), resp),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/tool-params") {
    val body = runCatching { call.receive<RecordToolParamsRequest>() }.getOrNull()
    if (body == null || body.className.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, RecordToolParamsResponse(ok = false, error = "className is required"))
      return@post
    }
    val params = withContext(Dispatchers.IO) {
      runCatching { ToolCatalogBuilder.paramsForToolClass(body.className.trim()) }.getOrDefault(emptyList())
    }
    call.respondText(
      text = JSON.encodeToString(RecordToolParamsResponse.serializer(), RecordToolParamsResponse(ok = true, parameters = params)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/scripted-tool-params") {
    val body = runCatching { call.receive<RecordScriptedToolParamsRequest>() }.getOrNull()
    if (body == null || body.trailmap.isBlank() || body.toolId.isBlank()) {
      call.respond(HttpStatusCode.BadRequest, RecordToolParamsResponse(ok = false, error = "trailmap and toolId are required"))
      return@post
    }
    val params = runCatching { ToolCatalogBuilder.scriptedToolParams(body.trailmap.trim(), body.toolId.trim()) }.getOrDefault(emptyList())
    call.respondText(
      text = JSON.encodeToString(RecordToolParamsResponse.serializer(), RecordToolParamsResponse(ok = true, parameters = params)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/record/disconnect") {
    val body = runCatching { call.receive<RecordDeviceRequest>() }.getOrNull()
    if (service != null && body != null) service.disconnect(body.trailblazeDeviceId)
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = true)),
      contentType = ContentType.Application.Json,
    )
  }
}

@Serializable
internal data class RecordDeviceRequest(val trailblazeDeviceId: TrailblazeDeviceId)

@Serializable
internal data class RecordToolParamsRequest(val className: String)

@Serializable
internal data class RecordScriptedToolParamsRequest(val trailmap: String, val toolId: String)

@Serializable
internal data class RecordToolParamsResponse(
  val ok: Boolean,
  val parameters: List<ToolParamDto> = emptyList(),
  val error: String? = null,
)

@Serializable
internal data class RecordConnectResponse(
  val ok: Boolean,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
  val error: String? = null,
)

@Serializable
internal data class RecordScreenResponse(
  val ok: Boolean,
  val screenshotBase64: String? = null,
  /** `image/png` or `image/jpeg`, detected from the bytes so the client's data URL renders correctly. */
  val mime: String = "image/png",
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
  val error: String? = null,
)

/**
 * A single interaction to dispatch + record. Flat (rather than a sealed `DeviceInteraction`) so the
 * web UI can POST a plain JSON object without polymorphic-serialization config; [type] selects the
 * gesture and the relevant fields are read per branch.
 */
@Serializable
internal data class RecordGestureRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  /** One of: tap, longPress, swipe, inputText, pressKey. */
  val type: String,
  val x: Int? = null,
  val y: Int? = null,
  val startX: Int? = null,
  val startY: Int? = null,
  val endX: Int? = null,
  val endY: Int? = null,
  val durationMs: Long? = null,
  val text: String? = null,
  val key: String? = null,
  /**
   * For a tap: `"tap"` (default) records a `tapOnElementBySelector`/`tapOnPoint` and drives the
   * device; `"assertVisible"` records an `assertVisibleBySelector` (a verify) for the tapped
   * element and NEVER drives the device. Lets the author add assertions by tapping the thing to
   * check, reusing the same selector resolution.
   */
  val action: String = "tap",
  /**
   * When true, resolve the gesture to a tool + YAML and return it WITHOUT dispatching it to the
   * device — the "propose the next step" path. The UI shows the proposal for the author to confirm
   * (with a human prompt); confirming re-sends the same gesture with `resolveOnly = false` to
   * actually drive the device. Default false = resolve + dispatch (direct actions: type, keys).
   */
  val resolveOnly: Boolean = false,
)

@Serializable
internal data class RecordGestureResponse(
  val ok: Boolean,
  val toolName: String? = null,
  /** The captured tool serialized as a runnable trail item (`- tools:\n    - <tool>`). */
  val yaml: String? = null,
  /** Short human label for the step card (e.g. `Tap (120, 340)`, `Type "hello"`). */
  val label: String? = null,
  /**
   * Alternative tools the author can pick for a tap — the ranked selector candidates
   * ([TrailblazeNodeSelectorGenerator]) plus the raw coordinate fallback. Lets the author promote
   * a `tapOnPoint` to a stable `tapOnElementBySelector` (or choose a different selector strategy)
   * instead of being stuck with whatever the auto-resolver picked. Empty for non-tap gestures.
   */
  val options: List<RecordToolOption> = emptyList(),
  /**
   * The element under the tap, in plain language (label + type + bounds) so the author can confirm
   * "yes, that's the Pay button" without reading selector JSON. Null for swipe/type/key and for taps
   * that didn't land on a resolvable element.
   */
  val element: RecordElement? = null,
  val error: String? = null,
)

/**
 * A single on-screen element described for a non-technical author: its visible label, a friendly
 * type ("Button", "TextView"), whether it's interactive, and its screen bounds (so the UI can draw a
 * highlight box on the mirror). Produced from a [TrailblazeNode] via [TrailRunnerRecordingService.toRecordElement].
 */
@Serializable
internal data class RecordElement(
  /** Visible/spoken text for the element (resolveText), or null for an unlabeled container. */
  val label: String? = null,
  /** Friendly element type — short class name, role, or AX type ("Button", "TextView", "link"). */
  val type: String? = null,
  /** Developer id the selector tools match on (resourceId / accessibility id / Compose testTag). */
  val resourceId: String? = null,
  /** True when the user can tap/type/scroll this element (vs. a static label or container). */
  val interactive: Boolean = false,
  val left: Int = 0,
  val top: Int = 0,
  val right: Int = 0,
  val bottom: Int = 0,
  val centerX: Int = 0,
  val centerY: Int = 0,
)

@Serializable
internal data class RecordTreeResponse(
  val ok: Boolean,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
  /** Interactive / identifiable elements on the current screen, in reading order (top, then left). */
  val elements: List<RecordElement> = emptyList(),
  val error: String? = null,
)

/** One pickable tool for a proposed gesture: a label, the tool name, and its runnable trail-item YAML. */
@Serializable
internal data class RecordToolOption(
  val label: String,
  val toolName: String,
  val yaml: String,
  /** true for `tapOnElementBySelector` candidates, false for the raw `tapOnPoint` fallback. */
  val isSelector: Boolean = false,
)

/**
 * Holds the live recording connections for the Trail Runner daemon and bridges each captured gesture
 * to the device + a recorded tool. Constructed once per daemon (see [recordRoutes]).
 */
internal class TrailRunnerRecordingService(
  private val deviceManager: TrailblazeDeviceManager,
) {
  private val connections = ConcurrentHashMap<TrailblazeDeviceId, RecordingDeviceConnection>()
  private val connectMutexes = ConcurrentHashMap<TrailblazeDeviceId, Mutex>()

  suspend fun connect(deviceId: TrailblazeDeviceId): RecordConnectResponse {
    connections[deviceId]?.let {
      return RecordConnectResponse(ok = true, deviceWidth = it.stream.deviceWidth, deviceHeight = it.stream.deviceHeight)
    }
    val device = resolveDevice(deviceId)
      ?: return RecordConnectResponse(ok = false, error = "Device not found: ${deviceId.toFullyQualifiedDeviceId()}")
    val mutex = connectMutexes.getOrPut(deviceId) { Mutex() }
    return mutex.withLock {
      // Re-check inside the lock so two racing Connect clicks don't open two live connections.
      connections[deviceId]?.let {
        return@withLock RecordConnectResponse(ok = true, deviceWidth = it.stream.deviceWidth, deviceHeight = it.stream.deviceHeight)
      }
      when (val state = deviceManager.connectionService.connectToDevice(device)) {
        is ConnectionState.Connected -> {
          connections[deviceId] = state.connection
          RecordConnectResponse(ok = true, deviceWidth = state.connection.stream.deviceWidth, deviceHeight = state.connection.stream.deviceHeight)
        }
        is ConnectionState.Error -> RecordConnectResponse(ok = false, error = state.message)
        else -> RecordConnectResponse(ok = false, error = "Could not connect to ${deviceId.toFullyQualifiedDeviceId()}")
      }
    }
  }

  suspend fun screen(deviceId: TrailblazeDeviceId): RecordScreenResponse {
    val conn = connections[deviceId]
      ?: return RecordScreenResponse(ok = false, error = "Not connected — call connect first")
    return runCatching {
      // Prefer the tree-less mirror fast path; but some drivers (Android on-device) return empty
      // bytes when there's no fresh frame, which would leave the live view blank. Fall back to the
      // full screenshot so a frame always lands — slower (it builds the tree) but reliable.
      val bytes = conn.stream.getMirrorScreenshot().takeIf { it.isNotEmpty() }
        ?: runCatching { conn.stream.getScreenshot() }.getOrNull()?.takeIf { it.isNotEmpty() }
        // The Android accessibility driver reads the tree but returns no screenshot; fall back to adb.
        ?: hostAndroidScreencap(deviceId)
        ?: ByteArray(0)
      if (bytes.isEmpty()) {
        // Transient capture miss — the client retries on the next poll.
        RecordScreenResponse(ok = true, deviceWidth = conn.stream.deviceWidth, deviceHeight = conn.stream.deviceHeight)
      } else {
        RecordScreenResponse(
          ok = true,
          screenshotBase64 = Base64.getEncoder().encodeToString(bytes),
          mime = mimeOf(bytes),
          deviceWidth = conn.stream.deviceWidth,
          deviceHeight = conn.stream.deviceHeight,
        )
      }
    }.getOrElse {
      RecordScreenResponse(ok = false, error = it.message ?: "capture failed")
    }
  }

  /**
   * Capture a PNG via host `adb screencap` (Android only) by writing to a device temp file and
   * pulling it back — binary-safe, unlike piping through the shell. Null on non-Android or failure.
   */
  private suspend fun hostAndroidScreencap(deviceId: TrailblazeDeviceId): ByteArray? {
    if (deviceId.trailblazeDevicePlatform != TrailblazeDevicePlatform.ANDROID) return null
    return withContext(Dispatchers.IO) {
      val remote = "/data/local/tmp/__tb_mirror_${deviceId.instanceId.filter { it.isLetterOrDigit() }}.png"
      val tmp = File.createTempFile("tb-mirror", ".png")
      try {
        AndroidHostAdbUtils.execAdbShellCommand(deviceId, listOf("screencap", "-p", remote))
        if (!AndroidHostAdbUtils.pullFile(deviceId, remote, tmp)) return@withContext null
        tmp.readBytes().takeIf { it.isNotEmpty() }
      } catch (e: Exception) {
        Console.log("[record] host screencap fallback failed: ${e.message}")
        null
      } finally {
        tmp.delete()
        runCatching { AndroidHostAdbUtils.execAdbShellCommand(deviceId, listOf("rm", "-f", remote)) }
      }
    }
  }

  suspend fun gesture(req: RecordGestureRequest): RecordGestureResponse {
    val conn = connections[req.trailblazeDeviceId]
      ?: return RecordGestureResponse(ok = false, error = "Not connected — call connect first")
    val stream = conn.stream
    val factory = conn.toolFactory
    // Alternative tools the author can choose for a tap (selector candidates + coordinate). Set in
    // the tap branch; empty for other gestures.
    var options: List<RecordToolOption> = emptyList()
    // The element under a tap, described in plain language (label + type + bounds). Null for
    // non-tap gestures and taps that didn't land on a resolvable element.
    var element: RecordElement? = null
    return runCatching {
      // (tool, toolName, label) — null means an unsupported/unknown gesture, mapped to an error below.
      // No explicit type annotation so the trailing `?: return` narrows `captured` to non-null.
      val captured = when (req.type) {
        "tap", "longPress" -> {
          val x = req.x ?: error("tap requires x,y")
          val y = req.y ?: error("tap requires x,y")
          val longPress = req.type == "longPress"
          // Capture the accessibility tree BEFORE the gesture changes the screen, so the selector
          // generator resolves against what the user actually tapped (atomic with the desktop path).
          val tree = runCatching { stream.getTrailblazeNodeTree() }.getOrNull()
          // Plain-language identity of the element under the tap, so the UI can show "you selected
          // the Pay button" + a highlight box instead of raw selector JSON.
          element = tree?.hitTest(x, y)?.let { toRecordElement(it) }
          if (req.action == "assertVisible") {
            // Assertion: resolve the tapped element to a selector and record an assertVisibleBySelector
            // (a verify). Never drives the device. Needs a real element — coordinates can't be asserted.
            val candidates = runCatching { factory.findSelectorCandidates(tree, x, y) }.getOrDefault(emptyList())
            if (candidates.isEmpty()) {
              return@runCatching RecordGestureResponse(
                ok = false,
                error = "Nothing to assert here — tap directly on a labeled element (text, button, icon).",
              )
            }
            options = candidates.map { ns -> assertOption(ns.selector, ns.strategy + if (ns.isBest) " · best" else "") }
            val name = AssertVisibleBySelectorTrailblazeTool::class.toolName().toolName
            Triple(
              AssertVisibleBySelectorTrailblazeTool(nodeSelector = candidates.first().selector) as TrailblazeTool,
              name,
              "Assert visible ($x, $y)",
            )
          } else {
            val (tool, toolName) = if (longPress) {
              factory.createLongPressTool(null, x, y, tree)
            } else {
              factory.createTapTool(null, x, y, tree)
            }
            options = tapToolOptions(factory, tree, x, y, longPress)
            if (!req.resolveOnly) { if (longPress) stream.longPress(x, y) else stream.tap(x, y) }
            Triple(tool, toolName, if (longPress) "Long press ($x, $y)" else "Tap ($x, $y)")
          }
        }
        "swipe" -> {
          val sx = req.startX ?: error("swipe requires start/end")
          val sy = req.startY ?: error("swipe requires start/end")
          val ex = req.endX ?: error("swipe requires start/end")
          val ey = req.endY ?: error("swipe requires start/end")
          val (tool, toolName) = factory.createSwipeTool(sx, sy, ex, ey, req.durationMs)
          if (!req.resolveOnly) stream.swipe(sx, sy, ex, ey, req.durationMs)
          Triple(tool, toolName, "Swipe")
        }
        "inputText" -> {
          val text = req.text.orEmpty()
          val (tool, toolName) = factory.createInputTextTool(text)
          if (!req.resolveOnly) stream.inputText(text)
          Triple(tool, toolName, "Type \"${text.take(24)}\"")
        }
        "pressKey" -> {
          val key = req.key.orEmpty()
          val pair = factory.createPressKeyTool(key)
          if (pair == null) {
            null
          } else {
            if (!req.resolveOnly) stream.pressKey(key)
            Triple(pair.first, pair.second, "Press $key")
          }
        }
        else -> null
      } ?: return@runCatching RecordGestureResponse(
        ok = false,
        error = "Unsupported gesture: ${req.type}${req.key?.let { " ($it)" }.orEmpty()}",
      )

      val (tool, toolName, label) = captured
      val interaction = RecordedInteraction(
        tool = tool,
        toolName = toolName,
        screenshotBytes = null,
        viewHierarchyText = null,
        timestamp = System.currentTimeMillis(),
      )
      RecordGestureResponse(
        ok = true,
        toolName = toolName,
        yaml = RecordingYamlCodec.singleInteractionToTrailYaml(interaction),
        label = label,
        options = options,
        element = element,
      )
    }.getOrElse {
      Console.log("[RecordRoutes] gesture failed: ${it.message}")
      RecordGestureResponse(ok = false, error = it.message ?: "gesture failed")
    }
  }

  /** An `assertVisibleBySelector` option for a selector candidate (used by the Assert-visible action). */
  private fun assertOption(selector: xyz.block.trailblaze.api.TrailblazeNodeSelector, label: String): RecordToolOption =
    toolOption(
      AssertVisibleBySelectorTrailblazeTool(nodeSelector = selector),
      AssertVisibleBySelectorTrailblazeTool::class.toolName().toolName,
      label,
      isSelector = true,
    )

  /** Wrap a tool as a pickable option (label + name + runnable trail-item YAML). */
  private fun toolOption(tool: TrailblazeTool, name: String, label: String, isSelector: Boolean): RecordToolOption =
    RecordToolOption(
      label = label,
      toolName = name,
      yaml = RecordingYamlCodec.singleInteractionToTrailYaml(
        RecordedInteraction(tool, name, null, null, System.currentTimeMillis()),
      ),
      isSelector = isSelector,
    )

  /**
   * The tools an author can pick for a tap: every ranked selector candidate
   * ([InteractionToolFactory.findSelectorCandidates]) as a `tapOnElementBySelector`, plus the raw
   * coordinate `tapOnPoint` as a always-available fallback. The best selector is listed first.
   */
  private fun tapToolOptions(
    factory: xyz.block.trailblaze.recording.InteractionToolFactory,
    tree: xyz.block.trailblaze.api.TrailblazeNode?,
    x: Int,
    y: Int,
    longPress: Boolean,
  ): List<RecordToolOption> {
    val out = mutableListOf<RecordToolOption>()
    val selectorName = TapOnByElementSelector::class.toolName().toolName
    runCatching { factory.findSelectorCandidates(tree, x, y) }.getOrDefault(emptyList()).forEach { ns ->
      out += toolOption(
        TapOnByElementSelector(nodeSelector = ns.selector, longPress = longPress),
        selectorName,
        label = ns.strategy + if (ns.isBest) " · best" else "",
        isSelector = true,
      )
    }
    val pointName = TapOnPointTrailblazeTool::class.toolName().toolName
    out += toolOption(
      TapOnPointTrailblazeTool(x = x, y = y, longPress = longPress),
      pointName,
      label = "Coordinates ($x, $y)",
      isSelector = false,
    )
    return out
  }

  /**
   * The interactive / identifiable elements on the current screen, in reading order. Powers the
   * "Elements" inspector: a non-technical author browses what's on screen (each row a plain label +
   * type), hovers to highlight it on the mirror, and clicks to propose a tap or an assertion — no
   * pixel-hunting, no selector JSON. Filters out unlabeled containers so the list stays scannable.
   */
  suspend fun tree(deviceId: TrailblazeDeviceId): RecordTreeResponse {
    val conn = connections[deviceId]
      ?: return RecordTreeResponse(ok = false, error = "Not connected — call connect first")
    return runCatching {
      val tree = runCatching { conn.stream.getTrailblazeNodeTree() }.getOrNull()
        ?: return@runCatching RecordTreeResponse(
          ok = true,
          deviceWidth = conn.stream.deviceWidth,
          deviceHeight = conn.stream.deviceHeight,
        )
      val elements = tree.aggregate()
        .filter { it.bounds != null && (it.driverDetail.isInteractive || it.driverDetail.hasIdentifiableProperties) }
        .mapNotNull { toRecordElement(it) }
        .filter { (it.right - it.left) > 0 && (it.bottom - it.top) > 0 }
        // Collapse identical boxes (a wrapper + its labeled child often share bounds).
        .distinctBy { "${it.left},${it.top},${it.right},${it.bottom},${it.label},${it.type}" }
        .sortedWith(compareBy({ it.top }, { it.left }))
        .take(150)
      RecordTreeResponse(
        ok = true,
        deviceWidth = conn.stream.deviceWidth,
        deviceHeight = conn.stream.deviceHeight,
        elements = elements,
      )
    }.getOrElse {
      RecordTreeResponse(ok = false, error = it.message ?: "could not read the screen elements")
    }
  }

  /** Builds the plain-language [RecordElement] (label + friendly type + bounds) for a node. */
  private fun toRecordElement(node: TrailblazeNode): RecordElement? {
    val b = node.bounds ?: return null
    val (label, type) = labelAndType(node)
    return RecordElement(
      label = label?.takeIf { it.isNotBlank() },
      type = type?.takeIf { it.isNotBlank() },
      resourceId = identifierOf(node),
      interactive = node.driverDetail.isInteractive,
      left = b.left,
      top = b.top,
      right = b.right,
      bottom = b.bottom,
      centerX = b.centerX,
      centerY = b.centerY,
    )
  }

  /**
   * Splits a node's identity into (visible label, friendly type) for display — same field priority as
   * [xyz.block.trailblaze.api.describe], but kept separate so the UI can lay them out independently.
   */
  private fun labelAndType(node: TrailblazeNode): Pair<String?, String?> =
    when (val d = node.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility -> d.resolveText() to d.className?.substringAfterLast('.')
      is DriverNodeDetail.AndroidMaestro -> d.resolveText() to d.className?.substringAfterLast('.')
      is DriverNodeDetail.IosMaestro -> d.resolveText() to d.className
      is DriverNodeDetail.IosAxe -> d.resolveText() to (d.type ?: d.role?.removePrefix("AX"))
      is DriverNodeDetail.Compose -> d.resolveText() to d.role
      is DriverNodeDetail.Web -> d.ariaName to d.ariaRole
    }

  /** The developer identifier the selector tools key on, per platform. */
  private fun identifierOf(node: TrailblazeNode): String? =
    when (val d = node.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility -> d.uniqueId ?: d.resourceId
      is DriverNodeDetail.AndroidMaestro -> d.resourceId
      is DriverNodeDetail.IosMaestro -> d.resourceId
      is DriverNodeDetail.IosAxe -> d.uniqueId
      is DriverNodeDetail.Compose -> d.testTag
      is DriverNodeDetail.Web -> null
    }?.takeIf { it.isNotBlank() }

  fun disconnect(deviceId: TrailblazeDeviceId) {
    val conn = connections.remove(deviceId) ?: return
    (conn.stream as? AutoCloseable)?.let { runCatching { it.close() } }
  }

  /** Mirrors [xyz.block.trailblaze.host.recording.rpc.ConnectToDeviceHandler]'s device lookup. */
  private fun resolveDevice(deviceId: TrailblazeDeviceId): TrailblazeConnectedDeviceSummary? =
    deviceManager.deviceStateFlow.value.devices[deviceId]?.device
      ?: deviceManager.webBrowserManager.getAllRunningBrowserSummaries()
        .firstOrNull { it.trailblazeDeviceId == deviceId }
      ?: if (deviceId.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE) {
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
          instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
          description = "Playwright Browser (Native)",
        )
      } else {
        null
      }

  /**
   * Sniffs the image MIME from magic bytes. The Android mirror emits WebP, which renders blank in
   * the strict native WKWebView unless labeled correctly (Chromium sniffs and hid the bug on web).
   */
  private fun mimeOf(bytes: ByteArray): String = when {
    bytes.size >= 4 && bytes[0].toInt() and 0xFF == 0x89 && bytes[1].toInt() == 0x50 -> "image/png"
    bytes.size >= 12 &&
      bytes[0].toInt() == 'R'.code && bytes[1].toInt() == 'I'.code &&
      bytes[2].toInt() == 'F'.code && bytes[3].toInt() == 'F'.code &&
      bytes[8].toInt() == 'W'.code && bytes[9].toInt() == 'E'.code &&
      bytes[10].toInt() == 'B'.code && bytes[11].toInt() == 'P'.code -> "image/webp"
    else -> "image/jpeg"
  }
}
