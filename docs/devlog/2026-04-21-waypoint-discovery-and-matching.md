---
title: "Waypoint Discovery via `matchWaypoint` — Agent-Driven, Retroactive"
type: devlog
date: 2026-04-21
---

# Waypoint Discovery via `matchWaypoint` — Agent-Driven, Retroactive

## Summary

Captures the direction that emerged from a discussion about how Trailblaze should
**discover** waypoints (Decision 028 left this open). Short version: agents do the
semantic thinking, **one deterministic primitive — `matchWaypoint` — does the
structural checking**. Discovery becomes a retroactive analysis of existing session
logs rather than a staged multi-phase pipeline. The critical new capability is
`matchWaypoint`; everything else falls out of it.

This is a direction note, not a merged design. Calls out a concrete waypoint schema to
pin and a concrete tool signature to implement as the first real step.

## Design stance: agents are the primary user

Trailblaze's architecture treats agents (Claude Code, Codex, Goose, and the built-in
multi-agent loop) as first-class users. Waypoint discovery should reflect that. Two
principles:

- **Agent does the semantic work** — naming, judgment calls on granularity, deciding
  when two screens are "the same place" vs "the same place in different states."
- **Framework provides deterministic primitives** — structural comparison, assertion
  matching — so the agent's judgment is anchored in reproducible signals, not
  hallucinated structure.

The corollary: no clustering heuristics, no similarity thresholds, no multi-phase
review pipeline. Agent iterates on a draft definition, primitive validates it against
session data, agent refines. Done.

## The critical primitive: `matchWaypoint`

Given a waypoint definition and a set of session logs, return every step that matches
plus every step that *nearly* matches (off by one assertion). The near-miss signal is
the load-bearing piece — it's how the agent tightens or loosens iteratively.

```typescript
// MCP tool signature (also exposed via CLI and scripted-tool surface)
matchWaypoint(
  definition: WaypointDefinition,
  sessionPath?: string,          // default: all sessions under trails/sessions/
  includeSamples?: boolean       // include screenshot paths in results
): {
  matches: Array<{
    session: string;
    step: number;
    matched_required: string[];    // which assertions hit
    screenshot?: string;           // if includeSamples
  }>;
  near_misses: Array<{
    session: string;
    step: number;
    missing_required: string[];    // assertions that failed
    present_forbidden: string[];   // forbidden elements present
  }>;
  total_steps_scanned: number;
  total_sessions: number;
}
```

**Three invocation surfaces, one implementation:**

1. **MCP tool** — primary surface for external agents driving discovery
2. **CLI wrapper** — `blaze waypoints match --def clock:alarm-tab` for automation and humans
3. **Scripted-tool-accessible** — `trailblaze.execute("matchWaypoint", {...})` for building higher-level scripted tools on top

Retroactive by design: the default scope is **all existing session logs**. Every prior
blaze run retroactively contributes to validating proposed waypoint definitions. No
new exploration required to bootstrap the graph.

## Pinned waypoint schema

Reuse the existing Trailblaze selector system. No new DSL. Whatever selectors work in
`tapOn` / `assertVisible` work here — including the regex forms (`textRegex`, `idRegex`)
already in the selector model.

```yaml
id: clock:alarm-tab
description: >
  The Alarm tab with the list of alarms and the add-alarm FAB.
  The Alarm tab control itself is in selected state.

required:
  - { selectorType: text, text: "Alarm", state: selected }
  - { selectorType: accessibilityId, id: "alarms-list" }
  - { selectorType: accessibilityId, id: "fab-add-alarm" }
  # Regex with count constraint — at least one alarm row
  - selectorType: accessibilityId
    idRegex: "^alarm-item-.*"
    minCount: 1

forbidden:
  - { selectorType: text, textRegex: "^Allow (notifications|location)$" }
  - { selectorType: accessibilityId, id: "onboarding-overlay" }

captures:                            # observable values, NOT used for matching
  - name: alarm_count
    from: { selectorType: accessibilityId, id: "alarms-list" }
    property: childCount
```

### Matching rules

A step matches iff:

1. **Every** entry in `required` is satisfied (AND semantics).
2. **No** entry in `forbidden` is present.
3. `captures` never affect matching — they're observable values for downstream steps.

A single `required` entry is satisfied when at least one element in the hierarchy matches
its selector and all its modifiers (`state`, `minCount`, etc.).

### Design calls pinned here

- **Selectors reuse the existing system.** Non-negotiable. Parallel selector DSLs are
  a maintenance disaster.
- **Regex comes for free** where the selector layer already supports it. `textRegex`,
  `idRegex`, etc.
- **`forbidden` is essential**, not optional. This is how overlays, A/B variants, and
  runtime-state intrusions (permission dialogs, onboarding prompts) are caught. Every
  non-trivial waypoint will have forbidden entries.
- **State qualifiers are separate waypoint IDs**, not fields on one waypoint. If the
  available user actions differ, it's a different waypoint. `clock:stopwatch:idle` and
  `clock:stopwatch:running` are sibling waypoints, not states of one. Flat data model,
  no special state mechanism.
