---
title: "Trailmap-scoped tool naming"
type: decision
date: 2026-05-27
---

# Trailmap-scoped tool naming

## Summary

The 2026-04-26 trailmap-local-first decision named `<trailmap>:<local-name>` (e.g.
`gmail:click`) as the forward direction for tool naming. That syntactic form
isn't satisfiable on the OpenAI/MCP wire — function names are constrained to
`^[a-zA-Z0-9_-]{1,64}$`, which rejects `:`. This devlog maps the wire,
recording, MCP, typed-surface, and migration constraints onto three concrete
designs (wire-scoped, context-scoped, hybrid) and ends with a chosen direction
in the **Decision** section at the bottom.

**The Decision, in short:** trailmap ids must match `^[a-z][a-zA-Z0-9]*$`
(single token, lowerCamelCase — no underscores, no dashes). Tools owned by
trailmap X must be named `X_<localName>` — a **single underscore** as the
boundary, with the local-name half keeping the existing 2026-01-14 convention
regex. The framework `trailblaze` library trailmap is exempt; its primitives
(`tap`, `tapOnElementBySelector`, `mobile_*`, `android_*`, etc.) keep their
flat names so every existing recording continues to resolve unchanged. The
first underscore in a wire name is the trailmap-boundary marker —
unambiguous because the id regex forbids underscores in trailmap ids
(if both `foo` and `foo_bar` were legal ids, the wire name `foo_bar_X`
would split two different ways; pinning ids to a single token kills that
ambiguity by construction).

This chip ships **the advisory load-time check only.** The loader emits a
`[TrailmapScopingCheck]` warning when a trailmap violates either rule. No
exception, no behavior change. Strict enforcement, framework-primitive
migration, and any recording rewrites are deferred to later chips.

> **Note on the design-space walk below.** The Recommendation section and
> Axis B explore a **double-underscore** (`__`) form as a hybrid wire-scoped
> approach. That exploration is the path that led to the simpler
> single-underscore + framework-exempt answer in the Decision section.
> Where the Recommendation / Axis B and the Decision disagree, **the
> Decision section is authoritative.** The design-space sections are kept
> intact as a record of how the conclusion was reached, not as the rule.

## The constraint stack

Every option has to clear all five layers below; any design that fails one of
them is a non-starter.

### 1. Wire (OpenAI / MCP) — `^[a-zA-Z0-9_-]{1,64}$`

`:`, `.`, `/`, `@` — all rejected. Only `_` and `-` are available as
separator characters. The 64-character cap is the second half of the same
constraint and matters more than it looks: an existing flat name like
`org_payments_ios_someVeryLongToolName` is already in the 35–40 char range,
so a prefix budget of ~20 chars is realistic, not a comfortable 40.

### 2. Recording uniqueness

Every `.trail.yaml` under `trails/` and the
example workspaces records tool names verbatim. The
`contacts back-navigation recording` (`trails/ios-contacts/test-back-navigation/ios-iphone.trail.yaml:9-22`)
illustrates the shape: a list of `- <toolName>:` keys with arg objects. A
recording authored under trailmap `gmail` may call a framework tool (e.g.
`tapOnElementBySelector` lives in the framework trailmap, not in `gmail`),
so each step potentially crosses trailmap boundaries. Whatever scoping
mechanism we land on has to leave per-step replay unambiguous months later
in a possibly-different repo state, without the recording author needing to
know which trailmap a tool came from.

### 3. MCP `tools/list`

External MCP clients (Claude Desktop, etc.) call
`TrailblazeMcpServer.addToolsAsMcpToolsFromRegistry` (`trailblaze-server/src/main/java/xyz/block/trailblaze/logs/server/TrailblazeMcpServer.kt:574-606`)
and see whatever name `tool.descriptor.name` carries. There is no MCP
standard for namespaced tool names. Any scoping marker we introduce ships
to those clients as-is.

### 4. Per-trailmap typed surface

`PerTrailmapClientDtsEmitter` (`trailblaze-host/src/main/java/xyz/block/trailblaze/host/PerTrailmapClientDtsEmitter.kt`)
emits one `client.d.ts` per filesystem-backed trailmap. The emitted
`client.tools.<name>(...)` overloads are derived from this trailmap's OWN
`platforms.<p>.tool_sets:` declarations plus inherited `exports:` from deps.
A scoping mechanism must flow through this emitter coherently — both for
locally-owned tools and for transitively-inherited ones — and ideally not
force authors writing inside trailmap `gmail` to type `client.tools.gmail__click`
when `click` would do.

