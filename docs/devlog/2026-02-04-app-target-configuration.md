---
title: "App Target Configuration"
type: decision
date: 2026-02-04
---

# Trailblaze Decision 031: App Target Configuration

## Context

Trailblaze tests are scoped to a **target application**. Each target app has an identity, package IDs per platform, custom tools, version requirements, and app-specific settings.

Currently implemented via `TrailblazeHostAppTarget` in Kotlin — this works for built-in targets but requires Kotlin knowledge, compilation, and coupling to the Trailblaze codebase for external teams.

## Decision

**App targets are configured via YAML in `trailblaze.yaml`, with optional user-level defaults in `~/.trailblaze/app-targets.yaml`.**

```yaml
# trailblaze.yaml (project root)
target: rideshare_driver

# App target definitions
targets:
  rideshare_driver:
    displayName: "Rideshare Driver"
    appIds:
      android: [com.example.driver, com.example.driver.debug]
      ios: [com.example.RideshareDriver]
    tools:
      mcpServers: [driver-tools]
      namespaces: [driver_, shared_]
      exclude: [tap, scroll]
    minVersion:
      android: "2024.01.15"
      ios: "2024.01.15"
```

Configuration loads in order: built-in targets → user-level (`~/.trailblaze/`) → project-level (`./trailblaze.yaml`), with later sources overriding earlier ones.

Custom driver factories and other code-based customizations still require Kotlin. YAML handles the declarative parts.

## Status

**Not yet implemented.** `TrailblazeHostAppTarget` Kotlin classes remain the active mechanism. [Decision 035](2026-03-09-agentic-dev-loop.md) later introduced dynamic app targets via `setAppTarget()`, allowing runtime creation without YAML or Kotlin — which may partially supersede this approach.

### How App Targets Filter Tools

When a user selects an app target, Trailblaze filters the available tools:

```
┌─────────────────────────────────────────────────────────────────────┐
│  User selects: target: rideshare_driver                            │
│                                                                     │
│  1. Load app target config from trailblaze.yaml                     │
│                                                                     │
│  2. Connect to MCP servers listed in tools.mcpServers               │
│     → driver-tools server provides: driver_login, driver_accept, .. │
│                                                                     │
│  3. Load tool registries from each server                           │
│     → resources/read("trailblaze://registry")                       │
│                                                                     │
│  4. Filter tools by namespace (if configured)                       │
│     → Keep: driver_*, shared_*                                      │
│     → Exclude: myapp_*, otherapp_* (not in namespace list)          │
│                                                                     │
│  5. Apply explicit exclusions                                       │
│     → Remove: tap, scroll (excluded in config)                      │
│                                                                     │
│  6. Result: Tools available to LLM for this test session            │
│     → driver_login, driver_accept, block_mockServer, ...            │
└─────────────────────────────────────────────────────────────────────┘
```

### Relationship to MCP Tool Registry

The **app target config** and **MCP tool registry** serve complementary purposes:

| Concern | Where Defined | Purpose |
|---------|--------------|---------|
| **Tool implementation** | MCP server code | What the tool does |
| **Tool metadata** | MCP registry (`resources/read`) | Platforms, groups, flags |
| **Which tools for this app** | App target config | Filtering for this test session |

### Built-in vs Custom App Targets

Trailblaze ships with built-in app targets for common apps:

```yaml
# Built-in (ships with Trailblaze)
targets:
  none:
    displayName: "None"
    # No app-specific tools, just primitives
  
  myapp:
    displayName: "MyApp"
    appIds:
      android: [com.example.myapp]
      ios: [com.example.myapp]
    tools:
      namespaces: [myapp_, shared_]

  otherapp:
    displayName: "OtherApp"
    appIds:
      android: [com.example.otherapp]
      ios: [com.example.otherapp]
    tools:
      namespaces: [otherapp_, shared_]
```

Users can override or extend these in their `trailblaze.yaml`:

```yaml
# User's trailblaze.yaml
targets:
  myapp:
    # Override built-in MyApp config
    minVersion:
      android: "2024.06.01"   # Require newer version for this project
    tools:
      exclude:
        - myapp_legacyLogin  # Don't use deprecated tool
```

### Configuration Loading Order

```
1. Built-in app targets (ships with Trailblaze)
   ↓ merged with
2. User-level config (~/.trailblaze/app-targets.yaml)
   ↓ merged with
3. Project-level config (./trailblaze.yaml)
   ↓
4. Final app target configuration
```

Later sources override earlier ones (same pattern as LLM config).

### Migration from TrailblazeHostAppTarget

For built-in app targets, the existing `TrailblazeHostAppTarget` subclasses can be:

1. **Kept as-is** — Code-based targets still work
2. **Exported to YAML** — Generate YAML from Kotlin for external teams
3. **Gradually migrated** — Move config to YAML, keep Kotlin for custom logic

```kotlin
// Kotlin targets can reference YAML config
class MyAppHostAppTarget : TrailblazeHostAppTarget(
    id = "myapp",
    displayName = "MyApp",
) {
    // Custom logic that can't be expressed in YAML
    override fun getCustomIosDriverFactory(deviceId, originalDriver): Any {
        return MyAppIosDriver(originalDriver)  // Custom driver wrapper
    }
}
```

For **custom driver factories** and other code-based customizations, Kotlin remains necessary. YAML config handles the declarative parts.

### Desktop App Integration

The Trailblaze Desktop App uses app target config to:

1. **Show target selector** — Dropdown of available app targets
2. **Detect installed apps** — Match `appIds` against device's installed apps
3. **Show version warnings** — Compare installed version against `minVersion`
4. **Filter tool palette** — Show only tools for selected target

## Consequences

**Positive:**

- **Declarative** — App targets defined in YAML, not code
- **External-friendly** — No Kotlin required for basic app targets
- **Project-scoped** — Config lives with the test project, not Trailblaze
- **Consistent** — Same YAML pattern as LLM config
- **Extensible** — Easy to add new fields without code changes

**Negative:**

- **Limited customization** — Custom driver factories still need Kotlin
- **Migration effort** — Existing targets need conversion
- **Validation complexity** — YAML schema must be validated
- **Documentation** — Another config file to document

## Related Documents

- [030: LLM Provider Configuration](2026-02-04-llm-provider-configuration.md)
- [035: Agentic Development Loop](2026-03-09-agentic-dev-loop.md)
