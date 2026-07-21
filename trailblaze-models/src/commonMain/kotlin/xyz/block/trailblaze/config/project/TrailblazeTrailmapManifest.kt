package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Local-first authored trailmap manifest.
 *
 * Trailmaps are the authored boundary. The nested [target] block carries the target's display
 * shape (id, display_name, platforms) plus references to per-file scripted tools under
 * the trailmap's `tools/` directory. The trailmap's own file-backed artifacts
 * ([toolsets], [waypoints], plus operational tool YAMLs auto-discovered from
 * `<trailmap>/tools/`) stay separate.
 *
 * ## Library trailmaps vs target trailmaps
 *
 * The presence or absence of [target] discriminates two trailmap shapes — a single source of
 * truth that doesn't risk a `type:` field disagreeing with the actual content:
 *
 *  - **Target trailmap** ([target] is non-null): models a runnable app under test. May declare
 *    everything below — operational tools (auto-discovered from `<trailmap>/tools/`),
 *    [toolsets], [waypoints], trailheads — and surfaces an [AppTargetYamlConfig] that the
 *    runtime binds devices/sessions to. The framework-bundled `clock`, `wikipedia`,
 *    `contacts` trailmaps are target trailmaps.
 *  - **Library trailmap** ([target] is null): ships cross-target reusable tooling — operational
 *    tools (auto-discovered from `<trailmap>/tools/`) and [toolsets] only. **Library trailmaps
 *    MUST NOT declare [waypoints] or trailhead tools** (a trailhead bootstraps to a known
 *    waypoint, which only makes sense within a target). The framework-bundled `trailblaze`
 *    trailmap — which publishes per-platform [defaults] for the standard built-in toolsets —
 *    is the canonical library trailmap.
 *
 * Tools and toolsets are orthogonal to trailmap type — both shapes contribute them through
 * the same registries. The runtime tool registry doesn't care which trailmap a tool came
 * from; it resolves by id.
 *
 * The library/target distinction also makes the reverse lookup deterministic: given a
 * waypoint id, walk the resolved trailmap list to find the (single) trailmap that declared it,
 * then read that trailmap's [target] for the owning target. No id-prefix convention needed.
 *
 * Library-trailmap contract violations (waypoints declared, trailhead tool referenced) are
 * caught at load time by [TrailblazeTrailmapManifestLoader] and the project config loader,
 * with the offending field/file named in the error.
 *
 * ## Composition via [dependencies]
 *
 * A trailmap may declare a list of other trailmap ids it depends on. The model mirrors a Gradle
 * classpath: depending on a trailmap pulls in *its* dependencies transitively, and each trailmap
 * in the resolved graph contributes its bundled artifacts (toolsets, tools, waypoints) to
 * the workspace pool.
 *
 * The framework ships a single `trailblaze` trailmap that publishes per-platform [defaults]
 * for the standard built-in toolset references. The trailmap itself does NOT define those
 * toolsets — `core_interaction`, `web_core`, etc. ship alongside their owning trailmaps at
 * `trails/config/trailmaps/<id>/toolsets/<name>.yaml` and surface globally through
 * [ToolSetYamlLoader.discoverAndLoadAll], independent of trailmap resolution. The
 * `trailblaze` trailmap's role is to give consumers a single dependency id whose
 * `defaults:` points at those globally-discovered toolset names. The conventional consumer
 * preamble is `dependencies: [trailblaze]` — explicit, no magic.
 *
 * ## Per-platform defaults via [defaults]
 *
 * A trailmap can publish a [defaults] map (same shape as `target.platforms`) that fills in
 * missing fields on a consumer's `target.platforms`. Resolution is **closest-to-root
 * wins** at the field level — the consumer's own values always win; for fields the
 * consumer leaves null, the dep-graph walk takes the closest depth's value (later-
 * declared deps at the same depth win on ties). No list concatenation: a consumer that
 * writes `tool_sets:` for a platform overrides the inherited list entirely. This
 * preserves the per-platform visibility of the original target shape — authors who
 * want to see "memory shows up on every platform" just keep the explicit listing.
 *
 * Cycles and missing deps cause the consumer trailmap to be skipped with a logged error;
 * sibling trailmaps continue to load (atomic-per-trailmap failure model).
 *
 * ## Field implementation status
 *
 * | Field | Runtime status |
 * | --- | --- |
 * | [id], [target] | Fully wired by `TrailblazeProjectConfigLoader.resolveTrailmapArtifacts`. |
 * | [dependencies], [defaults] | Fully wired — dep graph walked at trailmap resolution time, and a depended-on trailmap id is brought into scope the same way as a workspace-listed target. |
 * | [toolsets] | Fully wired — sibling refs resolved at trailmap load time. |
 * | [waypoints] | Fully wired — surfaced via `TrailblazeResolvedConfig.waypoints` and the `trailblaze waypoint` CLI. |
 * | [trails] | **Reserved schema slot; runtime loading deferred.** |
 *
 * **No top-level `tools:` field.** Operational tool YAMLs (`*.tool.yaml`, `*.shortcut.yaml`,
 * `*.trailhead.yaml`, `*.waypoint.yaml`) are auto-discovered from `<trailmap>/tools/` (and
 * `<trailmap>/waypoints/` for the waypoint flavor) by directory walk. Scripted-tool descriptors
 * (`*.yaml` files in `<trailmap>/tools/` whose name doesn't carry one of those operational
 * suffixes) are also auto-discovered into a per-trailmap name-keyed registry. Authoring is
 * "drop a YAML file in `tools/`, it ships with the trailmap" — the manifest doesn't enumerate the
 * tool files themselves. The `target.tools:` list still appears, but as a per-target list of
 * scripted-tool *names* selecting which discovered tools this target exposes — names, not paths.
 *
 * The previous `routes:` reserved slot was removed in 2026-04-28 — routes were dropped as a
 * separate concept in favor of "shortcuts that invoke other shortcuts."
 */
