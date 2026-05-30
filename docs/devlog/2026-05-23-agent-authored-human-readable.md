---
title: "Agent-Authored, Human-Readable"
type: decision
date: 2026-05-23
---

# Agent-Authored, Human-Readable

Trailblaze is built around an asymmetric authoring contract: **LLM agents
are the primary authoring layer for trails, YAML, and recordings; humans
are the comprehension, audit, and bootstrap layer**. The framework's
surfaces — trail YAML, scripted tool APIs, recording formats, memory
schemas, navigation primitives — are optimized for what an agent emits,
not for what a human ergonomically types from scratch. Readability is
preserved everywhere humans need it (review, debugging, hand-correcting
at the margin), but it is not there so humans can author primary content.
This devlog names that principle, captures the design heuristic it
produces, and traces how it explains decisions already made.

## Summary

The line we are drawing: trails are written by agents, building blocks
are bootstrapped by humans and increasingly extracted by agents, and the
whole pipeline replays deterministically without paying the LLM tax. Pure
human-authored trail YAML and pure-TypeScript test scripts both
work as side effects of building the agent-authored path well — but
neither is the optimized use case the framework is shaping around.

## Background

The framework has spent several months reaching a fork where the
authoring model needed to be made explicit. Two competing framings were
visible in design conversations:

1. **"AI helps you write tests."** The classic AI-assisted authoring
   framing. Human writes most of the test; LLM fills in some bits;
   replay runs against deterministic recorded artifacts. This is how
   most "AI testing" tools position themselves today.

2. **"Agents write tests; humans understand them."** The asymmetric
   framing this devlog formalizes. Agents are the authoring layer.
   Humans audit, verify, debug, and bootstrap the durable foundations
   that agents lean on to scale. Replay is still deterministic; the LLM
   is never a per-replay tax.

The second framing is what Trailblaze has actually been building
toward — but it had not been written down. Several recent PRs make
sharper sense once it is named:

- **#3322 — Typed `trailblaze.tool<I, O>(handler)` bare-form authoring**
  added typed authoring as a parallel surface to YAML, not a replacement.
- **#3329 — Per-trailmap `client.d.ts` codegen**
  surfaced typed tool methods for human IDE comprehension, not for human typing.
- **#3338 — Analyzer-driven runtime registration**
  pinned the `.ts` file as the source of truth, with `extract-tool-defs.mjs`
  walking the AST to derive both runtime registration AND the typed bindings.
- **#3343 — Workspace SDK distribution at `.trailblaze/sdk/`**
  made the SDK visible to humans (IDE imports, hover-doc) without requiring
  per-trailmap `package.json` ceremony.
- **#3344 — Deleted the on-device MCP bundle subsystem**
  removed a substantial code path that existed for a "scripted tools as
  cross-language MCP servers" framing that nobody was actually using.
- **#3346 / #3348 — First migrations of `ios-contacts` and `wikipedia` trailmaps**
  to the typed authoring form. Proof-of-concept that the new surface holds
  on real trailmaps.
- **#3349 — `package.json` + postinstall bootstrap**
  picked the universal IDE-bootstrap mechanism (`npm install` runs
  `trailblaze check`) over a Gradle-sync hook that would only have worked
  for framework developers.
- **#3351 — Selector grammar codegen + binary compatibility validator**
  made the Kotlin sealed-class surface canonical and the TS surface derived,
  with apiCheck enforcing the contract. The selector grammar is now
  Kotlin-authored, TS-consumed.
- **#3353 — `trailblaze.tool<I, O>(spec, handler)` overload with inline config**
  collapsed per-tool YAML from 13-17 lines to a single `script:` marker.
  Per-tool metadata moved into TSDoc + the inline spec object; the analyzer
  extracts both and feeds them into the runtime `_meta` JSON.

Each of those PRs makes a sharper case under the agent-authored framing
than under the AI-assisted framing. The principle is not new; only the
name is.

