---
title: "Library vs Target Trailmaps"
type: decision
date: 2026-05-08
---

# Library vs Target Trailmaps

Trailmap v1 (#2026-04-27 devlog) introduced `trailmap.yaml` as the authored boundary for both runnable targets and the cross-target reusable tooling that already shipped via the framework `trailblaze` trailmap — but never named the distinction. Empirically two trailmap shapes were already present: ones with a `target:` block (`clock`, `wikipedia`, `contacts`) and ones without (`trailblaze`). Any contract that should only apply to one of them lived as a comment, an implicit convention, or nothing at all.

This PR draws the line. A trailmap's `target:` field — the existing `target: TrailmapTargetConfig?` slot — is the discriminator:

- **Target trailmap** (`target` non-null) — models a runnable app under test. May declare `target:` (display shape, platforms, app ids), `tools:`, `toolsets:`, `waypoints:`, and trailhead tools. The framework-bundled `clock`, `wikipedia`, `contacts` trailmaps are target trailmaps.
- **Library trailmap** (`target` null) — ships cross-target reusable tooling: `tools:` and `toolsets:` only. **Library trailmaps MUST NOT declare `waypoints:` or trailhead tools** (a trailhead bootstraps to a known waypoint, which only makes sense within a target). The framework-bundled `trailblaze` trailmap — which contributes per-platform `defaults:` — is the canonical library trailmap and was retroactively the first one.

We deliberately did **not** add a new `type: library | target` field. A separate type tag risks disagreeing with the actual content (`type: library` next to a populated `target:` block); the existing `target:` slot is the single source of truth. Tools and toolsets are orthogonal to trailmap type — both shapes contribute them through the same registries; the runtime tool registry doesn't care which trailmap a tool came from.

The library/target distinction also makes the reverse lookup deterministic: given a waypoint id, walk the resolved trailmap list to find the (single) trailmap that declared it, then read that trailmap's `target` for the owning target. No id-prefix convention needed, no first-slash split. This is enabled but not implemented in this PR.

## Load-time enforcement

The contract is enforced at load time, atomic-per-trailmap — a violation drops the offending trailmap but does not poison sibling trailmaps:

- `TrailblazeTrailmapManifestLoader.parseManifest` rejects a trailmap with `target == null` and a non-empty `waypoints:` list, naming the offending entries in the error.
- `TrailblazeProjectConfigLoader.resolveTrailmapSiblings` rejects library trailmaps whose tool YAMLs declare a `trailhead:` block. This rule has to live one layer deeper than the manifest because the `trailhead:` block is inside the tool YAML, not the manifest — so the check has to happen after sibling tools are read.

## The discovery hook this PR also lands

Tool discovery for the global registry runs through `ToolYamlLoader.discoverAndLoadAll`, which scans `trails/config/tools/` non-recursively. Trailmap-resolved tools (the `tools:` list in `trailmap.yaml`) only landed in `projectConfig.tools` for the host-side path; they never made it into `TrailblazeSerializationInitializer.buildAllTools()` or `ToolNameResolver.fromBuiltInAndCustomTools()`. That meant a tool YAML moved into a trailmap subdirectory would be silently invisible to toolset name resolution.

The fix is a recursive scan of `trails/config/trailmaps/<id>/tools/*.{tool,shortcut,trailhead}.yaml` added to `ToolYamlLoader.discoverAllConfigs` — trailmap-bundled tool YAMLs surface in the same map the flat scan populates, and downstream consumers (toolset name resolver, serializer, trail decoder) see them transparently. Plain-`.yaml` `TrailmapScriptedToolFile` descriptors (the per-target shape used by `clock`'s `target.tools:`) are deliberately excluded — they flow through per-target resolution, not the global registry. The convention is intentionally narrow: only `<trailmap-id>/tools/<name>.<kind>.yaml` is accepted, so a stray YAML elsewhere in the trailmap tree doesn't falsely register as a tool.

JVM-only path. The Android `AssetManager`-backed `ConfigResourceSource` doesn't recurse, so on-device instrumentation tests don't pick up trailmap-bundled tools today. Acceptable for now — the host-side path is sufficient for the use cases that drove this hook. When trailmap-bundled tools that need on-device discovery land, `AssetManagerConfigResourceSource` will need a recursive variant.

## Library trailmap happy path

```yaml
# trails/config/trailmaps/<library-trailmap-id>/trailmap.yaml
id: my-library
tools:
  - tools/foo.tool.yaml
  - tools/bar.tool.yaml
```

No `target:`. No `waypoints:`. Toolset YAMLs and trails reference these tools by bare id. Consumer target trailmaps enable a toolset that lists them — same authoring surface as flat-`tools/`-dir tools.

## Scope choices

What this PR is **not**:

- No `type:` field on `TrailblazeTrailmapManifest`. The `target:` slot is the discriminator.
- No id-prefix-equals-trailmap-id rule for waypoints. The trailmap-manifest binding is enough; the prefix convention is a separate, debatable change.
- No changes to trailmap-tool dispatch or the runtime tool registry's resolution semantics. Library-trailmap tools and target-trailmap tools register identically; the registry stays unaware of which trailmap a tool came from.
- No changes to the `trailblaze waypoint` CLI. Once this PR lands, a follow-up can simplify `WaypointDiscovery.resolveWaypointRoot` to walk trailmap-manifest target bindings instead of the convention `<workspace>/trailmaps/<id>/waypoints/`, but the existing `--root` flag's behavior is preserved.
- No `AssetManagerConfigResourceSource` recursion for trailmap-bundled tools on Android. Tracked as a follow-up if a trailmap-bundled on-device tool ships.
