package xyz.block.trailblaze.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal `@Serializable` view of the pack manifest YAML — only the fields the bundler
 * actually reads. The authoritative shape lives in `:trailblaze-models`
 * (`TrailblazePackManifest`, `PackTargetConfig`, `PackScriptedToolFile`,
 * `ScriptedToolProperty`); this is a deliberate slim mirror that keeps the bundler's
 * classpath independent of the trailblaze-models graph (koog, MCP, etc.) so the same
 * library can be consumed from build-logic's lean Gradle plugin classpath AND from the
 * CLI / daemon paths.
 *
 * **Drift mitigation.** kaml is configured with `strictMode = false` — fields the
 * bundler doesn't care about (`defaults`, `toolsets`, `waypoints`, `routes`, etc.) are
 * silently ignored. Drift is only observable when a field this file *does* declare
 * diverges from the authoritative shape (rename, type change). When that happens, both
 * must move in lockstep. A future consolidation could replace this file with a direct
 * dependency on trailblaze-models if the build-logic classpath concern is solved (e.g.,
 * by extracting trailblaze-models' YAML schema into its own leaner module).
 */
@Serializable
internal data class BundlerPackManifest(
  @SerialName("id") val id: String,
  @SerialName("dependencies") val dependencies: List<String> = emptyList(),
  @SerialName("target") val target: BundlerTarget? = null,
)

@Serializable
internal data class BundlerTarget(
  @SerialName("tools") val tools: List<String> = emptyList(),
)

@Serializable
internal data class BundlerToolFile(
  /** Required iff [tools] is null (single-tool shape). */
  @SerialName("name") val name: String? = null,
  @SerialName("description") val description: String? = null,
  @SerialName("inputSchema") val inputSchema: Map<String, BundlerScriptedToolProperty> = emptyMap(),
  /**
   * Multi-tool shape — when set, this descriptor declares N tools each authored as a named
   * export on the shared `script:` source. Mirrors `PackScriptedToolFile.tools` in
   * `:trailblaze-models`; the bundler decodes only the fields it surfaces in the typed
   * `.d.ts` augmentation (name / description / inputSchema). File-wide shortcuts
   * (`requiresHost`, `supportedPlatforms`, `_meta`) aren't relevant to the typed surface
   * and stay silently ignored by `strictMode = false`.
   */
  @SerialName("tools") val tools: List<BundlerToolEntry>? = null,
)

@Serializable
internal data class BundlerToolEntry(
  @SerialName("name") val name: String,
  @SerialName("description") val description: String? = null,
  @SerialName("inputSchema") val inputSchema: Map<String, BundlerScriptedToolProperty> = emptyMap(),
)

@Serializable
internal data class BundlerScriptedToolProperty(
  @SerialName("type") val type: String? = null,
  @SerialName("description") val description: String? = null,
  @SerialName("enum") val enum: List<String>? = null,
  @SerialName("required") val required: Boolean = true,
)
