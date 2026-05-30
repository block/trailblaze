---
title: "Trailblaze Positioning: Developer-First AI Testing and the Trailmap Ecosystem"
type: decision
date: 2026-05-23
---

# Trailblaze Positioning: Developer-First AI Testing and the Trailmap Ecosystem

## Summary

Trailblaze is **developer-first AI testing**: natural-language test
specs that compile to editable cross-platform YAML, with
full-fidelity view-hierarchy logs that let agent harnesses
deterministically diagnose and fix tests without re-running them.
Tests live in your repo. There's a real coding surface — typed
scripted tools in TypeScript that the LLM can call as first-class
native tools. The natural-language layer is portable across
frameworks (your exit strategy), and trailmaps distribute crystallized
app-navigation expertise across iOS, Android, and web as a public
ecosystem of cross-platform Page Object Models for agents.

This devlog captures the corrected positioning after deep
discussion: where Trailblaze sits in the 2026 AI-testing landscape,
what the actual differentiators are, what we're explicitly NOT, and
what the trailmap ecosystem becomes when the framework's identity is
clear. It supersedes the earlier overclaiming version of this entry
that compared us to Gherkin and asserted novelty we don't have.

## The category we're in

AI-driven testing as a category is no longer speculative. As of 2026:

- **SaaS natural-language testing platforms** like revyl.ai, drizz,
  variata, mabl, Testim, Functionize, and Reflect.run pitch "AI
  agents author and run tests in plain English" with web-UI authoring
  and proprietary backends. Their audience is PMs, QA managers, and
  business stakeholders.
- **Code-first AI browser/computer agents** like Stagehand
  (Browserbase), browser-use, Skyvern, and Magnitude pitch
  programmatic primitives (`act`, `extract`, `observe`) wrapped
  around an LLM. Their audience is developers building automated
  workflows.
- **Cross-platform UI test frameworks** like Maestro pitch readable
  YAML + multi-platform support without agents at all. Audience:
  mobile-focused QA engineers and dev teams.
- **Traditional code-first test frameworks** like Espresso, XCUITest,
  and Playwright pitch maturity, stability, and full programmatic
  control. Audience: developers committed to their platform.
- **BDD/Gherkin/Cucumber** is in commercial decline (SmartBear
  divested Cucumber in Dec 2024; Tricentis killed SpecFlow the
  same month). The AI-testing wave has explicitly rejected the
  Gherkin shape.

Trailblaze sits at a specific intersection: **developer-first like
Stagehand/Espresso, agent-driven like the SaaS cohort, cross-platform
like Maestro, with editable compiled artifacts that nobody else has,
and full-fidelity view-hierarchy logs that nobody else captures.**

## The Maestro lesson — and our deliberate middle position

