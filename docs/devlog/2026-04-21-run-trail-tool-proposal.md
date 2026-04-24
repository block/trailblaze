---
title: "runTrail: Trail-as-Tool Primitive (Proposal)"
type: devlog
date: 2026-04-21
---

# runTrail: Trail-as-Tool Primitive (Proposal)

## Summary

Forward-looking proposal for a `runTrail` tool: a `DelegatingTrailblazeTool` that
invokes another `.trail.yaml` file and returns its tool calls as the delegation
expansion. Surfaced during the Maestro comparison
([2026-04-21-maestro-scripting-and-control-flow-comparison.md](2026-04-21-maestro-scripting-and-control-flow-comparison.md))
as the Trailblaze analog to Maestro's `runFlow`, but implemented as a tool rather than
a YAML keyword so it composes with existing delegation, recording, and scripted-tool
infrastructure. Not yet scoped or scheduled.

## What `runTrail` would be

A single new tool:

```yaml
- runTrail:
    path: trails/edges/home-to-alarm-tab.trail.yaml
    params:
      alarm_time: "07:00"
```

Implementation shape: a `DelegatingTrailblazeTool` whose `toExecutableTrailblazeTools(ctx)`
loads the referenced trail file, walks its `trail:` objective list, and returns the
expanded tool calls. Identical behavior to every other delegating tool — top-level call
is recorded, expansion is recorded underneath.

That's the whole proposal. The infrastructure it needs already exists.

## Why this fell out of the Maestro comparison

Maestro's `runFlow` is function-call semantics for YAML flows (pass an `env:` block,
invoke a sub-flow, continue). Trail YAML today has no equivalent — if you want to
reuse a sequence of steps across trails, your options are: (a) a YAML-defined tool
(Decision 037), (b) a Kotlin tool, (c) a scripted tool. All three require elevating the
sequence into the tool registry.

`runTrail` gives a fourth option: **reuse a trail file as-is, in place, by path**. The
trail file doesn't need to be registered, doesn't need a tool ID, doesn't need a
separate name. You point at it and run it.

## Why implement it as a tool, not a YAML keyword

Maestro made `runFlow` a YAML keyword. We shouldn't, for a few reasons that flow directly
from our existing architecture:

- **Flat-YAML principle holds.** Trail YAML is a list of objectives, each with tools.
  `runTrail` is just another tool — the trail stays flat and uniform.
- **Recording works for free.** Delegating tools already record both layers (the
  top-level call and the expansion). `runTrail` inherits that behavior automatically.
  Making it a YAML keyword would require a new recording code path.
- **Scripted tools can invoke it.** `trailblaze.execute("runTrail", { path, params })`
  works the same way as any other tool invocation. A scripted tool can pick which trail
  to run based on runtime state. A YAML keyword couldn't be invoked from a script
  without a second mechanism.
- **Uniform with everything else in the registry.** Tools are the composition unit.
  Introducing a parallel composition unit (special keywords) is a complexity multiplier
  everyone has to learn.

This is one of the non-obvious wins of having taken the "logic-in-tools" path in the
first place: a feature that Maestro needed a dedicated YAML keyword for is a one-tool
addition for us.

## How it slots into the existing model

### Trail v2 syntax (2026-03-06-trail-yaml-v2-syntax.md)

No new syntax. `runTrail` is a tool like any other, used under an `objective`:

```yaml
trail:
  - objective: Reach the alarm tab
    tools:
      - runTrail:
          path: trails/edges/home-to-alarm-tab.trail.yaml
  - objective: Create a 7am alarm
    tools:
      - tap: "+"
      - inputText: { text: "07:00" }
      - tap: "Save"
```

### Scripted tools (Decisions 025/038)

Scripted tools can branch on runtime state and select which trail to invoke:

