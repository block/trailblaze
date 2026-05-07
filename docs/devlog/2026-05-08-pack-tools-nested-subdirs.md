---
title: "Pack Tools: Nested Subdirectories + Waypoint Auto-Discovery"
type: decision
date: 2026-05-08
---

# Pack Tools: Nested Subdirectories + Waypoint Auto-Discovery

The [2026-04-27 pack manifest devlog](2026-04-27-pack-manifest-v1.md) introduced `<pack-id>/tools/<name>.<kind>.yaml` as the canonical location for pack-bundled tool YAMLs, and the [2026-05-08 library-vs-target packs devlog](2026-05-08-library-vs-target-packs.md) added the auto-discovery hook (`ToolYamlLoader.discoverPackBundledToolContents`) that registers every matching YAML in the pack's `tools/` dir into the global tool registry. Both pinned a deliberate constraint: tool YAMLs must be **direct children** of `tools/` — `<pack>/tools/<subdir>/foo.tool.yaml` was silently dropped, with a test (`pack-bundled YAML nested under tools subdirectory is NOT picked up`) and a kdoc rationale (*"the convention is intentionally narrow"*) enforcing it.

That constraint outlives its purpose once a target pack grows past a handful of tools. The Square pack landed ~85 tool YAMLs across three platforms (web, android, ios) plus universal launch trailheads — all flat in one directory. Sister waypoints organize the same surface across `waypoints/{android,ios,web}/` subdirs without any registration drama, because the waypoint loader was always recursive. The asymmetry was the artifact of two different scan paths landing in different PRs, not a load-bearing design decision.

This PR loosens the rule: **tool YAMLs can sit at any depth under `<pack>/tools/`**. The structural guarantee — *only* YAMLs under `tools/` register as tools, so a stray YAML at `<pack>/waypoints/foo.tool.yaml` doesn't accidentally pollute the registry — is preserved by keeping the `segments[1] == "tools"` check; what's relaxed is the prior `segments.size == 3` exact match, now `segments.size >= 3`. The auto-discovery hook itself was already recursive (uses `ClasspathResourceDiscovery.discoverFilenamesRecursive`); the segment check was the only thing rejecting nested matches.

Pack authors can now organize the tool surface by platform, by sub-flow, or by any other grouping that fits the pack — same patterns waypoints already support:

```
packs/<pack-id>/
├── waypoints/
│   ├── android/...
│   ├── ios/...
│   └── web/...
└── tools/
    ├── android/                    # <pack-id>_android_*.shortcut.yaml
    │   └── checkout/               # nested deeper if a sub-flow grows
    ├── ios/                        # <pack-id>_ios_*.{shortcut,trailhead}.yaml
    └── web/                        # <pack-id>_web_*.{shortcut,trailhead}.yaml
```

Library packs benefit too: a cross-target tooling pack with a hundred helpers can group them by category (`tools/auth/`, `tools/db-fixtures/`, ...) instead of relying on filename prefix to give the directory shape.

## What this is not

