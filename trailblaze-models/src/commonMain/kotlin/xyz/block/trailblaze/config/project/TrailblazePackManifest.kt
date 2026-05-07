package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.config.AppTargetYamlConfig
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.config.PlatformConfig

/**
 * Local-first authored pack manifest.
 *
 * Packs are the authored boundary. The nested [target] block carries the target's display
 * shape (id, display_name, platforms, mcp_servers) plus references to per-file scripted
 * tools under the pack's `tools/` directory. The pack's own file-backed artifacts
 * ([toolsets], [waypoints], plus operational tool YAMLs auto-discovered from
 * `<pack>/tools/`) stay separate.
 *
 * ## Library packs vs target packs
 *
 * The presence or absence of [target] discriminates two pack shapes — a single source of
 * truth that doesn't risk a `type:` field disagreeing with the actual content:
 *
 *  - **Target pack** ([target] is non-null): models a runnable app under test. May declare
 *    everything below — operational tools (auto-discovered from `<pack>/tools/`),
 *    [toolsets], [waypoints], trailheads — and surfaces an [AppTargetYamlConfig] that the
 *    runtime binds devices/sessions to. The framework-bundled `clock`, `wikipedia`,
 *    `contacts` packs are target packs.
 *  - **Library pack** ([target] is null): ships cross-target reusable tooling — operational
 *    tools (auto-discovered from `<pack>/tools/`) and [toolsets] only. **Library packs
 *    MUST NOT declare [waypoints] or trailhead tools** (a trailhead bootstraps to a known
 *    waypoint, which only makes sense within a target). The framework-bundled `trailblaze`
 *    pack — which publishes per-platform [defaults] for the standard built-in toolsets —
 *    is the canonical library pack.
 *
 * Tools and toolsets are orthogonal to pack type — both shapes contribute them through
 * the same registries. The runtime tool registry doesn't care which pack a tool came
 * from; it resolves by id.
 *
 * The library/target distinction also makes the reverse lookup deterministic: given a
 * waypoint id, walk the resolved pack list to find the (single) pack that declared it,
 * then read that pack's [target] for the owning target. No id-prefix convention needed.
 *
 * Library-pack contract violations (waypoints declared, trailhead tool referenced) are
 * caught at load time by [TrailblazePackManifestLoader] and the project config loader,
 * with the offending field/file named in the error.
 *
 * ## Composition via [dependencies]
 *
 * A pack may declare a list of other pack ids it depends on. The model mirrors a Gradle
 * classpath: depending on a pack pulls in *its* dependencies transitively, and each pack
 * in the resolved graph contributes its bundled artifacts (toolsets, tools, waypoints) to
 * the workspace pool.
 *
 * The framework ships a single `trailblaze` pack that publishes per-platform [defaults]
 * for the standard built-in toolset references. The pack itself does NOT define those
 * toolsets — `core_interaction`, `web_core`, etc. are discovered globally from the
 * `trailblaze-config/toolsets/` directory on the classpath, independent of pack
 * resolution. The `trailblaze` pack's role is to give consumers a single dependency
 * id whose `defaults:` points at those globally-discovered toolset names. The
 * conventional consumer preamble is `dependencies: [trailblaze]` — explicit, no magic.
 *
 * ## Per-platform defaults via [defaults]
 *
 * A pack can publish a [defaults] map (same shape as `target.platforms`) that fills in
 * missing fields on a consumer's `target.platforms`. Resolution is **closest-to-root
 * wins** at the field level — the consumer's own values always win; for fields the
 * consumer leaves null, the dep-graph walk takes the closest depth's value (later-
 * declared deps at the same depth win on ties). No list concatenation: a consumer that
 * writes `tool_sets:` for a platform overrides the inherited list entirely. This
 * preserves the per-platform visibility of the original target shape — authors who
 * want to see "memory shows up on every platform" just keep the explicit listing.
 *
 * Cycles and missing deps cause the consumer pack to be skipped with a logged error;
 * sibling packs continue to load (atomic-per-pack failure model).
 *
 * ## Field implementation status
 *
 * | Field | Runtime status |
 * | --- | --- |
 * | [id], [target] | Fully wired by `TrailblazeProjectConfigLoader.resolvePackArtifacts`. |
 * | [dependencies], [defaults] | Fully wired — dep graph walked at pack resolution time, and a depended-on pack id is brought into scope the same way as a workspace-listed target. |
 * | [toolsets] | Fully wired — sibling refs resolved at pack load time. |
 * | [waypoints] | Fully wired — surfaced via `TrailblazeResolvedConfig.waypoints` and the `trailblaze waypoint` CLI. |
 * | [trails] | **Reserved schema slot; runtime loading deferred.** |
 *
 * **No top-level `tools:` field.** Operational tool YAMLs (`*.tool.yaml`, `*.shortcut.yaml`,
 * `*.trailhead.yaml`) are auto-discovered from `<pack>/tools/` by directory walk. Authoring is
 * "drop a YAML file in `tools/`, it ships with the pack" — the manifest doesn't enumerate. Per-
 * target scripted-tool descriptors stay listed under `target.tools:` because they're per-target
 * glue, not standard library content.
 *
 * The previous `routes:` reserved slot was removed in 2026-04-28 — routes were dropped as a
 * separate concept in favor of "shortcuts that invoke other shortcuts."
 */