```typescript
const waypoint = trailblaze.execute("whereAmI", {});
if (waypoint.type === "Success" && waypoint.message === "alarm-tab") {
  return; // already there
}
trailblaze.execute("runTrail", {
  path: "trails/edges/home-to-alarm-tab.trail.yaml",
});
```

This is the composition pattern that lets nav-graph pathfinding (Decision 028) be
implemented as an ordinary scripted tool rather than a framework primitive — pathfinder
chooses edges, calls `runTrail` on each. Left as a thread to pick up in the waypoints
follow-up devlog.

### Memory / param scope (needs to be pinned)

The one design choice that isn't free. Options:

1. **Shared scope (inherit parent memory, writes propagate back).** Simple; matches how
   tools behave today within a single trail.
2. **Declared params, writes propagate.** Child trail declares what inputs it accepts
   (via its `trailhead.memory` with defaults); caller passes `params:`; writes still
   propagate to parent memory.
3. **Isolated scope.** Child gets its own memory; caller sees nothing back. Safest,
   least ergonomic.

Tentative preference: **option 2**. Matches how tool params already work, matches
Maestro's `runFlow: env:` shape, and keeps writes visible so a called trail that
populates `user_id` into memory is useful to the caller. But this is the one thing
worth explicitly deciding before implementation rather than picking by default.

## Design choices to pin before implementation

- **Memory/param scoping rule** (see above). Probably option 2, worth confirming.
- **Failure semantics.** If a step inside the called trail fails, does the whole parent
  trail fail? Default yes (consistent with any other tool failure), but a `continueOnError`
  option might be worth it for optional cleanup trails. Defer unless a real case shows up.
- **Recursion depth.** A trail that calls itself (directly or via a chain) could loop.
  Same bounded-recursion cap that Decision 038 already established for scripted tool
  reentrance (~16 deep) applies uniformly — cap at the delegation layer, not per-tool.
- **Path resolution.** Absolute vs relative-to-caller vs relative-to-a-trails-root. Lean
  toward **relative to the caller's trail file**, matching how most import systems work
  and keeping trails portable across checkouts.
- **Should the expansion be visible to the LLM?** For scripted-tool-invoked `runTrail`,
  probably not — the LLM sees a completed `runTrail` result, not the expanded steps. For
  trail-YAML-invoked `runTrail` during replay, it's a no-op question (LLM isn't involved).
  Resolves naturally by treating `runTrail` as `isForLlm = true` but `isRecordable = true` —
  same as other delegating tools.

## Out of scope for this devlog

- **Waypoint integration** (`startAt` / `endAt` assertions bracketing a trail). Genuinely
  interesting but larger than `runTrail` itself — belongs in the waypoints follow-up
  devlog, not this one. The bare `runTrail` primitive is useful independently; waypoint
  assertions layer on top.
- **Nav-graph discovery / pathfinding.** Same deferral. `runTrail` is a prerequisite
  primitive; graph-driven navigation is the layer that uses it.
- **Trail file format changes.** `runTrail` should work against existing v2 trail files
  without modification. Anything that requires v2 schema changes gets deferred to the
  waypoints work.

## Not a decision

Recording this as a proposal, not as a merged decision. Next step when someone picks this
up: pin the scoping rule (question 1 above), write a scope devlog, implement. Expected
footprint is small — one new `TrailblazeTool` class, a trail loader that the delegation
point already has access to, tests for the scoping rule.

## Related

- [Maestro Scripting & Flow Control — Comparison and Self-Validation](2026-04-21-maestro-scripting-and-control-flow-comparison.md) —
  where this proposal originated
- Decision 002: Trail Recording Format (YAML) — recording model `runTrail` plugs into
- Decision (v2 syntax): `2026-03-06-trail-yaml-v2-syntax.md` — trail file shape
  `runTrail` invokes
- Decision 025 / 037 / 038: the existing tool authoring modes `runTrail` sits
  alongside
- Decision 028: Waypoints and App Navigation Graphs — the larger design context that a
  future devlog will tie together with this primitive