@Serializable
data class TrailblazeTrailmapManifest(
  @SerialName("id") val id: String,
  @SerialName("target") val target: TrailmapTargetConfig? = null,
  /**
   * Trailmap ids this trailmap depends on. Transitive — depending on a trailmap pulls in its
   * dependencies too. See class kdoc for resolution semantics.
   */
  @SerialName("dependencies") val dependencies: List<String> = emptyList(),
  /**
   * Per-platform defaults this trailmap contributes to consumers via dependency resolution.
   * Same shape as `target.platforms`: keys are platform names (`android`, `ios`, `web`,
   * `compose`), values are [PlatformConfig] objects whose nullable fields each act as
   * an opt-in default — `appIds`, `toolSets`, `tools`, `excludedTools`, `drivers`,
   * `baseUrl`, `minBuildVersion`. A consumer's `target.platforms[<key>].<field>` is
   * filled in from this map (closest-to-root wins) only when the consumer leaves the
   * field null; setting the field on the consumer overrides entirely (no list concat).
   *
   * Example (the bundled `trailblaze` framework trailmap):
   * ```yaml
   * defaults:
   *   android:
   *     tool_sets: [core_interaction, navigation, observation, verification, memory]
   *   web:
   *     drivers: [playwright-native, playwright-electron]
   *     tool_sets: [web_core, web_verification, memory]
   * ```
   *
   * See class kdoc for the resolution semantics and [PlatformConfig] for field details.
   */
  @SerialName("defaults") val defaults: Map<String, PlatformConfig>? = null,
  /**
   * Library-trailmap only. Top-level per-platform configuration that declares the library's
   * own runtime-registry needs — the toolsets the library's internal scripted tools
   * dispatch against at runtime. Same shape as [TrailmapTargetConfig.platforms]; keys are
   * platform names (`android`, `ios`, `web`, `compose`).
   *
   * **Authoring contract.** Only library trailmaps (no [target] block) may set this field.
   * A target trailmap's per-platform configuration belongs under `target.platforms:`; setting
   * top-level `platforms:` on a target trailmap is a category error rejected by
   * [TrailblazeTrailmapManifestLoader.enforceLibraryTrailmapContract].
   *
   * **Resolution layer.** Unlike [defaults] (the agent-toolbox closest-wins layer),
   * declarations here feed the *runtime-registry* layer — the transitive union of every
   * trailmap-in-closure's `platforms.<platform>.tool_sets:` (see
   * `TrailmapRuntimeRegistryResolver` in `:trailblaze-common`). A library trailmap's own scripted
   * tools can call any tool reachable via that union even if the consumer's agent toolbox
   * (decided by `target.platforms.tool_sets:` + library [exports]) doesn't surface it.
   *
   * Example (the `entity_factory` library trailmap):
   * ```yaml
   * id: entity_factory
   * platforms:
   *   web:
   *     tool_sets: [web_core]    # internal — added to runtime registry,
   *                               # not exposed to consumers' agent toolbox or typed surface
   * exports:
   *   - createEntity              # public — flows into consumers' toolbox + typed surface
   * ```
   */
  @SerialName("platforms") val platforms: Map<String, PlatformConfig>? = null,
  /**
   * Tool **names** (not file paths) this trailmap publishes to consumers. Listed tools become
   * part of consumers' agent toolbox + typed surface via the dependency resolver. Tools
   * present in the trailmap's `tools/` directory but NOT listed here are *internal helpers* —
   * callable inside the trailmap at runtime, invisible to consumers.
   *
   * Each name must match a `name:` field of a tool YAML file under `<trailmap>/tools/`.
   *
   * Available on both library and target trailmaps at the schema level. **Phase B caveat:**
   * the typed-surface emitter (`PerTrailmapClientDtsEmitter` in `:trailblaze-host`) flows
   * `exports:` through to consumers only for scripted tools authored under target trailmaps'
   * `target.tools:`. Library-trailmap exports of composed `.tool.yaml` tools — and library-
   * trailmap scripted-tool exports more broadly — are still pending the Phase C scripted-tool
   * relocation (`target.tools:` paths→names + scripted-tool authoring under library
   * trailmaps). The runtime registry surfaces them today; the typed `client.d.ts` doesn't
   * yet.
   */
  @SerialName("exports") val exports: List<String>? = null,
  @SerialName("toolsets") val toolsets: List<String> = emptyList(),
  /**
   * Deprecated / ignored at the resolution layer. Waypoints are now auto-discovered from
   * any `*.waypoint.yaml` file under `<trailmap-dir>/waypoints/` (at any depth). The resolver
   * no longer reads this list. The field remains in the schema only so legacy `trailmap.yaml`
   * files with explicit waypoint enumerations don't fail parsing during the migration
   * window. New trailmaps should leave this field absent and just drop their waypoint YAMLs
   * into the `waypoints/` directory.
   *
   * The manifest-side library-trailmap guard
   * ([TrailblazeTrailmapManifestLoader.enforceLibraryTrailmapContract]) still fires when this
   * field is non-empty on a target-less trailmap — defensive duplication of the discovery-
   * side guard so legacy trailmaps fail fast at parse time before disk traversal.
   */
  @SerialName("waypoints") val waypoints: List<String> = emptyList(),
  /** Reserved schema slot — runtime loading deferred. */
  @SerialName("trails") val trails: List<String> = emptyList(),
  /**
   * Opt this target's `.trail.yaml` recordings OUT of the (default-fatal) trail-recording
   * type-validation phase of `trailblaze check`. See [TrailValidationConfig].
   *
   * Mirrors [xyz.block.trailblaze.yaml.TrailConfig.skip]'s required-reason pattern: co-located
   * with the target it exempts, reviewable in the same diff, and visibly shrinking as targets
   * become clean. When [TrailValidationConfig.exempt] is a non-blank reason, findings on this
   * target — and its inability to be validated when it has no generated typed surface — are
   * reported but never fail the build. Leave the whole block absent for the steady state: a
   * target with no exemption must type-validate cleanly or the build fails.
   *
   * Appended at the end of the data class so existing positional component accessors
   * (component1..N) and binary-compatibility baselines for earlier fields stay stable.
   */
  @SerialName("trail_validation") val trailValidation: TrailValidationConfig? = null,
)