@Serializable
data class TrailblazePackManifest(
  @SerialName("id") val id: String,
  @SerialName("target") val target: PackTargetConfig? = null,
  /**
   * Pack ids this pack depends on. Transitive — depending on a pack pulls in its
   * dependencies too. See class kdoc for resolution semantics.
   */
  @SerialName("dependencies") val dependencies: List<String> = emptyList(),
  /**
   * Per-platform defaults this pack contributes to consumers via dependency resolution.
   * Same shape as `target.platforms`: keys are platform names (`android`, `ios`, `web`,
   * `compose`), values are [PlatformConfig] objects whose nullable fields each act as
   * an opt-in default — `appIds`, `toolSets`, `tools`, `excludedTools`, `drivers`,
   * `baseUrl`, `minBuildVersion`. A consumer's `target.platforms[<key>].<field>` is
   * filled in from this map (closest-to-root wins) only when the consumer leaves the
   * field null; setting the field on the consumer overrides entirely (no list concat).
   *
   * Example (the bundled `trailblaze` framework pack):
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
  @SerialName("toolsets") val toolsets: List<String> = emptyList(),
  /**
   * Deprecated / ignored at the resolution layer. Waypoints are now auto-discovered from
   * any `*.waypoint.yaml` file under `<pack-dir>/waypoints/` (at any depth). The resolver
   * no longer reads this list. The field remains in the schema only so legacy `pack.yaml`
   * files with explicit waypoint enumerations don't fail parsing during the migration
   * window. New packs should leave this field absent and just drop their waypoint YAMLs
   * into the `waypoints/` directory.
   *
   * The manifest-side library-pack guard
   * ([TrailblazePackManifestLoader.enforceLibraryPackContract]) still fires when this
   * field is non-empty on a target-less pack — defensive duplication of the discovery-
   * side guard so legacy packs fail fast at parse time before disk traversal.
   */
  @SerialName("waypoints") val waypoints: List<String> = emptyList(),
  /** Reserved schema slot — runtime loading deferred. */
  @SerialName("trails") val trails: List<String> = emptyList(),
)

/**
 * The target shape inside a pack. Differs from [AppTargetYamlConfig] in two ways:
 *
 * - [id] is optional (defaults to the pack's [TrailblazePackManifest.id]).
 * - [tools] holds **file paths**, not inline tool definitions. Each path is resolved by
 *   the pack loader to a [PackScriptedToolFile] under the pack directory and translated
 *   into the runtime [InlineScriptToolConfig] shape. This keeps every scripted tool in
 *   its own file under `<pack>/tools/<tool-name>.yaml` and avoids ballooning the pack
 *   manifest as a workspace grows.
 *
 * Use [toAppTargetYamlConfig] *after* the loader has resolved [tools] paths to the
 * runtime tool list.
 */
@Serializable
data class PackTargetConfig(
  @SerialName("id") val id: String? = null,
  @SerialName("display_name") val displayName: String,
  @SerialName("platforms") val platforms: Map<String, PlatformConfig>? = null,
  @SerialName("has_custom_ios_driver") val hasCustomIosDriver: Boolean = false,
  @SerialName("mcp_servers") val mcpServers: List<McpServerConfig>? = null,
  /**
   * Path (relative to the pack directory) of a markdown / text file containing the system-prompt
   * template. The pack loader reads the file and inlines its content into the generated
   * [AppTargetYamlConfig.systemPrompt] field.
   *
   * **Authoring contract:** prompts MUST live in a standalone file referenced from here — there
   * is no inline string slot on [PackTargetConfig]. Standalone files are easier to read and edit
   * than long YAML strings, and the file-reference shape leaves room for future per-device or
   * per-classifier prompt selection (e.g. `app-tablet.prompt.md` next to the pack manifest)
   * without an authoring-side schema change.
   */
  @SerialName("system_prompt_file") val systemPromptFile: String? = null,
  @SerialName("tools") val tools: List<String> = emptyList(),
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
      mcpServers = mcpServers,
      systemPrompt = resolvedSystemPrompt,
      tools = resolvedTools.takeIf { it.isNotEmpty() },
    )
}
