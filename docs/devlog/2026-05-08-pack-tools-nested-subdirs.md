---
title: "Trailmap Tools: Nested Subdirectories + Waypoint Auto-Discovery"
type: decision
date: 2026-05-08
---

# Trailmap Tools: Nested Subdirectories + Waypoint Auto-Discovery

The [2026-04-27 trailmap manifest devlog](2026-04-27-pack-manifest-v1.md) introduced `<trailmap-id>/tools/<name>.<kind>.yaml` as the canonical location for trailmap-bundled tool YAMLs, and the [2026-05-08 library-vs-target trailmaps devlog](2026-05-08-library-vs-target-packs.md) added the auto-discovery hook (`ToolYamlLoader.discoverTrailmapBundledToolContents`) that registers every matching YAML in the trailmap's `tools/` dir into the global tool registry. Both pinned a deliberate constraint: tool YAMLs must be **direct children** of `tools/` — `<trailmap>/tools/<subdir>/foo.tool.yaml` was silently dropped, with a test (`trailmap-bundled YAML nested under tools subdirectory is NOT picked up`) and a kdoc rationale (*"the convention is intentionally narrow"*) enforcing it.

That constraint outlives its purpose once a target trailmap grows past a handful of tools. The Square trailmap landed dozens of tool YAMLs across three platforms (web, android, ios) plus universal launch trailheads — all flat in one directory. Sister waypoints organize the same surface across `waypoints/{android,ios,web}/` subdirs without any registration drama, because the waypoint loader was always recursive. The asymmetry was the artifact of two different scan paths landing in different PRs, not a load-bearing design decision.

This PR loosens the rule: **tool YAMLs can sit at any depth under `<trailmap>/tools/`**. The structural guarantee — *only* YAMLs under `tools/` register as tools, so a stray YAML at `<trailmap>/waypoints/foo.tool.yaml` doesn't accidentally pollute the registry — is preserved by keeping the `segments[1] == "tools"` check; what's relaxed is the prior `segments.size == 3` exact match, now `segments.size >= 3`. The auto-discovery hook itself was already recursive (uses `ClasspathResourceDiscovery.discoverFilenamesRecursive`); the segment check was the only thing rejecting nested matches.

Trailmap authors can now organize the tool surface by platform, by sub-flow, or by any other grouping that fits the trailmap — same patterns waypoints already support:

```
trailmaps/<trailmap-id>/
├── waypoints/
│   ├── android/...
│   ├── ios/...
│   └── web/...
└── tools/
    ├── android/                    # <trailmap-id>_android_*.shortcut.yaml
    │   └── checkout/               # nested deeper if a sub-flow grows
    ├── ios/                        # <trailmap-id>_ios_*.{shortcut,trailhead}.yaml
    └── web/                        # <trailmap-id>_web_*.{shortcut,trailhead}.yaml
```

Library trailmaps benefit too: a cross-target tooling trailmap with a hundred helpers can group them by category (`tools/auth/`, `tools/db-fixtures/`, ...) instead of relying on filename prefix to give the directory shape.

## What this is not

