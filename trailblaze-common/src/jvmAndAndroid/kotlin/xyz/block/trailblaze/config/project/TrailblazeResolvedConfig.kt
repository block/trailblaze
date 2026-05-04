package xyz.block.trailblaze.config.project

import xyz.block.trailblaze.api.waypoint.WaypointDefinition

/**
 * Loader output that pairs a resolved [TrailblazeProjectConfig] (the `trailblaze.yaml`
 * schema with all `ref:` entries inlined) with runtime artifacts that are not part of
 * the on-disk schema.
 *
 * Today this carries pack-resolved [waypoints]. Future runtime artifacts (routes, trails,
 * materialized tool registries) belong here too rather than as transient fields on
 * [TrailblazeProjectConfig], which stays a pure schema class.
 *
 * ## Pack-id precedence
 *
 * When a workspace declares a pack via `packs:` AND the same pack id is also discovered
 * on the classpath (e.g. a workspace overrides the framework-bundled `clock` pack), the
 * workspace-declared pack wins. Both contribute to the resolved targets / toolsets /
 * tools, but the workspace value overrides the classpath value by id. This is intentional:
 * users get to tailor framework-shipped packs locally without having to fork them.
 *
 * Re-evaluate this precedence rule if the framework ever ships packs with non-overridable
 * security-relevant invariants — at that point we'd add a "sealed" flag on the manifest.
 */
data class TrailblazeResolvedConfig(
  val projectConfig: TrailblazeProjectConfig,
  val waypoints: List<WaypointDefinition> = emptyList(),
)
