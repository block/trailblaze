---
title: External Config
---

> **Auto-generated documentation** — Do not edit manually.

# External Config for Binary Users

Trailblaze's desktop/CLI binary currently builds the effective app-target config from three layers, in order: framework-bundled `trails/config/**` resources, an optional workspace `trails/config/` directory, and the current workspace's `trails/config/trailblaze.yaml` entries. Later layers override earlier ones by id / filename.

The intended split is now the live split: `trails/` is the workspace anchor, `trails/config/trailblaze.yaml` is the workspace manifest, and `trails/config/` is the artifact directory that holds concrete trailmap, target, toolset, and tool files, plus the reserved location for provider YAMLs.

## Lookup Order for Filesystem Config

The binary resolves the external `trails/config/` directory in this order:

1. `TRAILBLAZE_CONFIG_DIR` environment variable. Use this when you want an explicit per-run override.
2. Walk up from the current working directory until `trails/config/trailblaze.yaml` is found, then use that owning `trails/config/` directory.
3. Otherwise: bundled classpath config only.

## Recommended Alignment

The coherent model is to keep `trails/` as the workspace anchor, `trails/config/trailblaze.yaml` as the project entry point, and `trails/config/` as the directory that holds concrete trailmap, target, toolset, and tool files, plus the reserved provider location.

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
    │   ├── trailmaps/
    │   │   └── your-trailmap/
    │   │       ├── trailmap.yaml
    │   │       ├── toolsets/
    │   │       │   └── your-toolset.yaml
    │   │       └── tools/
    │   │           └── your-tool.tool.yaml
    │   ├── targets/
    │   │   └── your-target.yaml
    │   ├── providers/
    │   │   └── your-provider.yaml
    │   └── mcp/
    │       └── your-tools.ts
    └── login.trail.yaml
