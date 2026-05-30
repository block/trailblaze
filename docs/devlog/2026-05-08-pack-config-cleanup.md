---
title: "Trailmap config cleanup: id-based targets, implicit tools, transitive dependencies"
type: decision
date: 2026-05-08
---

# Trailmap config cleanup

The library-vs-target distinction landed earlier today. Working through the library-trailmap migration that drove that change surfaced four further configuration warts in the same file family, all variations on the theme of "the workspace declaration repeats information the directory layout already encodes." This change cleans them up together.

## What changed

### 1. Workspace declarations are id-only

The `trailblaze.yaml` `trailmaps:` field was a list of filesystem paths to trailmap manifests:

```yaml
# Before
trailmaps:
  - trailmaps/clock/trailmap.yaml
  - trailmaps/wikipedia/trailmap.yaml
```

It is now a list of trailmap ids, on a field renamed `targets:` to reflect what the field actually declares — the targets the workspace opts into:

```yaml
# After
targets:
  - clock
  - wikipedia
```

Each id resolves via the convention `<workspace>/trailmaps/<id>/trailmap.yaml`. Same convention as classpath discovery (`trails/config/trailmaps/<id>/trailmap.yaml`) and the same convention that `dependencies:` already used inside a trailmap manifest. Three surfaces, one mental model.

The legacy flat `targets:` field — which took inline `AppTargetYamlConfig` objects or `ref:` pointers to `targets/<id>.yaml` files — is gone. It had been deprecated in practice once the trailmap model landed; nothing in the repo still used it.

### 2. Workspace `targets:` only accepts target trailmaps

A workspace listing a library-trailmap id in `targets:` fails the load with a redirecting error:

> Workspace target id 'foo' resolves to a *library* trailmap (no `target:` block in …). The workspace `targets:` list only accepts target trailmaps. Library trailmaps come into scope automatically when a target trailmap declares them in `dependencies:`.

The motivation: a library trailmap at the workspace level is a category error — the field name says "targets," and the user-facing question "what apps does this workspace test?" can only be answered by target trailmaps. Library trailmaps are reusable tooling that consumer target trailmaps pull in via `dependencies:`; they have no reason to be named at the workspace level.

### 3. Empty `targets:` = auto-discover

When `targets:` is empty or omitted, the loader walks `<workspace>/trailmaps/<id>/trailmap.yaml` and loads every target trailmap it finds (skipping library trailmaps, which only enter scope transitively). When `targets:` is non-empty, only the listed ids load (plus their transitive deps).

Both modes have a place. Auto-discovery is the lazy default: drop a trailmap in the directory, it loads. The explicit list is for workspaces that want to control which subset of available trailmaps the daemon spins up — e.g. a monorepo with 100 on-disk trailmaps where only one is currently relevant. No-config and explicit-control supported through the same surface.

### 4. `dependencies:` actually loads trailmaps (transitive resolution)

Previously `dependencies:` only governed `defaults:` inheritance — it did NOT bring depended-on trailmaps into scope. Tool / toolset / waypoint contributions came from the every-loaded-trailmap pool, which meant a workspace that depended on a library trailmap still had to declare it explicitly somewhere (workspace `trailmaps:` or classpath bundling).

Now `dependencies:` is the loading mechanism. A workspace trailmap `my-app` declaring `dependencies: [my-helpers]` brings `my-helpers/trailmap.yaml` into scope automatically — the loader resolves `my-helpers` via the same id convention (`<workspace>/trailmaps/my-helpers/trailmap.yaml`, classpath fallback) and recurses into the dep graph. End result: the user's "rely on four other trailmaps" example just works without listing them all at the workspace level.

### 5. Strict dep-graph validation

Every loaded trailmap's `dependencies:` is now validated against the resolved pool. If any trailmap declares a dep that isn't in scope, the workspace load fails with one consolidated error listing every broken edge:

```
Trailmap dependency-graph validation failed:
  - trailmap 'consumer' (…) depends on 'missing-trailmap' which is not in the resolved pool.
Available trailmap ids in the resolved pool: [trailblaze, my-helpers, my-app].
Add the missing trailmap(s) to the workspace trailmap directory or to the framework
classpath, or remove the unresolvable dependency.
```

