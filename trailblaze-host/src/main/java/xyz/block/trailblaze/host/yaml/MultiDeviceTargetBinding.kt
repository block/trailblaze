package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.ResolvedTarget
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ResolvedAgentToolbox
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.commands.SwitchDeviceTrailblazeTool
import xyz.block.trailblaze.toolcalls.resolveToolScopeForDriver
import kotlin.reflect.KClass

/**
 * The runtime half of per-device targets: once each declared device name has been mapped to a
 * loaded target, these decide what the SESSION binds — which targets are in play, which custom
 * tools must decode, and how many scripted-tool runtimes to launch.
 *
 * Pure so the session-wide rules stay testable without a device, a daemon, or a trail run.
 */
object MultiDeviceTargetBinding {

  /**
   * Every DISTINCT target a session binds, start device first.
   *
   * Order is load-bearing: the start device's target is what session-level surfaces report, and
   * de-duplication is by target id so a configuration whose devices all inherit the session target
   * binds it once rather than once per device.
   */
  fun boundTargets(
    startDeviceTarget: TrailblazeHostAppTarget?,
    companionTargets: List<TrailblazeHostAppTarget?>,
  ): List<TrailblazeHostAppTarget> = (listOfNotNull(startDeviceTarget) + companionTargets.filterNotNull())
    .distinctBy { it.id }

  /**
   * The custom tools recorded steps must decode against: the union over [boundTargets], not just
   * the start device's. A step recorded on a companion names a tool only THAT device's target
   * declares.
   *
   * An unrecognized name does NOT fail the decode — it deserializes to an opaque
   * [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool] that no dispatch path can execute,
   * so without the union a companion's recorded step reaches dispatch and dies there as an unknown
   * tool, well after the session started.
   *
   * This is a decode surface, not an advertisement or dispatch surface — which app a tool acts on
   * is carried by the dispatching agent's own resolved target.
   */
  fun customToolClasses(
    boundTargets: List<TrailblazeHostAppTarget>,
    driverType: TrailblazeDriverType,
  ): Set<KClass<out TrailblazeTool>> = boundTargets
    .flatMapTo(mutableSetOf()) { it.getCustomToolsForDriver(driverType) }

  /**
   * What the session tool repo must carry ON TOP of the start device's target, so a step recorded
   * on a companion can actually dispatch.
   *
   * Each companion contributes its RESOLVED tool scope, not just its direct custom tools: a target
   * gets most of its tools from the `tool_sets:` its trailmap declares, and the repo's base catalog
   * is scoped to the start device's target alone. A companion declaring a toolset the start device
   * doesn't would otherwise have every tool from it missing — decodable, then undispatchable.
   *
   * Identity is by target id, not list position: [boundTargets] puts the first non-null companion
   * at index 0 when the session has no start-device target at all, so dropping the head would drop
   * a real companion's tools.
   *
   * Each contributed scope already has ITS OWN `excluded_tools:` subtracted. The start device
   * target's exclusions then apply to the whole composed repo, siblings included — that is the one
   * composer's documented contract (`toCustomTrailblazeToolsForDriver`), and the repo is
   * session-scoped while exclusions are per target. So a start-device exclusion can still remove a
   * tool only a companion declares; per-target exclusion scoping would have to happen in the
   * composer, not here.
   */
  fun companionToolAdditions(
    boundTargets: List<TrailblazeHostAppTarget>,
    startDeviceTarget: TrailblazeHostAppTarget?,
    driverType: TrailblazeDriverType,
  ): ResolvedAgentToolbox {
    val companionScopes = boundTargets
      .filter { it.id != startDeviceTarget?.id }
      .map { it.resolveToolScopeForDriver(driverType) }
    return ResolvedAgentToolbox(
      toolClasses = companionScopes.flatMapTo(mutableSetOf()) { it.toolClasses },
      yamlToolNames = companionScopes.flatMapTo(mutableSetOf()) { it.yamlToolNames },
      scriptedToolNames = companionScopes.flatMapTo(mutableSetOf()) { it.scriptedToolNames },
    )
  }

  /**
   * The handover tool a multi-device session adds to its own tool surface: `switchDevice`, read from
   * the `multi_device` catalog entry so the YAML toolset stays the source of truth for what the
   * toolset contains.
   *
   * Session-bound rather than target-declared, and that asymmetry is deliberate: no app's trailmap
   * can know whether the session that runs its trails bound a second device, and advertising a
   * handover tool to a session with nothing to hand over to would offer the LLM a tool whose every
   * call fails. So the condition is the binding itself — [isMultiDeviceSession].
   *
   * Empty for a single-device session, which is what keeps single-device tool surfaces byte-identical
   * to what they were before multi-device existed.
   */
  fun handoverToolSurface(
    isMultiDeviceSession: Boolean,
    catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
  ): ResolvedAgentToolbox = if (!isMultiDeviceSession) {
    ResolvedAgentToolbox(toolClasses = emptySet(), yamlToolNames = emptySet())
  } else {
    // All three name kinds, even though `multi_device.yaml` declares only a class-backed tool
    // today: reading two of three would make "the catalog is the source of truth" false the first
    // time the toolset gains a scripted tool, and silently — a missing scripted name doesn't fail
    // the session, it just never advertises.
    ResolvedAgentToolbox(
      toolClasses = TrailblazeToolSetCatalog.entryToolClasses(
        SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID,
        catalog,
      ),
      yamlToolNames = TrailblazeToolSetCatalog.entryYamlToolNames(
        SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID,
        catalog,
      ),
      scriptedToolNames = TrailblazeToolSetCatalog.entryScriptedToolNames(
        SwitchDeviceTrailblazeTool.MULTI_DEVICE_TOOLSET_ID,
        catalog,
      ),
    )
  }

  /**
   * The [ResolvedTarget] ONE agent resolves `ctx.target` against: its own device's target, paired
   * with its own device id.
   *
   * Trivial by construction, and that is the point — the defect this replaced was a companion
   * agent being handed the LAUNCH device's whole `ResolvedTarget`, device id included, so every
   * companion probed the launch device's installed packages and reported the launch device's
   * target no matter which app it was actually driving. Pairing a target with a device id happens
   * once, here, rather than at each agent's construction site.
   *
   * Null when the device has no target at all — a bare run against a device with no trailmap
   * bound, where `ctx.target` is legitimately undefined.
   */
  fun agentResolvedTarget(
    target: TrailblazeHostAppTarget?,
    deviceId: TrailblazeDeviceId,
  ): ResolvedTarget? = target?.let { ResolvedTarget(target = it, deviceId = deviceId) }

  /**
   * Scripted-tool runtimes are per TARGET, so a configuration binding more than one target needs
   * more than one launch. Each entry pairs a bound target with the per-device context of a device
   * that runs it (its device info), which is what a runtime carries as its static device context.
   *
   * The start device comes first so its runtime registers before any companion's, and a tool name
   * both targets declare resolves to the launch device's copy. A companion that inherits the
   * session target adds no entry — it shares the start device's runtime.
   */
  fun <DeviceContext> scriptedToolLaunchPlan(
    startDeviceTarget: TrailblazeHostAppTarget?,
    startDeviceContext: DeviceContext,
    companions: List<Pair<TrailblazeHostAppTarget?, DeviceContext>>,
  ): List<Pair<TrailblazeHostAppTarget?, DeviceContext>> = (
    listOf(startDeviceTarget to startDeviceContext) +
      companions.filter { (target, _) -> target != null }
    ).distinctBy { (target, _) -> target?.id }
}
