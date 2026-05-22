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
  /**
   * Top-level per-platform configuration. Library packs (no `target:` block) declare
   * the runtime-registry needs of their internal scripted tools here. See the
   * authoritative shape on `TrailblazePackManifest.platforms` in `:trailblaze-models`.
   *
   * The bundler decodes this so per-pack typed-surface codegen can read it without
   * the slim-mirror losing the field to `strictMode = false`. The bundler itself does
   * not interpret the contents today; the field is parsed-but-unused at this layer,
   * pinned here so it stays decoded once the runtime-registry resolver wires up.
   */
  @SerialName("platforms") val platforms: Map<String, BundlerPlatformConfig>? = null,
  /**
   * Tool **names** (not file paths) this pack publishes to consumers. Mirrors
   * `TrailblazePackManifest.exports` in `:trailblaze-models`. Tools authored under the
   * pack's `tools/` directory not listed here are internal helpers, invisible to
   * consumers' typed surface + agent toolbox.
   */
  @SerialName("exports") val exports: List<String>? = null,
)

@Serializable
internal data class BundlerTarget(
  /**
   * Scripted-tool names this target exposes — same semantics as
   * `PackTargetConfig.tools` in `:trailblaze-models`. Each entry must match the `name:`
   * field on a descriptor auto-discovered under `<pack>/tools/`. The bundler's discovery
   * walk in [TrailblazePackBundler] resolves each name against that registry.
   */
  @SerialName("tools") val tools: List<String> = emptyList(),
)

/**
 * Slim mirror of `:trailblaze-models`' `PlatformConfig`, scoped to the fields the bundler
 * surfaces (today: `tool_sets` only, since that's the field the runtime-registry resolver
 * walks). Other `PlatformConfig` fields (`app_ids`, `drivers`, `base_url`, etc.) are
 * silently dropped by `strictMode = false` — they aren't relevant to bundler output.
 *
 * Note: KDoc deliberately uses plain backticked names rather than `[PlatformConfig]` link
 * references because the bundler module does not depend on `:trailblaze-models`, so the
 * symbol is unreachable for Dokka/KDoc resolution.
 */
@Serializable
internal data class BundlerPlatformConfig(
  @SerialName("tool_sets") val toolSets: List<String>? = null,
)

@Serializable
internal data class BundlerToolFile(
  /**
   * Relative or absolute path to the source module backing this scripted tool. The
   * authoritative shape on `:trailblaze-models`' `PackScriptedToolFile.script` is
   * required (non-null); the bundler keeps it nullable here because its primary job is
   * type-surface generation, not source-file validation — a missing `script:` is a
   * runtime-loader concern, not a bundler concern. The bundler does, however, fail loudly
   * when the field IS present and references a `.js`/`.mjs`/`.cjs` file (see
   * [TrailblazePackBundleException.JsToolFileNotAllowed]).
   */
  @SerialName("script") val script: String? = null,
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
