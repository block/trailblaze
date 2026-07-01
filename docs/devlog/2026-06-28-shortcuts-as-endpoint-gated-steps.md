---
title: "Shortcuts and trailheads are endpoint-gated trail steps"
type: decision
date: 2026-06-28
---

# Shortcuts and trailheads are endpoint-gated trail steps

How a multi-platform shortcut (or trailhead) expresses its per-device bodies:
reuse the [unified trail step](2026-05-22-trail-yaml-unified-syntax.md) shape —
classifier-keyed tool lists resolved closest-wins — rather than inventing a
second multi-platform schema. The endpoint header (`from`/`to`) is the only
thing a shortcut adds on top of a step.

## Background

The navigation graph has two kinds of artifact:

- **Waypoints** (`*.waypoint.yaml`) — *nodes*. A named, assertable screen
  location.
- **Shortcuts** (`*.shortcut.yaml`) and **trailheads** (`*.trailhead.yaml`) —
  *edges* and *bootstrap edges*. A shortcut navigates between two waypoints
  (`{from, to}`); a trailhead drives from any state to a known waypoint (`{to}`).

Today a shortcut body is a single flat `tools:` list:

```yaml
id: app_homeToCardOrder
description: From the home tab, open the card-order sheet.
shortcut:
  from: app/home
  to: app/card-order
parameters: []
tools:
  - tapOnElementBySelector:
      nodeSelector:
        androidAccessibility:
          contentDescriptionRegex: "Order card"
```

That single `tools:` list is single-platform by construction — the selectors
inside it are platform-specific (an `androidAccessibility` selector means
nothing to an iOS driver). The moment a shortcut needs to work on more than one
device class, it faces the *exact* problem the unified trail format already
solved for steps: one logical edge, per-device recordings.

## What we decided

### A shortcut is a unified trail step with an endpoint header

Structurally, a shortcut is a single unified step (the navigation A→B) plus a
`{from, to}` header that gates *when* it applies. So a multi-platform shortcut
reuses the unified step's body shape verbatim — per-classifier tool lists,
resolved by the same closest-wins
[classifier lineage](2026-06-28-classifier-lineage-primitive.md). The YAML below
is the **target shape this decision adopts**; the shortcut/trailhead loaders
still parse today's single flat `tools:` list, and adopting the classifier-keyed
body is the parser follow-up tracked at the end of this entry:

```yaml
id: app_homeToCardOrder
description: From the home tab, open the card-order sheet.
shortcut:
  from: app/home
  to: app/card-order
android:                       # covers android-phone AND android-tablet
  - tapOnElementBySelector:
      nodeSelector:
        androidAccessibility:
          contentDescriptionRegex: "Order card"
ios:
  - tapOnElementBySelector:
      nodeSelector:
        iosMaestro:
          textRegex: "Order card"
```

Resolution is identical to a trail step: the device under test resolves
`[ios, iphone]` → `ios-iphone` → `[ios-iphone, ios]`, walks most-specific-first,
and runs the first matching classifier's tool list. `recordable: false` and
explicit `<classifier>: []` carry the same meaning they do in a step.

Trailheads adopt the same body shape; they keep their own `trailhead: {to}`
header (no `from` — a trailhead is always available, by design, and stays a
distinct metadata block from a shortcut for that reason). The shared thing is
the **body** (classifier-keyed + closest-wins), not the header.

### Why reuse the step shape instead of a bespoke schema

- **One mental model.** An author who can read a unified trail step can read a
  shortcut. The per-classifier override pattern reads like CSS/Tailwind
  responsive overrides in both places.
- **One resolver.** The closest-wins classifier lineage already exists and is
  the single source of truth; a shortcut-specific multi-platform schema would
  be a second place for the fallback rule to live and drift.
- **No new failure modes.** Coverage reporting, family-collapse, and the
  arbitrary-depth classifier story all transfer for free.

A bespoke `platforms:`/`per-os:` block on shortcuts was rejected: it would
duplicate the step semantics with subtly different rules and double the surface
area the graph tooling has to understand.

### Nodes and edges stay separate artifacts — do NOT embed shortcuts in waypoints

It's tempting to give a waypoint an `exits:` list and inline its shortcuts
there, so a node carries its outgoing edges. We explicitly do **not** do this.
Waypoints (nodes) and shortcuts/trailheads (edges) remain separate files:

- An edge has two endpoints; storing it under one of them privileges that node
  and makes the other endpoint's view incomplete. A shortcut authored once
  shouldn't have to be discoverable from two waypoint files.
- Edges get re-pointed during waypoint refactors (splitting `home` into
  `home/admin` + `home/employee`); keeping them as standalone artifacts keyed by
  `(from, to)` means a refactor edits references, not file membership.

The node-centric **"exits / entries" view is derived**, not stored: the graph
tooling (`WaypointGraphBuilder`) computes each waypoint's outgoing and incoming
edges by indexing every shortcut's `from`/`to`. The convenient node-centric
browse view exists at read time without coupling the artifacts at author time.

### `from`-safety comes from a lint, not from coupling

The reason one might want shortcuts embedded in waypoints is referential
safety — "guarantee every `from` points at a real waypoint." We get that from a
**lint** instead: every shortcut/trailhead `from`/`to` must resolve to a known
waypoint id, checked by the graph validator. That keeps the safety property
without paying the coupling cost — the artifacts stay independent, and a
dangling endpoint is a check failure, not a structural impossibility that
forces the schemas together.

## What changed

**Positive:**

- Multi-platform shortcuts and trailheads cost zero new schema — they're a
  unified step body plus the existing endpoint header.
- The closest-wins lineage is the single resolver for steps, shortcuts, and
  waypoint assertions.
- Nodes and edges stay decoupled; the node-centric view is a derived
  convenience, and `from`-safety is a lint.

**Negative / follow-up:**

- The shortcut/trailhead loaders still parse the single flat `tools:` list;
  adopting the classifier-keyed body is a parser change tracked alongside the
  unified-format rollout (the body serializer is already written for trail
  steps and can be shared).
- The `from`/`to`-resolves-to-a-real-waypoint lint needs to run wherever
  shortcuts are validated (`trailblaze check` / the graph validator), not only
  in the graph viewer.
