---
title: Publishing a Trailmap
---

# Publishing a Trailmap

You've authored a trailmap ([Your First Trailmap](your-first-trailmap.md)) and want
other workspaces — your own across repos, another team's, the broader community — to
consume it. This page covers the three distribution tiers and what shipped today versus
what's planned, per the
[npm distribution devlog](devlog/2026-05-12-npm-distribution-for-trailmaps.md).

## The three tiers

| Tier | Audience | Status today |
| --- | --- | --- |
| 1. Workspace-local | Single project, no sharing | Supported |
| 2. Cross-team via `file:` or `github:` | Other teams in your org, or your own repos | Partial — see below |
| 3. npm-published with auto-discovery | Community / internal-org sharing | Planned ([devlog](devlog/2026-05-12-npm-distribution-for-trailmaps.md)) |

The runtime discovery sources today are:

- **Workspace** — every `<anchor>/trailmaps/<id>/trailmap.yaml` the workspace contains.
  `trailblaze.yaml` only declares `targets: [<id>, …]` (an opt-in list of target-trailmap
  ids); when omitted, every target trailmap under `<anchor>/trailmaps/` is auto-discovered.
- **Classpath** — `trails/config/trailmaps/<id>/trailmap.yaml` resources shipped on
  the JVM classpath (framework-bundled examples, plus any dependency that ships its own
  trailmaps).

