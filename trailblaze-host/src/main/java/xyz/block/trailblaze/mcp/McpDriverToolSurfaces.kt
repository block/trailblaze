package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

/**
 * What tools an MCP session offers for the driver it is bound to.
 *
 * Two questions, both answered from one place because they were previously two `when (driverType)`
 * blocks in `TrailblazeMcpBridgeImpl` that had drifted: one listed Compose, the other listed
 * Playwright and Revyl, and neither said why the lists differed.
 *
 * The tool *classes* come from [xyz.block.trailblaze.host.driver.HostDriverDescriptor.toolClasses]
 * — the driver says what it brings, here as everywhere. What stays here is the part that is genuinely
 * MCP's own: which drivers get their tools substituted for the trailmap's, and which get the inner
 * agent's list replaced outright. Those are consumer policies, not facts about a driver, so they do
 * not belong on the descriptor.
 *
 * Pure and dependency-free so the policies are assertable without an MCP server, a device, or a
 * bridge — see `McpDriverToolSurfacesTest`.
 */
object McpDriverToolSurfaces {

  /**
   * Drivers whose own tools stand in for the trailmap's declared class-backed toolsets.
   *
   * For these, `TrailblazeToolSetCatalog` filters the trailmap's declarations out as
   * driver-incompatible, so a session would have nothing to drive the device with unless the
   * driver's tools were supplied here — no `web_click`, no `revyl_tap`.
   *
   * Compose is deliberately absent even though it also drives its device off-platform. Its
   * absence keeps `builtInToolClasses` empty for Compose, which is what makes the consumers use
   * the trailmap's `scope.toolClasses` — and a Compose target declares its `compose_*` toolsets
   * there. Adding Compose here would switch those consumers to the substitution branch and
   * discard the rest of that target's declared toolsets. Its inner agent is served instead by
   * [innerAgentOverride].
   */
  private val DRIVERS_SUBSTITUTING_FOR_TRAILMAP_TOOL_SETS: Set<TrailblazeDriverType> = setOf(
    TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    // Not empty for Electron, which would send the consumers to the Maestro tool set — a
    // Playwright-driven Electron session cannot use it.
    TrailblazeDriverType.PLAYWRIGHT_ELECTRON,
    TrailblazeDriverType.REVYL_ANDROID,
    TrailblazeDriverType.REVYL_IOS,
  )

  /**
   * Drivers whose inner agent gets its tool list replaced outright rather than added to.
   *
   * `StepToolSet.selectInnerAgentTools` treats a non-null answer as the whole list, discarding the
   * device-specific tools, the target's customs, and the YAML-defined ones. That is right for
   * Compose, whose RPC server can only execute Compose tools, and wrong for every other driver —
   * which is why this is a one-element set and not "every driver that contributes tools".
   */
  private val DRIVERS_REPLACING_INNER_AGENT_TOOLS: Set<TrailblazeDriverType> = setOf(
    TrailblazeDriverType.COMPOSE,
  )

  /**
   * The driver's tools to offer in place of the trailmap's, or empty to leave the trailmap's alone.
   *
   * Empty is the fallback signal both consumers read, so a driver absent from
   * [DRIVERS_SUBSTITUTING_FOR_TRAILMAP_TOOL_SETS] — or one with no descriptor registered — keeps
   * today's trailmap-resolved behavior rather than losing its tools.
   */
  fun builtInToolClasses(
    driverType: TrailblazeDriverType?,
    descriptors: HostDriverDescriptorRegistry,
  ): Set<KClass<out TrailblazeTool>> = driverType
    ?.takeIf { it in DRIVERS_SUBSTITUTING_FOR_TRAILMAP_TOOL_SETS }
    ?.let { descriptors.forDriverOrNull(it)?.toolClasses(it) }
    .orEmpty()

  /**
   * The tool list to hand the inner agent instead of its own, or null to leave it as resolved.
   *
   * Null rather than empty: empty would replace the inner agent's tools with nothing, leaving it
   * unable to act. A driver with no descriptor registered also answers null, for the same reason.
   */
  fun innerAgentOverride(
    driverType: TrailblazeDriverType?,
    descriptors: HostDriverDescriptorRegistry,
  ): Set<KClass<out TrailblazeTool>>? = driverType
    ?.takeIf { it in DRIVERS_REPLACING_INNER_AGENT_TOOLS }
    ?.let { descriptors.forDriverOrNull(it)?.toolClasses(it) }
    ?.takeIf { it.isNotEmpty() }
}
