package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.yaml.TrailYamlItem

/**
 * Discriminated union returned by the version-aware trail parser. The
 * dispatcher tries the legacy v1 format first (the vast majority of files in
 * the repo are still v1); if that fails, it tries the unified format.
 * Callers keep working against either shape during the transition window.
 */
sealed interface TrailDocument {
  /** Legacy per-platform trail YAML — top-level list of `config` / `prompts` items. */
  data class V1(val items: List<TrailYamlItem>) : TrailDocument

  /** Unified trail YAML — single file per test, classifier-keyed recordings. */
  data class Unified(val trail: UnifiedTrail) : TrailDocument
}
