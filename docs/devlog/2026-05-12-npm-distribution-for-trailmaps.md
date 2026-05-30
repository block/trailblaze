---
title: "Use npm for Trailmap Distribution"
type: decision
date: 2026-05-12
---

# Use npm for Trailmap Distribution

## Summary

Trailmaps (the unit formerly called "trailmaps" — see the parallel rename) will be distributed as npm packages, resolved by the actual npm CLI against `node_modules/`. The Trailblaze CLI gains a discovery hook that walks `node_modules/` for packages containing a `trailmap.yaml`. No Trailblaze-specific package manager is built; npm does the resolution, caching, lockfile, semver, and GitHub auth.

## Context

The trailmap system today has two distribution sources: classpath-bundled (shipped in the binary) and workspace file-based. This breaks down in two ways:

1. **Binary-bundled target trailmaps can't be extended.** Anyone outside the framework repo who wants to add tools or platforms to an existing target trailmap has to wholesale-shadow the entire trailmap, losing future updates. A downstream team consuming a target trailmap via binary distribution hit this when they wanted to add a web platform to a mobile-only target — they sidestepped it by defining their own target, but the gap is real for teams that genuinely need to extend an existing target.

2. **No story for an open community.** The vision is an ecosystem of community-authored trailmaps for hundreds of apps and websites (Shopify, Airbnb, Wikipedia, etc.) that agents can auto-discover. Classpath bundling can't scale to that; file-based dependencies don't have versioning or remote resolution.

Versioned, remotely-resolvable dependencies are the missing piece. Once that exists, the binary stays tightly version-coupled (one canonical version per release) and additional trailmaps layer on top via a real dependency mechanism.

## Decision

**Use npm as the actual package manager — don't build a clone.**

### Architecture

1. A Trailblaze workspace has a `package.json` (web teams already do; mobile teams gain one).
2. Trailmap dependencies are declared as ordinary npm dependencies.
3. `npm install` populates `node_modules/`.
4. The Trailblaze CLI walks `node_modules/` at daemon init, identifying packages that contain a `trailmap.yaml` at the package root (or declare a `"trailblaze"` field in their `package.json`).
5. Discovered trailmaps register as a third trailmap source alongside classpath and workspace, with the same resolution semantics that already exist for those two.

That's the entire integration. The trailmap resolver becomes a thin layer over `node_modules/` discovery. Everything else (caching, integrity, lockfile, registry auth, semver) is npm's job.

### Authoring loop

The standard npm primitives cover every authoring scenario:

| Goal | Dependency declaration |
|---|---|
| Develop a trailmap locally | `"trailmap-shopify": "file:../trailmap-shopify"` |
| Test a branch | `"trailmap-shopify": "github:block/trailmap-shopify#fix-checkout"` |
| Pin to a commit | `"trailmap-shopify": "github:block/trailmap-shopify#a3f2b1c"` |
| Track latest from main | `"trailmap-shopify": "github:block/trailmap-shopify#main"` |
| Use a published version | `"trailmap-shopify": "^1.2.0"` |

PR reviewers test a change with one line update; downstream consumers tracking `#main` pick up merged changes on their next `npm install`; consumers pinned to a tag are unaffected until they bump.

Trailmap repos should ship their own in-repo smoke trails so authors can validate changes without spinning up a consumer workspace.

### Public vs private trailmaps

npm doesn't care about repo visibility; it just clones via git. The auth posture varies but the resolution mechanism is identical:

| Tier | Repo | Auth |
|---|---|---|
| Community | `<oss-org>/trailmap-*` (public) | None — anyone can pull |
| Org-internal | `<internal-org>/trailmap-*` (private) | SSH for devs, deploy key / GitHub App for CI |
| Partner-shared | private repos with collaborators | Standard GitHub access controls |

The agent-driven discovery story (an agent encountering an unknown app and auto-fetching a matching trailmap) is naturally public-only — agents can't bootstrap private auth themselves. That's the right boundary.

### Why not build our own registry / package manager

Considered and rejected:

- **A Trailblaze-specific registry.** Reimplements caching, integrity, lockfiles, semver, mirror resolution, auth — all of which npm already does correctly. Maintenance burden with no upside.
- **Private-registry-only artifacts.** Could work for internal-only distribution but cuts off the OSS community and the agent-discovery vision.
- **File-based dependencies only.** No versioning, no remote resolution, no automatic updates. Doesn't scale past a single team.
- **Maven Central / GitHub Packages.** Higher bureaucracy to publish to Maven Central; GitHub Packages requires auth even for public reads. Both fight the OSS community contribution loop.

npm wins on every axis: zero infrastructure on our end, free public registry, the entire JS ecosystem (Renovate, Dependabot, `npm audit`, `npm publish`, `npm version`) works for free, and tools are already migrating to TS/JS/YAML so `node_modules/` already exists in a Trailblaze workspace's future state.

## Relationship to the trailmap → trailmap rename

This decision is paired with a separate decision to rename `trailmap` → `trailmap` across the codebase (file, Kotlin types, KDoc, error messages). `target` stays as-is for the CLI flag and YAML block name — that's a deliberate exception for coding-agent ergonomics ("target" has overwhelming corpus prior in dev tooling; renaming the CLI flag would hurt agent reliability more than the metaphor break costs).

Final vocabulary:

- **Trailmap** — the manifest, an npm package (formerly "trailmap")
- **Trailhead** — entry point into the navigated thing
- **Waypoint** — navigation reference
- **Shortcut** — faster route between waypoints
- **Trail** — a test definition (path)
- **Target** — the app or website being navigated (intentionally kept, breaks the metaphor for agent ergonomics)

The rename is mechanical and tracked separately; the npm decision in this entry is the architectural one.

## Scope and open questions

What this decision is **not**:

- Not a commitment to drop classpath-bundled trailmaps. The binary likely continues to ship the canonical versions for zero-setup. npm becomes the third source, not a replacement.
- Not a decision on target-level extension. With versioned dependencies, the extension problem softens (teams that need customization can fork a trailmap repo and pin to their fork), but a proper additive-merge mechanism may still be wanted later.
- Not a decision on the bootstrap-into-workspace question. If we keep bundling trailmaps in the binary, the "write canonical target config out to workspace" idea (discussed but not committed) becomes unnecessary — npm is the workspace-side authoritative source if users want one.

Open questions to resolve in the implementation PR:

1. Discovery convention — `trailmap.yaml` at the package root, or a `"trailblaze"` field in `package.json` pointing at the manifest? Probably the former for simplicity; the latter if we ever want one npm package to ship multiple trailmaps.
2. How `dependencies:` inside `trailmap.yaml` map to npm dependencies. Likely they're the same list — the YAML's `dependencies:` becomes a hint to the resolver, but the actual install happens via `package.json`.
3. Whether the Trailblaze CLI runs `npm install` itself at daemon init (convenience) or assumes the user has run it (predictability). Probably the latter, matching how every other JS tool behaves.
4. Resolution precedence between classpath, workspace trailmap, and `node_modules/`. Likely workspace > `node_modules/` > classpath, matching the current "closer wins" semantics.
