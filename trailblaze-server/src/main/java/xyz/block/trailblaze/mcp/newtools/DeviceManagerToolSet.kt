package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
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
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.trailblazeToolClassAnnotation
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.models.TrailblazeYamlBuilder

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
  private val toolSetCatalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  private val onActiveToolSetsChanged: (activeToolSetIds: List<String>, catalog: List<ToolSetCatalogEntry>) -> Unit = { _, _ -> },
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

    Your session is recorded automatically.
    Save it anytime as a reusable test: trail(action=SAVE, name="my_test")
    """
  )
  @Tool(McpToolProfile.TOOL_DEVICE)
  suspend fun device(
    @LLMDescription("Action: LIST, CONNECT, ANDROID, IOS, WEB, INFO, or CREATE_WEB")
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
        val currentDeviceId = mcpBridge.getCurrentlySelectedDeviceId()
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
            if (driverStatus == null) {
              val apps = mcpBridge.getInstalledAppIds()
              appendLine()
              appendLine("Installed apps (${apps.size}):")
              apps.sorted().forEach { appendLine("  - $it") }
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

  private suspend fun connectToDeviceUnified(
    trailblazeDeviceId: TrailblazeDeviceId,
    testName: String? = null,
    webHeadless: Boolean? = null,
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
    // Session creation is deferred to the first blaze/ask call so it can be named
    // after the first objective. Start implicit recording now (it just sets a flag).
    // Start implicit recording - user can save later with trail(action=SAVE, name="...")
    sessionContext?.startImplicitRecording()

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
      val toolSummary = buildAvailableToolsSummary(trailblazeDeviceId)
      if (toolSummary != null) {
        append("\n\n")
        append(toolSummary)
      }
    }
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
  suspend fun connectToDevice(trailblazeDeviceId: TrailblazeDeviceId): String {
    val result = mcpBridge.selectDevice(trailblazeDeviceId)
    sessionContext?.setAssociatedDevice(trailblazeDeviceId)
    // Session creation deferred to first blaze/ask call for meaningful naming.
    sessionContext?.startImplicitRecording()
    onDeviceConnected?.invoke()
    return TrailblazeJsonInstance.encodeToString(result)
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
      "Failed to switch target app. '$appTargetId' not found. Available targets: $availableIds"
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
        "'$appTargetId' is not a known target id. Available: $availableIds"
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
    val yaml = createTrailblazeYaml().encodeToString(
      TrailblazeYamlBuilder()
        .apply {
          steps.forEach { promptLine ->
            this.prompt(promptLine)
          }
        }
        .build()
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

  @LLMDescription(
    """Enable additional tool sets for device interaction. By default, only core tools (tap, input text) are available.
Use this to enable more tools when needed. You can enable multiple tool sets at once.
Call with an empty list to reset to only the core tools.""",
  )
  @Tool(TOOL_SET_ACTIVE_TOOLSETS)
  fun setActiveToolSets(
    @LLMDescription("The list of toolset IDs to enable (e.g. ['navigation', 'text-editing']). Core tools are always included.")
    toolSetIds: List<String>,
  ): String {
    val validIds = toolSetCatalog.map { it.id }.toSet()
    val invalidIds = toolSetIds.filter { it !in validIds }
    if (invalidIds.isNotEmpty()) {
      return "Unknown toolset IDs: $invalidIds. Valid IDs: ${validIds.filter { id -> toolSetCatalog.firstOrNull { it.id == id }?.alwaysEnabled != true }}"
    }

    onActiveToolSetsChanged(toolSetIds, toolSetCatalog)

    // Driver-aware resolve so the acknowledgement string matches what the LLM actually
    // gets registered (see `TrailblazeMcpServer.registerTools`'s onActiveToolSetsChanged
    // path). The central `resolveForSession` helper handles the null-driver fallback so
    // all three callsites (here, MCP server, TrailblazeToolRepo) stay aligned.
    val driverType = mcpBridge.getDriverType()
    val resolved = TrailblazeToolSetCatalog.resolveForSession(driverType, toolSetIds, toolSetCatalog)
    // Apply the same `surfaceToLlm` filter that `KClass.toKoogToolDescriptor()` applies
    // at MCP registration time so the acknowledgement count matches what the LLM ends up
    // with — without this, `android_adbShell` and friends (surfaceToLlm = false) inflate
    // the count even though they never reach the LLM.
    val classNames = resolved.toolClasses
      .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
      .map { it.simpleName?.removeSuffix("TrailblazeTool")?.removeSuffix("Tool") ?: it.toString() }
    val toolNames = classNames +
      resolved.yamlToolNames.map { it.toolName } +
      resolved.scriptedToolNames.map { it.toolName }
    return buildString {
      appendLine("Active tool sets updated.")
      appendLine("Enabled sets: ${(toolSetIds + "core").distinct()}")
      appendLine("Total tools available: ${toolNames.size}")
      appendLine("Tools: $toolNames")
      appendLine("[driver=${driverType?.name ?: "none"}]")
    }
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
    const val TOOL_SET_ACTIVE_TOOLSETS = "setActiveToolSets"

    // Parameter names for the `device` tool. MCP binds arguments by the Kotlin
    // parameter name via reflection, so these string keys must exactly match
    // the parameter names on [device]. Callers (e.g. CliMcpClient) should reference
    // these constants instead of hardcoding the string.
    const val PARAM_DEVICE_ID = "deviceId"
    const val PARAM_HEADLESS = "headless"
  }
}
