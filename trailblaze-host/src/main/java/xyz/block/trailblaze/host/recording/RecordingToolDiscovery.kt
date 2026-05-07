package xyz.block.trailblaze.host.recording

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.newtools.ToolDiscoveryIndexResult
import xyz.block.trailblaze.mcp.newtools.ToolDiscoveryTargetResult
import xyz.block.trailblaze.mcp.newtools.ToolDiscoveryToolSet
import xyz.block.trailblaze.model.TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Fetches the tool descriptors that are valid for the current (target app, driver) combination
 * and returns them flattened, deduped, and sorted by name. Used by the Tool Palette UI in the
 * recording tab to populate the "pick any tool" list.
 *
 * Wraps [ToolDiscoveryToolSet] (the MCP `toolbox(detail=true)` entry point). Reusing it means
 * the palette's "available tools" list is by construction the same set the agent runtime sees:
 * platform toolsets filtered by driver, plus the active target app's custom + YAML + inline
 * scripted tools. New custom toolsets a target adds tomorrow show up here without UI changes.
 *
 * **Two-call shape.** `toolbox()` in its default index mode returns target tools from *every*
 * target the daemon has loaded (so an LLM agent sees the full superset). The recording UI
 * shouldn't — the user has committed to a target, and listing tools from other targets just
 * surfaces names they can't actually run. So we call `toolbox` twice:
 *
 *  1. `target = "default"` — returns only platform toolsets (cross-platform actions like tap,
 *     swipe, scroll). The `default` sentinel is what `ToolDiscoveryToolSet` interprets as
 *     "suppress target tools."
 *  2. `target = currentTarget.id` — returns the selected target's grouped + scripted tools.
 *     Falls back to the index call's target tools when no target is selected (rare — the
 *     recording tab requires both target and device before opening the palette).
 *
 * Requires [driverType] to be non-null. The palette is only opened when a device is connected,
 * so the driver is always known at call time; passing null would degrade to an unfiltered list
 * mixing tools the user can't actually run on their selected device, which is worse UX than
 * hiding the palette entirely.
 */
suspend fun discoverAvailableTools(
  deviceManager: TrailblazeDeviceManager,
  driverType: TrailblazeDriverType,
): List<TrailblazeToolDescriptor> {
  val currentTarget = deviceManager.getCurrentSelectedTargetApp()
  val toolset = ToolDiscoveryToolSet(
    sessionContext = null,
    allTargetAppsProvider = { deviceManager.availableAppTargets },
    currentTargetProvider = { currentTarget },
    currentDriverTypeProvider = { driverType },
  )

  // Platform tools — pass the `default` sentinel so `handleIndexMode` runs with
  // `suppressTargetTools = true` and we get only the platform side. Decoding this as
  // ToolDiscoveryIndexResult is correct: `default` is the one target value that does NOT
  // route through `handleTargetMode`.
  val platformJson = toolset.toolbox(target = DefaultTrailblazeHostAppTarget.id, detail = true)
  val platformResult = paletteJson.decodeFromString<ToolDiscoveryIndexResult>(platformJson)
  val platformDescriptors = platformResult.platformToolsets.flatMap { it.toolDetails ?: emptyList() }

  // Target-scoped tools — only the selected target. Without a selected target we have nothing
  // to scope to, so return platform-only rather than leaking every target's tools.
  val targetDescriptors: List<TrailblazeToolDescriptor> = if (currentTarget != null) {
    val targetJson = toolset.toolbox(target = currentTarget.id, detail = true)
    val targetResult = paletteJson.decodeFromString<ToolDiscoveryTargetResult>(targetJson)
    targetResult.toolGroups?.flatMap { it.toolDetails ?: emptyList() } ?: emptyList()
  } else {
    emptyList()
  }

  return (platformDescriptors + targetDescriptors)
    .distinctBy { it.name }
    .sortedBy { it.name }
}

private val paletteJson = Json { ignoreUnknownKeys = true }