### 5. Existing flat tools

156+ `@TrailblazeToolClass(name = ...)` annotations exist today, plus every
recording under `trails/`. The convention is enforced by
`ToolNamingConventionTest` against the regex
`^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*)*$`
(see [2026-01-14 convention doc](2026-01-14-tool-naming-convention.md)).
A flag-day rename is unrealistic — too many recordings, too many call
sites, too many cross-checks (toolset YAMLs, recordings, scripted-tool
bindings, dependency-guard baselines, the convention test regex itself).
Whatever lands needs a staged adoption path.

## Design space

### Axis A — wire-scoped vs context-scoped vs hybrid

#### A1. Wire-scoped

The FQ name lives on the wire. `@TrailblazeToolClass(name = "gmail__click")`.
Every dispatcher, every recording, every MCP descriptor sees `gmail__click`.

What changes:

- `TrailblazeToolRepo.toolCallToTrailblazeTool` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeToolRepo.kt:257`)
  is unchanged structurally — still a one-arg `(name, contentJson)` lookup
  against three name-keyed maps. The map keys just carry a longer string.
- `JsScriptingCallbackDispatcher.dispatchCallTool` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/callback/JsScriptingCallbackDispatcher.kt:215`)
  unchanged — `action.toolName` is already an opaque string.
- `.trail.yaml` recordings keep their shape from
  `ios-contacts test-back-navigation` (`trails/ios-contacts/test-back-navigation/ios-iphone.trail.yaml:9-22`)
  — only the keys change (`- contacts_ios_openApp:` becomes
  `- contacts__ios_openApp:` once `contacts` adopts the scope marker).
- `PerTrailmapClientDtsEmitter.emit` (`trailblaze-host/src/main/java/xyz/block/trailblaze/host/PerTrailmapClientDtsEmitter.kt:81`)
  emits `client.tools.gmail__click(...)` — verbose-but-correct. Authors
  writing inside the `gmail` trailmap pay the verbosity tax on every line.
- MCP `tools/list` advertises FQ names. LLM-token cost grows by roughly
  one `<trailmap>__` prefix per descriptor (10–20 tokens × ~30 tools ≈
  300–600 extra tokens per session — measurable but trivial vs. trail
  content).
- `@TrailblazeToolClass(name = ...)` becomes FQ. Source-of-truth stays
  the class annotation, the value string just gets longer.

Cost: author ergonomics inside a trailmap, 64-char headroom on long
prefixes.

Benefit: minimal runtime change, no envelope plumbing, recording semantics
remain trivially unambiguous.

#### A2. Context-scoped

The wire stays local (`click`). A sibling `trailmap:` field (or path-based
inference) supplies the scope.

What changes:

- `TrailblazeToolRepo` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeToolRepo.kt:57-105`)
  swaps every `Map<ToolName, ...>` for either
  `Map<Pair<Trailmap, ToolName>, ...>` or a nested `Map<Trailmap, Map<ToolName, ...>>`.
  Every snapshot, every lookup, every collision check has to thread the
  trailmap key through. Net change touches ~20 methods.
- `JsScriptingCallbackAction.CallTool` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/scripting/callback/JsScriptingCallbackDispatcher.kt:50`)
  gains a `trailmap: String` field. Both transports
  (HTTP `/scripting/callback` and the on-device QuickJS binding) have to
  thread it through. `entry.toolRepo.toolCallToTrailblazeTool(name, args)`
  becomes a 3-arg call.
- `.trail.yaml` recording format gets either an outer envelope:
  ```yaml
  recording:
    trailmap: contacts
    tools:
    - openApp: { ... }
    - tapOnElementBySelector: { ... }  # ← but THIS is from the framework trailmap, not contacts
  ```
  or a per-step `trailmap:` annotation on every tool entry. The framework
  scenario (a `contacts` recording calling framework `tapOnElementBySelector`)
  forces per-step trailmap annotation or a fallback-resolver — neither is
  pretty. Inferring from directory path is brittle (consumer trailmaps
  legitimately re-record framework tools as steps).
