---
title: "Waypoints and App Navigation Graphs"
type: decision
date: 2026-03-11
---

# Waypoints and App Navigation Graphs

## Context

Today, every trail figures out navigation from scratch. If ten trails need to get from the home screen to Settings, the AI navigates there ten times and ten recordings each encode their own copy of that path. When the app changes, all ten break independently.

What's missing is **structural knowledge about the app** — a reusable map of where you can be and how to get between places. The building blocks exist (`blaze`, `ask`, recording optimization, template substitution, AI fallback), but there's no structural layer on top.

Consider "Set a 7am weekday alarm": steps 1-2 are **navigation** (launch app, go to Alarm tab), steps 3-4 are **task execution** (create alarm, verify). These are fundamentally different concerns but interleaved in a single trail with no separation. This causes redundant exploration, redundant recordings, no spatial reasoning, and brittle composition.

## Decision

### Core Concepts

**Waypoints** — Named, assertable locations within an app. Defined by identity (e.g., `clock:alarm-tab`), structural assertions (which elements are present/absent/selected), and optional captures (observable values like alarm count). A waypoint is not a screenshot — if the set of available user actions changes, it's a different waypoint. If only content changes, it's the same waypoint. State qualifiers handle different interaction modes (e.g., `clock:stopwatch:idle` vs `clock:stopwatch:running`).

**Edges** — Short, recorded trail segments moving between exactly two waypoints. Defined by from/to waypoints, tool call steps, and optional variables. Edges are unidirectional — `alarm-tab → settings` (tap menu) is separate from `settings → alarm-tab` (press Back). Edges can be parameterized with `{{variables}}` via template substitution (Decision 024).

**Navigation Graph** — Directed graph of all waypoints and edges for an app, stored as a `.nav.yaml` file. Multi-hop navigation becomes **pathfinding** — "get from Cities to Stopwatch" resolves to `cities-list → clock-tab → stopwatch:idle` by replaying two edges sequentially. No LLM needed.

**Edges as Discoverable Skills** — Edges carry metadata (name, description, variables) making them discoverable skills the agent can look up and invoke. A `create-alarm` edge with `alarm_time` and `repeat_days` variables is a skill: the LLM finds it, fills in variables, and it executes deterministically.

**Trail-to-Trail References** — Trails declare `startAt:` and `endAt:` waypoint references. The execution engine navigates to the starting waypoint using graph edges, then runs only the task-specific steps. Intermediate `checkpoint:` waypoints provide validation and recovery points.

### Implementation Steps

Each step depends on the one before it:

1. **Waypoint Schema and File Format** — Define YAML schema for waypoints and nav graph files. Hand-author an example for a simple app. Open questions: assertion types, mapping to TrailblazeNode model, naming conventions, file location.

2. **Waypoint Assertion Engine** — `checkWaypoint(waypoint, screenState) → WaypointMatch` resolving assertions against the existing view hierarchy model. Foundation for everything else.

3. **"Where Am I?" Screen Identification** — `identifyCurrentWaypoint(graph, screenState) → WaypointMatch?` checking all waypoints and returning the best match. More specific waypoints (more assertions) win over less specific ones.

4. **Edge Recording** — Recording mode: assert `from` waypoint, record navigation steps, assert `to` waypoint. Integrates with existing session recording, post-processed through recording optimization (Decision 034) and variable extraction (Decision 024).

5. **Edge Playback and Validation** — Execute a recorded edge: assert `from`, replay steps, assert `to`. Validation mode runs every edge in a graph to produce a pass/fail report. AI fallback (Decision 021) can optionally attempt recovery on step failure.

### Future Phases

With steps 1-5, the system has all primitives. Built on top:

- **Graph Pathfinding** — BFS shortest path between waypoints; enables `startAt:` in trail files
- **Automated Exploration** — AI-driven exploration agent discovers waypoints and edges, human reviews
- **Graph Maintenance** — Failed edges trigger localized re-exploration; track volatile app areas
- **Compositional Trail Authoring** — Trails focused purely on task logic; navigation fully separated

## What changed

**Positive:**
- Navigation knowledge captured once, reused across all trails
- Multi-hop navigation becomes deterministic pathfinding — no LLM needed
- Trail recordings become shorter, focused on task logic
- Navigation failures fixed in one place, not across every trail
- Graph provides structural app map for coverage analysis

**Negative:**
- Initial graph creation requires exploration time per app
- Graph maintenance is a new ongoing cost
- State explosion for complex apps — heuristics needed for what differences matter
- Waypoint assertions require tuning (too strict = false negatives, too loose = false positives)

## Related Decisions

- Decision 002: Trail Recording Format — edges are small trail recordings
- Decision 021: AI Fallback — recovery when edge playback fails
- Decision 024: Recording Memory Template Substitution — waypoint captures feed edge variables
- Decision 034: Recording Optimization Pipeline — edge steps go through same optimization