- **Structural, not content.** Assert on element identity (IDs, roles, stable tab
  labels), never on per-run content like "shows the alarm named '7:00 AM'." Content
  varies per run; structure doesn't.
- **`description` is for the agent.** When another agent later reads the waypoints file,
  this is its summary. Keep it short but informative.

## How the agent actually uses this

Concrete walkthrough. Claude Code given "build a navigation graph for the Clock app's
alarm features":

1. **Explore.** Runs existing `blaze` with a scoped goal. Session logs already capture
   view hierarchies, screenshots, tool calls, and reasoning at every step.
2. **Browse.** Reads session logs, picks step 14 as a candidate waypoint. Reads the
   hierarchy.
3. **Hypothesize.** Proposes a definition — a set of `required` assertions pulled from
   stable-looking elements in the hierarchy.
4. **Check.** Calls `matchWaypoint(def, all_sessions)`.
5. **Inspect matches and near-misses.**
   - 11 clean matches across 3 sessions — good.
   - 4 near-misses missing `fab-add-alarm`. Agent looks at those screenshots — they're
     the empty state (no alarms yet). Judgment call: do those belong as the same
     waypoint with the FAB assertion removed, or as a sibling `clock:alarm-tab:empty`?
     The action sets differ (empty state has an onboarding prompt), so split.
6. **Iterate** until the definition captures what it should and nothing else.
7. **Commit** to `trails/clock.waypoints.yaml`.

Edges fall out for free after the waypoints exist: walk every session, find
transitions between matched waypoints, emit the tool calls between them as candidate
edge files (the `runTrail`-executable shape from the sibling devlog). Agent reviews,
names, commits.

## Image diffs vs hierarchy diffs

Both, different roles:

- **Hierarchy diff is the primary signal** — structural, content-independent, maps
  directly to the required/forbidden assertion model. Cheap.
- **Image diff is the sanity check** — "these two screens match by hierarchy; do they
  actually look substantially different?" Useful for catching state qualifiers where
  the hierarchy is identical but the visual isn't (idle vs running stopwatch), and
  for detecting visual-only state that the hierarchy misses (badges, colors, icons).

Default to hierarchy diff. Reach for image diff when the agent suspects visually
distinct states with similar structure. Probably a second tool,
`diffScreens(stepA, stepB)`, that returns both — deferred, not yet scoped.

## What's deliberately *not* in this devlog

- **The full discovery pipeline.** No staged phases, no draft formats, no commit
  workflow. Once `matchWaypoint` exists, the pipeline is "agent iterates until
  happy, writes the file." Anything more structured is premature.
- **The `runTrail` primitive.** Captured in
  [runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md) — edges
  are trail files, `runTrail` executes them. Independent of discovery.
- **Nav-graph execution (pathfinding, edge replay, graph-authoritative-at-replay).**
  Decision 028 laid out the direction; implementation details of edge playback,
  graceful-degradation tiers, and the `trailhead` / `startAt` integration are for a
  follow-up once `matchWaypoint` exists and a real graph can be built.
- **Agent-authoring conventions** (how agents should name waypoint IDs, how to
  structure the waypoint file across multiple apps, etc.). Deferred until real
  discovery sessions show what conventions actually emerge.

## Open questions to pin before implementation

- **Near-miss radius.** Start at "fails by exactly 1 assertion" for high-signal
  feedback, expand only if the agent needs more. Worth confirming this is the right
  default before it calcifies.
- **Session scope default for `matchWaypoint`.** Lean toward *all* sessions under
  `trails/sessions/**` — that's what delivers the retroactive property. Tool is
  cheap per step, so scale is a non-issue.
- **"suggestWaypoint(step)" helper.** Worth having? Takes a step, proposes a starting
  `required:` set from stable hierarchy elements. Saves the agent from transcribing
  hierarchies by hand. Trivial to implement; probably yes.
- **`minCount: 0` semantics.** Does it mean "zero or more is fine" (effectively a
  documentation hint, no constraint) or "must be absent" (stronger)? Proposed reading:
  the former, since "must be absent" is what `forbidden` is for.

## Next steps

When someone picks this up:

1. Confirm the waypoint schema above against the existing selector model — fix
   anything that doesn't line up with real `tapOn` selector semantics.
2. Implement `matchWaypoint` as an MCP tool + CLI wrapper. Scripted-tool surface is
   automatic once it's a registered tool.
3. Run it retroactively against the existing trails corpus. First real usage is
   validation that the schema captures what authors intuitively think of as
   "waypoints."
4. Only after real usage: build the rest (edge extraction, pathfinding, trailhead
   integration).

## Related

- Decision 028:
  [Waypoints and App Navigation Graphs](2026-03-11-waypoints-and-app-navigation-graphs.md)
  — the broader waypoint/graph vision this proposes a concrete discovery mechanism for
- [runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md) — the
  execution-side sibling; edges are trail files, `runTrail` invokes them
- [Maestro Scripting & Flow Control — Comparison and Self-Validation](2026-04-21-maestro-scripting-and-control-flow-comparison.md) —
  the discussion that triggered this direction
- Decision (v2 syntax): `2026-03-06-trail-yaml-v2-syntax.md` — the `trailhead` /
  `setup` / `startAt` shape that waypoints will eventually plug into