/**
 * Per-target opt-out for the trail-recording type-validation phase of `trailblaze check`.
 *
 * The phase transpiles every recorded tool call in a target's `.trail.yaml` files into a
 * throwaway TypeScript statement and compiles it against the target's generated typed tool
 * surface (`tools/trailblaze-client.d.ts`), catching tools that don't exist for the target,
 * wrong-typed args, and missing required args. It fails the build by default. A target that is
 * not yet clean — or that has no generated surface to validate against — carries an [exempt]
 * reason here so it is reported but non-fatal.
 *
 * This is the durable, co-located exemption mechanism: it lives in the target's own manifest,
 * so it is reviewed in the same PR that touches the target and disappears from the diff the
 * moment the target is brought to zero findings. It is honored for any manifest the validator
 * can reach (filesystem workspace trailmaps and classpath-bundled trailmaps alike). Targets
 * whose manifest the validator cannot reach at all (and the no-`target:` trails) are handled by
 * a separate, explicitly-transitional central allow-list in the CLI — see `CheckCommand`.
 */
@Serializable
data class TrailValidationConfig(
  /**
   * When non-blank, exempt this target from failing the trail-recording validation phase, with
   * the given human-readable reason — typically an issue reference (e.g.
   * `"Selector args not yet modeled on raw tools — see block/trailblaze#NNNN"`). An empty or
   * blank string is treated as "not exempt" so an accidental `exempt: ""` can't silently
   * disable the gate (same guard as [xyz.block.trailblaze.yaml.TrailConfig.skip]).
   */
  @SerialName("exempt") val exempt: String? = null,
)

