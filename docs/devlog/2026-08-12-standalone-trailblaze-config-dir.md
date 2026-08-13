---
title: "`trailblaze-config/` — a standalone workspace config dir alongside `trails/config/`"
type: decision
date: 2026-08-12
---

# `trailblaze-config/` — a standalone workspace config dir alongside `trails/config/`

A workspace can now anchor its config in a `trailblaze-config/` directory
directly under the workspace root, instead of (or in addition to) the legacy
`trails/config/`. Both layouts are discovered by every walk-up; when both exist
at the same ancestor, `trailblaze-config/` wins and the CLI prints a one-time
consolidation warning.

## Background

The workspace anchor has always been `trails/config/trailblaze.yaml`: the
walk-up from the CWD (or the invoked trail's directory) stops at the first
ancestor carrying it, and the owning `trails/` directory becomes the workspace
root. That couples two independent decisions: *where the config lives* and
*where the trail library lives*. Workspaces that keep trails next to the
features they test (e.g. `jobs/<job>/trails/<case>`) end up with a top-level
`trails/` directory that contains **only** `config/` — a confusing name for a
directory with no trails in it.

## Decision

- `TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES` lists the two layouts
  in precedence order: `trailblaze-config/` (standalone), then `trails/config/`
  (legacy). Every filesystem probe for the workspace config dir iterates this
  list, so the precedence rule cannot drift between resolvers.
- **Closest ancestor wins regardless of layout.** The standalone-over-legacy
  precedence only breaks ties when both layouts exist at the *same* ancestor.
  That tie also logs a once-per-JVM-per-root warning (`Console.info`, so it
  survives quiet mode) asking the author to consolidate.
- `WorkspaceRoot.Configured` gained a derived `configDir` (the anchor file's
  parent), and `dir` is redefined as *the directory that owns the config dir*:
  `<root>/trails` for the legacy layout, the workspace root itself for the
  standalone layout. Every generated-artifact anchor (`.trailblaze/` for the
  SDK bundle, tsc payload, analyzer cache, classpath validation surfaces) is
  `configDir`'s parent, so for a standalone workspace they land at
  `<root>/.trailblaze/` with no per-consumer changes.
- The CLI walk-up marker (`CliPathUtils.findWorkspaceRoot`, used by
  `trailblaze compile` / `check`) probes both layouts' `trailmaps/` dir, and
  the new `workspaceConfigDir` / `workspaceTrailmapsDir` /
  `workspaceGeneratedArtifactsRoot` helpers give every CLI command one shared
  resolution of the layout.
- `TRAILBLAZE_CONFIG_DIR` already accepted any directory name and is unchanged.

## Deliberately out of scope

- **The classpath resource prefix stays `trails/config/`.** Bundled jars/APKs
  contribute entries at `trails/config/...`; Android AGP strips dot-prefixed
  dirs, and every published artifact already uses this prefix. The new name is
  workspace-filesystem-only.
- **The trail library location is untouched.** Trails can already live
  anywhere; this change only decouples the *config dir* from the `trails/`
  convention. Phases that scan for trail files keep their existing roots
  (the config dir's owning directory).
