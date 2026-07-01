---
title: "assertWaypoint tool replaces the per-step postcondition field"
type: decision
date: 2026-06-30
---

# assertWaypoint tool replaces the per-step postcondition field

## Summary
Retired the bespoke step-level `postcondition: { waypoint: ... }` YAML field and replaced it with
`assertWaypoint`, a first-class framework tool. Waypoint end-state assertions are now something any
step can call, a branchy tool can compose, and — most importantly — the agent can *select* on its
own while authoring. Behavior is preserved; the capability just moved from a field to a tool.

## Key decision (and why)
A `postcondition:` field can only ever be authored by a human (or special-cased codegen). The agent
drives an app by **selecting tools** — `tap`, `inputText`, `assertVisible` — and a step field isn't
in that vocabulary, so the agent could never decide "I've arrived; lock in that I'm on the Checkout
waypoint." That cuts against Trailblaze's core idea of agent-driven authoring via tool selection.

Making it the `assertWaypoint` tool subsumes the field:
- The agent can pick it during authoring/exploration like any other tool.
- A branchy tool (`handleOptionalScreens`, `dismissSetupBanner`) can call it before returning to
  guarantee its own "ends in a known state" contract.
- It stays hand-authorable in YAML/scripted trails (three-way tool parity).

The field was the strictly-less-capable form. The tool is the primitive.

## What changed
- **`AssertWaypointTrailblazeTool`** (`trailblaze-common`) — `@TrailblazeToolClass(name = "assertWaypoint",
  isVerification = true, requiresHost = true)`. Reads the live screen + the session's resolved target
  from the `TrailblazeToolExecutionContext`, resolves the registry host-side, polls, returns `Success`
  on match / `Error` on timeout/unknown-waypoint. Listed in the generic `verification` toolset
  (android/ios) and in `web_verification` + `compose_verification` so it surfaces on every
  host-orchestrated driver that supplies a selector node tree. Revyl is intentionally excluded —
  it's vision-grounded and has no selector tree for the matcher to evaluate.
- **`WaypointAssertion`** — the pure poll-until-match engine, relocated from `StepPostconditionAsserter`
  and decoupled from the deleted `StepPostcondition` model (takes plain `waypointId`/`timeoutMs`/
  `pollIntervalMs` + injected `screenStateProvider`/`waypointResolver`/`now`). Unit-tested directly.
- **`WaypointRegistryResolver`** — consolidates the two near-identical host helpers
  (`resolveWaypointsForRun` / `resolveWaypointsForTrail`) into one process-cached resolver. The
  analyzer-backed scripted-tool enrichment (host-only) is installed once via `TrailblazeHostYamlRunner`'s
  `init`.
- Removed the `postcondition` field from `PromptStep`/`DirectionStep`/`VerificationStep` + its
  serializer element + the `StepPostcondition` model, and the dormant per-step assertion blocks in
  `DeterministicTrailExecutor`, `MultiAgentV3Runner`, and `TrailblazeRunnerUtil` (plus their
  `screenStateProvider`/`waypointResolver`/`target` wiring through `TrailApi`,
  `TrailblazeHostYamlRunner`, `BaseHostTrailblazeTest`, `AndroidTrailblazeRule`).
- `trailblaze waypoint shortcut verify` codegen now emits `assertWaypoint` recorded-tool steps.
- Regenerated the `:trailblaze-models` `apiCheck` baselines (the `postcondition` accessor is gone).

## Why requiresHost
The waypoint registry + matcher live host-side. With `requiresHost = true` the tool executes on the
host JVM for host-orchestrated runs of *any* driver (where the registry resolves), while pure
on-device agents — which can't reach the registry — drop it at registration. Keeping the class in
`trailblaze-common` (not `trailblaze-host`) means the toolset name still resolves on every classpath;
the flag, not the class location, is what scopes execution.

One wiring gap fixed along the way: the host V3-accessibility tool-execution context didn't populate
`screenStateProvider`, so a host-side verification tool had nothing to poll on that path. It now does.

## What this rode on top of
Landed right after the Waypoints v2 hard cut (#4229, classifier-keyed route-bound definitions). The
matcher/asserter public surface was unchanged by v2 — the only v2-specific addition the relocated
engine carries is the `NO_CLASSIFIER_BLOCK` skip reason in the mismatch diagnostic.

## Migrated callers
- The one in-tree trail under `trails/` that declared a postcondition (a Square iOS create-item
  case) had its postcondition **removed**, not converted. That postcondition was a dormant no-op on
  the real run paths, so dropping it is behavior-preserving. It is NOT re-expressed as an
  `assertWaypoint` step yet because the registry resolver only loads classpath-bundled framework
  waypoints, and that trail's waypoint (`square/ios/more-tab-no-sheet`) is an app/workspace waypoint
  the resolver can't see on the on-demand IOS_HOST path — making the live assertion fail where the
  no-op silently passed. Wiring app/workspace-waypoint resolution is the immediate follow-up; that
  trail (and others) can adopt `assertWaypoint` once it lands.
- `acceptance/postcondition-wiring-smoke` — **deleted.** Its only purpose was proving the now-removed
  wiring fired through the daemon, asserting an intentionally-unknown waypoint (could never pass as a
  real assertion). The tool gets focused unit + contract tests instead.

## Known limitation / immediate follow-up
`WaypointRegistryResolver` resolves **classpath-bundled** waypoints only (the framework `trailblaze`
trailmap). Waypoints defined by a *target app's own* workspace trailmap (rather than the framework
stdlib) aren't loaded, so `assertWaypoint` against them reports "unknown waypoint" on
host-orchestrated runs. The dormant postcondition hid this; the live tool exposes it. Follow-up:
load the workspace trailmaps (via the configured `trailblaze.yaml` anchor) in the resolver so
app-defined waypoints resolve, then migrate the real callers.

## Not touched (different systems)
`EnhancedRecording.postconditions` (inferred recording conditions) and the TypeScript SDK's
`conditional-action.ts` `postcondition` predicate are unrelated and left alone.

## Tests
- `WaypointAssertionTest` — the four poll outcomes (Matched / NotMatched / WaypointNotFound /
  NoScreenState), late-match settling, and templated-target forwarding, all with an injected clock.
- `AssertWaypointTrailblazeToolTest` — construction validation and the verdict polarity of the
  Result → `TrailblazeToolResult` mapping (match is the only success).