- **Not a discovery-path change.** The auto-discovery scan was already recursive. Only the post-scan path filter changed.
- **Not a per-target-tools change.** `PackScriptedToolFile` descriptors (the per-target `target.tools:` shape used by `clock`'s scripted tools) flow through a different resolution path and have always supported arbitrary relative paths via the manifest's `tools:` list.
- **Not an Android on-device change.** `AssetManagerConfigResourceSource` still doesn't recurse, so Android instrumentation tests don't pick up pack-bundled tools today (called out as a follow-up in the original devlog). The host-side hook is what's loosened here; the Android gap is unchanged.
- **Not a tool-id semantic change.** Tool ids are still the matching key for toolset / trail references. The discovery map is keyed by full pack-relative path (so two same-basename files in two packs don't collide before parse), but downstream resolution by id is unaffected.

## Test changes

The previous test `pack-bundled YAML nested under tools subdirectory is NOT picked up` was inverted to `pack-bundled YAML nested under tools subdirectory IS picked up`. A second test was added — `pack-bundled YAML at multiple depths under tools subdirectory all register` — to pin that the depth is genuinely unrestricted (shallow `tools/web/` and deep `tools/android/checkout/keypad/` both work in the same pack).

The structural-integrity test `pack-bundled YAML in non-tools subdirectory is NOT picked up as a tool` is unchanged — that's the rule that actually mattered (don't pull random YAMLs from `<pack>/waypoints/`), and it's still enforced by the `segments[1] == "tools"` check.

## Sibling change: waypoint auto-discovery

This same PR closes the symmetric asymmetry on the waypoint side. Before:

- **Tools** were auto-discovered from `<pack>/tools/**.{tool,shortcut,trailhead}.yaml` — the manifest didn't list them.
- **Waypoints** still had to be enumerated under `waypoints:` in `pack.yaml`. The Square pack manifest carried a 120-line list mirroring the on-disk directory structure exactly.

Both sides were already doing recursive directory walks (Square's web waypoints sit four levels deep at `waypoints/web/dashboard/items/categories.waypoint.yaml`); only the resolution code differed. The tools side called `PackSource.listSiblings` then iterated; the waypoints side iterated `manifest.waypoints` instead. There was no design reason for the split — same artifact of two PRs landing in different sequences.

After this PR, waypoint resolution mirrors tool resolution:

```kotlin
val waypointPaths = loadedManifest.source.listSiblingsRecursive(
  relativeDir = "waypoints",
  suffixes = listOf(".waypoint.yaml"),
)
val resolvedWaypoints = waypointPaths.map { path ->
  loadPackSibling(path, source, WaypointDefinition.serializer(), "pack waypoint")
}
```

A new `PackSource.listSiblingsRecursive` was added rather than walking through `ClasspathResourceDiscovery` directly so the abstraction stays clean (filesystem and classpath sources keep the same containment guarantees and dispatch through one entrypoint). The `listSiblings` non-recursive variant remains because the `tools/` side has its own `segments.size >= 3` filter that benefits from "one pack-relative path per file" rather than "raw on-disk path"; waypoints have no equivalent constraint.

### Library-pack guard, on both sides

The library-pack contract — *a pack with no `target:` block cannot ship waypoints* — was previously enforced only by `TrailblazePackManifestLoader.enforceLibraryPackContract`, which fires on `manifest.waypoints.isNotEmpty()`. With the manifest list ignored at the resolution layer, that check would silently pass on a target-less pack that drops `.waypoint.yaml` files into `waypoints/` without listing them.

The fix is defensive duplication: the manifest-side check stays (legacy enumerated packs fail fast at parse time before disk traversal), and a parallel discovery-side check fires post-`listSiblingsRecursive` if any waypoint files exist. Both throw the same `TrailblazeProjectConfigException` shape so the error feels uniform regardless of which path detected the violation.

### `manifest.waypoints` field: deprecated, not removed

The `waypoints: List<String>` slot stays in `TrailblazePackManifest` to keep legacy `pack.yaml` files parsing (kaml `strictMode = false` already drops unknown keys, but explicit keys still need a serial slot). It's silently ignored at the resolution layer; the kdoc on the field is now explicit about that. Removal can come later once all in-tree packs drop their lists — Square is the worked migration here, and Clock + Wikipedia + Contacts only had ~3-5 entries each.

### Worked migration: Square

The Square pack's `waypoints:` block went from ~120 lines to zero — the directory structure is the source of truth now. The remaining comment in `square/pack.yaml` only points at the rationale devlog (2026-05-07) and the `waypoints/web/` subdirectory.

Symmetry checked: Square's tools were already auto-discovered after the previous PR; waypoints now match. `pack.yaml` reads as a target descriptor (id, target block, platform tool_sets, dependencies) rather than a directory mirror.
