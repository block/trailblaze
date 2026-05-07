---
title: "Library vs Target Packs"
type: decision
date: 2026-05-08
---

# Library vs Target Packs

Pack v1 (#2026-04-27 devlog) introduced `pack.yaml` as the authored boundary for both runnable targets and the cross-target reusable tooling that already shipped via the framework `trailblaze` pack — but never named the distinction. Empirically two pack shapes were already present: ones with a `target:` block (`clock`, `wikipedia`, `contacts`) and ones without (`trailblaze`). Any contract that should only apply to one of them lived as a comment, an implicit convention, or nothing at all.

This PR draws the line. A pack's `target:` field — the existing `target: PackTargetConfig?` slot — is the discriminator:

- **Target pack** (`target` non-null) — models a runnable app under test. May declare `target:` (display shape, platforms, app ids), `tools:`, `toolsets:`, `waypoints:`, and trailhead tools. The framework-bundled `clock`, `wikipedia`, `contacts` packs are target packs.
- **Library pack** (`target` null) — ships cross-target reusable tooling: `tools:` and `toolsets:` only. **Library packs MUST NOT declare `waypoints:` or trailhead tools** (a trailhead bootstraps to a known waypoint, which only makes sense within a target). The framework-bundled `trailblaze` pack — which contributes per-platform `defaults:` — is the canonical library pack and was retroactively the first one.

We deliberately did **not** add a new `type: library | target` field. A separate type tag risks disagreeing with the actual content (`type: library` next to a populated `target:` block); the existing `target:` slot is the single source of truth. Tools and toolsets are orthogonal to pack type — both shapes contribute them through the same registries; the runtime tool registry doesn't care which pack a tool came from.

The library/target distinction also makes the reverse lookup deterministic: given a waypoint id, walk the resolved pack list to find the (single) pack that declared it, then read that pack's `target` for the owning target. No id-prefix convention needed, no first-slash split. This is enabled but not implemented in this PR.

## Load-time enforcement

The contract is enforced at load time, atomic-per-pack — a violation drops the offending pack but does not poison sibling packs:

- `TrailblazePackManifestLoader.parseManifest` rejects a pack with `target == null` and a non-empty `waypoints:` list, naming the offending entries in the error.
- `TrailblazeProjectConfigLoader.resolvePackSiblings` rejects library packs whose tool YAMLs declare a `trailhead:` block. This rule has to live one layer deeper than the manifest because the `trailhead:` block is inside the tool YAML, not the manifest — so the check has to happen after sibling tools are read.

## The discovery hook this PR also lands

Tool discovery for the global registry runs through `ToolYamlLoader.discoverAndLoadAll`, which scans `trailblaze-config/tools/` non-recursively. Pack-resolved tools (the `tools:` list in `pack.yaml`) only landed in `projectConfig.tools` for the host-side path; they never made it into `TrailblazeSerializationInitializer.buildAllTools()` or `ToolNameResolver.fromBuiltInAndCustomTools()`. That meant a tool YAML moved into a pack subdirectory would be silently invisible to toolset name resolution.

The fix is a recursive scan of `trailblaze-config/packs/<id>/tools/*.{tool,shortcut,trailhead}.yaml` added to `ToolYamlLoader.discoverAllConfigs` — pack-bundled tool YAMLs surface in the same map the flat scan populates, and downstream consumers (toolset name resolver, serializer, trail decoder) see them transparently. Plain-`.yaml` `PackScriptedToolFile` descriptors (the per-target shape used by `clock`'s `target.tools:`) are deliberately excluded — they flow through per-target resolution, not the global registry. The convention is intentionally narrow: only `<pack-id>/tools/<name>.<kind>.yaml` is accepted, so a stray YAML elsewhere in the pack tree doesn't falsely register as a tool.

JVM-only path. The Android `AssetManager`-backed `ConfigResourceSource` doesn't recurse, so on-device instrumentation tests don't pick up pack-bundled tools today. Acceptable for now — the host-side path is sufficient for the use cases that drove this hook. When pack-bundled tools that need on-device discovery land, `AssetManagerConfigResourceSource` will need a recursive variant.

## Library pack happy path

```yaml
# trailblaze-config/packs/<library-pack-id>/pack.yaml
id: my-library
tools:
  - tools/foo.tool.yaml
  - tools/bar.tool.yaml
```

No `target:`. No `waypoints:`. Toolset YAMLs and trails reference these tools by bare id. Consumer target packs enable a toolset that lists them — same authoring surface as flat-`tools/`-dir tools.

## Scope choices

What this PR is **not**:

- No `type:` field on `TrailblazePackManifest`. The `target:` slot is the discriminator.
- No id-prefix-equals-pack-id rule for waypoints. The pack-manifest binding is enough; the prefix convention is a separate, debatable change.
- No changes to pack-tool dispatch or the runtime tool registry's resolution semantics. Library-pack tools and target-pack tools register identically; the registry stays unaware of which pack a tool came from.
- No changes to the `trailblaze waypoint` CLI. Once this PR lands, a follow-up can simplify `WaypointDiscovery.resolveWaypointRoot` to walk pack-manifest target bindings instead of the convention `<workspace>/packs/<id>/waypoints/`, but the existing `--root` flag's behavior is preserved.
- No `AssetManagerConfigResourceSource` recursion for pack-bundled tools on Android. Tracked as a follow-up if a pack-bundled on-device tool ships.
