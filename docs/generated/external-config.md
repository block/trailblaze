---
title: External Config
---

> **Auto-generated documentation** — Do not edit manually.

# External Config for Binary Users

Trailblaze's desktop/CLI binary currently builds the effective app-target config from three layers, in order: framework-bundled `trailblaze-config/**` resources, an optional workspace `trails/config/` directory, and the current workspace's `trails/config/trailblaze.yaml` entries. Later layers override earlier ones by id / filename.

The intended split is now the live split: `trails/` is the workspace anchor, `trails/config/trailblaze.yaml` is the workspace manifest, and `trails/config/` is the artifact directory that holds concrete pack, target, toolset, and tool files, plus the reserved location for provider YAMLs.

## Lookup Order for Filesystem Config

The binary resolves the external `trails/config/` directory in this order:

1. `TRAILBLAZE_CONFIG_DIR` environment variable. Use this when you want an explicit per-run override.
2. Walk up from the current working directory until `trails/config/trailblaze.yaml` is found, then use that owning `trails/config/` directory.
3. Otherwise: bundled classpath config only.

## Recommended Alignment

The coherent model is to keep `trails/` as the workspace anchor, `trails/config/trailblaze.yaml` as the project entry point, and `trails/config/` as the directory that holds concrete pack, target, toolset, and tool files, plus the reserved provider location.

That gives you a clean split:

1. `trails/` answers 'what Trailblaze workspace am I in?'
2. `trails/config/trailblaze.yaml` answers 'what project config should be active here?'
3. `trails/config/` answers 'where do the contributed config artifacts actually live?'
4. `~/.trailblaze/` remains user-level state/config, not the project contribution surface.

This is a better fit than making users author a project-local `.trailblaze/` directory. It preserves the existing `trails/` mental model, keeps config and trail assets together, and keeps the migration surface localized to the workspace/config loaders rather than every target/toolset/tool author.

## Recommended Workspace Layout

This layout is the current binary behavior:

```text
your-workspace/
└── trails/
    ├── config/
    │   ├── trailblaze.yaml
    │   ├── packs/
    │   │   └── your-pack/
    │   │       └── pack.yaml
    │   ├── targets/
    │   │   └── your-target.yaml
    │   ├── toolsets/
    │   │   └── your-toolset.yaml
    │   ├── tools/
    │   │   └── your-tool.yaml
    │   ├── providers/
    │   │   └── your-provider.yaml
    │   └── mcp/
    │       └── your-tools.ts
    └── login.trail.yaml
```

The binary auto-discovers `trails/config/targets`, `toolsets/`, and `tools/`. `packs:` entries in `trails/config/trailblaze.yaml` pull pack manifests through the same loader path. `mcp/` is just a convention for the JS/TS files you reference from pack or target YAML. `providers/` remains the reserved location for provider YAMLs; today provider loading still comes from the `llm:` block in `trails/config/trailblaze.yaml` plus built-in classpath metadata.

## Pack Discovery Sources

Pack manifests can reach the runtime via two sources, in precedence order **base → override**:

1. **Classpath-bundled packs** under `trailblaze-config/packs/<id>/pack.yaml`. Auto-discovered from JAR or compiled-resources entries by the framework — users get framework-shipped packs (`clock`, `wikipedia`, `contacts`) without writing any `packs:` entry.
2. **Workspace `packs:` entries** in `trailblaze.yaml`. Anchor-relative filesystem paths to your own pack manifests.

### Pack-id Collision

When the same pack `id` appears in both sources, **the workspace pack wholesale shadows the classpath pack**. Workspace authors can locally override framework-shipped packs without having to fork them — useful when you want a different `target.platforms` block, a tweaked toolset list, or an overridden waypoint set for a bundled pack.

If you re-author a framework pack id locally, **all** of its bundled contributions are dropped — the override is wholesale, not per-field. To extend rather than replace, wait for `extend:` semantics (reserved schema field today, runtime semantics deferred).

This precedence is intentional and is documented in code on `TrailblazeResolvedConfig`. If the framework ever ships packs with non-overridable invariants, we'd revisit by adding a sealed/locked flag on the manifest rather than changing this default.

## What Works Today