## What we decided

**Trailblaze optimizes for agent-emission over human-typing.** Trail
YAML, scripted-tool authoring surfaces, recording wire shapes, and
memory schemas are shaped around what an LLM agent naturally produces
when asked to accomplish a task. Where humans need to interact with
those artifacts — code review, debugging, hand-correcting a broken
selector, extracting a recurring pattern into a reusable tool — the
framework optimizes for the **read** path: diffability, hover-doc,
grep-friendliness, log-style failure traces.

The framework is **not** optimized for humans hand-authoring trails as
the primary mode. Where that path exists (and it does — the TS surface
is a perfectly capable testing framework on its own), it is a side
effect of the underlying primitives being good. We will not fight it,
but we will not invest in features that exist solely to make hand
authoring easier.

## The two readers

There are two readers of every Trailblaze artifact, and they are
asymmetric:

**Agent reader (primary author).** An LLM emitting a trail YAML, a
scripted tool implementation, a memory write, a selector for a captured
recording. The agent is producing content. The agent works best when:

- Schemas are flat and forgiving — extra fields ignored, missing
  optional fields defaulted, no required formal declarations the agent
  has to remember
- Tools have short stable names the model can predict from the task
  description
- Single semantic units stay in single artifacts (one trail, one
  scripted tool, one recording) rather than splintered across multiple
  files that demand cross-references
- The framework provides building blocks the agent can compose rather
  than asking it to re-derive the same scaffolding for every task

**Human reader (audit, debug, bootstrap).** A developer reviewing what
the agent produced, debugging why a recording fails on `pixel-7-pro`,
hand-editing a selector that misclassified, extracting a recurring
pattern into a typed scripted tool the trailmap can reuse. The human works
best when:

- Types flow through the IDE — autocomplete, hover-doc, refactor across
  files
- Diffs are small, semantic, and grep-friendly
- Tool names match what's in the YAML so investigation is one search
  away from one layer to the next
