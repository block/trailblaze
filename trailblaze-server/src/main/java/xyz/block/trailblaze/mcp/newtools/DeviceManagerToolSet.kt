package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import xyz.block.trailblaze.config.KnownTargetMessages
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.devices.WebViewportSpec
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.DeviceBusyException
import xyz.block.trailblaze.mcp.DeviceClaimRegistry
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.McpToolNames
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.toolcalls.SessionDeviceBindings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Minimal MCP tool for device connection.
 *
 * This is the default toolset - provides just the `device` tool for connecting.
 * No session management here - that's for test authoring mode.
 *
 * For screen observation tools, use [ObservationToolSet].
 * For session/trail management, use [SessionManagementToolSet].
 */
@Suppress("unused")
class DeviceManagerToolSet(
  private val sessionContext: TrailblazeMcpSessionContext?,
  private val mcpBridge: TrailblazeMcpBridge,
  private val deviceClaimRegistry: DeviceClaimRegistry? = null,
  /** Callback to terminate a displaced MCP session when yielding a device claim. */
  private val onTerminateSession: ((sessionId: String) -> String?)? = null,
  /** Callback after the session binds to a device and driver. */
  private val onDeviceConnected: (suspend () -> Unit)? = null,
  /** Callback after the current target app changes. */
  private val onTargetAppChanged: (suspend () -> Unit)? = null,
  /**
   * Callback after this session's per-device target override is set or
   * cleared via [setSessionTargetForBoundDevice]. The session id is passed
   * so the server can re-register tools against the right MCP `Server`
   * instance, which triggers `notifications/tools/list_changed` so a
   * connected MCP client (Claude Desktop, Cursor, Goose, …) refetches its
   * tool list and sees the new target's tools. Wired to
   * [xyz.block.trailblaze.logs.server.TrailblazeMcpServer.refreshToolsForSession]
   * in production; null in unit tests, in which case the daemon updates its
   * routing immediately but no list_changed notification is fired (fine for
   * fixtures that aren't running a real MCP transport).
   */
  private val onSessionTargetChanged: ((sessionId: String) -> Unit)? = null,
  /**
   * Pins the most-recently-active unbound real-MCP-client session to
   * [deviceSpec] (with optional target + optional explicit session id).
   * Wired to `TrailblazeMcpServer.pinMostRecentUnboundMcpSession` in
   * production; null in unit tests, which causes [pinMostRecentUnboundMcpSession]
   * to behave as if no candidates exist (the tool stays callable but always
   * reports empty — the same shape it has in production when no MCP clients
   * are open).
   */
  private val onPinMostRecentUnboundMcpSession:
    (suspend (deviceSpec: String, target: String?, explicitSessionId: String?) -> xyz.block.trailblaze.logs.server.TrailblazeMcpServer.PinResult)? = null,
) : ToolSet {

  /**
   * Action type for the device tool.
   */
  enum class DeviceAction {
    /** List all available devices */
    LIST,
    /** Connect to a specific device by ID */
    CONNECT,
    /** Auto-connect to the first available Android device */
    ANDROID,
    /** Auto-connect to the first available iOS device */
    IOS,
    /** Connect to the web browser (Playwright) */
    WEB,
    /**
     * Connect to the Compose desktop driver (the running Trailblaze desktop window's
     * own UI via the in-process Compose RPC server). Hidden platform: addresses as
     * `desktop/self`, only available when the desktop app is running with the
     * self-test server enabled.
     */
    DESKTOP,
    /** Show info about the currently connected device */
    INFO,
    /**
     * Provision a web browser slot with a viewport / device-emulation profile baked
     * in (does NOT launch a browser yet). The slot's spec is consumed the next time
     * `device(action=WEB, deviceId=<id>)` or a trail with `--device web/<id>` launches
     * a browser for that instance. Used by `trailblaze device create web`.
     */
    CREATE_WEB,

    /**
     * Add a device to this session under a NAME, keeping every device already bound —
     * the interactive counterpart of a trail's `config.devices:` configuration, and the
     * multi-device authoring surface for an agent that has no trail yet.
     *
     * Unlike [CONNECT] / [ANDROID] / [IOS] / [WEB], which replace the session's single
     * device, BIND accumulates: bind `seller` then `buyer` and the session holds both,
     * with `switchDevice(name=…)` handing it between them. The first name bound is the
     * active one, mirroring "the first declared entry is the start device".
     */
    BIND,

    /**
     * Remove a named binding added by [BIND]. Unbinding the ACTIVE device hands the
     * session to the first remaining name; unbinding the last one is refused (ending a
     * session is `session(action=STOP)`, not an empty roster).
     */
    UNBIND,
  }

  /**
   * Detail level for the INFO action.
   */
  enum class DeviceDetail {
    /** Basic device summary (default) */
    SUMMARY,
    /** List installed app IDs */
    APPS,
    /** Full info including installed apps */
    FULL,
  }

  @LLMDescription(
    """
    Connect to a device or get device info.

    A single Android or iOS device is auto-connected at session start.
    Use this tool only if you need to switch devices or connect manually:

    device(action=ANDROID) → connect to Android
    device(action=IOS) → connect to iOS
    device(action=WEB) → connect to default web browser (always available)
    device(action=WEB, deviceId="foo") → connect to a named browser instance
    device(action=WEB, deviceId="foo", headless=false) → launch a visible window
    device(action=LIST) → see available devices
    device(action=INFO) → info about the connected device
    device(action=INFO, detail=APPS) → list installed apps
    device(action=INFO, detail=FULL) → full info including apps

    To drive MORE THAN ONE device in this session, bind each one under a name and hand
    the session between them. BIND is additive — it keeps the devices already bound:

    device(action=BIND, name="seller", deviceId="emulator-5554") → first bind is active
    device(action=BIND, name="buyer", deviceId="emulator-5556")
    switchDevice(name="buyer") → subsequent screens and tools act on the buyer device
    device(action=UNBIND, name="buyer")

    Your session is recorded automatically.
    Save it anytime as a reusable test: trail(action=SAVE, name="my_test")
    """
  )
  @Tool(McpToolNames.TOOL_DEVICE)
  suspend fun device(
    @LLMDescription("Action: LIST, CONNECT, ANDROID, IOS, WEB, INFO, CREATE_WEB, BIND, or UNBIND")
    action: DeviceAction,
    @LLMDescription("Device ID / instance ID (for CONNECT, ANDROID, IOS, WEB, CREATE_WEB actions). For WEB and CREATE_WEB, any value provisions a new browser instance keyed by this ID.")
    deviceId: String? = null,
    @LLMDescription("Detail level for INFO action: SUMMARY (default), APPS, or FULL")
    detail: DeviceDetail = DeviceDetail.SUMMARY,
    @LLMDescription("Optional display name for this session (shown in the Trailblaze report)")
    testName: String? = null,
    @LLMDescription("For WEB and CREATE_WEB actions: launch the browser headless (default true). Set false to show a visible browser window. For CREATE_WEB null means 'inherit the slot's current preference', so the desktop UI's headed-when-display-available default is preserved.")
    headless: Boolean? = null,
    @LLMDescription("For CREATE_WEB action: Playwright `devices` preset name (e.g. 'iPhone 14', 'Pixel 7', 'iPad Pro 11') OR raw '<width>x<height>' viewport like '375x812'. Sets the slot's viewport / emulation profile. Pass null to clear.")
    viewport: String? = null,
    @LLMDescription("For INFO action: inspect only this MCP session's device binding. Internal CLI reuse probe; default false preserves the current process-wide device view.")
    sessionOnly: Boolean = false,
    @LLMDescription("For BIND and UNBIND actions: the name this session addresses the device by (e.g. 'seller', 'buyer'). switchDevice(name=…) uses the same names.")
    name: String? = null,
  ): String {
    return when (action) {
      DeviceAction.LIST -> {
        val devices = mcpBridge.getAvailableDevices()
        if (devices.isEmpty()) {
          "Error: No devices available. Connect an Android device/emulator or start an iOS simulator. Web browser is always available via device(action=WEB)."
        } else {
          // Group by physical device (instanceId + platform) to avoid showing
          // duplicate entries for different driver types of the same device.
          // Show only the device matching the configured driver type per platform.
          val configuredAndroid = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.ANDROID)
          val configuredIos = mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.IOS)

          val deduped = devices
            .groupBy { it.instanceId to it.platform }
            .map { (_, variants) ->
              val platform = variants.first().platform
              val configuredType = when (platform) {
                TrailblazeDevicePlatform.ANDROID -> configuredAndroid
                TrailblazeDevicePlatform.IOS -> configuredIos
                else -> null
              }
              // Prefer the variant matching the configured driver type
              variants.find { it.trailblazeDriverType == configuredType } ?: variants.first()
            }

          buildString {
            appendLine("Available devices:")
            deduped.forEach { device ->
              appendLine("  - ${device.instanceId} (${device.platform.displayName}) - ${device.description}")
            }
          }
        }
      }

      DeviceAction.INFO -> {
        // Ordinary INFO retains its process-wide behavior for existing lifecycle commands. The
        // reusable CLI session probe opts into the session-only view so another MCP session's
        // selected device cannot be mistaken for this session's intact association.
        val currentDeviceId = when {
          sessionOnly && sessionContext != null -> sessionContext.associatedDeviceId
          else -> mcpBridge.getCurrentlySelectedDeviceId()
        }
          ?: return "Error: No device connected. Use device(action=LIST) to see available devices, then connect with ANDROID, IOS, WEB, or CONNECT."

        // Driver status (non-null means the driver is installing, initializing, or failed
        // to create). We emit the full device info block AND the status, so both agents
        // and humans see what's running AND what state it's in. CLI polling loops look
        // for "installing"/"failed"/"error" substrings in the response, which still match
        // when the status is appended to the structured block.
        val driverStatus = mcpBridge.getDriverConnectionStatus(currentDeviceId)

        when (detail) {
          DeviceDetail.SUMMARY -> buildString {
            appendDeviceHeader(currentDeviceId)
            appendDriverStatusIfPresent(driverStatus)
            // The roster is what tells an agent which names switchDevice accepts, so INFO reports it
            // rather than making the agent remember what it bound. Omitted entirely for a session
            // with no named bindings — the single-device response is unchanged.
            if (sessionContext != null && sessionContext.boundDeviceNames().isNotEmpty()) {
              appendLine()
              appendLine(describeNamedBindings(sessionContext))
            }
            val toolSummary = buildAvailableToolsSummary(currentDeviceId)
            if (toolSummary != null) {
              appendLine()
              append(toolSummary)
            }
          }
          DeviceDetail.APPS -> {
            // Apps list requires a ready driver — surface the status verbatim instead.
            if (driverStatus != null) {
              buildString {
                appendDeviceHeader(currentDeviceId)
                appendDriverStatusIfPresent(driverStatus)
              }
            } else {
              val apps = mcpBridge.getInstalledAppIds()
              if (apps.isEmpty()) {
                "No installed apps found on device."
              } else {
                buildString {
                  appendLine("Installed apps (${apps.size}):")
                  apps.sorted().forEach { appendLine("  - $it") }
                }
              }
            }
          }
          DeviceDetail.FULL -> buildString {
            appendDeviceHeader(currentDeviceId)
            appendDriverStatusIfPresent(driverStatus)
            // FULL is SUMMARY plus apps, so it carries the roster too — otherwise the agent asking
            // for everything is the one left without the names switchDevice accepts.
            if (sessionContext != null && sessionContext.boundDeviceNames().isNotEmpty()) {
              appendLine()
              appendLine(describeNamedBindings(sessionContext))
            }
            if (driverStatus == null) {
              appendLine()
              // A failed device probe throws; degrade this section rather than discarding the
              // header and driver status the caller also asked for. `catch (Exception)` rather
              // than `runCatching`, which would also swallow the caller's own cancellation (the
              // probe rethrows it) and fatal Errors, and leave us building a payload for a
              // request nobody is waiting on.
              try {
                val apps = mcpBridge.getInstalledAppIds()
                appendLine("Installed apps (${apps.size}):")
                apps.sorted().forEach { appendLine("  - $it") }
              } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
              } catch (e: Exception) {
                appendLine("Installed apps: unavailable (${e.message})")
              }
            }
          }
        }
      }

      DeviceAction.CONNECT -> {
        if (deviceId.isNullOrBlank()) {
          return "Error: deviceId required for CONNECT action. Use LIST to see available devices."
        }
        // CONNECT is intentionally platform-agnostic: the caller has chosen CONNECT
        // precisely because they want to resolve a specific instanceId without caring
        // about platform. ANDROID/IOS are the platform-scoped forms.
        val devices = mcpBridge.getAvailableDevices()
        val device = devices.find { it.instanceId == deviceId }
          ?: return "Error: Device '$deviceId' not found. Use LIST to see available devices."

        connectToDeviceUnified(device.trailblazeDeviceId, testName)
      }

      DeviceAction.ANDROID -> {
        when (val selection = selectDeviceForPlatform(TrailblazeDevicePlatform.ANDROID, deviceId)) {
          is SelectResult.Found -> connectToDeviceUnified(selection.device.trailblazeDeviceId, testName)
          SelectResult.NoneOnPlatform ->
            "Error: No Android device available. Connect an Android device or start an emulator."
          is SelectResult.IdNotFound ->
            "Error: Android device '${selection.requestedId}' not found. Available: ${selection.availableIds.joinToString()}."
        }
      }

      DeviceAction.IOS -> {
        when (val selection = selectDeviceForPlatform(TrailblazeDevicePlatform.IOS, deviceId)) {
          is SelectResult.Found -> connectToDeviceUnified(selection.device.trailblazeDeviceId, testName)
          SelectResult.NoneOnPlatform ->
            "Error: No iOS device available. Start an iOS simulator."
          is SelectResult.IdNotFound ->
            "Error: iOS device '${selection.requestedId}' not found. Available: ${selection.availableIds.joinToString()}."
        }
      }

      DeviceAction.WEB -> {
        // Web devices are virtual: any instanceId can be provisioned on demand.
        // - deviceId=null → fall back to the configured driver-type default
        //   (PLAYWRIGHT_ELECTRON when configured, otherwise PLAYWRIGHT_NATIVE).
        // - deviceId="foo" → connect to (or create) a Playwright browser keyed
        //   by "foo" so multiple CLI commands can run in parallel against
        //   distinct browsers.
        val explicitInstance = deviceId?.takeIf { it.isNotBlank() }
        val resolvedInstanceId = explicitInstance ?: defaultWebInstanceId()
        val webDeviceId = TrailblazeDeviceId(
          instanceId = resolvedInstanceId,
          trailblazeDevicePlatform = TrailblazeDevicePlatform.WEB,
        )
        // Headless preference is applied INSIDE connectToDeviceUnified after the
        // claim succeeds. Setting it here would race against another concurrent
        // device(action=WEB, deviceId=foo, headless=…) call: the second writer
        // wins on the side-channel preference even though only one wins the
        // claim, so the winning command's browser could launch in the wrong mode.
        // WEB preserves the historical "default to headless when caller omits the
        // flag" behavior; CREATE_WEB threads null through so the slot's stored
        // preference (or WebBrowserManager's headed-when-display-available
        // default) takes over.
        connectToDeviceUnified(webDeviceId, testName, webHeadless = headless ?: true)
      }

      DeviceAction.CREATE_WEB -> {
        // Provision-and-launch: apply the slot's viewport/headless preferences
        // and launch the browser in a single bridge call so two concurrent
        // CREATE_WEB invocations for the same instanceId can't observe a
        // half-written slot state. We launch (not just provision) so that
        // subsequent `trail --device web/<id>` commands find the slot in
        // `loadDevicesSuspend`'s output — that listing only surfaces running
        // slots, not provisioned-but-cold ones.
        //
        // [headless] is intentionally a tri-state nullable: null means "inherit
        // the slot's stored preference" so that running CREATE_WEB on a headed
        // workstation doesn't force the singleton browser into a hidden window.
        val instanceId = deviceId?.takeIf { it.isNotBlank() } ?: WebInstanceIds.PLAYWRIGHT_NATIVE
        val spec = viewport?.takeIf { it.isNotBlank() }
        if (spec != null) {
          // Eagerly validate the spec shape so a typo like `web/foo, viewport=375x`
          // surfaces here instead of crashing the next trail's browser launch.
          try {
            WebViewportSpec.parse(spec)
          } catch (e: IllegalArgumentException) {
            return "Error: ${e.message}"
          }
        }
        val descriptor = spec?.let { "viewport=$it" } ?: "Playwright default viewport (1280x800)"
        // Catch the slot-cap IllegalStateException so it surfaces in the
        // structured "Error: …" response shape callers expect, not as an MCP
        // infrastructure failure. Parallel `device create web --instance-id …`
        // loops will trip this once they exhaust MAX_NAMED_SLOTS.
        val launchError = try {
          mcpBridge.launchWebBrowserAwait(
            instanceId = instanceId,
            viewportSpec = spec,
            headless = headless,
          )
        } catch (e: IllegalStateException) {
          return "Error: ${e.message}"
        }
        if (launchError != null) {
          "Error: Provisioned slot 'web/$instanceId' ($descriptor) but browser launch failed: $launchError"
        } else {
          "Provisioned and launched web browser slot 'web/$instanceId' ($descriptor). " +
            "Reference from trails as `--device web/$instanceId`."
        }
      }

      DeviceAction.DESKTOP -> {
        // Compose desktop has exactly one logical instance ("self") — the running
        // Trailblaze desktop window's own UI. The device summary is published by
        // `TrailblazeDeviceManager.loadDevicesSuspend` only when the in-process
        // ComposeRpcServer is responding, so finding the device here doubles as
        // a reachability check.
        val desktopDevice = mcpBridge.getAvailableDevices()
          .find { it.platform == TrailblazeDevicePlatform.DESKTOP }
          ?: return "Error: No Compose desktop driver available. " +
            "Is the Trailblaze desktop app running with self-test server enabled? " +
            "Start it with `trailblaze app`."

        connectToDeviceUnified(desktopDevice.trailblazeDeviceId, testName)
      }

      // BIND and UNBIND move the session's device, so they serialize against each other and against
      // a concurrent switchDevice — see [withDeviceRoutingLock].
      DeviceAction.BIND -> withDeviceRoutingLock("BIND${name?.let { " '$it'" } ?: ""}") {
        bindNamedDevice(name = name, deviceId = deviceId, testName = testName)
      }

      DeviceAction.UNBIND -> withDeviceRoutingLock("UNBIND${name?.let { " '$it'" } ?: ""}") {
        unbindNamedDevice(name = name)
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Named bindings (interactive multi-device)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * Adds [deviceId] to the session under [name] without dropping the devices already bound.
   *
   * Each named device holds its own claim — [DeviceClaimRegistry] is keyed by (device, session), so
   * one session holding several is expressible — and each gets its driver warmed at bind time via
   * [TrailblazeMcpBridge.selectDevice], so a later `switchDevice` is a handover rather than a cold
   * start. Because `selectDevice` also points the bridge at the device it just warmed, binding a
   * NON-active name re-selects the active one afterwards; otherwise the bridge and the session would
   * disagree about which device the next tool call drives.
   */
  private suspend fun bindNamedDevice(
    name: String?,
    deviceId: String?,
    testName: String?,
  ): String {
    val sessionContext = sessionContext
      ?: return "Error: BIND needs an MCP session context, and this toolset was built without one."
    if (name.isNullOrBlank()) {
      return "Error: name required for BIND action, e.g. device(action=BIND, name=\"buyer\", deviceId=\"emulator-5556\")."
    }
    if (deviceId.isNullOrBlank()) {
      return "Error: deviceId required for BIND action. Use LIST to see available devices."
    }
    val device = mcpBridge.getAvailableDevices().find { it.instanceId == deviceId }
      ?: return "Error: Device '$deviceId' not found. Use LIST to see available devices." +
        webBindHint(deviceId)

    // Two names for one device would advertise a cast of two that is really one, and a
    // switchDevice between them would report a handover that moved nothing — the failure is
    // invisible until an agent wonders why the other device never reacts. SessionDeviceBindings
    // rejects the same roster at construction; catching it here keeps it out of the roster.
    val conflictingName = sessionContext.boundDeviceNames().firstOrNull { boundName ->
      boundName != name && sessionContext.boundDevice(boundName)?.trailblazeDeviceId == device.trailblazeDeviceId
    }
    if (conflictingName != null) {
      // Naming a device that already answers to a name means renaming it, and a rename is the one
      // reading that keeps one device on one name. Refusing outright would dead-end the sole
      // binding of a session: unbinding the old name first is what UNBIND refuses.
      val nameHolder = sessionContext.boundDevice(name)
      if (nameHolder == null) {
        return renameBoundDevice(sessionContext, from = conflictingName, to = name, testName = testName)
      }
      return "Error: ${device.instanceId} is already bound as '$conflictingName', and '$name' is " +
        "bound to ${nameHolder.trailblazeDeviceId.instanceId}. One device holds one name, so this " +
        "would have to rename '$conflictingName' AND displace '$name'. Do it in two steps: " +
        "device(action=UNBIND, name=\"$name\"), then bind ${device.instanceId} as '$name'."
    }

    // A name already pointing at a DIFFERENT device is a rebind, not a duplicate: release the
    // outgoing device's claim so it isn't held by a session that no longer addresses it.
    val previous = sessionContext.boundDevice(name)
    // A FIRST bind while an ordinary CONNECT is in effect displaces that device — it never enters
    // the roster and stops being the associated device, so its claim would be held for the life of
    // a session that can no longer address it. Release and report, which is what the reverse
    // direction does when CONNECT drops a roster.
    val displacedUnnamed = sessionContext.associatedDeviceId
      ?.takeIf { sessionContext.boundDeviceNames().isEmpty() && it != device.trailblazeDeviceId }
    val claimError = claimForSession(device.trailblazeDeviceId)
    if (claimError != null) return claimError

    try {
      mcpBridge.selectDevice(device.trailblazeDeviceId)
    } catch (e: Exception) {
      releaseClaimForSession(device.trailblazeDeviceId)
      return "Error: Bound nothing — connecting to '$deviceId' failed: ${e.message}"
    }

    val becameActive = sessionContext.bindNamedDevice(
      name = name,
      device = SessionDeviceBindings.BoundDevice(
        trailblazeDeviceId = device.trailblazeDeviceId,
        // Identity is all a bind knows: nothing on this path probes geometry, and reporting 0x0
        // would be indistinguishable from a real answer.
        trailblazeDeviceInfo = null,
        description = device.description,
        targetId = mcpBridge.getSessionTargetAppIdForDevice(device.trailblazeDeviceId),
      ),
    )
    if (becameActive) {
      sessionContext.setAssociatedDevice(device.trailblazeDeviceId)
      sessionContext.startImplicitRecording()
    } else {
      // Restore the bridge's selection to the active device — see the note on this method.
      sessionContext.activeDeviceName()
        ?.let { sessionContext.boundDevice(it) }
        ?.let { mcpBridge.selectDeviceForSession(it.trailblazeDeviceId) }
    }
    // Both releases run after the roster and the associated device are settled, so the
    // still-addressed check below sees the state a subsequent tool call will dispatch against.
    if (previous != null && previous.trailblazeDeviceId != device.trailblazeDeviceId) {
      releaseDeviceNoLongerAddressed(previous.trailblazeDeviceId)
    }
    displacedUnnamed?.let { releaseDeviceNoLongerAddressed(it) }
    // The driver type is only known once a device is bound, and a newly-bound device can change the
    // advertised surface (its own driver, its own target) — same reason connect fires this.
    refreshToolsForActiveDevice()

    val driverStatus = mcpBridge.getDriverConnectionStatus(device.trailblazeDeviceId)
    return buildString {
      append("Bound '$name' to ${device.instanceId} (${device.platform.displayName})")
      if (previous != null && previous.trailblazeDeviceId != device.trailblazeDeviceId) {
        append(", replacing ${previous.trailblazeDeviceId.instanceId}")
      }
      append(if (becameActive) " — now the ACTIVE device." else ".")
      appendDriverStatusIfPresent(driverStatus)
      displacedUnnamed?.let {
        appendLine()
        append(
          "Released ${it.instanceId}, which was connected without a name — a named roster addresses " +
            "devices by name, so bind it as well if this session still needs it.",
        )
      }
      appendLine()
      append(describeNamedBindings(sessionContext))
      if (testName != null) {
        appendLine()
        append("(testName is ignored by BIND — name the session with session(action=START, title=…).)")
      }
    }
  }

  /**
   * Moves an existing binding from [from] to [to] — a BIND naming a device that is already bound.
   *
   * Nothing device-side changes: the same device stays claimed, selected and active, and the
   * advertised surface is unaffected because `switchDevice` is gated on the roster's SIZE and the
   * names only appear in tool responses.
   */
  private fun renameBoundDevice(
    sessionContext: TrailblazeMcpSessionContext,
    from: String,
    to: String,
    testName: String?,
  ): String {
    val renamed = sessionContext.renameNamedDevice(from, to)
      ?: return "Error: '$from' is no longer bound, so there was nothing to rename to '$to'."
    return buildString {
      append(
        "Renamed '$from' to '$to' (${renamed.trailblazeDeviceId.instanceId}) — the same device, " +
          "under a new name.",
      )
      appendLine()
      append(describeNamedBindings(sessionContext))
      if (testName != null) {
        appendLine()
        append("(testName is ignored by BIND — name the session with session(action=START, title=…).)")
      }
    }
  }

  /**
   * Removes the binding for [name], releasing that device's claim.
   *
   * Refuses to unbind the session's last named device: the session would be left with a connected,
   * claimed device and an empty roster, which reads like a disconnect but isn't one.
   */
  private suspend fun unbindNamedDevice(name: String?): String {
    val sessionContext = sessionContext
      ?: return "Error: UNBIND needs an MCP session context, and this toolset was built without one."
    if (name.isNullOrBlank()) {
      return "Error: name required for UNBIND action, e.g. device(action=UNBIND, name=\"buyer\")."
    }
    return when (val result = sessionContext.unbindNamedDevice(name)) {
      TrailblazeMcpSessionContext.UnbindResult.NotBound -> {
        val bound = sessionContext.boundDeviceNames()
        "Error: No device bound as '$name'." + if (bound.isEmpty()) {
          " This session has no named bindings — add one with device(action=BIND, name=…, deviceId=…)."
        } else {
          " Bound names: ${bound.joinToString()}."
        }
      }

      TrailblazeMcpSessionContext.UnbindResult.LastRemaining ->
        "Error: '$name' is this session's only bound device, so it was kept. Bind another device " +
          "first, or end the session with session(action=STOP)."

      is TrailblazeMcpSessionContext.UnbindResult.Unbound -> {
        if (result.activeChanged) {
          // The unbound device was active — hand the session to the name that took over, so the
          // next tool call routes somewhere real instead of at a released device.
          result.activeName
            ?.let { sessionContext.boundDevice(it) }
            ?.let { promoted ->
              sessionContext.setAssociatedDevice(promoted.trailblazeDeviceId)
              mcpBridge.selectDeviceForSession(promoted.trailblazeDeviceId)
            }
        }
        // Every successful unbind, not just one that moved the active device: the roster's SIZE
        // decides whether switchDevice is advertised, so dropping an inactive name from two to one
        // has to retract it.
        refreshToolsForActiveDevice()
        // After the handover, so a device that was active and is still bound under another name
        // keeps its claim.
        releaseDeviceNoLongerAddressed(result.unbound.trailblazeDeviceId)
        buildString {
          append("Unbound '$name' (${result.unbound.trailblazeDeviceId.instanceId}).")
          if (result.activeChanged) {
            append(" '${result.activeName}' is now the ACTIVE device.")
          }
          appendLine()
          append(describeNamedBindings(sessionContext))
        }
      }
    }
  }

  /**
   * Renders the session's named roster, or a one-liner when it has none. Named devices are what
   * `switchDevice` addresses, so this block is what tells an agent which names are legal.
   */
  private fun describeNamedBindings(sessionContext: TrailblazeMcpSessionContext): String {
    val names = sessionContext.boundDeviceNames()
    if (names.isEmpty()) return "No named device bindings in this session."
    val activeName = sessionContext.activeDeviceName()
    return buildString {
      appendLine(NAMED_DEVICES_HEADER)
      names.forEach { boundName ->
        val bound = sessionContext.boundDevice(boundName) ?: return@forEach
        append("  - $boundName: ${bound.trailblazeDeviceId.toFullyQualifiedDeviceId()}")
        // Resolved now, not at bind time: switchTargetApp and setSessionTargetForBoundDevice both
        // move a device's target without rebuilding the roster, and a stale target here would tell
        // an agent its tools resolve against something they don't.
        val target = mcpBridge.getSessionTargetAppIdForDevice(bound.trailblazeDeviceId)
          ?: bound.targetId
        target?.let { append(" (target: $it)") }
        if (boundName == activeName) append(" [ACTIVE]")
        appendLine()
      }
      append(
        if (names.size > 1) {
          "Hand the session over with ${SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME}(name=\"…\")."
        } else {
          "Bind another device to make ${SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME} available."
        },
      )
    }
  }

  /**
   * `web/<id>` instances are virtual and only appear in the device listing once launched, so a BIND
   * naming one that hasn't been provisioned reads as "not found" without saying why.
   */
  private fun webBindHint(deviceId: String): String =
    if (deviceId.startsWith("web") || deviceId.contains('/')) {
      " Web instances must be launched before they can be bound — run " +
        "device(action=CREATE_WEB, deviceId=\"$deviceId\") first."
    } else {
      ""
    }

  /**
   * Runs [block] as the session's only in-flight device move, so no two operations that move the
   * session's device can interleave.
   *
   * Every one of them writes the roster and [TrailblazeMcpSessionContext.associatedDeviceId] (the id
   * every subsequent `tools/call` routes by) around a suspending bridge call, so two running at once
   * commit different halves: a `switchDevice` that suspends selecting its device while an UNBIND
   * promotes another name leaves the session driving the device it just released and reporting the
   * other. MCP dispatches each `tools/call` independently, so overlapping calls in one session are
   * the client's to make.
   *
   * A move that can't get its turn within
   * [TrailblazeMcpSessionContext.deviceMoveWaitMs] reports what is in flight rather than waiting out
   * a cold bridge connect with nothing to show for it. Unserialized when this toolset was built
   * without a session context — there is no roster to protect.
   */
  private suspend fun withDeviceRoutingLock(label: String, block: suspend () -> String): String {
    val sessionContext = sessionContext ?: return block()
    return sessionContext.runDeviceMove(
      label = label,
      onBusy = { inFlight ->
        "Error: $label did not start — ${inFlight ?: "another device operation"} is still in flight " +
          "on this session, and two of them at once would leave it driving one device and " +
          "reporting another. Retry once it reports back."
      },
      block = block,
    )
  }

  /**
   * Re-registers the session's tools with the device it now routes to as the in-scope device.
   *
   * The registration side resolves the driver and target through
   * `TrailblazeMcpServer.activeDeviceId`, which prefers the per-call
   * [McpDeviceContext.currentDeviceId] — set at dispatch to the device this `tools/call` arrived
   * for, which is the device a bind, unbind or connect has just moved OFF. Left unscoped, the
   * refresh advertises the previous device's surface while dispatch routes to the new one.
   */
  private suspend fun refreshToolsForActiveDevice() {
    val refresh = onDeviceConnected ?: return
    val deviceId = sessionContext?.associatedDeviceId ?: return refresh()
    withContext(McpDeviceContext.currentDeviceId.asContextElement(deviceId)) { refresh() }
  }

  /**
   * Claims [deviceId] for this MCP session, terminating a displaced idle holder the same way
   * [connectToDeviceUnified] does. Returns null on success, or the error string to hand back.
   */
  private fun claimForSession(deviceId: TrailblazeDeviceId): String? {
    val mcpSessionId = sessionContext?.mcpSessionId?.sessionId ?: return null
    val registry = deviceClaimRegistry ?: return null
    return try {
      val previousClaim = registry.claim(deviceId, mcpSessionId)
      if (previousClaim != null && previousClaim.mcpSessionId != mcpSessionId) {
        onTerminateSession?.invoke(previousClaim.mcpSessionId)
      }
      null
    } catch (e: DeviceBusyException) {
      "Error: ${e.message}"
    }
  }

  private fun releaseClaimForSession(deviceId: TrailblazeDeviceId) {
    val mcpSessionId = sessionContext?.mcpSessionId?.sessionId ?: return
    deviceClaimRegistry?.release(deviceId, mcpSessionId)
  }

  /**
   * Ends [deviceId]'s Trailblaze session and releases its claim, but only if nothing in this
   * session still routes to it.
   *
   * The registry holds ONE claim per (device, session), and a device can be reached both as the
   * active association and as a bound name. An unconditional release on unbind or rebind would free
   * a device another session can then take while this one still dispatches there.
   *
   * Ending the session is part of letting the device go, not a nicety: session teardown only walks
   * the devices the session can still address, so a device that leaves that set takes its open
   * session with it. Releasing the claim alone would hand the next MCP session a device whose
   * still-current session it would silently log into.
   *
   * "Addressed" is read as one snapshot ([TrailblazeMcpSessionContext.addressedDeviceIds]) rather
   * than a roster walk, both because it takes the lock once and because it is the same definition
   * session teardown uses — a device this returns cannot be one teardown will forget.
   */
  private suspend fun releaseDeviceNoLongerAddressed(deviceId: TrailblazeDeviceId) {
    val sessionContext = sessionContext ?: return
    if (deviceId in sessionContext.addressedDeviceIds()) return
    // The release runs even if ending the session is cancelled: cancelling a request doesn't put
    // the device back in this session's reach, and an unreleased claim would keep it away from
    // every other session for good.
    try {
      endSessionOnDevice(deviceId)
    } finally {
      releaseClaimForSession(deviceId)
    }
  }

  /**
   * Ends any session running on [deviceId], addressing it explicitly rather than through whatever
   * device this call happens to be dispatched against — the device being let go is by definition
   * not the one the session is switching to.
   *
   * A failure here must not fail the bind or unbind that triggered it: the claim still has to be
   * released, or the device stays locked to a session that can no longer reach it.
   */
  private suspend fun endSessionOnDevice(deviceId: TrailblazeDeviceId) {
    try {
      withContext(McpDeviceContext.currentDeviceId.asContextElement(deviceId)) {
        if (mcpBridge.getActiveSessionId() != null) {
          withTimeout(SESSION_END_TIMEOUT_MS) { mcpBridge.endSession() }
        }
      }
    } catch (e: TimeoutCancellationException) {
      Console.error("Timed out ending session on ${deviceId.instanceId} while releasing it: ${e.message}")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Console.error("Error ending session on ${deviceId.instanceId} while releasing it: ${e.message}")
    }
  }

  /**
   * Default instance ID used by `device(action=WEB, deviceId=null)`. Honors the
   * platform-level configured driver type so installs that have set
   * `web-driver=playwright-electron` route to the electron singleton instead of
   * the native default. Falls back to PLAYWRIGHT_NATIVE for any other case.
   */
  private fun defaultWebInstanceId(): String =
    when (mcpBridge.getConfiguredDriverType(TrailblazeDevicePlatform.WEB)) {
      TrailblazeDriverType.PLAYWRIGHT_ELECTRON -> WebInstanceIds.PLAYWRIGHT_ELECTRON
      else -> WebInstanceIds.PLAYWRIGHT_NATIVE
    }

  /**
   * Replaces the session's device, serialized against every other operation that moves it — see
   * [withDeviceRoutingLock].
   */
  private suspend fun connectToDeviceUnified(
    trailblazeDeviceId: TrailblazeDeviceId,
    testName: String? = null,
    webHeadless: Boolean? = null,
  ): String = withDeviceRoutingLock("connect to ${trailblazeDeviceId.instanceId}") {
    connectAndReplaceSessionDevice(trailblazeDeviceId, testName, webHeadless)
  }

  private suspend fun connectAndReplaceSessionDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
    testName: String?,
    webHeadless: Boolean?,
  ): String {
    // Check exclusive device claim before connecting
    val mcpSessionId = sessionContext?.mcpSessionId?.sessionId
    var displacedSessionInfo: String? = null
    var displacedFromOtherSession = false
    if (deviceClaimRegistry != null && mcpSessionId != null) {
      try {
        val previousClaim = deviceClaimRegistry.claim(trailblazeDeviceId, mcpSessionId)
        // Cross-session yield: terminate the displaced session cleanly. The
        // registry's yield-unless-busy policy already verified the prior holder
        // was idle, so we're not interrupting real work.
        if (previousClaim != null && previousClaim.mcpSessionId != mcpSessionId) {
          val clientName = onTerminateSession?.invoke(previousClaim.mcpSessionId)
          displacedSessionInfo = clientName ?: previousClaim.mcpSessionId
          displacedFromOtherSession = true
        }
      } catch (e: DeviceBusyException) {
        return "Error: ${e.message}"
      }
    }

    // Now that we hold the claim, record the WEB headless preference. Done after
    // the claim so a concurrent caller losing the claim (DeviceBusyException
    // above) can't overwrite our preference before our selectDevice fires. Same-session
    // re-claims also pass through this point, so each command's headless choice is
    // honored on the launch it triggers.
    if (webHeadless != null && trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.WEB) {
      mcpBridge.setWebBrowserHeadless(trailblazeDeviceId.instanceId, webHeadless)
    }

    // When yielding from another iOS session, release any existing persistent
    // driver so selectDevice creates a fresh one. iOS Maestro drivers can't be
    // reused across MCP sessions (the XCTest connection goes stale), unlike
    // Android which handles reconnection.
    if (displacedFromOtherSession && trailblazeDeviceId.trailblazeDevicePlatform == TrailblazeDevicePlatform.IOS) {
      mcpBridge.releasePersistentDeviceConnection(trailblazeDeviceId)
    }

    try {
      mcpBridge.selectDevice(trailblazeDeviceId)
    } catch (e: Exception) {
      // Release only the specific claim we just acquired — not all session claims.
      // The session may have a valid claim on a different device that should be preserved.
      if (deviceClaimRegistry != null && mcpSessionId != null) {
        deviceClaimRegistry.release(trailblazeDeviceId, mcpSessionId)
      }
      throw e
    }

    sessionContext?.setAssociatedDevice(trailblazeDeviceId)
    val droppedBindings = dropNamedRosterForReplacement()
    // Session creation is deferred to the first blaze/ask call so it can be named
    // after the first objective. Start implicit recording now (it just sets a flag).
    // Start implicit recording - user can save later with trail(action=SAVE, name="...")
    sessionContext?.startImplicitRecording()
    // The driver type is only known once a device is bound — this hook lets the MCP
    // server re-register the session's target-scoped tool surface for the new driver.
    refreshToolsForActiveDevice()

    // For WEB: browser may still be initializing (downloading Playwright/Chromium).
    // Surface the status so the MCP client knows to call device(action=WEB) again.
    val driverStatus = mcpBridge.getDriverConnectionStatus(trailblazeDeviceId)
    if (driverStatus != null) return driverStatus

    val displacedMsg = if (displacedSessionInfo != null) {
      " (ended previous session: $displacedSessionInfo)"
    } else {
      ""
    }
    return buildString {
      append("Connected to ${trailblazeDeviceId.instanceId} (${trailblazeDeviceId.trailblazeDevicePlatform.displayName})$displacedMsg. Session recording - save anytime with trail(action=SAVE, name='...')")
      if (droppedBindings.isNotEmpty()) {
        append(
          "\n\nDropped this session's named device bindings (${droppedBindings.joinToString()}) — " +
            "connecting replaces the session's device. Use device(action=BIND, name=…, deviceId=…) " +
            "to build a named roster instead.",
        )
      }
      val toolSummary = buildAvailableToolsSummary(trailblazeDeviceId)
      if (toolSummary != null) {
        append("\n\n")
        append(toolSummary)
      }
    }
  }

  /**
   * Drops the session's named roster because its single device was just replaced, releasing the
   * claim of every device that leaves the session's reach. Returns the names dropped, for the
   * response.
   *
   * CONNECT/ANDROID/IOS/WEB/DESKTOP and the older [connectToDevice] all replace the device, so a
   * roster built by BIND can't survive any of them: leaving it would advertise `switchDevice` names
   * that no longer route anywhere while dispatch goes to the replacement.
   */
  private suspend fun dropNamedRosterForReplacement(): List<String> {
    val sessionContext = sessionContext ?: return emptyList()
    val droppedBindings = sessionContext.boundDeviceNames()
    if (droppedBindings.isEmpty()) return emptyList()
    val dropped = droppedBindings
      .mapNotNull { sessionContext.boundDevice(it)?.trailblazeDeviceId }
      .distinct()
    // Clear first so the release below sees the roster a subsequent tool call dispatches
    // against — the device just connected is still addressed and must keep its claim.
    sessionContext.clearNamedDeviceBindings()
    dropped.forEach { releaseDeviceNoLongerAddressed(it) }
    return droppedBindings
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Individual tools from main (with TOOL_* constant pattern)
  // ─────────────────────────────────────────────────────────────────────────────

  suspend fun getInstalledApps(): String {
    val packages = mcpBridge.getInstalledAppIds()
    return packages.sorted().joinToString("\n")
  }

  suspend fun listConnectedDevices(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableAppTargets().map { it.id }
    )
  }

  @LLMDescription("Connect to the attached device using Trailblaze.")
  @Tool(TOOL_CONNECT_DEVICE)
  suspend fun connectToDevice(
    trailblazeDeviceId: TrailblazeDeviceId,
  ): String = withDeviceRoutingLock("connect to ${trailblazeDeviceId.instanceId}") {
    val result = mcpBridge.selectDevice(trailblazeDeviceId)
    sessionContext?.setAssociatedDevice(trailblazeDeviceId)
    // This tool predates named bindings and keeps its JSON response, but it replaces the session's
    // device like every other connect entry point — so it owes the roster the same cleanup.
    dropNamedRosterForReplacement()
    // Session creation deferred to first blaze/ask call for meaningful naming.
    sessionContext?.startImplicitRecording()
    refreshToolsForActiveDevice()
    TrailblazeJsonInstance.encodeToString(result)
  }

  fun getAvailableAppTargets(): String {
    return TrailblazeJsonInstance.encodeToString(
      mcpBridge.getAvailableAppTargets().map { it.id }
    )
  }

  fun getCurrentTargetApp(): String {
    return mcpBridge.getCurrentAppTargetId() ?: "No target app selected."
  }

  @LLMDescription(
    "Switch the **daemon-wide** target app. Per-device overrides set via " +
      "$TOOL_SET_SESSION_TARGET take precedence on their device — use this for " +
      "fresh sessions, use $TOOL_SET_SESSION_TARGET to scope one device. Read " +
      "the trailblaze://devices/connected resource to see valid app target IDs."
  )
  @Tool(TOOL_SWITCH_TARGET)
  suspend fun switchTargetApp(
    @LLMDescription("The ID of the app target to switch to (e.g., 'myApp', 'otherApp'). Must match one of the available app target IDs.")
    appTargetId: String,
  ): String {
    val displayName = mcpBridge.selectAppTarget(appTargetId)
    if (displayName != null) {
      onTargetAppChanged?.invoke()
    }
    return if (displayName != null) {
      "Switched target app to: $displayName ($appTargetId)"
    } else {
      val availableIds = mcpBridge.getAvailableAppTargets().map { it.id }
      "Failed to switch target app. '$appTargetId' not found. Available targets: $availableIds" +
        KnownTargetMessages.unavailableTargetHint(appTargetId)?.let { "\n$it" }.orEmpty()
    }
  }

  /**
   * Set or clear the **per-device** target override for the MCP session's
   * currently-bound device. Unlike [switchTargetApp] (daemon-wide), this
   * writes to the daemon's in-memory per-device map and dies with the daemon
   * — used by the CLI to scope `--target X --device Y` to a single device
   * without contaminating commands run against a different device.
   *
   * Pass an empty string (or the literal `"clear"`) for [appTargetId] to
   * remove the override, falling back to the daemon-wide target.
   *
   * Throws on failure (no device bound; unknown target id). The MCP framework
   * converts the exception into a tool-call response with `isError=true`, so
   * CLI callers can use `result.isError` to detect failure rather than parsing
   * the message text.
   */
  @LLMDescription(
    "Set the target app for this session's bound device only. " +
      "Does NOT change the daemon-wide default. Pass '' or 'clear' to remove the override. " +
      "Read the trailblaze://devices/connected resource for valid target IDs."
  )
  @Tool(TOOL_SET_SESSION_TARGET)
  suspend fun setSessionTargetForBoundDevice(
    @LLMDescription("Target app ID, or '' / 'clear' to remove the per-device override.")
    appTargetId: String,
  ): String {
    val deviceId = sessionContext?.associatedDeviceId
      ?: throw IllegalStateException(
        "No device is bound to this session. Connect a device first via $TOOL_CONNECT_DEVICE."
      )
    val cleared = appTargetId.isBlank() || appTargetId.equals("clear", ignoreCase = true)
    if (cleared) {
      mcpBridge.setSessionTargetForDevice(deviceId = deviceId, appTargetId = null)
      // Fire list_changed so the MCP client refetches and drops any
      // target-specific tools that just disappeared.
      sessionContext.mcpSessionId.sessionId.let { onSessionTargetChanged?.invoke(it) }
      return "Cleared session target override for ${deviceId.toFullyQualifiedDeviceId()}."
    }
    val resolvedDisplayName = mcpBridge.setSessionTargetForDevice(
      deviceId = deviceId,
      appTargetId = appTargetId,
    ) ?: run {
      val availableIds = mcpBridge.getAvailableAppTargets().map { it.id }
      throw IllegalArgumentException(
        "'$appTargetId' is not a known target id. Available: $availableIds" +
          KnownTargetMessages.unavailableTargetHint(appTargetId)?.let { "\n$it" }.orEmpty()
      )
    }
    // Daemon-side target is now set; re-register tools so the SDK fires
    // `notifications/tools/list_changed` and the MCP client picks up
    // target-specific tools (e.g. `myapp_launchSignedIn`) that weren't in
    // the initialize-time tool list.
    sessionContext.mcpSessionId.sessionId.let { onSessionTargetChanged?.invoke(it) }
    return "Set session target to $resolvedDisplayName ($appTargetId) for ${deviceId.toFullyQualifiedDeviceId()}."
  }

  /**
   * Pin the most-recently-active unbound real-MCP-client session to the given
   * device (and optionally target).
   *
   * Called by the CLI's `device connect` flow right after its own session has
   * bound the device + target. If a real MCP client (Claude Desktop, Cursor,
   * Goose, …) is open and hasn't yet picked a device, we silently adopt it
   * into the same device + target the shell user just chose — so the user's
   * next "tap the login button" from inside Claude Desktop routes to the same
   * place their shell is now driving.
   *
   * Returns:
   * - one-line "Pinned …" description when a candidate was found and pinned
   * - empty string when no candidates exist (non-error — the common case
   *   where nobody has an MCP client open)
   * - throws [IllegalArgumentException] when [deviceSpec] doesn't resolve to a
   *   known device (the MCP framework converts this into isError=true)
   */
  @LLMDescription(
    "Pin the most-recently-active unbound MCP client session to a device. " +
      "Used by the CLI's `device connect` flow; agents should not call this directly.",
  )
  @Tool(TOOL_PIN_MCP_SESSION)
  suspend fun pinMostRecentUnboundMcpSession(
    @LLMDescription("Device spec, e.g. `android`, `android/emulator-5554`, or `web/checkout`.")
    deviceSpec: String,
    @LLMDescription("Optional target app id to pin alongside the device. Empty/null = no target override.")
    target: String? = null,
    @LLMDescription("Optional explicit MCP session id to pin (skips the most-recent-active selection).")
    mcpSessionId: String? = null,
  ): String {
    val callback = onPinMostRecentUnboundMcpSession ?: return ""
    val result = callback(deviceSpec, target?.takeIf { it.isNotBlank() }, mcpSessionId?.takeIf { it.isNotBlank() })
    return when (result) {
      is xyz.block.trailblaze.logs.server.TrailblazeMcpServer.PinResult.Pinned ->
        "Pinned session ${result.sessionId} (${result.mcpClientName ?: "unknown client"}) to ${result.deviceId.toFullyQualifiedDeviceId()}"
      xyz.block.trailblaze.logs.server.TrailblazeMcpServer.PinResult.NoCandidates -> ""
      is xyz.block.trailblaze.logs.server.TrailblazeMcpServer.PinResult.DeviceNotFound ->
        throw IllegalArgumentException("Unknown device spec: ${result.deviceSpec}")
      // Explicit-id misses are reported as IllegalArgumentException so the
      // MCP framework converts them to `isError=true` and the CLI surfaces
      // the typo loudly rather than going silent.
      is xyz.block.trailblaze.logs.server.TrailblazeMcpServer.PinResult.ExplicitSessionNotFound ->
        throw IllegalArgumentException(
          "No unbound real-MCP-client session matches the explicit id '${result.explicitSessionId}'. " +
            "Run `trailblaze mcp sessions` (or check the daemon logs) to see live sessions."
        )
    }
  }

  @LLMDescription(
    "Runs a natural language prompt on the connected device.",
  )
  @Tool(TOOL_RUN_PROMPT)
  suspend fun runPrompt(
    @LLMDescription(
      """
      The natural language steps you would like performed on the device.
      NOTE: The more steps you give, the longer it will take to perform the tasks.  Prefer fewer steps.
      """
    )
    steps: List<String>,
  ): String {
    // Advertisement gating already hides runPrompt when no daemon LLM is configured, but
    // that's list-time only — a client with a cached descriptor (or one that raced a
    // tools/list_changed) could still dispatch it, starting the daemon-side agent loop
    // with no model. Guard the dispatch too, returning a clean error instead.
    val llmProvider = mcpBridge.getLlmConfig()?.first
    if (llmProvider.isNullOrBlank() || llmProvider.equals("none", ignoreCase = true)) {
      return "runPrompt requires a daemon LLM, but none is configured. " +
        "Configure one with config(action=SET, key=\"llmProvider\", value=\"...\") and a model, " +
        "or drive the device directly with step(tools=[...]) / the primitive device tools."
    }

    // Emit the unified format (a `trail:` of `step:` entries), not the legacy v1 list shape, so the
    // dispatched YAML decodes without the v1 parser.
    val yaml = createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrail(
        config = UnifiedTrailConfig(),
        trail = steps.map { promptLine -> UnifiedTrailStep(step = promptLine) },
      ),
    )

    val sessionId = mcpBridge.runYaml(
      yaml = yaml,
      startNewSession = false,
      agentImplementation = sessionContext?.agentImplementation ?: AgentImplementation.DEFAULT,
    )

    return buildString {
      appendLine("Execution started.")
      appendLine("Steps: ${steps.size}")
      if (sessionId != null) {
        appendLine("Session ID: $sessionId")
        appendLine()
        appendLine("The test is now running asynchronously on the device.")
        appendLine("Wait at least 30 seconds before calling getSessionResults with this session ID.")
        appendLine("If the status is still IN PROGRESS, wait another 15-30 seconds and check again.")
      }
    }
  }

  @LLMDescription(
    "End a running Trailblaze session on the connected device.",
  )
  @Tool(TOOL_END_SESSION)
  suspend fun endSession(): String {
    val wasSessionEnded = mcpBridge.endSession()
    return "Session ended with result: $wasSessionEnded"
  }

  /**
   * Builds a summary of custom tools available for the current target + driver.
   * Returns null if no target is set, no driver is connected, or no custom tools exist.
   *
   * Resolves the target via the per-device session override
   * ([TrailblazeMcpBridge.getSessionTargetAppIdForDevice]) so `--target` set
   * on `device connect` shows up in the summary instead of the daemon-wide
   * default — otherwise an LLM/human reading the connect summary would see
   * a tool list built against the wrong target and not understand why their
   * dispatched YAML tools (e.g. `pressBack`) get rejected.
   */
  private fun buildAvailableToolsSummary(deviceId: TrailblazeDeviceId): String? {
    val driverType = mcpBridge.getDriverType() ?: return null
    val targetId = mcpBridge.getSessionTargetAppIdForDevice(deviceId) ?: return null
    val target = mcpBridge.getAvailableAppTargets().firstOrNull { it.id == targetId } ?: return null
    if (target.id == TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget.id) return null

    val groups = try {
      target.getCustomToolGroupsForDriver(driverType)
    } catch (_: Exception) {
      return null
    }
    if (groups.isEmpty()) return null

    return buildString {
      appendLine("Available ${target.displayName} tools (${driverType.platform.displayName}):")
      for (group in groups) {
        val toolNames = group.toMergedDescriptors()
          .map { it.name }
          .sorted()
        if (toolNames.isNotEmpty()) {
          appendLine("  ${group.id}: ${toolNames.joinToString(", ")}")
        }
      }
      append("Use tools(target=\"${target.id}\") for details, or blaze(objective=\"...\") — the inner agent selects the right tool automatically.")
    }
  }

  /**
   * Emits the `Connected device:` header with Instance ID, Platform, and Driver lines.
   * Parsed by `CliMcpClient.parseConnectedInstanceId` / `parseDevicePlatform`, so the
   * format must stay stable even when a driver status is also reported.
   */
  private fun StringBuilder.appendDeviceHeader(deviceId: TrailblazeDeviceId) {
    val driverType = mcpBridge.getDriverType()
    appendLine("Connected device:")
    appendLine("  Instance ID: ${deviceId.instanceId}")
    appendLine("  Platform: ${deviceId.trailblazeDevicePlatform.displayName}")
    if (driverType != null) {
      appendLine("  Driver: $driverType")
    }
  }

  /**
   * Appends a `Driver status:` block when the driver is installing, initializing,
   * or has failed to start. Keeps the original wording (including "installing" /
   * "failed" / "error" keywords) so CLI polling loops still match on substring.
   */
  private fun StringBuilder.appendDriverStatusIfPresent(driverStatus: String?) {
    if (driverStatus == null) return
    appendLine()
    appendLine("Driver status: $driverStatus")
  }

  /**
   * Selects a device on [platform]. When [deviceId] is non-blank the caller has
   * asked for a specific instance, so we never silently pick a different one.
   * Otherwise we prefer the configured driver type from settings, falling back
   * to the first device on the platform.
   */
  private suspend fun selectDeviceForPlatform(
    platform: TrailblazeDevicePlatform,
    deviceId: String?,
  ): SelectResult {
    val platformDevices = mcpBridge.getAvailableDevices().filter { it.platform == platform }
    if (!deviceId.isNullOrBlank()) {
      if (platformDevices.isEmpty()) return SelectResult.NoneOnPlatform
      val match = platformDevices.find { it.instanceId == deviceId }
        ?: return SelectResult.IdNotFound(
          deviceId,
          platformDevices.map { it.instanceId }.distinct().sorted(),
        )
      return SelectResult.Found(match)
    }
    val configuredDriverType = mcpBridge.getConfiguredDriverType(platform)
    val auto = if (configuredDriverType != null) {
      platformDevices.find { it.trailblazeDriverType == configuredDriverType }
        ?: platformDevices.firstOrNull()
    } else {
      platformDevices.firstOrNull()
    }
    return auto?.let { SelectResult.Found(it) } ?: SelectResult.NoneOnPlatform
  }

  private sealed interface SelectResult {
    data class Found(val device: TrailblazeConnectedDeviceSummary) : SelectResult
    object NoneOnPlatform : SelectResult
    data class IdNotFound(val requestedId: String, val availableIds: List<String>) : SelectResult
  }

  companion object {
    // Tool names - referenced in @Tool annotations and LLM descriptions
    const val TOOL_GET_INSTALLED_APPS = "getInstalledApps"
    const val TOOL_LIST_DEVICES = "listConnectedDevices"
    const val TOOL_CONNECT_DEVICE = "connectToDevice"
    const val TOOL_GET_APP_TARGETS = "getAvailableAppTargets"
    const val TOOL_GET_CURRENT_TARGET = "getCurrentTargetApp"
    const val TOOL_SWITCH_TARGET = "switchTargetApp"
    const val TOOL_SET_SESSION_TARGET = "setSessionTargetForBoundDevice"
    const val TOOL_PIN_MCP_SESSION = "pinMostRecentUnboundMcpSession"
    const val TOOL_RUN_PROMPT = "runPrompt"
    const val TOOL_END_SESSION = "endSession"

    /**
     * Cap on ending the session of a device this session is letting go, matching the one session
     * teardown uses. A device that won't finalize can't be allowed to hold up the bind or unbind
     * that released it.
     */
    private const val SESSION_END_TIMEOUT_MS = 5_000L

    // Parameter names for the `device` tool. MCP binds arguments by the Kotlin
    // parameter name via reflection, so these string keys must exactly match
    // the parameter names on [device]. Callers (e.g. CliMcpClient) should reference
    // these constants instead of hardcoding the string.
    const val PARAM_DEVICE_ID = "deviceId"
    const val PARAM_HEADLESS = "headless"

    /**
     * Header the INFO response prints above a session's named-device roster.
     *
     * A const rather than an inline literal because the CLI keys on this line to find the roster
     * in the response text, and uses its presence to decide which MCP session owns a session's
     * lifecycle. Reworded inline, that decision would silently invert with nothing failing.
     */
    const val NAMED_DEVICES_HEADER = "Named devices in this session:"
  }
}
