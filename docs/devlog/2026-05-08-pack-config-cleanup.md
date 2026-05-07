---
title: "Pack config cleanup: id-based targets, implicit tools, transitive dependencies"
type: decision
date: 2026-05-08
---

# Pack config cleanup

The library-vs-target distinction landed earlier today. Working through the library-pack migration that drove that change surfaced four further configuration warts in the same file family, all variations on the theme of "the workspace declaration repeats information the directory layout already encodes." This change cleans them up together.

## What changed

### 1. Workspace declarations are id-only

The `trailblaze.yaml` `packs:` field was a list of filesystem paths to pack manifests:

```yaml
# Before
packs:
  - packs/clock/pack.yaml
  - packs/wikipedia/pack.yaml
```

It is now a list of pack ids, on a field renamed `targets:` to reflect what the field actually declares — the targets the workspace opts into:

```yaml
# After
targets:
  - clock
  - wikipedia
```

Each id resolves via the convention `<workspace>/packs/<id>/pack.yaml`. Same convention as classpath discovery (`trailblaze-config/packs/<id>/pack.yaml`) and the same convention that `dependencies:` already used inside a pack manifest. Three surfaces, one mental model.

The legacy flat `targets:` field — which took inline `AppTargetYamlConfig` objects or `ref:` pointers to `targets/<id>.yaml` files — is gone. It had been deprecated in practice once the pack model landed; nothing in the repo still used it.

### 2. Workspace `targets:` only accepts target packs

A workspace listing a library-pack id in `targets:` fails the load with a redirecting error:

> Workspace target id 'foo' resolves to a *library* pack (no `target:` block in …). The workspace `targets:` list only accepts target packs. Library packs come into scope automatically when a target pack declares them in `dependencies:`.

The motivation: a library pack at the workspace level is a category error — the field name says "targets," and the user-facing question "what apps does this workspace test?" can only be answered by target packs. Library packs are reusable tooling that consumer target packs pull in via `dependencies:`; they have no reason to be named at the workspace level.

### 3. Empty `targets:` = auto-discover

When `targets:` is empty or omitted, the loader walks `<workspace>/packs/<id>/pack.yaml` and loads every target pack it finds (skipping library packs, which only enter scope transitively). When `targets:` is non-empty, only the listed ids load (plus their transitive deps).

Both modes have a place. Auto-discovery is the lazy default: drop a pack in the directory, it loads. The explicit list is for workspaces that want to control which subset of available packs the daemon spins up — e.g. a monorepo with 100 on-disk packs where only one is currently relevant. No-config and explicit-control supported through the same surface.

### 4. `dependencies:` actually loads packs (transitive resolution)

Previously `dependencies:` only governed `defaults:` inheritance — it did NOT bring depended-on packs into scope. Tool / toolset / waypoint contributions came from the every-loaded-pack pool, which meant a workspace that depended on a library pack still had to declare it explicitly somewhere (workspace `packs:` or classpath bundling).

Now `dependencies:` is the loading mechanism. A workspace pack `my-app` declaring `dependencies: [my-helpers]` brings `my-helpers/pack.yaml` into scope automatically — the loader resolves `my-helpers` via the same id convention (`<workspace>/packs/my-helpers/pack.yaml`, classpath fallback) and recurses into the dep graph. End result: the user's "rely on four other packs" example just works without listing them all at the workspace level.

### 5. Strict dep-graph validation

Every loaded pack's `dependencies:` is now validated against the resolved pool. If any pack declares a dep that isn't in scope, the workspace load fails with one consolidated error listing every broken edge:

```
Pack dependency-graph validation failed:
  - pack 'consumer' (…) depends on 'missing-pack' which is not in the resolved pool.
Available pack ids in the resolved pool: [trailblaze, my-helpers, my-app].
Add the missing pack(s) to the workspace pack directory or to the framework
classpath, or remove the unresolvable dependency.
```

