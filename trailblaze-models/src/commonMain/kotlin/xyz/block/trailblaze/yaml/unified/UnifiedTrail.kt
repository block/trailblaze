package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Top-level shape of a unified Trail YAML file — a YAML mapping with a
 * singleton `config` key, an ordered `trail` list of steps, and an optional
 * `trailhead` (the deterministic step 0).
 *
 * The unified format collapses what used to live across one per-platform
 * `*.trail.yaml` per device plus a sibling `blaze.yaml` into a single file
 * per test, with each step's natural-language description appearing exactly
 * once and per-device tool recordings nested underneath via classifier keys.
 *
 * Named `Unified*` (rather than `TrailV3*`) so it doesn't collide in
 * conversation or code-search with [xyz.block.trailblaze.mcp.AgentImplementation.MULTI_AGENT_V3],
 * which references a completely unrelated agent-architecture concept from
 * the Mobile-Agent-v3 paper. The on-disk filename is still `trail.yaml`.
 *
 * See [the design devlog](../../../../../../../docs/devlog/2026-05-22-trail-yaml-unified-syntax.md)
 * for the full spec.
 */
@Serializable
data class UnifiedTrail(
  val config: UnifiedTrailConfig,
  /**
   * Optional deterministic starting state — the trail's step 0, run before any [trail] steps.
   * Structurally a [UnifiedTrailStep]: one NL `step:` plus per-classifier tool lists, so the
   * trailhead's bootstrap tools vary per device exactly like a regular step's recordings.
   */
  val trailhead: @Contextual UnifiedTrailStep? = null,
  val trail: List<@Contextual UnifiedTrailStep>,
)