- `PerTrailmapClientDtsEmitter` (`trailblaze-host/src/main/java/xyz/block/trailblaze/host/PerTrailmapClientDtsEmitter.kt:118-132`)
  emits `client.tools.click(...)` for trailmap-local tools. The
  collision-throw at lines 437-456 already prevents two deps from
  exporting the same name into one consumer's typed surface; context
  scoping doesn't relax that — it just means the FQ form never appears in
  the user-facing surface.
- MCP `tools/list`: there's no MCP standard for trailmap context. We'd be
  inventing a `_meta` field or hidden parameter; downstream clients ignore
  it. The agent toolbox would have to disambiguate purely through
  closest-wins surface curation.

Cost: invasive change across every dispatch path, recording format change,
MCP semantics drift.

Benefit: shorter wire names, slightly lower LLM token cost.

#### A3. Hybrid

Wire is flat (FQ names exactly as in A1: `gmail__click`). The
author-facing form inside the per-trailmap typed `client.d.ts` is a
namespaced literal, e.g.
`client.tools.gmail.click(...)` — TypeScript's nested-object overloads
translate to a `client.callTool("gmail__click", args)` call under the
hood. The translation lives in the codegen, not the runtime.

What changes:

- TrailblazeToolRepo, JsScriptingCallbackDispatcher, MCP `tools/list`:
  unchanged from A1 (everything keys by the FQ string).
- `.trail.yaml` recordings: same as A1 (FQ names on disk; the recording
  format itself doesn't change shape).
- `PerTrailmapClientDtsEmitter` (`trailblaze-host/src/main/java/xyz/block/trailblaze/host/PerTrailmapClientDtsEmitter.kt:81`)
  + `WorkspaceClientDtsGenerator` (`trailblaze-trailmap-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/WorkspaceClientDtsGenerator.kt`)
  grow a two-shape emit:
  1. The flat overload (`client.tools.gmail__click(...)`) — keeps the
     callsite-grep story working.
  2. The namespaced overload (`client.tools.gmail.click(...)`) — sugar.
  Both lower to `client.callTool("gmail__click", ...)`.
- `@TrailblazeToolClass(name = ...)` carries the FQ value. Same as A1.

Cost: codegen does slightly more work; the d.ts file gets bigger.

Benefit: A1's runtime simplicity + a nicer author surface for the common
case (calling tools owned by your own trailmap). The cost lives in the
emitter, not the dispatcher.

### Axis B — separator choice (assuming A1 or A3)

> **Superseded by the Decision section.** Axis B picks `__` (double
> underscore) as the wire-scoping marker. The Decision section ends up at
> a **single underscore** instead, made unambiguous by pinning trailmap
> ids to a single token (no underscores allowed). The discussion below
> reflects the original Axis B exploration; the actual rule is in the
> Decision section.

Only `_` and `-` are wire-legal. The realistic candidates:

- **`__` (double underscore)** — visually distinct, easy to detect
  (`name.indexOf("__")`), aesthetically reminiscent of Python dunder. No
  collision with existing names (the current convention regex
  `^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*)*$` already forbids consecutive
  underscores, so no flat tool today happens to look scoped). **Picked.**
- **`-` (single dash)** — distinct character class. Fatal flaw: TS
  identifier rules forbid `-` in property names, so
  `client.tools.gmail-click(...)` doesn't compile — you'd have to write
  `client.tools["gmail-click"](...)`, losing the dot-syntax that's most of
  the typed surface's ergonomic value. Reject.
- **`_x_` (sentinel-bracketed)** — visually ugly, adds three chars instead
  of two to the 64-char budget, no compelling benefit. Reject.
- **Reuse `_` with positional convention** — the status quo. Resolver
  can't reliably split (`org_ios_foo` could be trailmap `org_ios` + tool
  `foo`, or trailmap `org` + tool `ios_foo`, or unscoped `org_ios_foo`).
  Reject.

`__` is the only realistic wire-legal scoping marker. **Detection
becomes**: `name.contains("__")` ⇒ scoped, otherwise legacy flat.

### Axis C — recording-uniqueness mechanism

