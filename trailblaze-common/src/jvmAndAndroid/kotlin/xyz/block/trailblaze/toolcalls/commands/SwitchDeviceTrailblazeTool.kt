package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolBatchScope
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Hand the session over to another bound device in a multi-device session — the key
 * primitive for trails that span multiple devices, like a dual-display point-of-sale
 * device's seller and buyer displays. After a successful switch, screen capture and tool
 * dispatch follow the newly-active device until the next switch.
 *
 * Devices are addressed by the **names declared in the trail's multi-device
 * configuration** (the named entries of a configuration's `devices:` map) — never by
 * serial/UDID, so trails stay portable across runs. The session starts on the first
 * declared device.
 *
 * Implemented as a [HostLocalExecutableTrailblazeTool] so `BaseTrailblazeAgent` runs it
 * in-process for every agent, before driver-specific dispatch — a device handover is
 * session state, not a device action, and must not be routed to the device being switched
 * away from. `requiresHost = true` because only a host-orchestrated session can hold a
 * second device: on-device instrumentation has no transport to reach one, and the
 * annotation flag keeps this tool out of on-device registration.
 *
 * Mid-batch semantics: the switch takes effect from the NEXT dispatch — this tool
 * invalidates the shared [ToolBatchScope] context so recorded replay rebuilds against the
 * new device, but tools batched into the SAME `runTrailblazeTools` call after this one
 * still run on the already-built context. Authors and the LLM should treat a switch as a
 * step boundary.
 */
@Serializable
@TrailblazeToolClass("switchDevice", requiresHost = true)
@LLMDescription(
  """
Hand the session over to another device bound to this multi-device session. All subsequent
screen observations and tool calls act on that device until the next switchDevice call.
Address devices by the names declared in the trail's multi-device configuration (e.g.
'buyer' or 'seller'). Only available when the session bound a multi-device configuration.
Issue a switch as its own step, then act on the new device's screen.
    """,
)
data class SwitchDeviceTrailblazeTool(
  @LLMDescription(
    "Name of the device to hand the session to — a name declared in the trail's " +
      "multi-device configuration.",
  )
  val name: String,
) : HostLocalExecutableTrailblazeTool {

  init {
    // Fail at decode/construction rather than surfacing a blank name as a confusing
    // "no device bound for name ''" session error.
    require(name.isNotBlank()) {
      "switchDevice requires a non-blank `name:` — the name of a device declared in the " +
        "trail's multi-device configuration."
    }
  }

  companion object {
    /**
     * The name this tool is advertised and RECORDED under. A recorded leg names its tools by
     * string, so callers that recognize a recorded handover (session-start validation, lints)
     * match on this rather than the class.
     */
    const val ADVERTISED_TOOL_NAME = "switchDevice"

    /**
     * Catalog id of the YAML toolset carrying this tool (`multi_device.yaml`). Not target-declared,
     * and not enabled by anything yet: multi-device sessions are mechanical-replay-only, and
     * recorded `switchDevice` steps dispatch without toolset enablement. When the LLM-facing wiring
     * lands, the host runner will auto-enable this toolset on sessions that bind a multi-device
     * configuration — the only condition under which the tool is meaningful.
     */
    const val MULTI_DEVICE_TOOLSET_ID = "multi_device"
  }

  override val advertisedToolName: String get() = ADVERTISED_TOOL_NAME

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val bindings = toolExecutionContext.deviceBindings
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "switchDevice requires a multi-device session, but this session has no " +
          "device bindings. Declare a multi-device configuration in the trail's `config.devices:` " +
          "so the runner binds its devices at session start.",
        command = this,
      )
    // Idempotent on the already-active device: a replayed or retried switch must not fail the
    // trail — the post-condition ("this device is active") already holds.
    if (name == bindings.activeName) {
      return TrailblazeToolResult.Success(
        message = "Device '$name' is already active — no switch needed.",
      )
    }
    if (bindings.deviceFor(name) == null) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "switchDevice: no device bound for name '$name'. " +
          "Bound devices: ${bindings.names.joinToString()}.",
        command = this,
      )
    }
    val previousName = bindings.activeName
    val bound = bindings.switchTo(name)
    // A shared recorded-replay batch context binds the PREVIOUS device (its command executor,
    // device info) — drop it so the next dispatch rebuilds against the new active device.
    ToolBatchScope.invalidateContext()
    return TrailblazeToolResult.Success(
      message = "Switched active device from '$previousName' to '$name' " +
        "(${bound.trailblazeDeviceInfo.trailblazeDeviceId.toFullyQualifiedDeviceId()}). " +
        "Subsequent observations and tools act on this device.",
    )
  }
}