```

The binary auto-discovers `trails/config/targets` and every `trails/config/trailmaps/<id>/{tools,toolsets,shortcuts,trailheads}/` tree. `trailmaps:` entries in `trails/config/trailblaze.yaml` pull trailmap manifests through the same loader path. `mcp/` is just a convention for the JS/TS files you reference from trailmap or target YAML. `providers/` remains the reserved location for provider YAMLs; today provider loading still comes from the `llm:` block in `trails/config/trailblaze.yaml` plus built-in classpath metadata.

## Trailmap Discovery Sources

Trailmap manifests can reach the runtime via two sources, in precedence order **base → override**:

1. **Classpath-bundled trailmaps** under `trails/config/trailmaps/<id>/trailmap.yaml`. Auto-discovered from JAR or compiled-resources entries by the framework — users get framework-shipped trailmaps (`clock`, `wikipedia`, `contacts`) without writing any `trailmaps:` entry.
2. **Workspace `trailmaps:` entries** in `trailblaze.yaml`. Anchor-relative filesystem paths to your own trailmap manifests.

### Trailmap-id Collision

When the same trailmap `id` appears in both sources, **the workspace trailmap wholesale shadows the classpath trailmap**. Workspace authors can locally override framework-shipped trailmaps without having to fork them — useful when you want a different `target.platforms` block, a tweaked toolset list, or an overridden waypoint set for a bundled trailmap.

If you re-author a framework trailmap id locally, **all** of its bundled contributions are dropped — the override is wholesale, not per-field. To extend rather than replace, wait for `extend:` semantics (reserved schema field today, runtime semantics deferred).

This precedence is intentional and is documented in code on `TrailblazeResolvedConfig`. If the framework ever ships trailmaps with non-overridable invariants, we'd revisit by adding a sealed/locked flag on the manifest rather than changing this default.

## What Works Today

| Contribution | Filesystem Overlay | Notes |
| --- | --- | --- |
| `trailmaps/<id>/trailmap.yaml` via `trails/config/trailblaze.yaml` `trailmaps:` | Yes | Trailmap-first authored unit. Flattens nested `target:` plus referenced toolsets/tools back into the existing runtime model. |
| `targets/*.yaml` | Yes | Defines target ids, per-platform app ids, tool selection, and driver scoping. Still supported as the legacy compatibility path. |
| `trailmaps/<id>/toolsets/*.yaml` | Yes | Groups tools and can scope them with `platforms:` or `drivers:`. |
| `trailmaps/<id>/tools/*.tool.yaml` with `class:` | Yes | The class must already be on the JVM classpath. |
| `trailmaps/<id>/tools/*.tool.yaml` with `tools:` | Yes | YAML-defined tool composition. Workspace-authored entries register through `AppTargetDiscovery` and resolve via the toolset → tool dispatch chain the same way classpath-bundled tools do. |
| `trailblaze.yaml` targets / toolsets / tools | Partially | Targets and toolsets are live today, and class-backed `tools:` entries participate in discovery. Provider refs and external YAML-defined (`tools:` mode) project tools are still follow-up work. |

## Authoring a Target

Targets are declared in `targets/*.yaml`. Each target has an `id`, a `display_name`, and one or more platform sections.

| Field | Purpose |
| --- | --- |
| `platforms.<platform>.app_ids` | App identifiers for that platform. **List ordering is preserved** — the first entry is treated as the primary id (used by host-side launch / kill / clear flows that pick a single id), and subsequent entries are fallbacks consulted when the primary isn't installed on the device. |
| `platforms.<platform>.tool_sets` | Toolset ids enabled for that platform section. |
| `platforms.<platform>.tools` | Extra tool names added directly for that platform section. |
| `platforms.<platform>.excluded_tools` | Tool names explicitly removed for that platform section after `tool_sets` and `tools` are merged in. Use when a target ships its own implementation of a default tool (e.g. a `swipe` replacement that needs target-specific gestures) and wants the LLM to see only the custom variant. Names match the `@TrailblazeToolClass` registration string. |
| `platforms.<platform>.drivers` | Narrow the section to specific drivers instead of the platform shorthand. |
| `platforms.<platform>.min_build_version` | Optional minimum build gate. |

### Platform Section Keys

- `android`
- `ios`
- `web`
- `desktop`

`compose` currently rides on the `web` platform bucket and is selected with `drivers: [compose]`.

### Reference Trailmap in This Repo

The sample app ships a filesystem-backed trailmap at `examples/android-sample-app/trails/config/trailmaps/sampleapp/trailmap.yaml`:

```yaml
id: sampleapp
target:
  display_name: Trailblaze Sample App
  platforms:
    android:
      app_ids:
        - xyz.block.trailblaze.examples.sampleapp
  # Authoring surface: trailmap scripted tools — flat-schema, one tool per file under tools/.
  # No `bun install` required, no subprocess. The host runner synthesizes a small wrapper at
  # session start.
  tools:
    - sampleapp_writeArtifact
    # Authoring references — bare typed `.ts`, no `.yaml` sidecar (the framework reads the description
    # from the JSDoc; the analyzer derives the input schema from the TS types). They show how to
    # package the wait-until pattern as a reusable tool / a TypeScript trailhead, and are validated by
    # their unit tests. They are NOT backed by a shipped runnable trail (compiling a workspace `.ts`
    # tool needs the dev toolchain), like `sampleapp_writeArtifact`. The example's runnable wait demo
    # is the pure-YAML `loading/wait-for-content` trail, which uses only built-in tools.
    - sampleapp_waitForText
    - sampleapp_launchToLoadedContent
```

## Authoring Toolsets

Toolsets are declared in `trailmaps/<id>/toolsets/*.yaml`. They are pure YAML groupings: `id`, `description`, optional `platforms:` / `drivers:` filters, optional `always_enabled`, and a `tools:` list.

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
| `android_framework` | Yes | `android-ondevice-accessibility`, `android-ondevice-instrumentation` | 3 |
| `android_primitives` | Yes | `android-ondevice-accessibility`, `android-ondevice-instrumentation` | 7 |
| `compose_core` | No | `compose` | 6 |
| `compose_verification` | No | `compose` | 3 |
| `core_interaction` | Yes | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 20 |
| `memory` | No | `all drivers` | 8 |
| `meta` | Yes | `all drivers` | 1 |
| `mobile_primitives` | Yes | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 5 |
| `navigation` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 4 |
| `observation` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 1 |
| `revyl_core` | No | `revyl-android`, `revyl-ios` | 7 |
| `revyl_verification` | No | `revyl-android`, `revyl-ios` | 1 |
| `verification` | No | `android-ondevice-accessibility`, `android-ondevice-instrumentation`, `ios-host` | 3 |
| `web_core` | No | `playwright-electron`, `playwright-native` | 16 |
| `web_framework` | Yes | `playwright-electron`, `playwright-native` | 1 |
| `web_verification` | No | `playwright-electron`, `playwright-native` | 6 |

## Authoring Tools

Tool definitions have three current shapes:

1. **Class-backed YAML** in `trailmaps/<id>/tools/*.tool.yaml`.
2. **YAML-defined composition** in `trailmaps/<id>/tools/*.tool.yaml` with a `tools:` block.
3. **JS/TS scripted tools** referenced from a target's `tools:` block (each entry resolves to a `<trailmap>/tools/<name>.yaml` descriptor).

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
  - mobile_maestro:
      commands:
        - eraseText:
            charactersToErase: "{{params.charactersToErase}}"
```

This authoring mode is part of the tool schema today, but new filesystem contributions of this kind are not yet wired into the binary's global tool resolver.

### JS / TS Scripted Tools

For binary users, JS/TS tools are the path that does **not** require rebuilding Trailblaze. Put each tool's `<name>.ts` (or `.js`) file plus a sibling `<name>.yaml` descriptor under `<trailmap>/tools/`, and list the tool's `name:` under the trailmap's `target.tools:` block.

```yaml
# trailmaps/<your-trailmap>/trailmap.yaml
target:
  tools:
    - yourTool
```

Each name resolves to a sibling `<trailmap>/tools/<name>.yaml` descriptor. Runtime selection happens per descriptor: tools run in-process (QuickJS) by default; set `runtime: subprocess` to dispatch through a host bun subprocess for full Node APIs. The file extension is not a runtime hint. `requiresHost: true` is a separate, on-device visibility gate — not a runtime selector.

## Distribution Pattern for Pre-Vetted Target Trailmaps

The current loader already supports a good packaging model for app-specific bundles such as a Gmail web trailmap or a pre-vetted enterprise app trailmap:

1. Ship a self-contained `trails/config/` directory with one or more targets, app-specific toolsets, and JS/TS scripted tools.
2. Point the binary at that directory with `TRAILBLAZE_CONFIG_DIR=/path/to/trails/config` or place it at `<workspace>/trails/config/` under the `trails/` anchor.
3. Keep target-specific capabilities inside the trailmap's nested target block so the agent only sees the extra tools when that target is active.
4. Use toolsets to keep high-value actions coarse-grained. That lets the LLM solve common website/app tasks with fewer tool calls.

What is still missing for a full remote-download story is install/update UX and filesystem discovery for YAML-defined `tools:` compositions. The directory shape above is still the right place to put those contributions as the wiring lands.

---

**Source**: `xyz.block.trailblaze.ui.TrailblazeSettingsRepo`, `xyz.block.trailblaze.host.AppTargetDiscovery`, `xyz.block.trailblaze.config.AppTargetYamlConfig`, `xyz.block.trailblaze.config.ToolSetYamlConfig`, `xyz.block.trailblaze.config.ToolYamlConfig`, `xyz.block.trailblaze.config.project.TrailblazeProjectConfig`, `xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader`, `examples/android-sample-app/trails/config/trailmaps/sampleapp/trailmap.yaml`

**Regenerate**: `./gradlew :docs:generator:run`
