package xyz.block.trailblaze.yaml.unified

/**
 * Wrapper returned by the trail parser. The legacy v1 format has been removed —
 * only the unified format (single file per test, classifier-keyed recordings)
 * is parsed now. Kept as a sealed interface so a future format could be added
 * back as a sibling without churning every `when` over the result.
 */
sealed interface TrailDocument {
  /** Unified trail YAML — single file per test, classifier-keyed recordings. */
  data class Unified(val trail: UnifiedTrail) : TrailDocument
}
