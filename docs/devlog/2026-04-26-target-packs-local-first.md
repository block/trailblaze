---
title: "Target Packs: Local-First Packaging for Target-Aware Capabilities"
type: decision
date: 2026-04-26
---

# Target Packs: Local-First Packaging for Target-Aware Capabilities

## Context

Trailblaze's current external-config story is converging on a clear workspace model:

- `trails/` is the workspace anchor
- `trails/config/trailblaze.yaml` is the workspace manifest
- `trails/config/` is the artifact directory

That is a good base, but the current top-level config unit is still too narrow. A
`targets/*.yaml` file can describe how to detect an app/site and which tools/toolsets to load,
but the real thing we want to share is bigger than that:

- target detection (`gmail.com`, Android package name, iOS bundle id)
- tools and toolsets
- waypoints
- short recorded navigation segments between waypoints
- runnable trails
- optional JS/TS implementations backing some tools

This matters for both testing and agentic device control. A user may never author a test, but
still benefits enormously if Trailblaze can recognize "this is Gmail" and immediately load
better tools, stronger selectors, known-good waypoints, and reusable navigation segments.

## Decision

### Core model

Trailblaze will move toward a **target-pack** model.

- A **target** remains the runtime concept: the thing Trailblaze is acting against.
- A **target pack** is the reusable, composable, distributable unit.

Examples:

- `gmail`
- `wikipedia`
- `android-settings`
- `consumer-payment-app`
- `merchant-point-of-sale`

Each target pack is self-describing and target-aware. It already knows how to recognize itself
on supported platforms and what capabilities it exports once active.

### One pack may span multiple platforms

A target pack may support:

- `web`
- `android`
- `ios`

or only one of them.

The pack is the published/local dependency unit; the runtime resolves a more specific target
variant such as `gmail:web` or `gmail:android`.

### Packs replace standalone authored `targets/*.yaml`

The current standalone target YAML shape is too small to be the long-term top-level unit.
Going forward, the authored unit should become `pack.yaml`, not a sibling `targets/foo.yaml`.

In other words:

- **today's** `target.yaml` content becomes part of a pack manifest
- **tomorrow's** `pack.yaml` owns target detection plus all exported artifacts

This keeps "what is this target?" and "what does this reusable bundle provide?" in one place.

### Pack ownership is implicit by layout

Artifacts should normally inherit their owning pack from directory layout rather than repeating
that ownership in every file.

For example:

```text
trails/config/
  packs/
    gmail/
      pack.yaml
      tools/
        inputText.yaml
      toolsets/
      waypoints/
      routes/
      trails/
```

In this shape, `tools/inputText.yaml` implicitly belongs to the `gmail` pack because it lives
under that pack's root.

This matches how package ownership works in other ecosystems:

- Python modules inherit package ownership from import path and directory structure
- TypeScript modules inherit package ownership from the package manifest and file location

The system should still be able to represent ownership explicitly in generated or resolved output,
but authored pack content should not require repetitive `pack: gmail` declarations in each file.

### Waypoints are first-class

Waypoints are not a sidecar to trails. They are a core primitive alongside tools.

The model becomes:

- `target` — what this thing is
- `tools` — atomic capabilities
- `toolsets` — grouped capabilities
- `waypoints` — named, assertable locations/states
- `routes` or `segments` — deterministic movement between waypoints
- `trails` — runnable authored flows and validations

Trails become one consumer of this structure rather than the only organizing surface.

### Declarative metadata, dynamic implementation

Pack discovery and composition must stay cheap.

That means:

- YAML is the source of truth for discovery and metadata
- JS/TS is the source of truth for behavior

Trailblaze must not need to execute TypeScript just to discover what tools a pack contains.

Tool metadata should be declared in YAML. JS/TS implementations are referenced by that metadata
and executed lazily at runtime. Optional generators may help authors keep YAML and TS in sync,
but runtime discovery should remain declarative.

### Resolve is automatic; build is lazy

Users should not be required to run a separate `resolve` command during normal use.

Instead:

- pack resolution is automatic
- resolution is cheap
- expensive JS/TS build work is lazy and cached

The split is:

- **resolve**: load manifests, merge dependencies, compute active capabilities, validate ids,
  materialize normalized metadata
- **build**: only when a JS/TS implementation is actually needed and no usable runtime artifact
  is already present

Prebuilt JS remains the preferred runtime artifact when available. Source TS is a convenient
authoring surface, not a requirement for runtime startup.

### Resolve produces one flattened runtime tree

Runtime should not operate directly on an unresolved forest of packs.

Instead, authored inputs are compiled into one resolved output tree, similar to how a JVM build
turns many source files and dependencies into one set of generated classes or one packaged JAR.

That means:

- source packs can be modular and layered
- meta-packs can depend on other packs
- runtime sees one clean, collision-checked output tree