The Maestro founders tried pure natural-language testing several
years ago. They abandoned it because their QA-engineer customers
wanted to assert specific things ("verify *three* items are visible,
not just one") that the agent kept missing. The agent's blind spots
are real — it drops nuance in long sessions, misses one assertion
out of three, conflates similar elements. Re-running to coax better
behavior is expensive and frustrating. QA engineers ended up
hand-writing the specifics anyway, so the natural-language layer
became friction rather than abstraction.

Maestro responded by going deep on the coding surface: cross-platform
YAML, no natural-language layer, humans hand-author everything. That
was the right call given the constraints they faced.

The SaaS natural-language testing cohort (revyl, drizz, variata, mabl,
Reflect) made the opposite bet: stay pure-natural-language, hide all
code behind a web UI. When users hit the inevitable cases where
natural language isn't enough — mock a network call, seed test data,
custom assertion, conditional flow — they're forced to inject
JavaScript into the vendor's web IDE. They're coding anyway, just
in a worse environment: no type-checking, no refactoring, no git,
no PR review, no version control of the actual logic. The "no code"
pitch erodes the moment you hit a real test.

Both ends fail in distinct ways. Trailblaze's position is the
deliberate middle — structurally distinct from either:

- **Agent authors** the natural language and lowers it to compiled YAML
- **Compiled YAML is human-editable** for the assertions and actions
  the agent missed
- **Natural language stays as the portable source** of intent
- **Edits at either layer survive** — the source can be re-edited too
- **The coding surface is real**: typed scripted tools in TypeScript
  with type-checking, IDE support, git, and PR review

The workflow this enables for QA engineers (who, in the agentic era,
become more like "agentic engineers" — orchestrators of agents rather
than direct authors of code):

1. Agent records the trail — gets two of three assertions
2. QA engineer notices the third is missing
3. **QA engineer assigns the gap to an agent harness** (PR comment,
   ticket, Claude Code session) rather than coding it themselves
4. The harness reads Trailblaze's full-fidelity view-hierarchy logs,
   finds the relevant step, computes a selector for the missing item
5. The harness adds the assertion to the compiled YAML and proposes the
   change as a PR
6. The QA engineer reviews and merges — a human verification gate

This is the "surgical agent-driven test management" pattern. The
QA engineer doesn't code; they orchestrate agents and verify outcomes.
The framework provides the substrate — full-fidelity logs, typed
tools, deterministic replay — that makes the agent harness's job
tractable.

## Deterministic runs: speed + real failure signal

QA and developer teams expect deterministic runs for two reasons,
and both are first-class commitments of the framework:

**Speed.** A deterministic replay runs at the underlying driver's
speed (Maestro / Playwright / Axe). Sub-second per step. No LLM
round-trips at replay time means no token cost per run, no network
latency to a model provider, no model availability dependency, no
rate limits, and the CI matrix runs in minutes rather than tens of
minutes. For teams running tests on every commit, this is a real
order-of-magnitude difference.

**Real failure signal.** When a deterministic run fails, it's a
*real* failure — the app changed, a selector broke, an assertion
didn't hold. When an LLM-driven run fails, you can't tell: was it
an app issue or AI flake? This ambiguity destroys signal quality.
Teams end up ignoring intermittent failures or building heuristics
to dismiss them ("re-run twice; if it passes once, ignore"), which
masks real regressions.

Deterministic-by-default replay restores trust in CI failure signal.
A failing trail means something is actually wrong. A human or agent
investigates because the framework is asserting "this used to work,
something changed." That's the contract the framework keeps. The
opposite contract — "this might be wrong, or might be the AI being
flaky, you'll have to investigate either way" — is what teams running
pure-LLM tests live with, and it's why the SaaS NL-test cohort has
struggled to win developer trust.

This is the operational complement to the compiler model below: the
model captures *why* the framework is deterministic (compile once,
run many). The operational property is *why teams care* — they get
speed AND signal AND don't have to choose. Pure-LLM-at-replay can
match neither.

## Three tiers of replay — scope honesty as a feature

Not every step in a trail can or should be deterministic. The framework
has a deliberate scope boundary, and the explicit per-step opt-in to
LLM-at-replay is a feature, not a workaround. Three tiers:

### Tier 1 — Deterministic by default (the framework's primary path)

The vast majority of test operations are deterministic at replay
because they operate on data the view hierarchy already exposes:

- Taps, swipes, scrolls, typing — selector-resolved, frozen at record time
- View-hierarchy assertions (`assertVisibleBySelector`, `assertNotVisibleBySelector`)
- Parsed-value assertions over `{{var}}`-interpolated `assertMath` and `assertEquals`
- Cross-screen state continuity via `rememberText` + `assertEquals`
- Launch / lifecycle / app-control operations

These cover the 80-90% case for most trails. No LLM at replay, no token
cost, no flake.

### Tier 2 — LLM-bound by author choice (explicit opt-in)

Some assertions are intentionally LLM-bound because they require
semantic or visual reasoning the view hierarchy can't deliver. The
framework provides two mechanisms for explicit opt-in:

**Per-step `recordable: false` flag.** Tells the framework "this
step is always LLM-handled, never bake a recording for it." Useful
when the UI lacks stable selectors and the agent must blaze the step
every run. Visible in the trail YAML, surveyable via `trailblaze check`,
costed honestly.

**AI-driven assertion tools.** `assertWithAi`, `rememberWithAi`, and
similar are explicit LLM-at-replay tools. An author choosing
`assertWithAi("the submit button is green")` is acknowledging upfront
that this step requires the LLM at replay because color, visual
weight, layout, and similar properties aren't on the view hierarchy.
Same honesty as `recordable: false`, just at the tool granularity
rather than the step granularity.

The framework deliberately does NOT extend deterministic primitives
into the visual / pixel domain. Building deterministic color
extraction, screenshot diffing, OCR-based assertion, font-weight
verification, etc. would massively expand the framework's surface
area and create a maintenance burden on a category of features that
LLMs already handle well. We make the trade explicitly: the framework
owns view-hierarchy-based assertions; the LLM (via `assertWithAi`)
owns visual / semantic assertions; authors choose per step.

### Tier 3 — Custom extensions via typed scripted tools

When neither Tier 1 (deterministic view-hierarchy) nor Tier 2 (LLM
at replay) is the right answer, the framework's extension surface —
typed scripted tools in TypeScript — accepts arbitrary code. A team
that genuinely needs deterministic visual assertions can write a
typed tool that:

- Captures a screenshot via the framework's snapshot API
- Pipes it through `sharp` or `jimp` for color extraction
- Asserts against expected pixel values

…or hits a vision API, runs OCR, calls a custom backend that knows
the app's design tokens, whatever the team needs. The framework
doesn't bundle any of this — but the extension surface accepts it,
and tools written this way become first-class LLM-callable primitives
recordable just like any other tool invocation.

This three-tier structure is what makes the deterministic-replay
pitch honest rather than overclaimed. We don't say "everything is
deterministic" (it isn't, and saying so would erode trust). We say
"the framework provides deterministic primitives for the things
view hierarchy can resolve, explicit opt-in to LLM-at-replay for
the things it can't, and an extension surface for the cases where
your team wants custom deterministic logic the framework doesn't
ship." Authors see exactly which tier each step uses; cost and
behavior are predictable per step; the trail itself is the audit
trail of which tier was chosen where.

The SaaS NL-test cohort can't make this distinction visible — their
authoring surface hides the implementation tier. A user writing "the
submit button is green" in revyl/drizz/variata doesn't know whether
that assertion costs zero tokens, costs a model call per run, or
fails silently when the model can't tell. Trailblaze's three tiers
are surface-level visible. Tier 1 is the trail's recorded YAML; Tier
2 is `recordable: false` or an AI-tool name; Tier 3 is an imported
scripted tool. Authors and reviewers see the choice; CI runs reflect
it; cost projections work.

This isn't a limitation we're papering over. It's the operational
shape of the framework, and naming it makes the deterministic claim
credible where competitors' "AI does everything" pitch erodes the
first time a user asks "wait, how is that assertion actually run?"

## The compiler model

Internally we describe Trailblaze using the compiler-vs-shim framing
from [2026-05-11 — Why Trailblaze beats code generators](2026-05-11-code-generation-and-the-thin-shim-tradeoff.md).
Applied to test artifacts:

```
Natural-language trail = SOURCE (what you write, what you keep, what's portable)
                ↓
Agent + framework + drivers = COMPILER (LLM resolves NL → typed tool calls + selectors)
                ↓
Structured YAML recording = COMPILED OUTPUT (cache for fast deterministic replay)
                ↓
Replay (runs structured YAML; never invokes LLM)
```

Same shape as TypeScript → JavaScript or Markdown → HTML. The source
is the source of truth. The compiled output is what runs, and you can
regenerate it from the source whenever the source changes or the
compiled artifact goes stale.

The natural language never disappears at runtime — it stays alongside
the compiled YAML in the same trail file ([2026-05-22-trail-yaml-unified-syntax](2026-05-22-trail-yaml-unified-syntax.md)).
It's there for:

- Human readers debugging or onboarding to the test
- Agent harnesses doing re-compilation when the compiled form breaks
- The portability / exit-strategy guarantee (more below)

## What's actually distinctive (the combination)

Each of these lines has prior art *somewhere* in the testing or
AI-agent space. The combination, stacked together, is what's new:

### 1. TypeScript tools as first-class native LLM tools

When a trailmap author writes a typed scripted tool in TypeScript, that
tool becomes **first-class available to the LLM** at authoring time.
The LLM picks the right tool by description, calls it with typed
arguments, and the invocation is recorded as a YAML step. Authors
provide their *code* to the LLM as a callable primitive — not just
their natural-language hints or schema descriptions.

This is genuinely unique. The SaaS NL-test platforms hide code behind
their web UI; Stagehand's primitives are framework-fixed (`act`,
`extract`); Maestro has no agent-author path. Trailblaze is the only
framework where **your team's custom TypeScript tools become callable
agent primitives, recordable via the same tool invocation that the
agent chose**.

