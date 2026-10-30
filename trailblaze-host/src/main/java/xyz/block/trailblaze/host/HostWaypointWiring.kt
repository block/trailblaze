package xyz.block.trailblaze.host

/**
 * The host's one-time wiring of the waypoint registry: which workspace it loads, the analyzer
 * enrichment it needs, and the trailhead landing check that reads it. Every host entry point calls
 * [ensureInstalled] before it runs a tool — [TrailblazeHostYamlRunner] and the standalone JUnit
 * `BaseHostTrailblazeTest` — so a JUnit run that never touches the runner is still checked.
 */
object HostWaypointWiring {

  private val installed: Unit by lazy {
    // Install the analyzer-backed scripted-tool enrichment so the host-side `assertWaypoint`
    // tool can resolve the waypoint registry even in workspaces that carry meta-only
    // scripted-tool descriptors (whose trailmap loading would otherwise throw without
    // enrichment). The interface lives in trailblaze-common; the analyzer that builds it is
    // host-only, so this is the host's one-time install point. Mirrors what the retired
    // `resolveWaypointsForRun` did inline.
    xyz.block.trailblaze.waypoint.WaypointRegistryResolver.scriptedToolEnrichmentProvider = {
      xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    }
    // Point waypoint resolution at the SAME active-workspace dir that target/tool discovery uses
    // (`WorkspaceConfigDirHolder`), so a workspace selected in the desktop app / Trail Runner — which
    // installs it without changing the JVM cwd — resolves app waypoints too. The holder lives in
    // trailblaze-models (JVM-only); this host module bridges it into the common resolver. Ensure the
    // holder's own default delegation is installed first (idempotent).
    xyz.block.trailblaze.config.project.TrailblazeWorkspaceConfigBootstrap.ensureInstalled()
    xyz.block.trailblaze.waypoint.WaypointRegistryResolver.workspaceConfigDirProvider = {
      xyz.block.trailblaze.llm.config.WorkspaceConfigDirHolder.resolver()
    }
    // The same registry tells each trailhead which waypoints its setup ended on, so a reused setup
    // can be checked against the run it came from.
    xyz.block.trailblaze.trailhead.TrailheadLanding.waypoints = { targetId ->
      xyz.block.trailblaze.waypoint.WaypointRegistryResolver.forTarget(targetId)
    }
  }

  fun ensureInstalled() = installed
}