Under A1/A3, recordings carry the FQ name verbatim. The current shape
shown in `trails/ios-contacts/test-back-navigation/ios-iphone.trail.yaml:9-22`
needs no envelope change — the tool key just becomes the FQ form. Replay
months later resolves through the same one-step lookup as today.

Under A2, the recording envelope changes (per above), and forbidding
same-name tools across trailmaps would defeat the purpose. **A1/A3 wins
on this axis.**

### Axis D — cross-trailmap tool reach

The three-layer typing model from the
[2026-05-08 library-vs-target devlog](2026-05-08-library-vs-target-packs.md)
and the
[pack-typing three-layer memory](https://example.invalid/local-only)
already gives us the rule: a trailmap can call a tool from a dep iff that
dep declares the tool name in its `exports:` list.

Under A3, the codegen renders deps' exports under their owning trailmap's
namespace: `client.tools.wikipedia.search(...)` from inside the `gmail`
trailmap, as long as `gmail` lists `wikipedia` in `dependencies:` and
`wikipedia` lists `search` in `exports:`. The translation to the FQ wire
name (`wikipedia__search`) is mechanical and lives in the
`WorkspaceClientDtsGenerator` (`trailblaze-trailmap-bundler/src/main/kotlin/xyz/block/trailblaze/bundle/WorkspaceClientDtsGenerator.kt`)
.

The framework trailmap (`trailblaze`) is special — its tools are unscoped
today (`tap`, `tapOnElementBySelector`, etc.). Two options:
1. Leave the framework trailmap unscoped (no `trailblaze__` prefix on
   framework tools). The resolver detects an unscoped name and looks it
   up in the global flat registry as today. This keeps the framework
   surface backward-compatible with every existing recording.
2. Scope the framework trailmap too (`trailblaze__tap`). Cleaner but
   forces the recording rewrite to touch every recording for every tool,
   not just trailmap-owned ones.

Option 1 is the lower-cost adoption path. The framework trailmap's
identity in the namespace is "no prefix" — i.e. unscoped names are
implicitly framework-owned.

### Axis E — LLM-facing presentation

Under A1/A3, the LLM sees FQ names on the wire (`gmail__click`, etc.). Cost:

- **Token cost.** Each FQ prefix adds ~3-6 tokens per tool descriptor.
  For an agent with 30 tools, that's 90–180 extra tokens once per session
  — well within noise vs. trail content size.
- **Collisions.** The wire is globally unique by construction. The
  agent-toolbox composition rule (closest-wins on
  `TrailblazeToolSetCatalog.resolve` (`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeToolSetCatalog.kt`))
  still does its existing job of trimming what surfaces.
- **64-char limit.** Real concern. A name like
  `org_payments_ios_someVeryLongToolName` is already in the 35–40 char
  range. Adding a 10-char trailmap prefix takes it to 50, leaving 14
  chars before truncation. **Mitigation**: at the moment we rebrand an
  `org_*` prefix to a trailmap, we should drop the redundant prefix in
  the local name — `mytrailmap__someVeryLongToolName` rather than
  `mytrailmap__org_payments_ios_someVeryLongToolName`. The whole point of
  trailmap scoping is to retire the `org_*` / `app_*` / `*_ios_*`
  conventions of the
  [2026-01-14 naming doc](2026-01-14-tool-naming-convention.md) in favor
  of the trailmap prefix as the canonical scope. Tools that don't get a
  shorter local name on the rebrand cycle are stuck at the limit and
  should be flagged at the convention test.

### Axis F — migration path

Three approaches, in increasing risk:

#### F1. Per-trailmap opt-in (recommended)

A trailmap manifest gains a boolean flag (or implicit "id is the prefix"
rule) that says "tools owned by this trailmap register under
`<trailmap>__<localName>`." When `gmail` flips the flag, every tool YAML
under `trailmaps/gmail/tools/` and every
`@TrailblazeToolClass(name = ...)` annotation owned by `gmail` are
mechanically rewritten at the same time. Every recording that calls
those tools is mechanically rewritten in the same change. The convention
test is updated to accept both the legacy regex AND the new scoped form;
when every trailmap has adopted, the legacy branch is removed.

The framework trailmap stays unscoped (Axis D option 1) so existing
recordings continue to resolve unchanged.

This matches the pattern from the
[2026-04-27 pack manifest v1 devlog](2026-04-27-pack-manifest-v1.md) —
trailmap-by-trailmap adoption with the flat path as a transitional
fallback.

#### F2. Resolver fallback

For any unscoped lookup, search the global flat registry (current
behavior). For scoped lookups, parse `__` and search the per-trailmap
registry. Existing recordings keep working forever; new tools register
only under scoped form. Lower urgency but leaves the door open for
indefinite drift.

#### F3. Dual-name period

Every existing tool gets both a flat name AND a scoped alias for one
release. After the release, the flat name is removed. Aggressive — works
if every recording is rewritten in lockstep but leaves a window where
two names address the same tool, which the resolver-collision guards
already reject (see
`TrailblazeToolRepo.addDynamicTools` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeToolRepo.kt:170-193`)
).

**F1 + the framework-stays-flat rule** is the lowest-friction path. F2 is
fine as a side-effect of F1 but should not become the long-term story.

### Axis G — out-of-band issues surfaced

#### G1. `Map<String, V>` guard (PR #3430)

`TrailblazeKoogToolExt.asToolType` (`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeKoogToolExt.kt:76`)
throws on `Map<String, V>` parameters; the LLM-facing
`toKoogToolDescriptor` (`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeKoogToolExt.kt:154`)
gates the throw with the `surfaceToLlm` check first; the
scripted-tool-facing
`toScriptedToolDescriptor` (`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeKoogToolExt.kt:172`)
catches and logs. **Scoping is orthogonal**: the guard inspects parameter
shapes, not tool names. The only intersection is that under A3 the
generated namespaced d.ts entries lower through the same emitter as the
flat ones — both paths skip a tool that fails `asToolType`. No
additional design coupling.

#### G2. Playwright tool-shim codegen (deferred — see [2026-05-26 devlog](2026-05-26-playwright-tool-shim-codegen.md))

That codegen produces `web_<Page-method>` tool names today. Under wire-
scoping with the framework-stays-flat rule (Axis F1), Playwright-emitted
shims would live in the framework trailmap and keep their unscoped form.
If we ever decide the Playwright integration deserves its own trailmap,
the codegen's allowlist file is a single point of edit. Compatible with
A3 without further work — the codegen owns the names, switching their
prefix is mechanical.

#### G3. 64-character limit

Already covered in Axis E. Recommendation: at scoped-adoption time, drop
redundant prefixes from the local name. Surface a CI failure when a
tool's FQ name exceeds 60 chars (4-char safety margin) — this gives
authors actionable feedback the moment they cross the threshold rather
than at a Koog/MCP error later.

#### G4. The `@TrailblazeToolClass(name = ...)` source-of-truth question

Stays the class annotation, but its value becomes the FQ form. The
implicit-from-directory-layout rule from the
[2026-04-26 local-first devlog](2026-04-26-target-packs-local-first.md)
("`tools/inputText.yaml` implicitly belongs to the `gmail` trailmap
because it lives under that trailmap's root") would naturally extend
here: a tool annotated `name = "gmail__click"` that lives under
`trailmaps/gmail/tools/` is self-consistent; one annotated
`name = "gmail__click"` that lives under `trailmaps/wikipedia/tools/` is
a bug a load-time check should catch. Add that check at trailmap
discovery time.

## Recommendation

> **Superseded by the Decision section.** This section is the original RFC
> recommendation (hybrid A3 + `__` separator). The Decision section ends up
> at a simpler answer (single-underscore + framework-exempt). The
> Recommendation below reflects how the RFC originally landed; the actual
> rule is in the Decision section.

- **Axis A — wire shape:** A3 (hybrid). FQ on the wire, namespaced sugar
  in the per-trailmap typed `client.d.ts`. Same runtime behavior as A1,
  better author ergonomics.
- **Axis B — separator:** `__` (double underscore). The only wire-legal,
  TS-identifier-safe option that doesn't collide with the existing
  underscore convention.
- **Axis F — migration:** F1, per-trailmap opt-in. The framework trailmap
  stays unscoped during the transition (Axis D option 1) so existing
  recordings continue to resolve unchanged. Per-trailmap rewrites are
  mechanical (annotation + tool YAMLs + recordings in lockstep).

This combination:
- Keeps `TrailblazeToolRepo`, `JsScriptingCallbackDispatcher`, and
  `TrailblazeMcpServer` essentially unchanged.
- Keeps the recording format unchanged in shape — only the tool keys
  get longer.
- Gives scripted-tool authors `client.tools.gmail.click(...)` inside the
  `gmail` trailmap and `client.tools.wikipedia.search(...)` for inherited
  exports — without a runtime translation layer.
- Lets trailmaps migrate one at a time, behind a flag, with the
  framework surface as a fixed point.

## Migration sketch

The high-level steps (intentionally not implementation-detailed — that's
the next chip's job):

1. **Add an opt-in flag to the trailmap manifest** (working name:
   `scoped_tool_names: true`). Default is `false` for now.
2. **Extend the convention regex** to accept the scoped form. Update
   `ToolNamingConventionTest` to recognize `<trailmap>__<localName>`,
   gated by the trailmap manifest's flag — a trailmap that hasn't opted
   in is still held to the legacy regex; one that has must have every
   owned tool's FQ name match `^<trailmapId>__<localNameRegex>$`.
3. **Add a 60-char ceiling** to that test, with a clear "rename the local
   half" hint when crossed.
4. **Extend the per-trailmap dts emitter** to emit both the flat
   `client.tools.gmail__click(...)` and the namespaced
   `client.tools.gmail.click(...)` overloads when scoped names are
   detected.
5. **Pick the first trailmap to adopt** (probably one of the framework
   bundled targets — `clock` or `wikipedia` — small surface, easy to
   verify end-to-end). Rewrite its tool annotations + tool YAMLs + every
   recording that calls one of its tools, in a single PR.
6. **Resolve through the existing one-step lookup.** No resolver change
   is required — the FQ name is just a string. The
   `trailblazeToolClassAnnotation` (`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeToolExt.kt:9-11`)
   chain already keys class lookups off the annotation `name` value.
7. **Add a load-time check** at trailmap discovery: a tool owned by
   trailmap X must have `name = "X__<localName>"`. Reject otherwise.
8. **Once every trailmap has adopted** (and only then), retire the
   legacy-regex branch of the convention test and the
   `scoped_tool_names:` flag. The framework trailmap stays unscoped
   permanently as the "framework primitives" namespace.

## Open questions

1. **Should the framework trailmap stay unscoped forever?** Axis D option
   1 says yes (lowest migration cost). Option 2 (`trailblaze__tap`) is
   cleaner but forces every recording in the repo to be rewritten, not
   just trailmap-owned ones. Pre-decision: **option 1** unless someone
   raises a concrete reason for option 2.
2. **Is `__` the right separator, or should we look harder at one of the
   weirder options?** The double-underscore was chosen by elimination —
   it's the only viable one, not the only conceivable one. A reviewer
   who hates dunder aesthetics has a vote here, but the alternatives lose
   on substantive grounds (Axis B).
3. **Where does the scoped-form check live?** At `@TrailblazeToolClass`
   discovery (e.g. inside
   `TrailblazeSerializationInitializer` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/logs/client/TrailblazeSerializationInitializer.kt`)
   ) or at trailmap-manifest load (inside
   `TrailblazeTrailmapManifestLoader` (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/TrailblazeTrailmapManifestLoader.kt`)
   )? The former is closer to the source-of-truth annotation; the latter
   is closer to the trailmap-ownership concept. Probably both, with the
   error message tuned to point at the offender from whichever direction
   the developer enters from.
4. **What happens to LLM token cost for big trailmap closures?** The
   estimate above (~150–300 extra tokens) is for a typical agent with
   ~30 tools. A trailmap closure with 100+ tools (consumer trailmaps
   that compose framework + a couple of libraries + their own surface)
   could push that to ~1k. Worth measuring against a representative
   session before locking in.
5. **How does this interact with the recently-deferred Playwright
   shim codegen ([2026-05-26 devlog](2026-05-26-playwright-tool-shim-codegen.md))?**
   The codegen owns name emission for the `web_*` surface. Under the
   framework-stays-unscoped rule (Axis D option 1), nothing changes —
   Playwright tools keep their `web_*` form. If we ever move Playwright
   to its own trailmap, the change is a one-line allowlist tweak in the
   codegen.
6. **Cross-trailmap collisions inside the typed-surface namespace.**
   Today's
   `PerTrailmapClientDtsEmitter` (`trailblaze-host/src/main/java/xyz/block/trailblaze/host/PerTrailmapClientDtsEmitter.kt:437-456`)
   throws on two deps exporting the same name into one consumer. Under
   the namespaced d.ts (A3), this becomes
   `client.tools.depA.foo(...)` vs `client.tools.depB.foo(...)` —
   physically distinct in TS. We could either keep the throw (stay
   strict, no semantic ambiguity) or relax it (deps' names live in their
   own sub-namespace, no collision possible). Probably **keep the
   throw** — relaxing it loses the "one tool per consumer surface"
   invariant that downstream code can rely on.

## Decision

**Trailmap ids must match `^[a-z][a-zA-Z0-9]*$`.** Single token,
lowerCamelCase for multi-word — no underscores, no dashes. The
underscore restriction is the load-bearing part: it makes the wire name
`<trailmapId>_<localName>` deterministically parseable as a single
boundary at the first underscore. If both `foo` and `foo_bar` were
legal ids, then a wire name `foo_bar_X` could split either as
trailmap `foo_bar` + tool `X` or trailmap `foo` + tool `bar_X`, and
the resolver would have to disambiguate from context the recording
file no longer carries. Pinning ids to a single lowerCamelCase token
removes that ambiguity by construction. Existing bundled trailmap ids
(`trailblaze`, `clock`, `wikipedia`, `contacts`, `calendar`,
`sampleapp`, `playwrightsample`) already comply.

**Tools owned by trailmap X must be named `X_<localName>`.** The
local-name half keeps the existing
`^[a-z][a-zA-Z0-9]*(_[a-z][a-zA-Z0-9]*)*$` per-segment shape from the
2026-01-14 convention doc, just shifted one position to the right.
This is the wire-scoped form (Axis A1 above) — a single underscore as
the boundary rather than the `__` double underscore the RFC originally
sketched. The single-underscore form costs the parser a single
deterministic split (first `_`) and keeps recording keys exactly as
they look today (no visual double-underscore noise). The collision
class flagged for `__` (existing flat tool happens to look scoped)
doesn't apply once we enforce the trailmap-id regex above — `foo_bar_X`
is unambiguously `<trailmap foo>_<tool bar_X>` because the only
trailmap id that could match the wire prefix is `foo` (per rule one,
no underscores in ids).

**The framework `trailblaze` library trailmap is exempt.** Its
primitives — `tap`, `tapOnElementBySelector`, `web_evaluate`,
`findMatches`, `maestro`, `script`, `sharpUtility`,
`swipeWithRelativeCoordinates`, `assertVisibleBySelector`,
`assertNotVisibleWithText`, `mobile_*`, `android_*` — keep their flat
names. Any rule that forced renaming these would force rewriting every
`.trail.yaml` recording in the repo, which is a much bigger swing
than the trailmap-scoping payoff warrants. The framework trailmap is
the implicit "unscoped" namespace; a wire name with no `<trailmapId>_`
prefix resolves into it via the existing flat registry. This matches
Axis D option 1 from the design space above.

**This chip ships the advisory load-time check only.** At trailmap
manifest load time, for each non-framework trailmap, the loader emits
a `[TrailmapScopingCheck]` warning via `Console.log` if (a) the
trailmap id doesn't match `^[a-z][a-zA-Z0-9]*$`, or (b) any tool name
listed in `target.tools:` doesn't start with `<trailmapId>_`. The
warning is dedup-keyed per `<trailmap id>@<source identifier>` (same
pattern as the existing removed-field warnings) so two loads of the
same manifest don't double-fire. The framework `trailblaze` trailmap
is skipped entirely. **No exception, no behavior change** — authors
who hit the warning learn about the convention without having any
work blocked.

**Strict enforcement, framework migration, and recording rewrites are
out of scope for this chip.** A later chip flips the warning into a
hard `TrailblazeProjectConfigException`. A separate chip handles the
question of whether to leave framework primitives in the `trailblaze`
library trailmap forever or migrate selected ones (e.g.
`assertVisibleBySelector`) into per-target trailmaps. The recording
rewrite implied by either of those is its own multi-cycle effort. The
present chip just makes the rule visible at the point where authors
add a new trailmap or a new tool — earliest signal, lowest cost.