The previous behavior — atomic-per-trailmap drop with a `Console.log` warning — left authors staring at "no such target" mysteries downstream. A trailmap's `dependencies:` declaration is a contract; if the contract isn't met, that's a hard error, not a hint. Atomic-per-trailmap still applies for parse failures and trailmap-internal sibling-resolution failures (those genuinely are best-effort), but unresolvable deps fail loudly.

### 6. Trailmap-manifest top-level `tools:` field removed

Every trailmap ships its operational tools (`*.tool.yaml`, `*.shortcut.yaml`, `*.trailhead.yaml`) under `<trailmap>/tools/`. The directory IS the surface — both library and target trailmaps auto-discover from it. The manifest no longer enumerates them.

```yaml
# Before
id: my-library
tools:
  - tools/foo.tool.yaml
  - tools/bar.tool.yaml
  # (every file in tools/ listed, every time)

# After
id: my-library
# Drop a *.tool.yaml in tools/ and it ships with the trailmap.
```

The manifest's `target.tools:` field — for per-target scripted-tool descriptors (`TrailmapScriptedToolFile`) — stays explicit. Those are different in shape (plain `.yaml` files, not `*.tool.yaml`) and serve as per-target glue, not standard library content.

The directory walk uses a new `TrailmapSource.listSiblings(relativeDir, suffixes)` method that works for both filesystem-backed (`<workspace>/trailmaps/`) and classpath-backed (`trails/config/trailmaps/`) trailmaps. Same containment guarantees as `readSibling`: the `relativeDir` argument is path-validated, and the filesystem variant canonicalizes to ensure the directory still resolves under the trailmap root.

## In-repo migration

Every `trailblaze.yaml` in the framework + examples migrated to the new shape:

- `trails/config/trailblaze.yaml` (framework anchor) → `targets: [clock, contacts, wikipedia]`
- `examples/ios-contacts/trails/config/trailblaze.yaml` → `targets: [contacts]`
- `examples/android-sample-app/trails/config/trailblaze.yaml` → `targets: [sampleapp]`
- `examples/playwright-native/trails/config/trailblaze.yaml` → `targets: [playwrightsample]`

The first internal-extension library trailmap's manifest collapsed to two lines (just `id:` — the previous explicit `tools:` enumeration is gone, tools auto-discover from `<trailmap>/tools/`).

## Why this is a coherent set, not three separate PRs

The four changes share an implementation seam — `TrailblazeProjectConfigLoader.resolveTrailmapArtifacts` — and they each remove a redundancy that the others enable:

- Implicit tools needs `TrailmapSource.listSiblings` to discover them by directory walk.
- Id-based workspace declarations need a resolution convention that works the same way `dependencies:` already did.
- Transitive dependency loading needs the resolution convention to extend to `dependencies:` references too, not just workspace roots.
- Strict validation only makes sense once `dependencies:` is the load mechanism — under the old model, missing deps were just missing-defaults inheritance, harmlessly handled.

Each piece would have produced a backwards-incompatible change in isolation. Bundling them lets the migration be a single sweep across every workspace YAML and trailmap manifest in the repo.

## What this PR is not

- No `type:` field on `TrailblazeTrailmapManifest`. The `target:` slot remains the discriminator (library vs target). That decision was settled in the earlier devlog.
- No CLI surface for "validate my workspace's trailmap graph without running anything" yet. The strict validation runs as part of normal load, so any `trailblaze` invocation that touches the workspace surfaces broken dep edges. A dedicated `trailblaze trailmaps validate` command is a follow-up if there's demand.
- No removal of inline-or-`ref:` form for `toolsets:`, `tools:`, `providers:` at the workspace level. Those still accept both shapes — only `targets:` is now id-only. They can move to id-based forms later if the same simplification proves valuable for them.
- No on-device trailmap-bundled tool discovery on Android. The `TrailmapSource.listSiblings` filesystem walk is host-only; the Android `AssetManager` source doesn't recurse, so trailmap-bundled tools shipped on-device would still need a recursive variant. Not a blocker today (the library-trailmap tools driving this set are host-side `HostLocalExecutableTrailblazeTool`s).