The `node_modules/` walker described in the devlog isn't wired yet. Until it lands, the
**only** way the loader picks up a trailmap is by id-based resolution against one of those
two sources — there's no workspace-level filesystem-ref field that points at an arbitrary
path. See [Trailmaps → Discovery and precedence](trailmaps.md#discovery-and-precedence)
for the full resolution rules.

## Tier 1 — Workspace-local

Your trailmap lives at `trails/config/trailmaps/<id>/` next to
`trails/config/trailblaze.yaml`. The workspace anchor optionally names it under `targets:`
(or omit `targets:` entirely to auto-discover). No packaging needed.

## Tier 2 — Cross-team via `file:` or `github:`

Two teams in the same org, or two repos owned by the same team, share a trailmap by
publishing it as a tiny npm-flavored package and consuming it from each downstream
workspace. The package-manager side (`package.json` + `bun install`) is unblocked today;
what's **not** unblocked is the loader auto-discovering the installed package. The
consumer has to bridge that gap manually until the `node_modules/` walker lands.

### What the trailmap repo ships

A standalone trailmap repo has the trailmap at the repo root, plus a thin
`package.json` so npm can resolve it:

```text
trailmap-myapp/
├── package.json                         # name, version — that's the npm contract
├── trailmap.yaml                        # the manifest (devlog: package root)
├── tools/
│   └── myapp_login.ts                   # one typed scripted tool per .ts file
├── waypoints/
│   └── myapp-home-screen.waypoint.yaml
└── trails/
    └── smoke/
        └── trail.yaml                   # smoke trail for downstream validation
```

New scripted tools are `.ts`-only — schema, description, and metadata are derived from
the typed source (`trailblaze.tool<I, O>(spec, handler)`). Trailmaps maintaining the
legacy YAML-descriptor pair (`myapp_login.ts` + `myapp_login.yaml`) keep working
unmodified; see [Scripted Tools — Legacy Reference](scripted_tools.md).

The `package.json` is minimal:

```json
{
  "name": "trailmap-myapp",
  "version": "0.1.0",
  "files": ["trailmap.yaml", "tools", "waypoints", "trails"]
}
```

`files:` is the published-artifact whitelist. Author-time files (`.test.ts`, fixtures,
local scripts) stay in the repo but aren't shipped. The `trailmap.yaml` belongs at the
**package root** per the devlog — that's the path the future discovery walker will look
for.

### Dependency declaration shapes

From the devlog's authoring-loop table, all four shapes are ordinary npm dependencies
in the consumer's `package.json`:

| Goal | Dependency declaration |
| --- | --- |
| Develop a trailmap locally | `"trailmap-myapp": "file:../trailmap-myapp"` |
| Test a branch | `"trailmap-myapp": "github:my-org/trailmap-myapp#fix-checkout"` |
| Pin to a commit | `"trailmap-myapp": "github:my-org/trailmap-myapp#a3f2b1c"` |
| Track latest from main | `"trailmap-myapp": "github:my-org/trailmap-myapp#main"` |
| Use a published version | `"trailmap-myapp": "^1.2.0"` *(tier 3 — see below)* |

After `bun install`, the trailmap's source lands at
`<workspace>/node_modules/trailmap-myapp/`. The Trailblaze loader does not look there
today, so you have to bridge the package into the workspace's `trailmaps/` directory.

### Bridging an installed package into the workspace today

Pick one of these — the loader treats them all identically because each puts a real
`trailmap.yaml` at `<anchor>/trailmaps/<id>/trailmap.yaml`:

- **Symlink** (simplest). One-time setup; re-runs of `bun install` refresh the source
  in place:
  ```bash
  ln -s ../../node_modules/trailmap-myapp trails/config/trailmaps/myapp
  ```
- **Copy via a postinstall script** in your consumer's `package.json`:
  ```json
  "scripts": {
    "postinstall": "rsync -a --delete node_modules/trailmap-myapp/ trails/config/trailmaps/myapp/"
  }
  ```
- **git submodule / git subtree** — sidesteps the package manager entirely; pin to a
  commit via git rather than `package.json`. Same end-state, the trailmap content lives
  at `trails/config/trailmaps/myapp/`.

Whichever you pick, the consumer's `trailblaze.yaml` is the standard tier-1 shape:

```yaml
# <workspace>/trails/config/trailblaze.yaml
targets:
  - myapp
```

When the `node_modules/` walker lands, the symlink / postinstall step disappears and
`bun install` is enough — see Tier 3.

### Smoke trail convention

The trailmap repo should ship at least one runnable trail under `trails/` so downstream
consumers can validate the install before wiring real tests against it. The devlog calls
this out: *"Trailmap repos should ship their own in-repo smoke trails so authors can
validate changes without spinning up a consumer workspace."* A consumer pulls the
trailmap, runs the bundled smoke trail against their connected device, and gets a clean
green before integrating.

## Tier 3 — npm-published (planned)

The full npm-published flow is the architectural target documented in the
[devlog](devlog/2026-05-12-npm-distribution-for-trailmaps.md):

1. The trailmap repo publishes to the public npm registry (`npm publish` / `bun publish`)
   or to a private registry.
2. A consumer declares `"trailmap-myapp": "^1.2.0"` in `package.json`, runs
   `bun install`, and the Trailblaze CLI's `node_modules/` walker auto-discovers the
   trailmap — no manual symlink, no `trailblaze.yaml` plumbing.
3. Semver, lockfile, integrity, registry auth, Renovate/Dependabot — all of it works
   for free.

**Status today.** The registry-side mechanics (publishing to npm + consumer
`bun install`) work as soon as the trailmap repo ships a real `package.json` — the
npm package format is the universal ecosystem contract. What's **not** wired is the
auto-discovery step. Until the walker lands, an npm-published trailmap behaves exactly
like a tier-2 install: you still need the symlink / postinstall / submodule bridge to
expose the content under `<workspace>/trails/config/trailmaps/<id>/`.

Read the [devlog](devlog/2026-05-12-npm-distribution-for-trailmaps.md) for the
decision rationale (why npm over a custom registry, why `trailmap.yaml` at the package
root, why the binary continues to ship the canonical bundled versions for zero-setup).

## Repo visibility and auth

Your repo's visibility determines who can consume the trailmap, but the resolution
mechanism is identical across tiers (devlog's auth-posture table):

| Visibility | Repo | Auth |
| --- | --- | --- |
| Community | `<oss-org>/trailmap-*` (public) | None — anyone can pull |
| Org-internal | `<internal-org>/trailmap-*` (private) | SSH for devs, deploy key / GitHub App for CI |
| Partner-shared | Private repos with collaborators | Standard GitHub access controls |

The **agent-driven discovery story** — an agent encountering an unknown app and
auto-fetching a matching trailmap — is naturally public-only. Agents can't bootstrap
private auth themselves. If your trailmap is private, agents acting on its behalf still
need the auth their host environment provides; only the human-authored
`package.json` line travels.

## Publication checklist

Before publishing — to either a registry or a `github:` ref a consumer is going to pin:

1. **`trailmap.yaml` is at the package root.** Per the devlog, the future walker looks
   there; staying consistent today keeps tier-3 a no-op when the walker lands.
2. **`package.json#files`** lists the trailmap artifacts you want consumers to get
   (`trailmap.yaml`, `tools/`, `waypoints/`, `shortcuts/`, `trailheads/`, `trails/`) and
   excludes everything else (`.test.ts`, fixtures, internal scripts).
3. **A smoke trail in `trails/`** that a consumer can run against a connected device
   without any additional auth or setup. This is the artifact downstream consumers use
   to validate an install.
4. **Version bumps follow semver.** Breaking a tool's input interface, removing a tool
   from `target.tools:`, or renaming a waypoint id is a major bump. Adding a tool is a
   minor bump. Fixing a tool's body without changing its surface is a patch.

## See also

- [Your First Trailmap](your-first-trailmap.md) — author a trailmap before publishing it.
- [Trailmaps](trailmaps.md) — the manifest schema, dep graph, and discovery rules.
- [Use npm for Trailmap Distribution](devlog/2026-05-12-npm-distribution-for-trailmaps.md) —
  the decision record this page is built on.
