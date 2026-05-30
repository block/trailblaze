package xyz.block.trailblaze.config.project

import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.config.AppTargetYamlConfig

/**
 * Loader output that pairs a resolved [TrailblazeProjectConfig] (the `trailblaze.yaml`
 * schema with all `ref:` entries inlined) with runtime artifacts that are not part of
 * the on-disk schema.
 *
 * ## Why resolved targets live here, not on [TrailblazeProjectConfig]
 *
 * `TrailblazeProjectConfig.targets` is the on-disk shape — `List<String>` of target-trailmap
 * ids the workspace declared (or empty for auto-discovery). The fully-resolved
 * [AppTargetYamlConfig] objects produced by walking those ids' trailmap manifests, applying
 * the dependency graph, and inlining classpath-bundled trailmaps live here on [targets].
 * Keeping the schema class as a pure on-disk shape lets it stay serializable and avoids
 * polluting it with transient runtime artifacts.
 *
 * ## Trailmap-id precedence
 *
 * When a workspace trailmap declares the same id as a classpath-bundled trailmap, the workspace
 * trailmap wholesale shadows the classpath trailmap — both contribute to the resolved targets /
 * toolsets / tools, but the workspace value overrides the classpath value by id. This is
 * intentional: users get to tailor framework-shipped trailmaps locally without having to
 * fork them.
 *
 * Re-evaluate this precedence rule if the framework ever ships trailmaps with non-overridable
 * security-relevant invariants — at that point we'd add a "sealed" flag on the manifest.
 */
data class TrailblazeResolvedConfig(
  val projectConfig: TrailblazeProjectConfig,
  /**
   * Resolved target configs (inlined from each successfully-loaded target trailmap). The
   * list of *ids* lives on [TrailblazeProjectConfig.targets]; this field carries the
   * dereferenced [AppTargetYamlConfig] for each.
   */
  val targets: List<AppTargetYamlConfig> = emptyList(),
  val waypoints: List<WaypointDefinition> = emptyList(),
  /**
   * Every successfully-loaded trailmap (target and library) with its source manifest, source
   * location, and dereferenced sibling content. Surfaced for codegen consumers
   * (per-trailmap `client.d.ts` emission) that need the trailmap-local typing surface — toolset
   * declarations, scripted-tool list, `exports:` — without re-walking trailmap manifests.
   *
   * Library trailmaps that contribute scripted tools or platform `tool_sets:` show up here even
   * though they don't produce a [targets] entry. The runtime/codegen layers filter by source
   * type (filesystem vs classpath) and whether the trailmap has a `target:` block.
   *
   * Includes every trailmap that completed `resolveTrailmapSiblings` cleanly. Trailmaps that failed
   * sibling resolution drop out (atomic-per-trailmap failure model) and don't show up here.
   */
  val resolvedTrailmaps: List<ResolvedTrailmap> = emptyList(),
)
