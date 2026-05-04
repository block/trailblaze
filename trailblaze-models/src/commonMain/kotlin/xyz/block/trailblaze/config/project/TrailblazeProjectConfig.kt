package xyz.block.trailblaze.config.project

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.config.LlmConfig

/**
 * Top-level schema for `trailblaze.yaml` — the single config file sitting at
 * `trails/config/trailblaze.yaml` inside a Trailblaze workspace.
 *
 * All sections are optional. An empty file (or one that decodes to `{}`) is valid: the
 * framework's built-in classpath config still applies.
 *
 * Example:
 * ```yaml
 * defaults:
 *   target: sampleapp
 *   llm: openai/gpt-4.1
 *
 * packs:
 *   - packs/sampleapp/pack.yaml
 *
 * targets:
 *   # Inline entry:
 *   - id: sampleapp
 *     display_name: Trailblaze Sample App
 *     platforms:
 *       android:
 *         app_ids:
 *           - xyz.block.trailblaze.examples.sampleapp
 *   # External-file entry (anchor-relative path with leading `/`; caller-file-relative
 *   # otherwise — resolution happens at load time):
 *   - ref: targets/my-app.yaml
 *
 * toolsets:
 *   - ref: toolsets/my-custom-toolset.yaml
 *
 * providers:
 *   - ref: providers/custom.yaml
 *
 * llm:
 *   defaults:
 *     model: openai/gpt-4.1
 * ```
 *
 * The original plan proposed a YAML-native `!include` tag. kaml (the serialization library
 * this repo uses) doesn't expose custom tag resolution for file inclusion — its tag support
 * is limited to polymorphic type discrimination. The `ref:` pointer approach covered by
 * [TrailblazeProjectConfigEntry] works entirely through standard kaml deserialization and
 * is the option explicitly called out in the plan's "Open questions" section for this case.
 */
@Serializable
data class TrailblazeProjectConfig(
  @SerialName("defaults") val defaults: ProjectDefaults? = null,
  @SerialName("packs") val packs: List<String> = emptyList(),
  @SerialName("targets") val targets: List<TargetEntry> = emptyList(),
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
