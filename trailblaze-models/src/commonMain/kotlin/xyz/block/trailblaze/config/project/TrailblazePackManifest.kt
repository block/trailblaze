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
 * (`toolsets`, `tools`, `waypoints`) stay separate.
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
 * | [dependencies], [defaults] | Fully wired — dep graph walked at pack resolution time. |
 * | [toolsets], [tools] | Fully wired — sibling refs resolved at pack load time. |
 * | [waypoints] | Fully wired — surfaced via `TrailblazeResolvedConfig.waypoints` and the `trailblaze waypoint` CLI. |
 * | [trails] | **Reserved schema slot; runtime loading deferred.** |
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
  @SerialName("tools") val tools: List<String> = emptyList(),
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