The implication: as your trailmap grows, **the agent gets faster and
more accurate at operating your app** because it has more high-level
primitives to compose. The agent isn't re-orchestrating taps from
scratch each session — it reaches for your team's `loginAsUser` or
`createOrder` tool and the multi-step expertise lives inside.

### 2. Higher-level tools with scripted code paths and conditionals

Trailblaze tools aren't limited to atomic primitives. A typed scripted
tool can contain:

- Conditional logic (`if (await isVisible(banner)) await dismissBanner()`)
- Loops and retries
- Memory reads and writes
- Composed sub-tool calls
- Real TypeScript control flow

This is **the reason we don't ship a Playwright code-gen export**.
A trailmap's high-level tools encapsulate flows that can't be easily
unrolled into Playwright primitive sequences without losing the
conditional/branching logic that's the actual value. Generating
Playwright code would flatten our higher-level primitives into a
soup of low-level actions, defeating the whole point of the trailmap
abstraction.

The trade-off: we're not portable to Playwright at the tool level —
but the natural-language trail is portable, and that's the level
where portability actually matters.

### 3. Full-fidelity runtime data capture (THE headline differentiator)

Every Trailblaze recording captures, per step:

- **The full view-hierarchy tree** of the screen at that moment
- **The tool call** that was invoked, with timestamps
- **Screenshots** for visual verification
- **Multi-strategy selector candidates** from the various locator
  approaches the framework tried
- **The agent's reasoning** (the natural-language step that produced
  the tool call)
- **Device logs** captured during execution — logcat on Android,
  Console on iOS, browser console for web
- **Optional: network traffic** if the team has instrumented capture
  (proxy, mitm, or app-level interceptor)
- **Optional: analytics events** if the team has instrumented capture
  (logged to disk during the run)

No other framework captures this combination at this fidelity. SaaS
NL-test platforms have logs and screenshots. Stagehand has cached
JSON. Maestro has YAML. Espresso and XCUITest have stack traces.
None of them have the per-step view-hierarchy tree keyed to tool
calls, selectors, natural-language intent, AND the device-emitted
runtime data flowing alongside.

**This is the strongest single technical differentiator we have**, and
it's downstream of an architectural decision that nobody else made:
log everything at full fidelity, even if the logs are large, because
the value of post-hoc analysis exceeds the cost of storage.

The runtime-data-capture angle is what makes Tier 3 custom tools
genuinely powerful. The framework captures the data; the author's
custom TypeScript tools analyze it. Concrete examples a team could
build:

```ts
// Custom tool: assert an analytics event fired during this step
export const assertAnalyticsFired = trailblaze.tool<{ event: string }>(
  async ({ event }, ctx) => {
    const analytics = await readAnalyticsLog(ctx.session.logsDir);
    if (!analytics.events.some(e => e.name === event)) {
      throw new Error(`Expected analytics event '${event}' was not fired`);
    }
  }
);

// Custom tool: assert a network call to a specific endpoint
export const assertNetworkCall = trailblaze.tool<{ urlPattern: string }>(
  async ({ urlPattern }, ctx) => {
    const traffic = await readNetworkLog(ctx.session.logsDir);
    const match = traffic.requests.find(r => r.url.match(urlPattern));
    if (!match) {
      throw new Error(`No request matching '${urlPattern}' was made`);
    }
  }
);

// Custom tool: assert backend state via direct DB query
export const assertOrderCreated = trailblaze.tool<{ orderId: string }>(
  async ({ orderId }, ctx) => {
    const row = await db.query("SELECT * FROM orders WHERE id = ?", orderId);
    if (!row) throw new Error(`Order ${orderId} not found in DB`);
  }
);
```