/**
 * The target shape inside a trailmap. Differs from [AppTargetYamlConfig] in two ways:
 *
 * - [id] is optional (defaults to the trailmap's [TrailblazeTrailmapManifest.id]).
 * - [tools] holds **tool names**, not inline tool definitions. Each name is resolved by
 *   the trailmap loader against the per-trailmap scripted-tool registry — built by auto-
 *   discovering every `.yaml` file under the trailmap's `tools/` directory that isn't an
 *   operational descriptor (suffixes `.tool.yaml`, `.shortcut.yaml`, `.trailhead.yaml`,
 *   `.waypoint.yaml`) and indexing each [TrailmapScriptedToolFile] by its declared `name:`
 *   (single-tool shape) or by each entry's `name:` (multi-tool shape). The resolved
 *   descriptor is then translated into the runtime [InlineScriptToolConfig] shape. This
 *   keeps every scripted tool in its own file under `<trailmap>/tools/<tool-name>.yaml` and
 *   lets the manifest reference each one by its stable tool name rather than its
 *   filesystem path.
 *
 * Use [toAppTargetYamlConfig] *after* the loader has resolved [tools] names to the
 * runtime tool list.
 */
@Serializable
data class TrailmapTargetConfig(
  @SerialName("id") val id: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("platforms") val platforms: Map<String, PlatformConfig>? = null,
  @SerialName("has_custom_ios_driver") val hasCustomIosDriver: Boolean = false,
  /**
   * Path (relative to the trailmap directory) of a markdown / text file containing the system-prompt
   * template. The trailmap loader reads the file and inlines its content into the generated
   * [AppTargetYamlConfig.systemPrompt] field.
   *
   * **Authoring contract:** prompts MUST live in a standalone file referenced from here — there
   * is no inline string slot on [TrailmapTargetConfig]. Standalone files are easier to read and edit
   * than long YAML strings, and the file-reference shape leaves room for future per-device or
   * per-classifier prompt selection (e.g. `app-tablet.prompt.md` next to the trailmap manifest)
   * without an authoring-side schema change.
   */
  @SerialName("system_prompt_file") val systemPromptFile: String? = null,
  /**
   * Scripted-tool names this target exposes. Each entry must match the `name:` field on a
   * [TrailmapScriptedToolFile] (or one of its [TrailmapScriptedToolEntry] entries in the multi-tool
   * shape) auto-discovered from any `.yaml` file under the trailmap's `tools/` directory. The
   * loader builds a name-keyed registry per trailmap and errors helpfully if a name doesn't
   * resolve.
   */
  @SerialName("tools") val tools: List<String> = emptyList(),
  /**
   * Optional workspace-relative path to an icon shown beside this target in the TrailRunner UI
   * (Android launcher icon / web favicon). Threaded onto the generated [AppTargetYamlConfig.icon]
   * by [toAppTargetYamlConfig]. When absent, the UI may fall back to the filename convention in
   * [xyz.block.trailblaze.config.TargetIconConvention] (`android_<app_id>.png` /
   * `favicon_<host>.png` under the shared icons folder), so simply populating that folder fills the
   * first-run target list without per-target authoring. An explicit value overrides the convention.
   */
  @SerialName("icon") val icon: String? = null,
  /**
   * Launch configuration for a `PLAYWRIGHT_ELECTRON` target — see
   * [AppTargetYamlConfig.electron]. The target-level home for what a v1 trail expressed per-trail
   * under `config.electron`; threaded onto the resolved [AppTargetYamlConfig] by
   * [toAppTargetYamlConfig] so the host runner reads it off the resolved target at run time.
   */
  @SerialName("electron") val electron: xyz.block.trailblaze.yaml.ElectronAppConfig? = null,
) {
  fun toAppTargetYamlConfig(
    defaultId: String,
    resolvedTools: List<InlineScriptToolConfig>,
    resolvedSystemPrompt: String? = null,
  ): AppTargetYamlConfig =
    AppTargetYamlConfig(
      id = id ?: defaultId,
      displayName = displayName,
      platforms = platforms,
      hasCustomIosDriver = hasCustomIosDriver,
      systemPrompt = resolvedSystemPrompt,
      tools = resolvedTools.takeIf { it.isNotEmpty() },
      icon = icon,
      electron = electron,
    )
}
