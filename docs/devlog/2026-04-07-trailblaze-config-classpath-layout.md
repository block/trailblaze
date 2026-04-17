---
title: "Unified trailblaze-config/ Classpath Layout"
type: decision
date: 2026-04-07
---

# Unified `trailblaze-config/` Classpath Layout

## Summary

All classpath-bundled configuration now lives under a single `trailblaze-config/` resource directory. Provider YAML definitions moved from `llm/providers/` into `trailblaze-config/providers/`, consolidating two separate config roots into one.

## Context

We had two unrelated classpath resource paths for configuration:

| Path | Contents |
|------|----------|
| `llm/providers/{provider_id}.yaml` | Built-in LLM provider definitions (models, auth, pricing) |
| `trailblaze-config/trailblaze.yaml` | Project-level defaults (default model) |

Both serve the same system but lived in different directory trees. The `trailblaze-config/` prefix exists because Android AGP strips dot-prefixed directories from both assets and Java resources — so `trailblaze-config` is the dot-free alternative to `.trailblaze` for anything bundled into an APK.

Having `llm/providers/` as a separate root made it unclear where users should put their own provider YAMLs and created a split namespace for related config.

## Decision

### Directory layout

```
src/main/resources/
  trailblaze-config/
    trailblaze.yaml                     # project defaults (llm.defaults.model, etc.)
    providers/
      openai.yaml                       # built-in provider shipped with framework
      anthropic.yaml                    # built-in provider shipped with framework
      my_custom_provider.yaml           # user-contributed provider (same format)
```

### File naming convention

Provider YAML filenames **must** match the `provider_id` field inside the file:

- Filename: `{provider_id}.yaml` (snake_case)
- Inside the file: `provider_id: {provider_id}`

Example: `my_custom_provider.yaml` contains `provider_id: my_custom_provider`.

### How users contribute providers

Any module on the classpath can contribute provider definitions. Place a `{provider_id}.yaml` file at:

```
src/main/resources/trailblaze-config/providers/
```

Classpath discovery (`BuiltInProviderResourceReaderImpl`) scans all classpath entries for files under `trailblaze-config/providers/` and merges them. On Android runtimes where directory scanning isn't supported, it falls back to loading core providers by name.

### Path constants

`TrailblazeConfigPaths` is the single source of truth for all resource paths:

| Constant | Value | Purpose |
|----------|-------|---------|
| `CONFIG_DIR` | `trailblaze-config` | Root classpath config directory |
| `CONFIG_FILENAME` | `trailblaze.yaml` | Config filename |
| `CONFIG_RESOURCE_PATH` | `trailblaze-config/trailblaze.yaml` | Full path to project config |
| `PROVIDERS_DIR` | `trailblaze-config/providers` | Provider YAML directory |
| `DOT_TRAILBLAZE_DIR` | `.trailblaze` | Desktop user-level config dir (filesystem, not classpath) |

### What did NOT change

- **Desktop user config** stays at `~/.trailblaze/trailblaze.yaml` (filesystem path, not classpath)
- **Project-level config** at `trailblaze-config/trailblaze.yaml` (path unchanged)
- **MCP resource URI** `trailblaze://llm/providers` (protocol URI, not a file path)
- **Provider YAML schema** (same `BuiltInProviderConfig` format)

### Android constraint

Android AGP strips directories starting with `.` from both assets and Java resources. This is why classpath resources use `trailblaze-config` (no dot prefix) rather than `.trailblaze`. Desktop filesystem paths can use `.trailblaze` because they're not subject to AGP processing.

## Related Documents

- [030: LLM Provider Configuration](2026-02-04-llm-provider-configuration.md) — YAML schema for providers and models
- [036: Workspace Config Resolution](2026-04-07-trailblaze-yaml-config-resolution.md) — `.trailblaze/` directory walk-up strategy for desktop
