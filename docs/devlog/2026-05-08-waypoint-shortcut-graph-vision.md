---
title: "Waypoint + shortcut graph: planner/executor, express routes, semantic recording"
type: decision
date: 2026-05-08
---

# Waypoint + shortcut graph: planner/executor, express routes, semantic recording

## Summary

A direction note for where the waypoint + shortcut framework is heading, captured before any of it is built. Four pieces fall out of treating the waypoint set as an explicit graph: a single `goTo(waypoint_id)` agent surface backed by runtime pathfinding, multi-step "express route" shortcuts as graph-equivalent edges for common deep paths, session logs that record `(shortcut, params)` instead of raw taps, and a clean parallel to the Robot Pattern that makes the upgrade path obvious to anyone with a mobile-test background. None of this is implemented — the [pack manifest](2026-04-27-pack-manifest-v1.md) and [magic capture-example](2026-05-08-waypoint-target-flag-and-magic-capture.md) work landed the primitives the rest of this builds on.

## Planner/executor split

The agent's tool surface should be a single `goTo(waypoint_id)`. The runtime pathfinds through the shortcut graph from the matcher's current waypoint to the named destination and walks the chain. One LLM decision, N atomic taps.

What makes this safe — and the reason it's structurally better than "the LLM picks the next tap each turn" — is the precondition/postcondition contract every shortcut already carries. Each edge asserts `current waypoint == from` before firing and `current waypoint == to` after. If transition N of an N-step path fails because the device landed on `Y` instead of `to`, the executor bails back to the planner with structured feedback ("tried path A→B→C→D, transition 2 failed: expected B but matched B'"). The LLM gets to reason about a real failure mode rather than guess from screenshots.

What the LLM sees as graph context lands on a spectrum. Cheapest is just the tool — names of waypoints in the system prompt, no graph. Most expensive is the full adjacency list every turn. The middle option — "you're at `day_view`. Reachable in 1 hop: …. In 2 hops: …." injected each turn — is probably the sweet spot: small enough to fit, exactly the information needed to decide between "tap this thing right here" and "navigate somewhere first."

## Express-route shortcuts as graph-equivalent edges

The current `*.shortcut.yaml` schema already accepts a multi-step `tools:` body. We've underused it: 64 of 64 atomic shortcuts in the calendar pack landed today are single-tap edges. That's correct for the atom layer — every adjacent waypoint pair has a one-tap edge — but the framework also wants *express routes* for common deep paths.

The key insight is that an express route is just an edge from the planner's perspective. A `from: day_view → to: event_saved` shortcut whose body fires seven taps is graph distance 1, full stop. Shortest-path picks it over the equivalent seven-atom traversal automatically. No special casing. The planner doesn't know or care whether an edge is one tap or seven; the executor just walks the body.

The motivation is not just "fewer taps." Three properties matter:

- **Parameterization at the right step.** `createEvent(title, time, location)` substitutes params into the matching step of the body. A pure pathfind through atoms can't do that — it walks edges, no parameter context.
- **Curated reliability.** The author validated this exact sequence; the executor can skip mid-chain precondition checks. Trade safety margin for speed when the path is known-good.
- **Discoverability.** The LLM sees "create an event" as one named operation, not a planning problem.

Trailheads, atoms, and express routes form a complete coverage pattern: trailheads handle cold-start (no `from`), express routes handle warm-but-deep, atoms cover the rest.

## Semantic recording

A session log entry today records `tapOnElementBySelector` calls with raw selectors and (for accessibility-driver runs) bounds. That's mechanical-level — what the framework executed, not what the agent decided. The framework should record at the decided level instead:

```yaml
- shortcut: { from: day_view, to: event_saved, variant: createWithLocation }
  params: { title: "Lunch", location: "Cafe Venetia" }
```

Off-graph actions still record as raw tools — that's the escape hatch. But for behavior that did pass through a known shortcut, the recording is the shortcut invocation, not its expansion.

The downstream consequences are all wins:

- **Replay portability across UI changes.** A button moves; the shortcut body gets fixed once; every recording that used it keeps working. No re-record.
- **Cross-platform replay.** Same `(from, to, variant)` resolves to an Android body or an iOS body. A test recorded on one replays on the other if the platform pack has the matching shortcut.
- **Compact LLM context.** Past behavior shown to the agent is N shortcut entries instead of 5N raw taps. Fits in the prompt; the agent can plan against it.
- **Debuggable failure attribution.** "Shortcut `createEvent` failed at step 4 of 7 (tap on Add description)" is debuggable. "Tap at (300, 1500) failed" is not.

This also collapses the observed-vs-authored split that #2541 left as a follow-up. A `TrailSegment` is "the agent moved from waypoint A to waypoint B; here's the tap sequence in between." If the sequence matches a known shortcut's body, the segment IS the shortcut invocation. If not, it's a promotion candidate. Same data, two states — the recorder just emits one or the other.

## The Robot Pattern parallel

The Robot Pattern that mobile testing has used at Square for years gives the same maintenance economics: one method update fixes thousands of tests; here, one shortcut body update fixes thousands of recordings. Anyone with a mobile-test background already understands the load-bearing idea — the pitch becomes "this, but cross-platform, observable, and machine-navigable" instead of "here's a new abstraction."

What the waypoint+shortcut model adds beyond classical Robot Pattern:

- **Assertable state.** A Robot is implicit-by-class — `LoginRobot()` is an assertion the test author trusts, never checked. Waypoints make state explicit and assertable; the matcher can detect "you said `event_detail` but you're on `quick_create_expanded`" and fail loudly or recover.
- **Cross-platform identity.** Robots are per-platform classes. Shortcuts addressed by `(from, to, variant?)` have one identity; the body resolves per platform.
- **Derivable from observation.** Robots are write-only. Shortcuts can be mined from session logs (`TrailSegment` promotion).

## Open questions

- Whether the schema today supports referencing other shortcuts from inside a shortcut body, or only raw tool calls. If the former, express-route bodies that compose three atomic shortcuts cost three lines of YAML. If the latter, they re-encode the atom selectors and lose the maintenance benefit. Worth confirming before authoring more than a few.
- Variant disambiguation when multiple paths exist between the same waypoints — pure shortest-path may need a tie-break (graph distance, then explicitly-curated `priority:`, then lexicographic on id).
- The exact wire format for semantic recording entries. Existing `tapOnElementBySelector` records are sequence-numbered and timestamped; the shortcut form needs to round-trip through replay without losing intermediate-state debuggability.

## Future work

A working multi-step calendar shortcut as concrete proof — `from: day_view → to: event_saved` with `(title, time, location)` parameters — surfaces the schema question above and gives the design something to point at. That belongs in its own PR; this is the framing devlog.

`goTo` as an MCP tool, the recorder change, and the `TrailSegment` promotion UX each warrant their own devlog when there's running code to describe.