| Contribution | Filesystem Overlay | Notes |
| --- | --- | --- |
| `packs/<id>/pack.yaml` via `trails/config/trailblaze.yaml` `packs:` | Yes | Pack-first authored unit. Flattens nested `target:` plus referenced toolsets/tools back into the existing runtime model. |
| `targets/*.yaml` | Yes | Defines target ids, per-platform app ids, tool selection, driver scoping, and target-root `mcp_servers:`. Still supported as the legacy compatibility path. |
| `toolsets/*.yaml` | Yes | Groups tools and can scope them with `platforms:` or `drivers:`. |
| `tools/*.yaml` with `class:` | Yes | The class must already be on the JVM classpath. |
| `tools/*.yaml` with `tools:` | Not yet | YAML-defined tool composition is currently classpath-backed, not loaded as a new filesystem contribution. |
| `mcp_servers: [{ script: ... }]` at target root | Yes | JS/TS MCP servers are supported today from target YAML. |
| Toolset-level MCP server declarations | Not yet | `mcp_servers:` is currently a target feature, not a toolset feature. |
| `trailblaze.yaml` targets / toolsets / tools | Partially | Targets and toolsets are live today, and class-backed `tools:` entries participate in discovery. Provider refs and external YAML-defined (`tools:` mode) project tools are still follow-up work. |

## Authoring a Target

Targets are declared in `targets/*.yaml`. Each target has an `id`, a `display_name`, and one or more platform sections.

| Field | Purpose |
| --- | --- |
| `platforms.<platform>.app_ids` | App identifiers for that platform. |
| `platforms.<platform>.tool_sets` | Toolset ids enabled for that platform section. |
| `platforms.<platform>.tools` | Extra tool names added directly for that platform section. |
| `platforms.<platform>.excluded_tools` | Tool names explicitly removed for that platform section. |
| `platforms.<platform>.drivers` | Narrow the section to specific drivers instead of the platform shorthand. |
| `platforms.<platform>.min_build_version` | Optional minimum build gate. |
| `mcp_servers` | Target-specific JS/TS MCP servers. Current support is `script:` entries. |

### Platform Section Keys

- `android`
- `ios`
- `web`
- `desktop`

`compose` currently rides on the `web` platform bucket and is selected with `drivers: [compose]`.

### Reference Pack in This Repo

The sample app ships a filesystem-backed pack at `examples/android-sample-app/trails/config/packs/sampleapp/pack.yaml`:

```yaml
id: sampleapp
target:
  display_name: Trailblaze Sample App
  platforms:
    android:
      app_ids:
        - xyz.block.trailblaze.examples.sampleapp
  mcp_servers:
    - script: ./examples/android-sample-app/trails/config/mcp/tools.ts
    - script: ./examples/android-sample-app/trails/config/mcp-sdk/tools.ts
  # Pack scripted tools — flat-schema authoring surface, one tool per file under tools/.
  # Lighter-weight than the `mcp_servers:` block above (no `bun install` required, no
  # subprocess MCP server). The host runner synthesizes a small wrapper at session start.
  tools:
    - tools/host_writeArtifact.yaml
```

## Authoring Toolsets

Toolsets are declared in `toolsets/*.yaml`. They are pure YAML groupings: `id`, `description`, optional `platforms:` / `drivers:` filters, optional `always_enabled`, and a `tools:` list.

### Driver / Platform Keys for Toolsets