- The compiler enforces invariants the human would otherwise have to
  remember (no prose in spec objects, no duplicate variable keys, no
  selectors that don't match the platform)

**Reading is symmetric; authoring is not.** Both readers consume the
same artifacts. Only the agent is expected to author them at scale. The
human's job is to verify, audit, repair at the margin, and seed the
patterns the agent extracts going forward.

This asymmetry is what makes the framing different from "dual-reader"
or "LLM-plus-human." It is not 50/50. It is approximately 95/5 by
volume, biased toward the agent — but the 5% (the human reader) is
load-bearing for the long-run trust and scalability of the system.

## The trail format as the realization of the principle

The principle is abstract; the concrete artifact where it lives is
the **trail** — a YAML file expressing a test in approximately
natural language, with each step backed by a deterministic recorded
action and selector:

```yaml
config:
  memory:
    query: "Albert Einstein"

trail:
  - "Open the Wikipedia main page"
  - "Search for {{query}}"
  - "Tap the first matching result"
  - "Assert the article title contains 'Einstein'"
```

This format is the framework's signature ergonomic asset, and it's
where the agent-authored / human-readable contract is most directly
realized:

- **Authored by an agent**: the prose form is what an LLM naturally
  emits when asked to express a test plan. No declarative ceremony,
  no formal step types, no enum-constrained action vocabulary.
- **Readable to a human**: a reviewer reads the trail and understands
  what it does without learning framework-specific syntax. The
  natural-language step descriptions are the documentation.
- **Deterministic at replay**: each step carries its resolved
  selector in the recorded artifact, so replay never invokes the
  LLM. The natural-language description is the human-facing label;
  the resolved selector is what actually runs.
- **Diffable and reviewable**: small UI changes show up as small
  selector diffs in PRs. Conceptual flow changes show up as added
  or removed natural-language steps.

This is "agent authors, human reads, runtime replays" expressed at
the test-artifact layer. The same property propagates upward: scripted
tools are composed of trails worth of expertise; trailmaps bundle trails
plus the navigation graph; recordings carry the resolved selectors
forward. The trail is the unit; the principle is the contract.

## The compounding-value path

The agent-authored framing only pays off if the underlying foundations
get richer over time. Otherwise it is just "an LLM writes test code,"
which has been tried elsewhere and degrades into expensive,
unmaintainable tangle. Trailblaze's bet is that **the foundations
compound**:

1. **Day one** — agents author trails. Humans hand-write a handful of
   scripted tools to seed the trailmap with reusable primitives (`login`,
   `addItem`, `openSettings`). The seed set is small and human-curated.

2. **Once there are ~10 successful trails** — agents start spotting
   recurring patterns. With the right framework hooks, agents can begin
   *extracting* their own scripted tools from sequences they keep
   re-deriving. The human-authored seeds were never the endgame; they
   were the bootstrap for the agent's pattern recognition.

3. **Once there are 50-100 successful trails** — waypoints and
   shortcuts ([devlog: Waypoint + shortcut graph](2026-05-08-waypoint-shortcut-graph-vision.md))
   start being auto-discovered from the data. The agent learns the
   navigation graph of the application from observed paths, names the
   important screens, captures the routes between them. Humans review
   the discovered graph; they do not author it from scratch.

4. **Scaling endpoint** — the agent operates on a rich graph of:
   - Typed scripted tool primitives (mostly agent-extracted, human-audited)
   - Named waypoints + shortcuts (auto-discovered, human-verified)
   - Recorded selectors (LLM-resolved at record time, deterministic at replay)
   - Memory schemas (human-bootstrapped per trailmap, agent-leveraged everywhere)

   The framework's job at this point is to make the whole graph
   **inspectable and trustworthy to humans**, not editable by them.

The bet is that **the long-run cost curve of a Trailblaze suite bends
down because durable abstractions accumulate** — not because LLMs get
better (though they do). Even if the underlying model stays fixed, a
suite with 200 trails and 50 extracted scripted tools is dramatically
cheaper to extend than the same 200 trails authored without
abstractions. The compounding happens in the framework's typed
foundation, not in the model.

This is what the principle is protecting. Every framework decision that
weakens the typed foundation (in exchange for hand-author ergonomics
that nobody is asking for) erodes the compounding curve. Every
decision that strengthens it pays back as the trailmap grows.

## How the principle explains the calls we have made

The agent-emission optimization is the implicit logic behind many
recent design decisions. Making it explicit lets us audit each call:

| Decision | Agent lens | Human lens | Verdict |
|----------|-----------|------------|---------|
| Trail YAML as primary format | Agent emits YAML naturally — it's structured but forgiving | Humans diff, grep, review YAML easily | Both readings first-class |
| Typed `trailblaze.tool<I, O>(spec, handler)` (#3322, #3353) | Agent dispatches via tool name + schema; spec is short and inline | Humans get autocomplete, refactor, hover-doc, compile-time invariants | Both readings first-class |
| Selector grammar Kotlin-canonical, TS-derived (#3351) | Agent produces selectors via natural-language → resolver → typed shape | Humans audit drift via apiCheck + read the same generated TS surface IDE shows | Strengthens the read path |
| Per-trailmap `client.d.ts` codegen (#3329) | Agent sees stable tool names + schemas via runtime catalog | Humans see the same names in IDE autocomplete + hover | Both readings first-class |
| Workspace SDK at `.trailblaze/sdk/` (#3343) | Agent doesn't need to know about npm publishing | Humans `npm install`-bootstrap via postinstall, no ceremony | Optimizes for the bootstrap moment |
| `package.json` + postinstall bootstrap (#3349) | Agent never sees it — purely human-facing | Humans use the universal IDE bootstrap mechanism they already know | Pure human-lens optimization |
| `ctx.memory.set/get/...` flat surface with `setJson<T>` typed | Agent writes flat `set("orderId", "abc")` like any string-keyed store | Humans use typed helpers and `setJson<T>` for cross-tool state with editor-time typesafety | Both readings first-class |
| `config.memory:` flat seed map (accepted) | Agent would naturally write `{user: sam, password: hunter2}` flat | Humans write it the same way — no ceremony | Both readings first-class |
| `config.inputs:` typed declarations with required/enum/default (rejected) | Agent wouldn't bother with the formalism | Humans don't gain enough to justify the schema burden | Failed the agent-lens — dropped |
| `varKey<T>` typed handle pattern (deferred) | Agent doesn't need the indirection | Humans get marginal ergonomic gain over `setJson<T>` + free-function helpers | Doesn't pull enough weight — user-space pattern |
| Property-access sugar `ctx.memory.currentUser` (deferred) | Agent doesn't benefit | Humans get marginally nicer call sites at meaningful Proxy + codegen cost | Doesn't pull enough weight — deferred |
| Recorded selectors baked from LLM-driven authoring | Agent resolves selector at record time, freezes for replay | Humans can hand-edit the baked selector when it drifts | Replay is LLM-free; humans can fix at the margin |
| `rememberBySelector` + recording-rewrite hook (proposed) | Agent authors with natural-language `remember`; recording lowers to selector form | Humans see deterministic, replayable, debug-by-reading recordings | The keystone for the principle's deterministic-replay claim |
| Deletion of `rememberSensitive` dead code | Agent never reached for it | Humans never reached for it either (zero callers in the entire repo) | Pure deletion — no reader served |
| On-device MCP bundle subsystem deletion (#3344) | Agent didn't use it | Humans didn't use it; cross-language MCP framing was speculative | Pure deletion — no reader served |
| Meta-only YAML descriptors (#3353) | Agent emits the `.ts` file as a single semantic unit | Humans diff one file, not two; per-tool YAML collapses to `script:` marker | Both readings first-class |

The pattern is clear: features that serve **both** readers stay.
Features that serve **only the human** at meaningful framework cost
get deferred or rejected. Features that serve **only the agent** at
meaningful human-readability cost would also be deferred, though that
direction is less of a real failure mode in practice — agent-friendly
shapes are usually also human-friendly because they tend to be simpler.

## The design heuristic that falls out

When evaluating a new framework feature or surface:

- **For surface area (YAML schema, CLI flags, file shapes)** —
  optimize for the agent. If an LLM emits it cleanly, a human will read
  it cleanly. The reverse is not true: optimizing for "what a human
  ergonomically writes" often produces surfaces an LLM would not
  naturally produce (formal type declarations, required fields,
  complex defaults).

- **For implementation primitives (TypeScript APIs, Kotlin classes,
  internal data structures)** — optimize for the human. Typesafe,
  refactorable, IDE-friendly. The LLM does not read your `tool.ts`
  source — your humans do. Compile-time invariants and good naming
  here are pure human-lens wins.

- **For recordings** — optimize for replay determinism first, LLM
  diagnosis second. If a recording's failure can be diagnosed by
  reading it like a structured log, the LLM does not have to be
  re-engaged. The recording is the audit trail.

- **For documentation surfaces (devlogs, CLAUDE.md, README files)** —
  optimize for both. Humans read docs to onboard and debug; LLMs read
  docs (especially CLAUDE.md and inline kdoc) to author correctly. Each
  doc paragraph that helps one reader without confusing the other is
  worth keeping; each that confuses one to clarify the other should be
  rewritten.

Three quick tests to apply when sketching a feature:

1. **"Would an LLM author this naturally?"** If no, the framework is
   asking for ceremony that won't get paid back at scale.
2. **"Would a human reading the output understand what happened?"** If
   no, the framework is hiding behavior the human will need at debug
   time.
3. **"Does this feature serve both readers, or just one?"** If just
   one, what is the cost to the other reader, and is the served reader
   actually asking for it?

These tests are not new. They are the implicit logic that has been
shaping decisions in this codebase for months. Writing them down means
the next contributor (human or agent) can apply them consistently.

## The architectural commitment

The LLM is **never a required dependency at replay time, and always
an available tool**.

- **Recorded trails replay deterministically.** Zero LLM calls. No
  token cost. No model availability dependency. The recording is a
  resolved artifact — selectors are concrete, memory seed values are
  serialized, all decisions baked in.
- **Drift detection at CI time can re-engage the LLM.** A trail that
  starts failing because the UI changed can be opened with "LLM, look
  at this recording vs. the live UI — what's different?" The LLM is a
  diagnosis tool, not a per-step dependency.
- **Authoring is LLM-heavy by default but not mandatory.** A
  sufficiently motivated human can hand-author a trail YAML, or skip
  the YAML layer and write a pure-TS scripted-tool sequence. The
  framework does not break for them; it just is not the optimized
  case.

This puts Trailblaze in a distinct position vs. neighboring tools in
the AI-testing space:

- **Always-LLM tools** (every replay calls the model) — high quality at
  authoring, expensive and flaky at scale. Trailblaze is not this.
- **Never-LLM tools** (LLM at author time only, recording is a dumb
  artifact) — cheap and stable, but inflexible when the app changes.
  Trailblaze is not this either.
- **LLM-available-on-demand** (deterministic replay by default, LLM
  re-engaged when the framework or human asks for it) — Trailblaze's
  spot. The model is a tool the framework chooses to use when it
  needs to, not a tax it always pays.

That third position only works if the deterministic-replay path is
genuinely deterministic. The keystone work for this (`rememberBySelector`,
recording-rewrite hook, frozen selectors via the Kotlin-canonical
codegen) is what makes the commitment real rather than aspirational.

## Where the line is

Three positions, with decreasing strength of conviction:

### Strong conviction (this is what Trailblaze is)

- **Agent-authored trails as the primary mode.** YAML is something an
  agent writes. The framework's surfaces (file shapes, schemas,
  naming) are optimized for what an agent emits. If a human is writing
  YAML, they are tweaking what the agent produced, not authoring from
  scratch.
- **Typed scripted tools as the durable foundation.** Whether
  human-bootstrapped today or agent-extracted tomorrow, they are the
  building blocks the LLM leans on to scale. The framework treats them
  as first-class artifacts with type-safety, IDE support, and codegen.
- **Recordings as the deterministic-replay artifact.** LLM authors
  once; recording carries the resolved choices forward. No per-replay
  LLM cost in the steady state.
- **Humans read everything; humans rarely write trails.** The
  framework optimizes for the read path — diffability, hover-doc,
  grep-friendliness, log-style failure traces, structured recordings.

### Medium conviction (acceptable, not the optimized path)

- **Humans hand-editing scripted tools.** Will happen. It's fine. The
  framework supports it through typed APIs and good error messages.
  But the framework does not add features just to make this easier
  (e.g. no `varKey<T>` typed handles, no Proxy-based property access,
  no per-trailmap memory-shape codegen) when the cost is non-trivial and
  the gain is "humans like it better."
- **Humans hand-editing trail YAML to fix a broken step.** Will happen
  during the recording-rewrite-hook era. Framework supports it; the
  rewrite hook itself makes the case rare.
- **Agents extracting their own scripted tools from observed
  patterns.** This is the long-run direction. The framework hooks are
  not all in place yet, but the typed scripted-tool surface is what
  makes the extracted output reviewable.

### No conviction either way (tolerable, not optimized)

- **Someone using Trailblaze as a pure-TS test framework with no LLM
  and no YAML.** Could work. Would be a perfectly decent testing
  framework — arguably better than several existing options because
  the TS surface is genuinely good (typed tools, typed scripted-tool
  context, deterministic Maestro/Playwright dispatch underneath). We
  will not fight it, but we will not optimize for it. The agent-
  authoring layer is the actual differentiator; a user who walks
  the pure-TS path is using Trailblaze's primitives without its
  signature use case.

The line is **not** between "agent-driven" and "human-driven."
It is between **what Trailblaze is built to do brilliantly** (agent-
authored, deterministic-replayed, framework-extracted abstractions
over time) and **what falls out for free because the underlying
primitives are good** (pure-TS testing, hand-edited YAML, robot-
pattern composables, scripted tools used directly as a library).

## Waypoints, shortcuts, and trailheads as the navigation-layer expression

The same principle shapes the waypoint/shortcut architecture already
in flight ([devlog: Waypoint + shortcut graph vision](2026-05-08-waypoint-shortcut-graph-vision.md)).
Waypoints are named, assertable screen locations in an app. Shortcuts
are deterministic edges between waypoints. Trailheads are bootstrap
entries that get the agent to a known starting state.

In the agent-authored framing, these are:

- **Agent-discovered** from the corpus of successful trails. After
  enough recorded sessions, the framework can identify "this screen
  shows up in 47 trails; let's name it `cartScreen` and capture the
  view-hierarchy signature." That's a waypoint without anyone
  hand-authoring it.
- **Agent-leveraged** at authoring time. When the LLM is told "add an
  item to the cart, then verify the total," it can compose
  `goToWaypoint(cartScreen)` + `addItem` + `assertTotal`. The
  navigation graph short-circuits the agent's exploration; it doesn't
  have to re-derive the path through the app every time.
- **Human-reviewed** at the audit layer. The waypoint graph is visible,
  diffable, debuggable. Humans verify the auto-discovered names make
  sense, prune duplicates, add hand-curated waypoints for screens the
  corpus hasn't reached yet.

This is the principle's most ambitious expression. It says: not only
does the agent author trails today, the agent will eventually be
authoring the framework's own navigation primitives — and humans will
audit those just like they audit individual trails. The compounding
foundation grows itself.

## In-flight work the principle is steering

Two chips currently in flight are direct expressions of this principle
applied to the memory layer:

1. **`ctx.memory` primitive surface** (8 methods: 6 string primitives
   + `setJson<T>` / `getJson<T>`). Single namespace, sync API with
   transactional flush on tool return, dead-code deletion of unused
   `rememberSensitive`. Authoring is simple enough for an agent
   (`set("key", "val")`); typed access is rich enough for humans
   (`getJson<User>("currentUser")`).

2. **`--memory KEY=VAL` + `config.memory:` seeding** for trail input.
   Both surfaces use the same internal name (`memory`) — no
   `--var` / `--env` / `--input` alternative naming. Flat key-value
   only, no formal type declarations. CLI overrides YAML. Recording
   captures the resolved initial state so replay is self-contained.

Both chips were sized down from earlier proposals that included formal
typed declarations, runtime schema validation, and second namespaces.
The simpler shape that remains is the one that survives the "would an
LLM author this?" filter.

The keystone work this principle is most pointing toward, but not yet
chipped:

- **`rememberBySelector` + recording-rewrite hook.** Without it, the
  deterministic-replay claim is partially fictional — every
  `remember*` tool today round-trips through the LLM at replay time
  because the recording captures the natural-language prompt, not the
  resolved selector. Closing this gap is the credibility piece for the
  "LLM-free CI replay" pitch.
- **Trails-are-composable architectural layer.** Setup/teardown,
  saga checkpoints, cross-trail composition. All three are
  agent-friendly (the agent can author a composing trail that
  references a base trail by name) and human-friendly (the composition
  graph is visible at the YAML layer).

## What this rules out

A few framework directions that would have been on the table without
this principle in place:

- **A "Trailblaze IDE" plugin that auto-completes YAML.** Investing in
  Trailblaze-specific YAML completion for human authors is squarely
  the human-lens-only optimization the principle warns against. YAML
  comes from agents; the human reads it. The principle says: don't
  build the IDE plugin. Invest in agent-quality first.

- **A "no-AI mode" marketed as a feature.** Trailblaze can run without
  the LLM (recordings replay deterministically). But marketing or
  surfacing this as a primary mode would erode the agent-authored
  framing. The framework's value proposition is the agent-authoring
  layer; the deterministic-replay engine is what makes that layer
  trustworthy at scale.

- **Visual / drag-and-drop trail editors.** Same reason — these
  optimize for human authoring, which is not the optimized path. If
  the corpus demands it (a real user with real volume asks), revisit;
  otherwise, agent-emission optimization stays the focus.

- **Per-tool YAML schemas with required fields, enums, defaults.**
  The `_meta:` enrichment story (#3353)
  intentionally avoids this — the spec object on the typed scripted-
  tool is flat key-value-ish, descriptions live in TSDoc on the export
  binding, the analyzer extracts both. We will not be adding
  Avro / JSON-Schema-style declarations to the per-tool YAML surface.

- **Memory schemas with declared inputs / required / defaults blocks.**
  The `config.memory:` block is a flat map. Authors do not declare
  "this trail requires `username` and `password`." If a tool needs a
  value and it's absent, the tool throws at runtime. Anything more
  formal than this failed the "would an agent write this?" test.

## Open questions

A few things the principle does not resolve, intentionally:

1. **How far does agent extraction of scripted tools go?** Once the
   corpus is large enough, an agent could in principle author the
   trailmap's scripted tools entirely, with humans only auditing the
   output. We don't know what the right human-review interface looks
   like for that yet. The waypoint-graph viewer is a prototype of what
   review-of-agent-extracted-artifacts could look like; this likely
   needs a parallel for scripted tools as the extraction loop matures.

2. **When does it become worth investing in a pure-TS authoring
   experience?** The principle says "tolerable, not optimized."
   But if a customer with real volume shows up using Trailblaze as a
   pure-TS framework without the agent layer, the question becomes
   whether to lean into that or steer them back. The honest answer is
   probably "depends on the customer and the timing" — but the
   principle suggests defaulting to "steer back" unless the case for
   leaning in is concrete.

3. **What's the human-readable form of an agent-extracted abstraction?**
   When the agent extracts a recurring `addItemToCart` pattern, what
   gets written to disk? A typed scripted tool that humans review? A
   pure YAML snippet referenced from multiple trails? Both? The right
   answer is probably "the typed scripted tool with the human's
   audit/edit being the next step" — but the exact emission shape
   isn't designed yet.

4. **Does the principle apply equally to the trails themselves and to
   the framework's internal abstractions?** The devlog above mostly
   conflates the two ("trails are agent-authored, scripted tools are
   human-bootstrapped"). But there's a finer question: does the same
   principle apply to *framework code* (the Kotlin classes, the
   TypeScript SDK)? Probably not — framework code is human-authored,
   human-reviewed, machine-tested. The principle is about the
   **artifacts of using Trailblaze**, not about Trailblaze itself.
   Worth being clear about that boundary as the framework grows.

These are real open questions worth revisiting in 6-12 months when the
corpus is larger and the agent-extraction loop has more data to
operate on.

## Closing thought

The framing is asymmetric, the readers are asymmetric, the work
distribution is asymmetric. What balances the asymmetry is the
**framework's responsibility to both readers**: build for what the
agent emits, and make sure the human can always understand and audit
what got emitted. Neither reader works alone. The principle is the
contract between them.

This devlog formalizes what has been the implicit logic for months.
The shape of every framework decision under it is roughly the same:
**does this serve the agent at author time, and does it serve the
human at read time?** When both are yes, ship it. When only the agent
benefits at human-readability cost, hold the line. When only the
human benefits at framework-complexity cost, defer it to user-space
patterns.

That's the line we're drawing. Everything else follows.
