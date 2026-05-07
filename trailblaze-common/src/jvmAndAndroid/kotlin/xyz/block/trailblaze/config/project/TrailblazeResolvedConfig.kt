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
 * `TrailblazeProjectConfig.targets` is the on-disk shape — `List<String>` of target-pack
 * ids the workspace declared (or empty for auto-discovery). The fully-resolved
 * [AppTargetYamlConfig] objects produced by walking those ids' pack manifests, applying
 * the dependency graph, and inlining classpath-bundled packs live here on [targets].
 * Keeping the schema class as a pure on-disk shape lets it stay serializable and avoids
 * polluting it with transient runtime artifacts.
 *
 * ## Pack-id precedence
 *
 * When a workspace pack declares the same id as a classpath-bundled pack, the workspace
 * pack wholesale shadows the classpath pack — both contribute to the resolved targets /
 * toolsets / tools, but the workspace value overrides the classpath value by id. This is
 * intentional: users get to tailor framework-shipped packs locally without having to
 * fork them.
 *
 * Re-evaluate this precedence rule if the framework ever ships packs with non-overridable
 * security-relevant invariants — at that point we'd add a "sealed" flag on the manifest.
 */
data class TrailblazeResolvedConfig(
  val projectConfig: TrailblazeProjectConfig,
  /**
   * Resolved target configs (inlined from each successfully-loaded target pack). The
   * list of *ids* lives on [TrailblazeProjectConfig.targets]; this field carries the
   * dereferenced [AppTargetYamlConfig] for each.
   */
  val targets: List<AppTargetYamlConfig> = emptyList(),
  val waypoints: List<WaypointDefinition> = emptyList(),
)
