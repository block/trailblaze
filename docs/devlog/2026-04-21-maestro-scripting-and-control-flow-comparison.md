---
title: "Maestro Scripting & Flow Control — Comparison and Self-Validation"
type: devlog
date: 2026-04-21
---

# Maestro Scripting & Flow Control — Comparison and Self-Validation

## Summary

Walked through Maestro's JavaScript execution model and YAML flow control primitives
([JavaScript Overview](https://docs.maestro.dev/maestro-flows/javascript/javascript-overview),
[Run and Debug JavaScript](https://docs.maestro.dev/maestro-flows/javascript/run-and-debug-javascript),
[Flow Control Overview](https://docs.maestro.dev/maestro-flows/flow-control-and-logic/flow-control-and-logic-overview))
to sanity-check the scripted-tools direction currently in flight (Decisions 025, 037, 038
and their amendments). The comparison was mostly validating: Trailblaze's tool-based
approach hits most of the same use cases with a different — and, for our recording-driven
replay model, cleaner — set of design commitments. One primitive surfaced that we don't
have yet: a `runTrail` tool, captured separately in
[runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md).

## Maestro at a glance

**Three JavaScript execution surfaces, all sharing one global `output` object:**

1. **Inline `${...}` expressions** in any YAML field —
   `inputText: ${'User_' + faker.name().firstName()}`
2. **`evalScript: ${...}`** — logic-only step, no UI interaction.
3. **`runScript: file: x.js`** with an `env:` block — external `.js` file, env vars
   bound as script-scope names.

Sandbox: GraalJS (default, ES6+) in a restricted environment with no filesystem and no
npm, but with **HTTP client and `faker` as built-in globals**. The `output` object is the
blackboard — anything written in one step is readable by any later step or `${}`
expression.

**YAML-level flow control:**

| Primitive | Purpose |
|---|---|
| `runFlow: file.yaml` | Invoke a sub-flow as a function call; pass `env`, get outputs. |
| `when:` with `visible` / `notVisible` / `platform` / `true:` | Conditional step. |
| `repeat:` | Loop. |
| `retry`, `waitUntilVisible` | Stability primitives. |

The load-bearing observation: conditionals and loops live **in the YAML**. The flow
itself branches. JavaScript is just data-shaping glue on the side.

## Where Trailblaze lines up

- **Scripted tools exist** (Decision 038). TypeScript authoring, QuickJS (host) or
  node/bun subprocess (MCP), same source compiles across modes. Conceptually overlaps
  with `runScript: file: x.js` + `env:`.
- **`trailblaze.execute(toolName, params)`** (PR A2, merged) gives scripts a synchronous
  bridge back into the tool system, which is strictly more expressive than Maestro's
  `output`-global pattern for anything non-trivial.
- **YAML-defined tools (`tools:` mode, Decision 037)** cover static composition with
  param substitution — roughly what Maestro achieves with `runFlow` + inline `${param}`
  interpolation in simple cases. No scripting needed.
- **TypeScript as the authoring surface**, not raw JS — authors get real types and
  tooling. Maestro is JS-only.

## Where Trailblaze deliberately diverges

These are the points worth spelling out because they're design commitments, not
accidents.

### 1. Where the logic lives

Maestro embeds logic **in the flow**: `when:`, `repeat:`, inline `${}`, `evalScript`.
Trailblaze pushes logic **into tools**: trail YAML stays flat, and a TS toolset or a
YAML-defined tool encapsulates the branching behind a tool name.

The stance: **YAML is not a programming language.** Flat trails are the feature, not the
limitation. Determinism and readability come from keeping the trail a linear sequence of
declared objectives; branching lives where it can be unit-tested and type-checked. If a
specific trail genuinely needs "run tool A, then tool B or C based on A's result," that's
one scripted tool — the author of that trail encapsulates the complexity, the framework
doesn't grow it into a general-purpose DSL that everyone has to learn.

Consequence: Maestro's `when:` / `repeat:` primitives have no trail-YAML analog and aren't
planned to. Scripted tools cover the cases that matter, opt-in.

### 2. Recording-driven replay changes the budget for JS at replay time

Maestro has no recording concept; JS re-runs on every flow execution. Trailblaze's
`DelegatingTrailblazeTool` model captures both the top-level tool call **and** the
expanded primitive tool calls (Decision 002, 025 §5, 037, 038). On replay, the primitives
execute directly — the script/YAML-defined tool doesn't need to re-run. Android on-device
replay in particular never needs QuickJS present at all.

This is why the "where is the logic" choice upstream matters so much here. Logic in tools
gets captured once and replayed deterministically. Logic scattered through YAML
conditionals at many sites would force re-evaluation on every replay, or force the
recording to encode per-branch artifacts. Pushing logic into named tools keeps the
recording surface small and the replay story simple.

### 3. Memory is not a blackboard

Maestro's `output` global is a free-for-all — any step reads and writes. Trailblaze's
`AgentMemory` is deliberately asymmetric: scripts get `memory.get` / `memory.has`
(read-only), and writes route through tool calls (Decision 025 §4, reaffirmed in 038).

The reason: **tool calls are the system of record.** A recorded trail is a sequence of
tool invocations; memory mutations happen as side effects of those invocations. Direct
`memory.set()` from a script would create state changes that the recording can't see,
which breaks replay determinism on a JVM that has never loaded the script. Keeping writes
in the tool-call path means "re-run the recorded tools in order" is a complete
reproduction of the original session.

### 4. MCP-as-wire-protocol (Tier 2, per 2026-04-21-scripted-tools-mcp-integration-patterns)

Maestro's JS runs in-process GraalJS. There's no notion of "plug in a Python/Go
subprocess that exposes tools." Trailblaze's Tier-2 story — arbitrary MCP servers
declared at target root, any language — is a real expansion of the design space, not a
port of Maestro. This is additive: Tier-1 TypeScript toolsets handle the first-party,
Trailblaze-aware case; Tier-2 lets an author bolt on any existing MCP server (Python
data generators, compiled binaries, `npx` packages) without modifying its source.
Maestro has no equivalent.

### 5. `runFlow` has no direct equivalent — but the shape it represents is missing

Maestro's `runFlow: file.yaml` is function-call semantics for YAML flows: invoke another
flow, pass an `env:` block, get outputs back. The nearest Trailblaze analog today is
"register a named tool" — either via `tools:` YAML or a TS toolset. The tool name
becomes the reusable handle.

But there's a specific shape Maestro hits that we don't: **"run this trail file from
within another trail."** A recorded trail segment invokable by path would compose
cleanly with existing primitives (delegation, recording, memory substitution) and
doesn't require any new file format. We don't have this yet. Captured as a separate
proposal in
[runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md).

## Deferred capabilities (not rejections)

Two Maestro ergonomics we don't currently match, worth flagging as "deferred" rather
than positioned as design rejections:

- **HTTP from scripts.** Decision 025 excluded HTTP from the on-device scripting surface
  because QuickJS can't cleanly do it on Android. On host (subprocess or in-process),
  adding an `http` host binding is straightforward — same shape as `trailblaze.execute` —
  and can land when an author hits the need. It's a missing ergonomic, not a philosophical
  exclusion. Authors who need external API calls today use Kotlin tools or Tier-2 MCP
  servers.
- **Built-in `faker`-style utilities.** Maestro ships `faker` as a script global for
  randomized data. Trailblaze scripted tools today don't — authors would vendor their own
  from the npm ecosystem in a subprocess toolset, or do without in in-process mode. Worth
  considering a thin built-in if authors routinely reach for it.

## The one primitive this comparison surfaced

`runTrail` — a delegating tool that invokes a `.trail.yaml` file. Cleanest analog to
Maestro's `runFlow`, stays consistent with the flat-trail principle because it's **a
tool, not a YAML keyword**. Pulled into its own devlog so it can be evaluated on its own
merits:
[runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md).

## Not covered here

The `runTrail` proposal touches several adjacent threads that are larger than this
comparison and deserve their own treatment:

- **Waypoints and app navigation graphs** (Decision 028,
  `2026-03-11-waypoints-and-app-navigation-graphs.md`). Whether `runTrail` becomes the
  execution substrate for nav-graph edges, how the `trailhead` v2 structure absorbs
  `startAt` / `endAt`, and how `setup` (Decision 028 §3 in the v2 doc) relates to
  graph-resolved navigation — all deferred to a future devlog. There's a lot of design
  surface there and lumping it into this one would dilute both.
- **Pathfinding-at-replay semantics** (graph-authoritative vs recording-authoritative).
  Also deferred.

## What we learned

Doing this comparison was mostly self-validation: Trailblaze's divergences from
Maestro are load-bearing consequences of the recording/replay model, not gaps. The
flat-YAML + logic-in-tools stance holds up under pressure from a mature competitor that
chose the other path. Worth revisiting this devlog if we ever seriously consider adding
YAML-level conditionals — the answer so far is "no, and here's why."

## Related

- Decision 025: Scripted Tools Vision (TypeScript/QuickJS)
- Decision 037: YAML-Defined Tools (`tools:` mode)
- Decision 038: Scripted Tools Execution Model — plus the toolset-consolidation
  amendment and the 04-21 MCP integration patterns devlog
- Decision (v2 syntax): `2026-03-06-trail-yaml-v2-syntax.md` — the `trailhead` / `setup`
  model that any future `runTrail`+waypoints work will plug into
- [runTrail: Trail-as-Tool Primitive](2026-04-21-run-trail-tool-proposal.md) — the one
  concrete addition this comparison surfaced