**The framework ships zero coordination primitives.** There's no
"test-shared state" feature, no built-in account-pooling, no
parallel-coordination DSL, no inter-trail synchronization. What
makes the extension surface practical for these problems is the
typed scripted tool surface itself: TypeScript code authored against
typed inputs and outputs, callable by the LLM via MCP /
function-tool-calling, runnable in two distinct runtimes depending
on what the tool needs.

**Runtime 1: in-process QuickJS (the default).** Faster, sandboxed,
no inter-process overhead. Tools that don't need filesystem access,
network calls, subprocess spawning, or the npm ecosystem run here.
Most custom tools fit this profile — anything that operates on
memory, on the captured runtime data the framework exposes via `ctx`,
or on pure logic. The vast majority of useful Tier 3 tools can be
written this way.

**Runtime 2: host subprocess.** When a tool needs full Node access
(filesystem, network calls, subprocess spawning, npm packages), it
runs as a host subprocess. The framework communicates with it via an
MCP-shaped transport — currently STDIO, but the transport choice is
an implementation detail. The LLM-facing shape is just "this is a
tool you can call;" the tool's author chooses the runtime by writing
code that needs (or doesn't need) Node-specific APIs.

Both runtimes expose tools to the LLM through the same MCP-style
function-calling surface. Trail YAML doesn't distinguish them. The
runtime split is about *what the tool needs at execution time*, not
*how the agent uses it*.

**Deployment flexibility: agent-on-host vs. agent-on-device.** A
real bonus of the QuickJS in-process runtime: on Android, the
Trailblaze agent can run on the device itself without needing a host
driving unit, as long as every tool in the trail is QuickJS-compatible
(no host-subprocess dependencies). The on-device agent can still call
LLMs over the device's own network — it's a "limited" mode in tool
availability (QuickJS-only), not in agent capability.

Critically, on-device mode produces **the exact same recordings and
artifacts** as agent-on-host mode. Same session log format, same
view-hierarchy snapshots, same tool-call traces, same screenshots.
A trail recorded on-device replays identically to one recorded with
a host driving unit. The deployment topology is invisible to the
recorded artifact. This makes on-device a genuine production
deployment — not a second-class or limited variant.

On-device is **particularly well-suited to deterministic replay**
and to "limited" agent runs (where the agent has a constrained tool
set and produces highly reproducible behavior). For CI fleets where
each emulator hosts its own agent, on-device dogfood loops on a real
phone, or exploratory testing where someone hands their device to a
QA engineer — the agent is literally inside the device under test,
no intervening host process, no network hop between agent and device,
no host machine required at all.

**Why this works on Android (and not iOS, yet).** The framework is
written in Kotlin, with deliberate constraints on which libraries
it depends on; TypeScript custom tools compile to JavaScript and run
in QuickJS, which has clean Android bindings. The whole stack runs
inside an Android process without modification. iOS doesn't have a
planned on-device mode — it would require Kotlin Multiplatform
compilation of the framework to native iOS plus QuickJS iOS bindings
plus a compatible driver layer. Technically possible, but iOS
simulators are always attached to a Mac host anyway, so the practical
value is low — the host is already there; using it costs nothing.
Android is the exception that gets the on-device mode because of
the Kotlin-everywhere alignment and the lower friction of running a
JVM-flavored stack on a physical device.

The trade-off is real for whichever mode a team picks: on-device
mode constrains the trail to QuickJS-compatible tools only. A test
that needs the host-subprocess runtime (to provision an account via
a backend API, to write coordination files to the CI host's disk)
requires agent-on-host mode. Authors choose deployment per trail
based on what tools the trail uses — and the recorded artifact is
the same shape either way.

What teams have built on top of this surface in real production
(both runtimes represented):

**Rate-limited login coordination.** One team's QA engineer hit a
rate-limited login endpoint that broke parallel test runs. Their
solution: two custom tools, sharing state via the local filesystem.
One test authenticates and stashes credentials; other parallel
trails wait for the file and reuse them. Filesystem access means
these run in the host-subprocess runtime (agent-on-host mode):

```ts
export const stashCredentials = trailblaze.tool<{ path: string }>(
  async ({ path }, ctx) => {
    await fs.writeFile(path, JSON.stringify({
      sessionToken: ctx.memory.get("sessionToken"),
      userId: ctx.memory.get("userId"),
    }));
  }
);

export const loadSharedCredentials = trailblaze.tool<{ path: string }>(
  async ({ path }, ctx) => {
    while (!await fileExists(path)) await sleep(100);
    const creds = JSON.parse(await fs.readFile(path, "utf-8"));
    ctx.memory.set("sessionToken", creds.sessionToken);
    ctx.memory.set("userId", creds.userId);
  }
);
```

The framework has no idea these tools coordinate. The framework just
runs them when the agent picks them. The team's implementation does
the coordination work entirely on its own.

**Backend account provisioning via custom tool.** A more compelling
production example: a team's trailmap ships a `create_coffee_shop_account`
tool that, when called, hits their backend's provisioning API,
creates a fresh test account, and returns the credentials. Network
calls mean this also runs in the host-subprocess runtime. The LLM
authoring a trail picks this tool naturally when the natural-language
intent is "set up a new merchant for this test":

```ts
export const create_coffee_shop_account = trailblaze.tool<
  { accountType?: "trial" | "paid" },
  { email: string; password: string; merchantId: string }
>(
  async ({ accountType = "trial" }, ctx) => {
    const response = await fetch(`${process.env.TEST_BACKEND}/accounts/provision`, {
      method: "POST",
      headers: { Authorization: `Bearer ${process.env.TEST_ADMIN_TOKEN}` },
      body: JSON.stringify({ type: "coffee_shop", accountType }),
    });
    const { email, password, merchantId } = await response.json();
    ctx.memory.set("email", email);
    ctx.memory.set("password", password);
    ctx.memory.set("merchantId", merchantId);
    return { email, password, merchantId };
  }
);
```

A trail that uses it reads like:

```yaml
trail:
  - "Set up a fresh coffee shop merchant for this test"
  # → LLM picks create_coffee_shop_account, framework records the result

  - "Sign in to the new account"
  # → uses {{email}} / {{password}} the previous tool wrote to memory

  - "Verify the merchant dashboard shows the new account"
```

What's happening here: the agent at authoring time picks the custom
provisioning tool because its TSDoc description matches the natural-
language intent. The tool runs as a host subprocess, hits the
backend, returns credentials. The framework records the invocation
plus the result. At replay, the same tool runs again — a fresh
account is provisioned for that test run, so the test gets a
guaranteed-clean starting state every time.

This pattern — agent-callable tools that integrate deeply with the
team's backend, design system, analytics, or auth — is the actual
shape of what Trailblaze enables. The framework's job is to make
these custom tools first-class LLM-callable and recordable. The
team's job is to write the tools that match their own infrastructure.

These are the kinds of tools nobody on the SaaS NL-test platforms can
write — because the platforms own the data, the platforms decide what
gets captured, the platforms charge per assertion type, and the
platforms don't give you filesystem access, arbitrary network
access, or the ability to call your own backend's provisioning
endpoints from inside a test. Trailblaze captures the substrate;
your team's custom tools do whatever analysis, coordination, or
integration you need.

The framework's job: capture rich runtime data, expose it via the
filesystem and the `ctx` API in scripted tools.

The author's job: write the assertion logic that makes sense for
their app, their analytics schema, their backend, their compliance
requirements.

This is the developer-first pitch made concrete. The framework doesn't
try to know what analytics events your app fires or what database
schema your orders live in. It captures everything observable; you
write the rest.

### 4. Cross-platform reach (iOS + Android + web)

Maestro is the only neighboring tool that's cross-platform at the
test-format level, and Maestro is mobile-only. Stagehand, browser-use,
Skyvern, Magnitude, Playwright, all SaaS NL-test platforms — all
web-only or web-first.

Trailblaze trailmaps cover iOS + Android + web under one waypoint graph
with per-platform driver blocks ([2026-05-22-trail-yaml-unified-syntax](2026-05-22-trail-yaml-unified-syntax.md)).
The natural-language layer is platform-agnostic; the compiled
selectors are per-platform. Same source, three platforms.

### 5. The trailmap ecosystem (cross-platform POMs for agents)

A Trailblaze trailmap is a **Page Object Model that's portable across
iOS, Android, and web, distributed as npm packages, consumed by an
agent at runtime, with the ability to be trained or extracted from
accumulated trail recordings**.

The closest prior art is [Salesforce UTAM](https://utam.dev) —
hierarchical UI page objects for Salesforce Lightning, published to
npm. UTAM proves the artifact format works at scale for one app
maintained by one vendor. The gap is generalizing to a public
ecosystem covering arbitrary apps on multiple platforms.

What's in a trailmap:

- **Waypoints** — named, assertable screens
- **Shortcuts** — deterministic edges between waypoints
- **Trailheads** — bootstrap entries to known starting states
- **Typed scripted tools** — composite primitives with scripted
  code paths
- **Recorded trails + selectors** — a corpus of empirically validated
  navigation paths

The architectural pattern is decades old (POM, or its Android-cultural
dialect "Robot Pattern"). What's new is the combination: cross-platform,
agent-consumed (not human-test-author consumed), corpus-trained, and
distributed as a public ecosystem.

### 6. Natural-language portability as exit strategy

The natural-language trail is **framework-agnostic by construction**.
If Trailblaze gets superseded by something better in 2027, your test
corpus migrates with low cost — the source is still useful, the
compiled artifacts become irrelevant.

This is the inverse of every classical test investment. Espresso,
XCUITest, Playwright, Cypress: your tests are locked to the framework.
Trailblaze: your natural-language trails outlive Trailblaze.

What makes this credible in practice is that **the trail YAML is
already a structured, documented format that any team can extract**.
The natural-language step descriptions, the per-step intent, the
config, the memory schema — all live in the YAML as named fields,
not in proprietary blobs. If a team wants their trail corpus in
markdown, plain English, Cucumber `.feature` files, or anything
else, a small script does the conversion. No `trailblaze export`
command needed; the data is already yours, in a structured form a
30-line script can transform.

This is the more honest version of the portability pitch:
**Trailblaze ships the framework. The conversion to other formats
is your script, not our feature.** That's a stronger commitment than
"we ship export tooling" because it doesn't depend on us building
or maintaining one-format-per-team. Anyone with read access to the
trail YAML can do whatever extraction they need.

What's preserved in this stance:

- **Your test corpus survives Trailblaze obsolescence.** The YAML is
  structured; the natural-language layer is recoverable; the data
  belongs to your repo, not to a SaaS account or a vendor-controlled
  format.
- **No lock-in via opaque format.** Compare to SaaS NL-test platforms
  where your test specs live in their database and you can't easily
  extract them.
- **No vendor dependence on export tooling.** Even if Trailblaze
  stopped being maintained tomorrow, the YAML files are still
  readable and convertible.

What's NOT in scope for the framework:

- A `trailblaze export` command (write your own script if you want this)
- A `trailblaze import` command (write your own importer if you have
  external markdown/text to ingest)
- Format-conversion tooling generally
- A browsable HTML corpus viewer (use any markdown-rendering tool
  against the natural-language step descriptions, or just `grep`)

This is a deliberate scope choice. We're a testing framework that
produces structured artifacts you own; we are not a documentation
generator, a docs-export tool, or a corpus-discovery UI.

### 7. Other differentiators worth naming

- **Type safety via TypeScript** with auto-generated bindings — no
  one else combines NL authoring with typed primitive surfaces
- **Composability** — typed scripted tools compose into trails,
  waypoints into shortcuts, trailmaps into other trailmaps
- **Agent-first design** — the framework is shaped around what an
  agent emits ([2026-05-23-agent-authored-human-readable](2026-05-23-agent-authored-human-readable.md))
- **CLI as the canonical surface** — no SaaS lock-in, no proprietary IDE
- **Multi-strategy selector generation** with human verification — no
  one offers selectable strategies + diff review

## The full-fidelity-logs winner — agents fixing tests without re-running

The full-fidelity view-hierarchy logs enable a workflow that nobody
else can match: **agent harnesses fixing tests without re-running them**.

The workflow:

1. A test fails (or needs an addition like "assert the third item")
2. A QA engineer assigns the gap to an agent harness — typically by
   commenting on a PR, opening a ticket, or starting a Claude Code
   session pointed at the trail
3. The agent harness reads Trailblaze's logs from the most recent run
4. From the view-hierarchy + tool calls + screenshots, the agent
   deterministically identifies the relevant step and the elements
   on screen
5. For **assertion gaps**: the agent computes a selector for the
   missing element and inserts the assertion into the compiled YAML
6. For **action gaps**: the agent uses the view-hierarchy to find what
   the original action likely meant and computes a corrected selector
   that should work on the next recorded run
7. The agent proposes the change as a PR
8. The QA engineer reviews, merges, and a re-run validates

This is most powerful for **assertions** because they don't change
state — adding an assertion to an existing recording is genuinely
safe, no re-record needed. For actions, the corrected selector is a
best-guess that the next recorded run validates.

The unique enabler is the full-fidelity log. Without view-hierarchy
data, the agent harness would have to actually run the test against
the live app to figure out what to change — expensive, slow, requires
the live app to be in a known state. With our logs, the harness can
work entirely from the historical recording.

**Headline talking point**: *"Agents fix Trailblaze tests without
re-running them. Full-fidelity view-hierarchy logs make the agent's
job deterministic — it finds the step, computes the selector, proposes
the fix, all without touching the live app."* Nobody else can credibly
say this.

## Self-heal: runtime LLM + agent-harness

There are two distinct self-heal modes the framework supports, and
they cover different failure profiles:

**Runtime self-heal** (in-process LLM). The agent kicks in *during*
the run when a selector fails. The LLM has only local context — the
current screen state and the failed selector. It can try to find a
candidate match from the live view hierarchy and proceed if
successful. Useful for transient flakes, network races, slight
selector drift. Limited by local context — it doesn't know the
historical record.

**Agent-harness self-heal** (post-failure recompilation). Runs *after*
the failure, not during. Reads the full historical corpus — this
trail's prior runs, sibling trails touching the same waypoints,
recent UI changes. Has time to reason without blocking the test
run. Proposes a corrected compiled artifact that gets human-reviewed
before landing.

Both modes are real. Runtime is cheap recovery for transient issues;
agent-harness is durable repair for real UI changes. The framework
ships with runtime self-heal built in. Agent-harness self-heal is an
external workflow (Claude Code, Cursor, internal scripts) that
leverages the framework's full-fidelity logs.

The "agents fix tests without re-running them" pitch is specifically
about the agent-harness mode, not runtime. That's where the
distinctive value lives.

## Natural-language source-of-truth: team's choice

Trailblaze does not dictate a stance on natural-language-as-source-
of-truth strictness. The spectrum of positions:

**Strict invalidation.** Any NL edit nukes the compiled recording;
full re-record cycle. Clean, expensive, doesn't scale with 5
platforms per test.

**Author judgment.** Author decides whether an NL edit is meaningful
enough to require re-recording. Cheap, drift risk. Where most teams
land in practice.

**Per-platform staged invalidation.** Edit NL once; recordings are
marked "potentially stale" but don't auto-invalidate. Re-recording
per-platform as needed. Realistic for multi-platform trailmaps.

**Agent-judged invalidation.** When NL changes, an agent harness
diffs old vs new NL + reads the compiled recording + reasons about
which platforms need re-record. Author reviews. Eventually agents
decide autonomously; today, human approval gate.

Different teams will pick different positions based on their
testing maturity, agentic-workflow investment, and platform coverage.
**Trailblaze doesn't require one stance.** The framework supports
all four with the same primitives (typed scripted tools, full-fidelity
logs, natural-language source). Pick the loop that fits your team.

We've used the threat of *wholesale re-recording* internally — when
framework underlying changes happen (driver migrations, selector
format changes), every compiled YAML may need regeneration. This
has happened a few times in practice and is genuinely slow because
human verification is required (the 3-assertion-problem repeats:
the agent might miss things on re-record).

The fundamental gap: **LLMs cannot 100% guarantee they produce
exactly what you wanted**. Verification loops in agentic workflows
are the long-term answer, but they're cost-prohibitive or
complexity-prohibitive for many teams today. The framework doesn't
bundle verification loops out of the box — they're an external
investment teams make as their agentic maturity grows.

## Where Trailblaze doesn't fit (scope honesty, part 2)

Trailblaze is not one solution to rule them all. There are real
tests that belong in framework-native runners and should stay there:

- **Tests that exercise framework-specific internals** — Compose
  preview rendering, SwiftUI snapshot diffs, React component unit
  tests with mocked hooks, Vue Vitest tests. These belong in the
  platform's native test framework because they need access to
  internals Trailblaze deliberately abstracts away.
- **Tests that require deterministic-by-default visual regression**
  — pixel-perfect snapshot suites where Tier 3 custom tools would
  recreate what Percy / Chromatic / Happo already do well. If
  visual-regression is the primary purpose, those tools are the
  right substrate.
- **Unit tests** — by definition, these exercise code in isolation
  without driving the UI. Trailblaze is for end-to-end flows that
  go through the actual app. JUnit / XCTest / Vitest for unit tests
  stays unchanged.
- **Performance benchmarks with strict reproducibility** — micro-
  benchmarks measuring "this function in 1ms" need controlled
  environments Trailblaze's device-driven layer can't provide.
  Different tool class.
- **Some complex cross-system integration tests** — when the test
  spans multiple apps, backends, third-party services, and timing
  matters per-microsecond, the orchestration cost may exceed what
  Trailblaze's abstractions are designed for. There are bespoke
  integration harnesses for these.

The line between "Trailblaze can do this" and "use something else"
is fuzzy and we haven't fully mapped it. The trade-offs to weigh
when a team is deciding:

| Question | If yes, Trailblaze fits | If no, consider alternatives |
|----------|-------------------------|------------------------------|
| Does the test drive the actual app UI? | Yes | Unit framework |
| Does the test span iOS + Android + web? | Yes — single source | Native framework per platform |
| Should the agent author the first draft? | Yes | Hand-authored framework |
| Is determinism at replay worth the framework investment? | Yes | Pure-LLM tool, or hand-authored |
| Does the assertion need pixel-perfect visual diff? | Tier 3 custom tool or third-party | Percy / Chromatic / Happo |
| Is the team developer-first? | Yes | SaaS NL-test for non-developer teams |

The honest framing: **Trailblaze is the right substrate for
end-to-end UI tests that span platforms, benefit from agent
authoring, and need deterministic replay**. That's a large category
but not everything. Naming the boundary is part of staying credible.

## What we explicitly are NOT

To stay disciplined about positioning:

- **Not a SaaS NL-test platform.** revyl, drizz, variata, mabl, Reflect,
  Testim, Functionize all target PMs and business stakeholders with
  web UIs and proprietary backends. We're developer-first, file-based,
  git-friendly.
- **Not a "no code" platform.** Code is first-class. Typed scripted
  tools are how teams scale agent performance. The SaaS "no code"
  pitch is a leaky abstraction that forces JS injection in a web IDE
  the moment you need real logic.
- **Not a Gherkin/BDD framework.** Cucumber and SpecFlow are in
  commercial decline (2024 was the year). The AI-testing wave has
  explicitly rejected the Gherkin shape. We never aligned with
  Gherkin and we're being transparent about that.
- **Not a Playwright competitor.** Playwright is excellent at what it
  does — code-first browser testing. We're cross-platform with an
  agent-author layer and a natural-language source. Different
  problem space, complementary in some setups.
- **Not a pure runtime-LLM agent.** Stagehand, browser-use, Skyvern,
  Magnitude all run the LLM at every step. We compile once at
  recording time and replay deterministically — meaningfully cheaper
  per replay.

## The strategic curated trailmap catalog

A public trailmap ecosystem requires curation. The strategic posture is
**opinionated:**

**Tier 1 — friendly commerce.** OpenAI Agentic Commerce Protocol
partners: Walmart, Target, Etsy, eBay (and follow-ons: Sephora,
Nordstrom, Lowe's, Best Buy, Home Depot, Wayfair). They want agent
traffic; ChatGPT referrals now drive ~20% of Walmart's traffic.
Shipping trailmaps here is welcome, not adversarial.

**Tier 2 — stable reference cluster.** Wikipedia, GitHub, ArXiv,
Hugging Face, Wolfram Alpha, Cambridge Dictionary, Allrecipes, BBC
News, Coursera. WebVoyager-anchored, neutral-to-friendly owners, low
UI churn. Easy to ship, gives benchmark credibility for free.

**Tier 3 — partner-friendly travel.** Booking.com, OpenTable,
Priceline, Uber, DoorDash, Instacart, StubHub, Hipcamp — all OpenAI
Operator launch partners.

**Explicitly avoided.** Amazon (actively suing automation companies
2025-2026), LinkedIn (most litigious automation defendant in Big
Tech), Reddit (sued Perplexity Oct 2025 under DMCA §1201), Twitter/X
(hostile + UI-churning). **The negative space is part of the
positioning.** Trailblaze ships trailmaps for apps whose owners want
agent traffic.

This isn't just brand positioning — it's the legal risk mitigation.
Curated friendly targets have near-zero legal exposure. Hostile
targets carry contributory-liability risk and ecosystem-positioning
damage that outweighs the demand.

## Connection to the agent-authored / human-readable principle

The principle pinned in [2026-05-23-agent-authored-human-readable](2026-05-23-agent-authored-human-readable.md)
is the precondition that makes the trailmap ecosystem viable:

- **Agent-authored means trailmaps scale.** Hand-authoring every trailmap
  would cap the ecosystem at maybe 50 before maintainer burnout.
  Agent-extraction from trail corpora makes 1000+ tractable.
- **Human-readable means trailmaps are trustable.** Community-published
  trailmaps need security review. If the trailmap format is agent-emitted but
  human-auditable by reading, malicious trailmaps get caught at review.

The principle and the ecosystem are the same architectural commitment,
viewed from different angles.

## What's in place

Substantial foundation has landed:

- **Trailmap manifest format** — `trailmap.yaml`, dependency declarations
- **Waypoints / shortcuts / trailheads** — YAML files with
  agent-driven authoring ([2026-05-08-waypoint-shortcut-graph-vision](2026-05-08-waypoint-shortcut-graph-vision.md))
- **Typed scripted tools** — PRs #3322, #3329, #3338, #3343, #3346,
  #3348, #3349, #3353
- **Selector grammar codegen + binary compatibility** (#3351)
- **npm distribution decision** ([2026-05-12-npm-distribution-for-trailmaps](2026-05-12-npm-distribution-for-trailmaps.md))
- **Per-trailmap `client.d.ts` + workspace SDK**
- **Unified trail YAML** ([2026-05-22-trail-yaml-unified-syntax](2026-05-22-trail-yaml-unified-syntax.md))
- **Full-fidelity view-hierarchy logging** — the keystone for the
  agent-harness self-heal story

## What's missing

The gap between "framework works" and "public trailmap ecosystem":

- **Trailmap auto-discovery from URL or app context.** Today users
  declare trailmaps explicitly. End-user-agent integration requires
  "this is wikipedia.com — is there a trailmap?" lookup.
- **Public trailmap registry.** npm covers distribution; need a
  Trailblaze-aware index with quality signals.
- **Trust and signing.** Trailmap ownership claims, malicious-trailmap defense.
- **Trailmap versioning + app-version pinning.** Compatibility ranges
  for evolving target apps.
- **Trailmap quality signal.** "This trailmap passes its self-tests against
  the live app each morning" health signal.
- **Cross-platform trailmap matrix.** `wikipedia` web vs.
  `wikipedia-android` vs. `wikipedia-ios` as distinct artifacts.
- **Agent integration protocol.** How trailmaps surface in the agent's
  tool catalog at runtime.
- **Recording corpus distribution.** Trailmap-with-corpus vs. trailmap-only —
  trade-off unresolved.
- **Verification-loop scaffolding.** Frameworks for "agent verifies
  what the previous agent did" — not framework-bundled but useful for
  teams to build on top.

## Open questions

1. **End-user agent integration vs. test-author tool — which framing
   leads?** Both will be true. Test-author is the safer bootstrap;
   end-user-agent is the category-defining ambition.
2. **Registry governance.** Centralized vs. federated. Org-affiliation
   verification.
3. **Trailmap-as-Anthropic-Skill?** Could trailmaps publish themselves as
   SKILL.md bundles in the Skills ecosystem? Specialization within
   that ecosystem rather than competition.
4. **NL-edit invalidation default.** Strict, author-judged,
   per-platform staged, or agent-judged? Team's choice today, but
   framework defaults shape behavior.
5. **Verification-loop tooling.** Should the framework ship
   verification-loop primitives (agent re-verifies what agent did),
   or leave that to external workflows?

## Closing thought

The work landed in the last six weeks — typed scripted tools, selector
codegen, per-trailmap `client.d.ts`, the agent-authored / human-readable
principle, npm distribution, unified trail YAML — looks like a series
of independent improvements. With the positioning articulated, they're
visibly one arc: **everything Trailblaze has been building toward is
the foundation for developer-first AI testing with portable
natural-language source, editable compiled artifacts, agent-fixable
tests via full-fidelity logs, and a public ecosystem of cross-platform
trailmaps.**

The combination is what's new. No single layer is unprecedented:
POM is old, natural-language test authoring exists in many forms,
npm distribution is universal, agent-driven testing has many
practitioners, view-hierarchy logging exists in many test frameworks.
What's new is **integrating these so the natural language is portable,
the compiled artifact is editable, agent harnesses can fix tests from
logs, and the trailmap is the unit of distribution across iOS, Android,
and web.**

The pitch worth committing to externally:

> *"Trailblaze is developer-first AI testing. Natural-language test
> specs that compile to editable cross-platform YAML. Full-fidelity
> view-hierarchy logs so agent harnesses fix tests without re-running
> them. Your code becomes first-class LLM tools that get more
> efficient as your trailmap grows. Tests live in your repo; the natural
> language is portable across frameworks; trailmaps distribute app
> expertise across the web and mobile. Built for engineering teams
> that want agentic flows without giving up code, IDE support, or
> their exit strategy."*

That's the framing. Every claim in it is defensible against the 2026
landscape. Every line maps to a real differentiator. None of it
overclaims novelty in a layer where prior art exists. The combination
is the value.