The previous behavior — atomic-per-pack drop with a `Console.log` warning — left authors staring at "no such target" mysteries downstream. A pack's `dependencies:` declaration is a contract; if the contract isn't met, that's a hard error, not a hint. Atomic-per-pack still applies for parse failures and pack-internal sibling-resolution failures (those genuinely are best-effort), but unresolvable deps fail loudly.

### 6. Pack-manifest top-level `tools:` field removed

Every pack ships its operational tools (`*.tool.yaml`, `*.shortcut.yaml`, `*.trailhead.yaml`) under `<pack>/tools/`. The directory IS the surface — both library and target packs auto-discover from it. The manifest no longer enumerates them.

```yaml
# Before
id: my-library
tools:
  - tools/foo.tool.yaml
  - tools/bar.tool.yaml
  # (every file in tools/ listed, every time)

# After
id: my-library
# Drop a *.tool.yaml in tools/ and it ships with the pack.
```

The manifest's `target.tools:` field — for per-target scripted-tool descriptors (`PackScriptedToolFile`) — stays explicit. Those are different in shape (plain `.yaml` files, not `*.tool.yaml`) and serve as per-target glue, not standard library content.

The directory walk uses a new `PackSource.listSiblings(relativeDir, suffixes)` method that works for both filesystem-backed (`<workspace>/packs/`) and classpath-backed (`trailblaze-config/packs/`) packs. Same containment guarantees as `readSibling`: the `relativeDir` argument is path-validated, and the filesystem variant canonicalizes to ensure the directory still resolves under the pack root.

## In-repo migration

Every `trailblaze.yaml` in the framework + examples migrated to the new shape:

- `trails/config/trailblaze.yaml` (framework anchor) → `targets: [clock, contacts, wikipedia]`
- `examples/ios-contacts/trails/config/trailblaze.yaml` → `targets: [contacts]`
- `examples/android-sample-app/trails/config/trailblaze.yaml` → `targets: [sampleapp]`
- `examples/playwright-native/trails/config/trailblaze.yaml` → `targets: [playwrightsample]`

The first internal-extension library pack's manifest collapsed to two lines (just `id:` — the previous explicit `tools:` enumeration is gone, tools auto-discover from `<pack>/tools/`).

## Why this is a coherent set, not three separate PRs

The four changes share an implementation seam — `TrailblazeProjectConfigLoader.resolvePackArtifacts` — and they each remove a redundancy that the others enable:

- Implicit tools needs `PackSource.listSiblings` to discover them by directory walk.
- Id-based workspace declarations need a resolution convention that works the same way `dependencies:` already did.
- Transitive dependency loading needs the resolution convention to extend to `dependencies:` references too, not just workspace roots.
- Strict validation only makes sense once `dependencies:` is the load mechanism — under the old model, missing deps were just missing-defaults inheritance, harmlessly handled.

Each piece would have produced a backwards-incompatible change in isolation. Bundling them lets the migration be a single sweep across every workspace YAML and pack manifest in the repo.

## What this PR is not

- No `type:` field on `TrailblazePackManifest`. The `target:` slot remains the discriminator (library vs target). That decision was settled in the earlier devlog.
- No CLI surface for "validate my workspace's pack graph without running anything" yet. The strict validation runs as part of normal load, so any `trailblaze` invocation that touches the workspace surfaces broken dep edges. A dedicated `trailblaze packs validate` command is a follow-up if there's demand.
- No removal of inline-or-`ref:` form for `toolsets:`, `tools:`, `providers:` at the workspace level. Those still accept both shapes — only `targets:` is now id-only. They can move to id-based forms later if the same simplification proves valuable for them.
- No on-device pack-bundled tool discovery on Android. The `PackSource.listSiblings` filesystem walk is host-only; the Android `AssetManager` source doesn't recurse, so pack-bundled tools shipped on-device would still need a recursive variant. Not a blocker today (the library-pack tools driving this set are host-side `HostLocalExecutableTrailblazeTool`s).
