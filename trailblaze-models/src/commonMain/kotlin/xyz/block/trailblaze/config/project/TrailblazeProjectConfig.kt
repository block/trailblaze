package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.config.LlmConfig

/**
 * Top-level schema for `trailblaze.yaml` — the single config file sitting at
 * `trails/config/trailblaze.yaml` inside a Trailblaze workspace.
 *
 * All sections are optional. An empty file (or one that decodes to `{}`) is valid: the
 * framework's built-in classpath config still applies, and every target pack found at
 * `<workspace>/packs/<id>/pack.yaml` is auto-loaded.
 *
 * Example:
 * ```yaml
 * defaults:
 *   target: my-app
 *   llm: openai/gpt-4.1
 *
 * targets:
 *   - my-app
 *   - another-target
 *
 * llm:
 *   defaults:
 *     model: openai/gpt-4.1
 * ```
 *
 * ## `targets:` semantics
 *
 * [targets] holds a list of target-pack ids. Each id resolves via the convention
 * `<workspace>/packs/<id>/pack.yaml` (or, for classpath-bundled packs the framework
 * ships, `trailblaze-config/packs/<id>/pack.yaml`). The pack's own
 * [TrailblazePackManifest.dependencies] then transitively pulls in any library packs
 * the target depends on.
 *
 * **Workspace-listed targets must be target packs** (have a `target:` block in their
 * `pack.yaml`). Listing a library-pack id here is a load-time error — library packs
 * reach scope only via classpath bundling or via `dependencies:` from a target pack.
 *
 * **Empty / omitted = auto-discover.** When [targets] is empty, the loader walks
 * every `<workspace>/packs/<id>/pack.yaml` and loads every target pack it finds, the
 * same way classpath discovery works. When [targets] is non-empty, only the listed
 * pack ids (plus their transitive deps) load — useful in workspaces with many on-disk
 * packs where the daemon should only spin up a subset.
 *
 * The previous `packs:` field (which took filesystem paths to pack manifests) was
 * removed in favour of this id-based form.
 */
@Serializable
data class TrailblazeProjectConfig(
  @SerialName("defaults") val defaults: ProjectDefaults? = null,
  /**
   * Target-pack ids the workspace explicitly opts into. Empty/omitted = auto-discover
   * every target pack found under `<workspace>/packs/`. See class kdoc.
   *
   * After resolution this list reflects which target ids successfully landed (broken
   * pack refs and dependency-resolution failures filter out). The fully-resolved
   * [xyz.block.trailblaze.config.AppTargetYamlConfig] objects live on
   * [TrailblazeResolvedConfig.targets] alongside this id list — that wrapper is what
   * downstream consumers (host discovery, the compiler, the waypoint CLI) read.
   */
  @SerialName("targets") val targets: List<String> = emptyList(),
  @SerialName("toolsets") val toolsets: List<ToolsetEntry> = emptyList(),
  @SerialName("tools") val tools: List<ToolEntry> = emptyList(),
  @SerialName("providers") val providers: List<ProviderEntry> = emptyList(),
  @SerialName("llm") val llm: LlmConfig? = null,
)

/**
 * Project-level defaults. Separate from [LlmConfig.defaults] — these refer to top-level
 * entries (target id, model shorthand) rather than LLM-internal defaults.
 */
@Serializable
data class ProjectDefaults(
  @SerialName("target") val target: String? = null,
  @SerialName("llm") val llm: String? = null,
)
