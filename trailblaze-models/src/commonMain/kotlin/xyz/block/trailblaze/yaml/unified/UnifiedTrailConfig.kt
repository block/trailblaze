package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Serializable

/**
 * Unified-format `config:` block — identity, target, declared device support,
 * and free-form context/memory/metadata.
 *
 * Retired from the legacy per-platform format: `platform:` (replaced by
 * [devices]), `driver:` (now resolved at runtime from device+trailmap), `title:`
 * (use `metadata.title` if needed).
 */
@Serializable
data class UnifiedTrailConfig(
  /** Stable identifier; convention is the source-system path. */
  val id: String? = null,
  /** Target name from the trailmap manifest. */
  val target: String? = null,
  /**
   * What this test claims to support. Inherited from trailmap manifest's `platforms:`
   * if omitted. Soft-validated by `trailblaze check` against actual recording
   * coverage. Multi-segment classifiers are joined with `-` (e.g.
   * `["android-phone", "ios"]`).
   */
  val devices: List<String>? = null,
  /** Free-form context injected into the LLM system prompt. */
  val context: String? = null,
  /** Pre-seeded variables for `{{name}}` interpolation in NL and tool params. */
  val memory: Map<String, String>? = null,
  /** Informational only — never read at runtime. Used for traceability. */
  val metadata: Map<String, String>? = null,
)
