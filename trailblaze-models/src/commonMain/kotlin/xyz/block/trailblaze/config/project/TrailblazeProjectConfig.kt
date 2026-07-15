package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.config.LlmConfig

/**
 * Top-level schema for `trailblaze.yaml` — the single config file sitting at
 * `trails/config/trailblaze.yaml` inside a Trailblaze workspace.
 *
 * All sections are optional. An empty file (or one that decodes to `{}`) is valid: the
 * framework's built-in classpath config still applies, and every target trailmap found at
 * `<workspace>/trailmaps/<id>/trailmap.yaml` is auto-loaded.
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
 * [targets] holds a list of target-trailmap ids. Each id resolves via the convention
 * `<workspace>/trailmaps/<id>/trailmap.yaml` (or, for classpath-bundled trailmaps the framework
 * ships, `trails/config/trailmaps/<id>/trailmap.yaml`). The trailmap's own
 * [TrailblazeTrailmapManifest.dependencies] then transitively pulls in any library trailmaps
 * the target depends on.
 *
 * **Workspace-listed targets must be target trailmaps** (have a `target:` block in their
 * `trailmap.yaml`). Listing a library-trailmap id here is a load-time error — library trailmaps
 * reach scope only via classpath bundling or via `dependencies:` from a target trailmap.
 *
 * **Empty / omitted = auto-discover.** When [targets] is empty, the loader walks
 * every `<workspace>/trailmaps/<id>/trailmap.yaml` and loads every target trailmap it finds, the
 * same way classpath discovery works. When [targets] is non-empty, only the listed
 * trailmap ids (plus their transitive deps) load — useful in workspaces with many on-disk
 * trailmaps where the daemon should only spin up a subset.
 *
 * The previous `trailmaps:` field (which took filesystem paths to trailmap manifests) was
 * removed in favour of this id-based form.
 */
@Serializable
data class TrailblazeProjectConfig(
  @SerialName("defaults") val defaults: ProjectDefaults? = null,
  /**
   * Target-trailmap ids the workspace explicitly opts into. Empty/omitted = auto-discover
   * every target trailmap found under `<workspace>/trailmaps/`. See class kdoc.
   *
   * After resolution this list reflects which target ids successfully landed (broken
   * trailmap refs and dependency-resolution failures filter out). The fully-resolved
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
  /**
   * Target-trailmap id a run / tool dispatch uses when nothing more specific is set —
   * the committed, team-wide default so everyone in the workspace targets the same app
   * without per-machine setup.
   *
   * Precedence (highest first): explicit per-run target (`--target`, a trail's
   * `config.target`, or an active session override) → the per-machine persisted user
   * selection (`trailblaze config target`) → this workspace default → the neutral
   * built-in target. The id is validated against the loaded targets at resolution time;
   * an unknown id is logged and skipped (falls through to the neutral default) rather than
   * crashing every invocation in the workspace.
   */
  @SerialName("target") val target: String? = null,
  @SerialName("llm") val llm: String? = null,
  /**
   * Team-wide cap on LLM calls per objective for the legacy TRAILBLAZE_RUNNER agent.
   * Committed alongside the project so every developer / CI runner inherits the same
   * default without each invocation needing to pass `--max-llm-calls` or set
   * `TRAILBLAZE_MAX_LLM_CALLS`. Per-run CLI flag and env var still win when set; the
   * persisted per-machine `trailblaze config max-llm-calls` setting is consulted only
   * when this workspace value is absent.
   *
   * Must be a positive integer when set. Validation happens at the resolver site rather
   * than `init` so a malformed workspace file can be ignored with a warning rather than
   * crashing every CLI invocation in that workspace.
   */
  @SerialName("max-llm-calls") val maxLlmCalls: Int? = null,
)
