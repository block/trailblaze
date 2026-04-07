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

## Related Documents

- [030: LLM Provider Configuration](2026-02-04-llm-provider-configuration.md)
- [035: Agentic Development Loop](2026-03-09-agentic-dev-loop.md)
