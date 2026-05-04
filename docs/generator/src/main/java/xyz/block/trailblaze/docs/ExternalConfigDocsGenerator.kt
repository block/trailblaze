package xyz.block.trailblaze.docs

import java.io.File
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.config.ToolSetYamlLoader
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
/**
 * Generates a source-backed guide for the current external-config story used by the desktop/CLI
 * binary.
 */
class ExternalConfigDocsGenerator(
  private val generatedDir: File,
  private val opensourceRoot: File,
) {

  fun generate() {
    val outputFile = File(generatedDir, "external-config.md")
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    val toolSets = ToolSetYamlLoader.discoverAndLoadAll(resolver).values.sortedBy { it.config.id }
    val sampleConfigRoot =
      File(opensourceRoot, "examples/android-sample-app/${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}")
    val samplePackFile =
      File(sampleConfigRoot, "${TrailblazeConfigPaths.PACKS_SUBDIR}/sampleapp/${TrailblazeConfigPaths.PACK_MANIFEST_FILENAME}")
    val samplePackYaml = samplePackFile.takeIf { it.isFile }?.readText()?.trim()
    val sampleScriptFiles = listOf(
      "mcp/tools.ts",
      "mcp-sdk/tools.ts",
    ).map { File(sampleConfigRoot, it) }.filter { it.isFile }

    outputFile.writeText(
      buildString {
        appendLine("---")
        appendLine("title: External Config")
        appendLine("---")
        appendLine()
        appendLine("> **Auto-generated documentation** — Do not edit manually.")
        appendLine()
        appendLine("# External Config for Binary Users")
        appendLine()
        appendLine(
          "Trailblaze's desktop/CLI binary currently builds the effective app-target config " +
            "from three layers, in order: framework-bundled `${TrailblazeConfigPaths.CONFIG_DIR}/**` " +
            "resources, an optional workspace `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` " +
            "directory, and the current workspace's `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` " +
            "entries. Later layers override earlier ones by id / filename.",
        )
        appendLine()
        appendLine(
          "The intended split is now the live split: ${workspaceAnchorDir()} is the workspace anchor, " +
            "`${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` is the workspace manifest, and " +
            "`${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` is the artifact directory that holds " +
            "concrete pack, target, toolset, and tool files, plus the reserved location for provider YAMLs.",
        )
        appendLine()

        appendLine("## Lookup Order for Filesystem Config")
        appendLine()
        appendLine(
          "The binary resolves the external `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` directory in " +
            "this order:",
        )
        appendLine()
        appendLine(
          "1. `TRAILBLAZE_CONFIG_DIR` environment variable. Use this when you want an explicit " +
            "per-run override.",
        )
        appendLine(
          "2. Walk up from the current working directory until `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` " +
            "is found, then use that owning `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` directory.",
        )
        appendLine("3. Otherwise: bundled classpath config only.")
        appendLine()

        appendLine("## Recommended Alignment")
        appendLine()
        appendLine(
          "The coherent model is to keep ${workspaceAnchorDir()} as the workspace anchor, " +
            "`${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` as the project entry point, and " +
            "`${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` as the directory that holds concrete pack, " +
            "target, toolset, and tool files, plus the reserved provider location.",
        )
        appendLine()
        appendLine("That gives you a clean split:")
        appendLine()
        appendLine(
          "1. ${workspaceAnchorDir()} answers 'what Trailblaze workspace am I in?'",
        )
        appendLine(
          "2. `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` answers 'what project config should be active here?'",
        )
        appendLine(
          "3. `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` answers 'where do the contributed config " +
            "artifacts actually live?'",
        )
        appendLine("4. ${userStateDir()} remains user-level state/config, not the project contribution surface.")
        appendLine()
        appendLine(
          "This is a better fit than making users author a project-local `.trailblaze/` directory. " +
            "It preserves the existing ${workspaceAnchorDir()} mental model, keeps config and trail assets " +
            "together, and keeps the migration surface localized to the " +
            "workspace/config loaders rather than every target/toolset/tool author.",
        )
        appendLine()

        appendLine("## Recommended Workspace Layout")
        appendLine()
        appendLine(
          "This layout is the current binary behavior:",
        )
        appendLine()
        appendLine("```text")
        appendLine("your-workspace/")
        appendLine("└── ${TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR}/")
        appendLine("    ├── config/")
        appendLine("    │   ├── ${TrailblazeProjectConfigLoader.CONFIG_FILENAME}")
        appendLine("    │   ├── ${TrailblazeConfigPaths.PACKS_SUBDIR}/")
        appendLine("    │   │   └── your-pack/")
        appendLine("    │   │       └── ${TrailblazeConfigPaths.PACK_MANIFEST_FILENAME}")
        appendLine("    │   ├── ${subdirName(TrailblazeConfigPaths.TARGETS_DIR)}/")
        appendLine("    │   │   └── your-target.yaml")
        appendLine("    │   ├── ${subdirName(TrailblazeConfigPaths.TOOLSETS_DIR)}/")
        appendLine("    │   │   └── your-toolset.yaml")
        appendLine("    │   ├── ${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/")
        appendLine("    │   │   └── your-tool.yaml")
        appendLine("    │   ├── ${subdirName(TrailblazeConfigPaths.PROVIDERS_DIR)}/")
        appendLine("    │   │   └── your-provider.yaml")
        appendLine("    │   └── mcp/")
        appendLine("    │       └── your-tools.ts")
        appendLine("    └── login.trail.yaml")
        appendLine("```")
        appendLine()
        appendLine(
          "The binary auto-discovers `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/targets`, " +
            "`${subdirName(TrailblazeConfigPaths.TOOLSETS_DIR)}/`, and `${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/`. " +
            "`packs:` entries in `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` pull pack manifests through the same loader path. " +
            "`mcp/` is just a convention for the JS/TS files you reference from pack or target YAML. " +
            "`providers/` remains the reserved location for provider YAMLs; today provider loading " +
            "still comes from the `llm:` block in `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` " +
            "plus built-in classpath metadata.",
        )
        appendLine()

        appendLine("## Pack Discovery Sources")
        appendLine()
        appendLine(
          "Pack manifests can reach the runtime via two sources, in precedence order " +
            "**base → override**:",
        )
        appendLine()
        appendLine(
          "1. **Classpath-bundled packs** under `trailblaze-config/packs/<id>/pack.yaml`. " +
            "Auto-discovered from JAR or compiled-resources entries by the framework — " +
            "users get framework-shipped packs (`clock`, `wikipedia`, `contacts`) without " +
            "writing any `packs:` entry.",
        )
        appendLine(
          "2. **Workspace `packs:` entries** in `${TrailblazeProjectConfigLoader.CONFIG_FILENAME}`. " +
            "Anchor-relative filesystem paths to your own pack manifests.",
        )
        appendLine()
        appendLine("### Pack-id Collision")
        appendLine()
        appendLine(
          "When the same pack `id` appears in both sources, **the workspace pack " +
            "wholesale shadows the classpath pack**. Workspace authors can locally " +
            "override framework-shipped packs without having to fork them — useful when " +
            "you want a different `target.platforms` block, a tweaked toolset list, or " +
            "an overridden waypoint set for a bundled pack.",
        )
        appendLine()
        appendLine(
          "If you re-author a framework pack id locally, **all** of its bundled " +
            "contributions are dropped — the override is wholesale, not per-field. To " +
            "extend rather than replace, wait for `extend:` semantics " +
            "(reserved schema field today, runtime semantics deferred).",
        )
        appendLine()
        appendLine(
          "This precedence is intentional and is documented in code on " +
            "`TrailblazeResolvedConfig`. If the framework ever ships packs with " +
            "non-overridable invariants, we'd revisit by adding a sealed/locked flag " +
            "on the manifest rather than changing this default.",
        )
        appendLine()

        appendLine("## What Works Today")
        appendLine()
        appendLine("| Contribution | Filesystem Overlay | Notes |")
        appendLine("| --- | --- | --- |")
        appendLine(
          "| `packs/<id>/pack.yaml` via `${TrailblazeConfigPaths.WORKSPACE_CONFIG_FILE}` `packs:` | Yes | Pack-first authored unit. Flattens nested `target:` plus referenced toolsets/tools back into the existing runtime model. |",
        )
        appendLine(
          "| `${subdirName(TrailblazeConfigPaths.TARGETS_DIR)}/*.yaml` | Yes | Defines target ids, " +
            "per-platform app ids, tool selection, driver scoping, and target-root `mcp_servers:`. Still supported as the legacy compatibility path. |",
        )
        appendLine(
          "| `${subdirName(TrailblazeConfigPaths.TOOLSETS_DIR)}/*.yaml` | Yes | Groups tools and can " +
            "scope them with `platforms:` or `drivers:`. |",
        )
        appendLine(
          "| `${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/*.yaml` with `class:` | Yes | The class " +
            "must already be on the JVM classpath. |",
        )
        appendLine(
          "| `${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/*.yaml` with `tools:` | Not yet | " +
            "YAML-defined tool composition is currently classpath-backed, not loaded as a new " +
            "filesystem contribution. |",
        )
        appendLine(
          "| `mcp_servers: [{ script: ... }]` at target root | Yes | JS/TS MCP servers are supported " +
            "today from target YAML. |",
        )
        appendLine(
          "| Toolset-level MCP server declarations | Not yet | `mcp_servers:` is currently a target " +
            "feature, not a toolset feature. |",
        )
        appendLine(
          "| `${TrailblazeProjectConfigLoader.CONFIG_FILENAME}` targets / toolsets / tools | " +
            "Partially | Targets and toolsets are live today, and class-backed `tools:` entries " +
            "participate in discovery. Provider refs and external YAML-defined (`tools:` mode) " +
            "project tools are still follow-up work. |",
        )
        appendLine()

        appendLine("## Authoring a Target")
        appendLine()
        appendLine(
          "Targets are declared in `${subdirName(TrailblazeConfigPaths.TARGETS_DIR)}/*.yaml`. " +
            "Each target has an `id`, a `display_name`, and one or more platform sections.",
        )
        appendLine()
        appendLine("| Field | Purpose |")
        appendLine("| --- | --- |")
        appendLine("| `platforms.<platform>.app_ids` | App identifiers for that platform. |")
        appendLine("| `platforms.<platform>.tool_sets` | Toolset ids enabled for that platform section. |")
        appendLine("| `platforms.<platform>.tools` | Extra tool names added directly for that platform section. |")
        appendLine("| `platforms.<platform>.excluded_tools` | Tool names explicitly removed for that platform section. |")
        appendLine("| `platforms.<platform>.drivers` | Narrow the section to specific drivers instead of the platform shorthand. |")
        appendLine("| `platforms.<platform>.min_build_version` | Optional minimum build gate. |")
        appendLine("| `mcp_servers` | Target-specific JS/TS MCP servers. Current support is `script:` entries. |")
        appendLine()

        appendLine("### Platform Section Keys")
        appendLine()
        appendLine(
          TrailblazeDevicePlatform.entries.joinToString(
            separator = "\n",
            transform = { "- `${it.name.lowercase()}`" },
          ),
        )
        appendLine()
        appendLine(
          "`compose` currently rides on the `${TrailblazeDevicePlatform.WEB.name.lowercase()}` " +
            "platform bucket and is selected with `drivers: [compose]`.",
        )
        appendLine()

        samplePackYaml?.let { yaml ->
          appendLine("### Reference Pack in This Repo")
          appendLine()
          appendLine(
            "The sample app ships a filesystem-backed pack at " +
              "`examples/android-sample-app/${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/packs/sampleapp/pack.yaml`:",
          )
          appendLine()
          appendLine("```yaml")
          appendLine(yaml)
          appendLine("```")
          appendLine()
        }

        appendLine("## Authoring Toolsets")
        appendLine()
        appendLine(
          "Toolsets are declared in `${subdirName(TrailblazeConfigPaths.TOOLSETS_DIR)}/*.yaml`. " +
            "They are pure YAML groupings: `id`, `description`, optional `platforms:` / `drivers:` " +
            "filters, optional `always_enabled`, and a `tools:` list.",
        )
        appendLine()
        appendLine("### Driver / Platform Keys for Toolsets")
        appendLine()
        appendLine("| YAML key | Expands to |")
        appendLine("| --- | --- |")
        platformAndDriverRows().forEach { (key, expansion) ->
          appendLine("| `$key` | $expansion |")
        }
        appendLine()

        appendLine("### Bundled Toolsets Available Today")
        appendLine()
        appendLine("| Toolset | Always Enabled | Compatible Drivers | Tool Count |")
        appendLine("| --- | --- | --- | ---: |")
        toolSets.forEach { toolSet ->
          val drivers = toolSet.compatibleDriverTypes
            .map { it.yamlKey }
            .sorted()
            .ifEmpty { listOf("all drivers") }
            .joinToString(", ") { "`$it`" }
          val toolCount = toolSet.resolvedToolClasses.size + toolSet.resolvedYamlToolNames.size
          appendLine(
            "| `${toolSet.config.id}` | ${yesNo(toolSet.config.alwaysEnabled)} | $drivers | $toolCount |",
          )
        }
        appendLine()

        appendLine("## Authoring Tools")
        appendLine()
        appendLine("Tool definitions have three current shapes:")
        appendLine()
        appendLine("1. **Class-backed YAML** in `${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/*.yaml`.")
        appendLine("2. **YAML-defined composition** in `${subdirName(TrailblazeConfigPaths.TOOLS_DIR)}/*.yaml` with a `tools:` block.")
        appendLine("3. **JS/TS MCP tools** referenced from a target's `mcp_servers:` block.")
        appendLine()
        appendLine("### Class-Backed YAML")
        appendLine()
        appendLine("```yaml")
        appendLine("id: myCustomTool")
        appendLine("class: com.example.trailblaze.tools.MyCustomTrailblazeTool")
        appendLine("```")
        appendLine()
        appendLine(
          "This works from both bundled classpath config and the filesystem overlay, as long as " +
            "the backing class is already on the binary's JVM classpath.",
        )
        appendLine()

        appendLine("### YAML-Defined Composition")
        appendLine()
        appendLine("```yaml")
        appendLine("id: eraseTextSafely")
        appendLine("description: \"Erases characters from the focused field.\"")
        appendLine("parameters:")
        appendLine("  - name: charactersToErase")
        appendLine("    type: integer")
        appendLine("    required: false")
        appendLine("tools:")
        appendLine("  - maestro:")
        appendLine("      commands:")
        appendLine("        - eraseText:")
        appendLine("            charactersToErase: \"{{params.charactersToErase}}\"")
        appendLine("```")
        appendLine()
        appendLine(
          "This authoring mode is part of the tool schema today, but new filesystem contributions " +
            "of this kind are not yet wired into the binary's global tool resolver.",
        )
        appendLine()

        appendLine("### JS / TS MCP Tools")
        appendLine()
        appendLine(
          "For binary users, JS/TS tools are the path that does **not** require rebuilding " +
            "Trailblaze. Put the script anywhere in your workspace, then reference it from the " +
            "target YAML with `mcp_servers:`.",
        )
        appendLine()
        appendLine("```yaml")
        appendLine("mcp_servers:")
        appendLine("  - script: ./trails/config/mcp/your-tools.ts")
        appendLine("```")
        appendLine()
        appendLine(
          "Current path resolution for `script:` is against the JVM's current working directory " +
            "(where you launched `trailblaze`), not the YAML file's directory. Use a repo-relative " +
            "path that works from your launch directory.",
        )
        appendLine()

        if (sampleScriptFiles.isNotEmpty()) {
          appendLine("### Reference JS / TS Tool Packages in This Repo")
          appendLine()
          sampleScriptFiles.forEach { file ->
            val relativePath = file.relativeTo(opensourceRoot).invariantSeparatorsPath
            appendLine("- `$relativePath`")
          }
          appendLine()
          appendLine(
            "The sample app intentionally carries both the raw MCP SDK authoring surface and the " +
              "`@trailblaze/scripting` SDK authoring surface side-by-side.",
          )
          appendLine()
        }

        appendLine("## Distribution Pattern for Pre-Vetted Target Packs")
        appendLine()
        appendLine(
          "The current loader already supports a good packaging model for app-specific bundles " +
            "such as a Gmail web pack or a pre-vetted enterprise app pack:",
        )
        appendLine()
        appendLine(
          "1. Ship a self-contained `${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` directory with one or " +
            "more targets, app-specific toolsets, and JS/TS MCP tools.",
        )
        appendLine(
          "2. Point the binary at that directory with `TRAILBLAZE_CONFIG_DIR=/path/to/${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}` " +
            "or place it at `<workspace>/${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/` under the ${workspaceAnchorDir()} anchor.",
        )
        appendLine(
          "3. Keep target-specific capabilities inside the pack's nested target block so the agent only sees the " +
            "extra tools when that target is active.",
        )
        appendLine(
          "4. Use toolsets to keep high-value actions coarse-grained. That lets the LLM solve " +
            "common website/app tasks with fewer tool calls.",
        )
        appendLine()
        appendLine(
          "What is still missing for a full remote-download story is install/update UX, " +
            "toolset-level MCP sources, and filesystem discovery for YAML-defined `tools:` " +
            "compositions. The directory shape above is still the right place to put those " +
            "contributions as the wiring lands.",
        )
        appendLine()

        appendLine("---")
        appendLine()
        appendLine(
          "**Source**: `xyz.block.trailblaze.ui.TrailblazeSettingsRepo`, " +
            "`xyz.block.trailblaze.host.AppTargetDiscovery`, " +
            "`xyz.block.trailblaze.config.AppTargetYamlConfig`, " +
            "`xyz.block.trailblaze.config.ToolSetYamlConfig`, " +
            "`xyz.block.trailblaze.config.ToolYamlConfig`, " +
            "`xyz.block.trailblaze.config.McpServerConfig`, " +
            "`xyz.block.trailblaze.config.project.TrailblazeProjectConfig`, " +
            "`xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader`, " +
            "`examples/android-sample-app/${TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR}/packs/sampleapp/pack.yaml`",
        )
        appendLine()
        appendLine("**Regenerate**: `./gradlew :docs:generator:run`")
      },
    )
  }

  private fun platformAndDriverRows(): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    TrailblazeDevicePlatform.entries.forEach { platform ->
      val drivers = TrailblazeDriverType.entries
        .filter { it.platform == platform && !it.yamlKey.startsWith("revyl-") }
        .joinToString(", ") { "`${it.yamlKey}`" }
      rows += platform.name.lowercase() to drivers
    }
    rows += "all" to TrailblazeDriverType.entries.joinToString(", ") { "`${it.yamlKey}`" }
    TrailblazeDriverType.entries.forEach { driver ->
      rows += driver.yamlKey to "specific `${driver.platform.displayName}` driver"
    }
    return rows
  }

  private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

  private fun subdirName(path: String): String = path.substringAfterLast('/')

  private fun workspaceAnchorDir(): String = "`${TrailblazeConfigPaths.WORKSPACE_TRAILS_DIR}/`"

  private fun userStateDir(): String = "`~/${TrailblazeConfigPaths.DOT_TRAILBLAZE_DIR}/`"
}