| YAML key | Expands to |
| --- | --- |
| `android` | `android-ondevice-accessibility`, `android-ondevice-instrumentation` |
| `ios` | `ios-host`, `ios-axe` |
| `web` | `playwright-native`, `playwright-electron` |
| `desktop` | `compose` |
| `all` | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host`, `ios-axe`, `playwright-native`, `playwright-electron`, `revyl-android`, `revyl-ios`, `compose` |
| `android-ondevice-accessibility` | specific `Android` driver |
| `android-ondevice-instrumentation` | specific `Android` driver |
| `ios-host` | specific `iOS` driver |
| `ios-axe` | specific `iOS` driver |
| `playwright-native` | specific `Web Browser` driver |
| `playwright-electron` | specific `Web Browser` driver |
| `revyl-android` | specific `Android` driver |
| `revyl-ios` | specific `iOS` driver |
| `compose` | specific `Compose Desktop` driver |

### Bundled Toolsets Available Today

| Toolset | Always Enabled | Compatible Drivers | Tool Count |
| --- | --- | --- | ---: |
| `compose_core` | No | `compose` | 6 |
| `compose_verification` | No | `compose` | 2 |
| `core_interaction` | Yes | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 15 |
| `memory` | No | `all drivers` | 8 |
| `meta` | Yes | `all drivers` | 2 |
| `navigation` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 4 |
| `observation` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 1 |
| `revyl_core` | No | `revyl-android`, `revyl-ios` | 7 |
| `revyl_verification` | No | `revyl-android`, `revyl-ios` | 1 |
| `verification` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 2 |
| `web_core` | No | `playwright-electron`, `playwright-native` | 10 |
| `web_verification` | No | `playwright-electron`, `playwright-native` | 5 |

## Authoring Tools

Tool definitions have three current shapes:

1. **Class-backed YAML** in `tools/*.yaml`.
2. **YAML-defined composition** in `tools/*.yaml` with a `tools:` block.
3. **JS/TS MCP tools** referenced from a target's `mcp_servers:` block.

### Class-Backed YAML

```yaml
id: myCustomTool
class: com.example.trailblaze.tools.MyCustomTrailblazeTool
```

This works from both bundled classpath config and the filesystem overlay, as long as the backing class is already on the binary's JVM classpath.

### YAML-Defined Composition

```yaml
id: eraseTextSafely
description: "Erases characters from the focused field."
parameters:
  - name: charactersToErase
    type: integer
    required: false
tools:
  - maestro:
      commands:
        - eraseText:
            charactersToErase: "{{params.charactersToErase}}"
```

This authoring mode is part of the tool schema today, but new filesystem contributions of this kind are not yet wired into the binary's global tool resolver.

### JS / TS MCP Tools

For binary users, JS/TS tools are the path that does **not** require rebuilding Trailblaze. Put the script anywhere in your workspace, then reference it from the target YAML with `mcp_servers:`.

```yaml
mcp_servers:
  - script: ./trails/config/mcp/your-tools.ts
```

Current path resolution for `script:` is against the JVM's current working directory (where you launched `trailblaze`), not the YAML file's directory. Use a repo-relative path that works from your launch directory.

### Reference JS / TS Tool Packages in This Repo

- `examples/android-sample-app/trails/config/mcp/tools.ts`
- `examples/android-sample-app/trails/config/mcp-sdk/tools.ts`

The sample app intentionally carries both the raw MCP SDK authoring surface and the `@trailblaze/scripting` SDK authoring surface side-by-side.

## Distribution Pattern for Pre-Vetted Target Packs

The current loader already supports a good packaging model for app-specific bundles such as a Gmail web pack or a pre-vetted enterprise app pack:

1. Ship a self-contained `trails/config/` directory with one or more targets, app-specific toolsets, and JS/TS MCP tools.
2. Point the binary at that directory with `TRAILBLAZE_CONFIG_DIR=/path/to/trails/config` or place it at `<workspace>/trails/config/` under the `trails/` anchor.
3. Keep target-specific capabilities inside the pack's nested target block so the agent only sees the extra tools when that target is active.
4. Use toolsets to keep high-value actions coarse-grained. That lets the LLM solve common website/app tasks with fewer tool calls.

What is still missing for a full remote-download story is install/update UX, toolset-level MCP sources, and filesystem discovery for YAML-defined `tools:` compositions. The directory shape above is still the right place to put those contributions as the wiring lands.

---

**Source**: `xyz.block.trailblaze.ui.TrailblazeSettingsRepo`, `xyz.block.trailblaze.host.AppTargetDiscovery`, `xyz.block.trailblaze.config.AppTargetYamlConfig`, `xyz.block.trailblaze.config.ToolSetYamlConfig`, `xyz.block.trailblaze.config.ToolYamlConfig`, `xyz.block.trailblaze.config.McpServerConfig`, `xyz.block.trailblaze.config.project.TrailblazeProjectConfig`, `xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader`, `examples/android-sample-app/trails/config/packs/sampleapp/pack.yaml`

**Regenerate**: `./gradlew :docs:generator:run`