- **Not a discovery-path change.** The auto-discovery scan was already recursive. Only the post-scan path filter changed.
- **Not a per-target-tools change.** `TrailmapScriptedToolFile` descriptors (the per-target `target.tools:` shape used by `clock`'s scripted tools) flow through a different resolution path and have always supported arbitrary relative paths via the manifest's `tools:` list.
- **Not an Android on-device change.** `AssetManagerConfigResourceSource` still doesn't recurse, so Android instrumentation tests don't pick up trailmap-bundled tools today (called out as a follow-up in the original devlog). The host-side hook is what's loosened here; the Android gap is unchanged.
- **Not a tool-id semantic change.** Tool ids are still the matching key for toolset / trail references. The discovery map is keyed by full trailmap-relative path (so two same-basename files in two trailmaps don't collide before parse), but downstream resolution by id is unaffected.

## Test changes

The previous test `trailmap-bundled YAML nested under tools subdirectory is NOT picked up` was inverted to `trailmap-bundled YAML nested under tools subdirectory IS picked up`. A second test was added — `trailmap-bundled YAML at multiple depths under tools subdirectory all register` — to pin that the depth is genuinely unrestricted (shallow `tools/web/` and deep `tools/android/checkout/keypad/` both work in the same trailmap).

The structural-integrity test `trailmap-bundled YAML in non-tools subdirectory is NOT picked up as a tool` is unchanged — that's the rule that actually mattered (don't pull random YAMLs from `<trailmap>/waypoints/`), and it's still enforced by the `segments[1] == "tools"` check.

## Sibling change: waypoint auto-discovery

This same PR closes the symmetric asymmetry on the waypoint side. Before:

- **Tools** were auto-discovered from `<trailmap>/tools/**.{tool,shortcut,trailhead}.yaml` — the manifest didn't list them.
- **Waypoints** still had to be enumerated under `waypoints:` in `trailmap.yaml`. The Square trailmap manifest carried a long list mirroring the on-disk directory structure exactly.

Both sides were already doing recursive directory walks (Square's web waypoints sit four levels deep at `waypoints/web/dashboard/items/categories.waypoint.yaml`); only the resolution code differed. The tools side called `TrailmapSource.listSiblings` then iterated; the waypoints side iterated `manifest.waypoints` instead. There was no design reason for the split — same artifact of two PRs landing in different sequences.

After this PR, waypoint resolution mirrors tool resolution:

```kotlin
val waypointPaths = loadedManifest.source.listSiblingsRecursive(
  relativeDir = "waypoints",
  suffixes = listOf(".waypoint.yaml"),
)
val resolvedWaypoints = waypointPaths.map { path ->
  loadTrailmapSibling(path, source, WaypointDefinition.serializer(), "trailmap waypoint")
}
```

A new `TrailmapSource.listSiblingsRecursive` was added rather than walking through `ClasspathResourceDiscovery` directly so the abstraction stays clean (filesystem and classpath sources keep the same containment guarantees and dispatch through one entrypoint). The `listSiblings` non-recursive variant remains because the `tools/` side has its own `segments.size >= 3` filter that benefits from "one trailmap-relative path per file" rather than "raw on-disk path"; waypoints have no equivalent constraint.

### Library-trailmap guard, on both sides

The library-trailmap contract — *a trailmap with no `target:` block cannot ship waypoints* — was previously enforced only by `TrailblazeTrailmapManifestLoader.enforceLibraryTrailmapContract`, which fires on `manifest.waypoints.isNotEmpty()`. With the manifest list ignored at the resolution layer, that check would silently pass on a target-less trailmap that drops `.waypoint.yaml` files into `waypoints/` without listing them.

The fix is defensive duplication: the manifest-side check stays (legacy enumerated trailmaps fail fast at parse time before disk traversal), and a parallel discovery-side check fires post-`listSiblingsRecursive` if any waypoint files exist. Both throw the same `TrailblazeProjectConfigException` shape so the error feels uniform regardless of which path detected the violation.

### `manifest.waypoints` field: deprecated, not removed

The `waypoints: List<String>` slot stays in `TrailblazeTrailmapManifest` to keep legacy `trailmap.yaml` files parsing (kaml `strictMode = false` already drops unknown keys, but explicit keys still need a serial slot). It's silently ignored at the resolution layer; the kdoc on the field is now explicit about that. Removal can come later once all in-tree trailmaps drop their lists — Square is the worked migration here, and Clock + Wikipedia + Contacts only had ~3-5 entries each.

### Worked migration: Square

The Square trailmap's `waypoints:` block went from a long list to zero — the directory structure is the source of truth now. The remaining comment in `square/trailmap.yaml` only points at the rationale devlog (2026-05-07) and the `waypoints/web/` subdirectory.

Symmetry checked: Square's tools were already auto-discovered after the previous PR; waypoints now match. `trailmap.yaml` reads as a target descriptor (id, target block, platform tool_sets, dependencies) rather than a directory mirror.