Conceptually:

```text
workspace/
├── trails/
│   ├── config/
│   │   ├── trailblaze.yaml
│   │   └── packs/
│   │       ├── consumer-payment-app/
│   │       ├── merchant-point-of-sale/
│   │       ├── dashboard-admin-app/
│   │       └── company-suite/
│   └── ... trail files ...
└── .trailblaze/
    └── resolved/
        ├── manifest.yaml
        ├── targets/
        ├── tools/
        ├── toolsets/
        ├── waypoints/
        ├── routes/
        ├── trails/
        └── mcp/
```

The resolved tree is the runtime source of truth. Duplicate ids and ambiguous ownership should be
handled during resolve, not left for runtime to guess about.

## Local-first scope

Trailblaze does **not** need a registry, remote installer, version solver, or signatures to
prove this model.

The first implementation target is purely file-based local packs.

Recommended workspace shape:

```text
your-workspace/
└── trails/
    ├── config/
    │   ├── trailblaze.yaml
    │   └── packs/
    │       ├── gmail/
    │       │   ├── pack.yaml
    │       │   ├── tools/
    │       │   ├── toolsets/
    │       │   ├── waypoints/
    │       │   ├── routes/
    │       │   ├── trails/
    │       │   └── mcp/
    │       └── wikipedia/
    │           └── ...
    └── ... trail files ...
```

`trails/config/trailblaze.yaml` then composes the packs the workspace wants to use.

That composition should allow both leaf packs and aggregate meta-packs:

- `consumer-payment-app`, `merchant-point-of-sale`, `dashboard-admin-app` as app-specific packs
- `company-suite` as a meta-pack that depends on those packs and resolves into one flattened
  output

This mirrors dependency-management systems where published package identity is unique, but local
symbol names can remain simple inside a package.

Initial merge semantics should support:

- `use`
- `extend`
- `replace`

Forking should be the escape hatch, not the default extension story.

## Relationship to the older tool naming decision

[Tool Naming Convention](2026-01-14-tool-naming-convention.md) described the flat global tool
namespace that Trailblaze still uses today.

That older decision remains historically accurate for the current runtime model, but part of its
original rationale is no longer the right long-term constraint:

- previously, tool naming was tightly coupled to finding the backing Kotlin class during
  serialization and registration
- today, class-backed tools are described by YAML and can point to fully-qualified class names
  directly

So the old document is still relevant as a description of the **current flat runtime namespace**,
but it should no longer be treated as the main design argument against pack ownership or pack-local
simple names in the future.

## Why this is the right cut

This preserves the value of the current config-alignment work while opening a clean path to a
much stronger ecosystem:

- local workspaces stay simple
- downstream consumers can dogfood the model by moving app-specific behavior out of the binary
- open-source examples can ship as real packs
- future publishing is mostly packaging/distribution work, not a redesign of the runtime model

## What lands now vs later

### What should land with the current PR

The workspace/config alignment work is worth merging now:

- `trails/` as workspace anchor
- `trails/config/trailblaze.yaml` as workspace manifest
- `trails/config/` as filesystem artifact directory
- shared resolver/constants
- generated docs describing the current binary behavior

That PR should **not** wait on the full pack model.

### What should be tracked as follow-up work

1. Introduce local file-based target packs and `pack.yaml`.
2. Move standalone target YAML semantics into the pack manifest.
3. Add workspace composition of packs (`use / extend / replace`).
4. Make waypoints and routes/segments first-class pack artifacts.
5. Shift scripted-tool discovery to declarative YAML metadata with lazy JS/TS execution.
6. Dogfood the pack model by migrating downstream app-specific behavior out of forked binaries.

## Naming

The naming stack locked by this decision:

- **target** — runtime identity
- **target pack** — reusable/distributable unit
- **waypoint** — named app/site location or state
- **route** or **segment** — movement between waypoints
- **trail** — runnable authored flow

## Deferred

Explicitly deferred until the local model proves itself:

- remote registry
- versioned public package distribution
- trust scoring / signatures
- semver constraint solving
- automatic download/install based on target detection

These are packaging and ecosystem problems. The important near-term work is to make the local
pack shape, merge rules, and lazy runtime behavior solid.

## Related Documents

- [Workspace Config Resolution: .trailblaze/ and trailblaze-config/ Conventions](2026-04-07-trailblaze-yaml-config-resolution.md)
- [Waypoints and App Navigation Graphs](2026-03-11-waypoints-and-app-navigation-graphs.md)
- [Scripted Tools — MCP Server Integration Patterns (forward-looking)](2026-04-21-scripted-tools-mcp-integration-patterns.md)
- [@trailblaze/scripting — Authoring Vision & Roadmap (for TS authors)](2026-04-22-scripting-sdk-authoring-vision.md)
