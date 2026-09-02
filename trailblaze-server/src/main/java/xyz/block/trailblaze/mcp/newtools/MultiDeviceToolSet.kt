package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.mcp.McpDeviceContext
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool

/**
 * The interactive half of multi-device: hands an MCP session from one of its named devices to
 * another.
 *
 * Advertised only to a session holding **two or more** named bindings (see
 * `TrailblazeMcpServer.registerTools`), because below that there is nothing to switch to and a
 * single-device session shouldn't pay context for the tool. The names come from
 * `device(action=BIND, name=…)`.
 *
 * The wire name is [SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME] — the same verb a recorded
 * multi-device trail replays — so what an agent learns interactively is what it then records. This
 * is a separate implementation rather than a dispatch of that tool because a handover on an MCP
 * session has consequences the in-trail tool has no business knowing: the session's active device
 * id (which every subsequent `tools/call` routes by), the bridge's device selection, and a tool-list
 * re-registration, since the devices can differ in driver and per-device target and therefore in
 * which tools are legal.
 */
@Suppress("unused")
class MultiDeviceToolSet(
  private val sessionContext: TrailblazeMcpSessionContext,
  private val mcpBridge: TrailblazeMcpBridge,
  /**
   * Called after the active device changes, so the server re-registers the session's tools against
   * the new device's driver + target and the client gets a `tools/list_changed`. Null in tests.
   */
  private val onActiveDeviceChanged: (suspend () -> Unit)? = null,
) : ToolSet {

  @LLMDescription(
    """
    Hand this session over to another device bound to it. All subsequent screen observations and
    tool calls act on that device until the next switchDevice call.

    Address devices by the names they were bound under (device(action=BIND, name="buyer", ...)).
    device(action=INFO) lists them and marks the currently active one.

    Issue a switch as its own step, then act on the new device's screen.
    """,
  )
  @Tool(SwitchDeviceTrailblazeTool.ADVERTISED_TOOL_NAME)
  suspend fun switchDevice(
    @LLMDescription("Name of the device to hand the session to, as bound via device(action=BIND).")
    name: String,
  ): String = sessionContext.runDeviceMove(
    label = "switchDevice to '$name'",
    onBusy = { inFlight ->
      "Error: the session was not handed to '$name' — ${inFlight ?: "another device operation"} is " +
        "still in flight, and two of them at once would leave the session driving one device and " +
        "reporting another. Still active: ${sessionContext.activeDeviceName()}. Retry once it " +
        "reports back."
    },
    block = { handOverTo(name) },
  )

  /**
   * The handover itself. Runs as the session's only in-flight device move (see
   * [TrailblazeMcpSessionContext.runDeviceMove]) because it moves the active name, suspends
   * selecting the device on the bridge, and only then commits the routing id — a window in which a
   * second mover would commit the other half.
   */
  private suspend fun handOverTo(name: String): String {
    if (name.isBlank()) {
      return "Error: switchDevice requires a non-blank name — one of the names this session bound " +
        "devices under. ${boundNamesSuffix()}"
    }
    val boundNames = sessionContext.boundDeviceNames()
    if (boundNames.isEmpty()) {
      return "Error: This session has no named device bindings, so there is nothing to switch " +
        "between. Bind devices first: device(action=BIND, name=\"seller\", deviceId=\"…\") then " +
        "device(action=BIND, name=\"buyer\", deviceId=\"…\")."
    }
    // Idempotent on the already-active device, matching the in-trail tool: the post-condition
    // ("this device is active") already holds, so a repeated or retried switch is not a failure.
    if (name == sessionContext.activeDeviceName()) {
      return "Device '$name' is already active — no switch needed."
    }
    if (sessionContext.boundDevice(name) == null) {
      return "Error: No device bound as '$name'. ${boundNamesSuffix()}"
    }

    val previousName = sessionContext.activeDeviceName()
    val switched = sessionContext.switchActiveNamedDevice(name)
      ?: return "Error: No device bound as '$name'. ${boundNamesSuffix()}"

    // Point the bridge at the new device before reporting success. This is a full selectDevice, not
    // just a per-session selection: it guarantees the newly-active device has a live driver even if
    // its connection lapsed while another device held the session, and the refreshed tool surface is
    // resolved from the bridge's driver, so a stale selection would advertise the previous device's
    // tools.
    try {
      mcpBridge.selectDevice(switched.trailblazeDeviceId)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
      // Kotlin's CancellationException IS an Exception, so without this a cancelled `tools/call`
      // would be rolled back and reported as an unreachable device instead of propagating.
      handBackTo(previousName)
      throw e
    } catch (e: Exception) {
      // Hand the session back rather than leaving it pointed at a device we couldn't reach —
      // otherwise every following tool call would route to a device with no driver.
      handBackTo(previousName)
      return "Error: Could not hand the session to '$name' " +
        "(${switched.trailblazeDeviceId.toFullyQualifiedDeviceId()}): ${e.message}. " +
        "Still active: ${sessionContext.activeDeviceName()}."
    }

    // `selectDevice` can succeed against cached device state that has outlived the device — an
    // Android emulator that dropped off adb while it sat inactive, a driver whose creation failed.
    // Read the status BEFORE committing, so a dead device is refused instead of becoming the active
    // one with the bad news appended to a success message.
    val driverStatus = mcpBridge.getDriverConnectionStatus(switched.trailblazeDeviceId)
    if (driverStatus != null && isTerminalDriverStatus(driverStatus)) {
      handBackTo(previousName)
      return "Error: Could not hand the session to '$name' " +
        "(${switched.trailblazeDeviceId.toFullyQualifiedDeviceId()}): $driverStatus " +
        "Still active: ${sessionContext.activeDeviceName()}."
    }

    // The session's device id is what every subsequent `tools/call` routes by; the per-session
    // selection covers the remainder of THIS call.
    sessionContext.setAssociatedDevice(switched.trailblazeDeviceId)
    mcpBridge.selectDeviceForSession(switched.trailblazeDeviceId)
    // Scoped to the device the session just moved TO: the re-registration resolves the driver and
    // target through the per-call McpDeviceContext, which still holds the device this `tools/call`
    // was dispatched for — the one being handed off. Unscoped, the refresh would advertise the
    // previous device's tools for the new one.
    onActiveDeviceChanged?.let { refresh ->
      withContext(McpDeviceContext.currentDeviceId.asContextElement(switched.trailblazeDeviceId)) {
        refresh()
      }
    }

    return buildString {
      append(
        "Switched active device from '$previousName' to '$name' " +
          "(${switched.trailblazeDeviceId.toFullyQualifiedDeviceId()}). " +
          "Subsequent observations and tools act on this device.",
      )
      // Resolved now rather than read off the binding: a per-device or daemon-wide target change
      // doesn't rebuild the roster, and naming a target the device no longer resolves against is
      // worse than naming none.
      val target = mcpBridge.getSessionTargetAppIdForDevice(switched.trailblazeDeviceId)
        ?: switched.targetId
      target?.let { append(" Target: $it.") }
      if (driverStatus != null) {
        append("\n\nDriver status: $driverStatus")
      }
    }
  }

  /**
   * Undoes a switch that could not be completed: the roster's active name AND the bridge's
   * selection. Rolling back the name alone isn't enough — `selectDevice` points the bridge at the
   * device before it can fail or report a dead driver, so the rest of this call would still be
   * routed to the device just rejected.
   */
  private fun handBackTo(previousName: String?) {
    val restored = previousName?.let { sessionContext.switchActiveNamedDevice(it) } ?: return
    mcpBridge.selectDeviceForSession(restored.trailblazeDeviceId)
  }

  private fun boundNamesSuffix(): String {
    val names = sessionContext.boundDeviceNames()
    return if (names.isEmpty()) {
      "This session has no named bindings."
    } else {
      "Bound devices: ${names.joinToString()} (active: ${sessionContext.activeDeviceName()})."
    }
  }

  internal companion object {
    /**
     * Whether a driver status means the device can't serve tool calls at all, as opposed to being
     * on its way up.
     *
     * There is no status enum — the bridge returns prose — so the two in-progress shapes are named
     * and everything else (dropped off adb, driver creation failed, browser install failed) counts
     * as terminal. Erring in that direction keeps a switch from committing to a device nothing can
     * reach; a false positive costs a refused switch the agent can simply retry.
     */
    internal fun isTerminalDriverStatus(status: String): Boolean =
      !status.contains("initializing", ignoreCase = true) &&
        !status.contains("installing", ignoreCase = true)
  }
}
