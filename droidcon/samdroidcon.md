---
theme: seriph
title: 'Trailblaze: Map Your App for AI'
info: |
  Droidcon USA 2026 · Jul 17 · W222 B · Sam Edwards
  Block's open-source CLI for driving real apps with AI. Explore once, replay forever.
colorSchema: dark
highlighter: shiki
transition: slide-left
mdc: true
fonts:
  sans: Inter
  mono: Fira Code
layout: cover
class: cover text-center
---

# Trailblaze
## Map Your App for AI

<div class="pt-10 text-lg opacity-70 tracking-wide">
Droidcon USA 2026<br>Sam Edwards · Block
</div>

<!--
SKELETON DECK v3 — built from outline v3 in samdroidcon-notes.md.
Outline-level on purpose: judge the ORDERING first, then we go deep per slide.
Act + timing markers appear top-left of each slide.
Spine: natural language on top, determinism underneath. Refrain: "Blaze once, trail forever."
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0 · The problem · 0:00–4:00</div>

# AI can drive your app

## So why is good test coverage still hard?

<!--
Open on the question, not the tool. Let it sit for a beat.
Sam's framing: AI is prevalent and at our disposal — how do we get GOOD coverage with it?
(Phrasing still open to iteration.)
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# The missing link

- AI + mobile: **no canonical, natural-language way** to interact
- Existing AI solutions: **platform-specific**, or…
- …they **lose the link** between objective and actions
- The test case is the **what**

<!--
This is the differentiating problem statement. "Losing the link" is the phrase to hit.
Expand aloud: the "how" matters mainly for assertions you DELIBERATELY want mechanical
and handwritten — that thread returns in Act 2 (verify: steps). Don't leave dangling.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# One login flow. Seven targets.

<div class="grid grid-cols-7 gap-2 pt-8 text-center text-sm">
<div>📱<br>Android<br>phone</div>
<div>📱<br>Android<br>tablet</div>
<div>📱<br>iPhone</div>
<div>📱<br>iPad</div>
<div>🖥️<br>Square<br><b>Terminal</b></div>
<div>📟<br>Square<br><b>Handheld</b></div>
<div>🧾<br>Square<br><b>Register</b></div>
</div>

<div class="pt-10 text-xl opacity-80">

Hundreds of trails today → projecting **thousands**.

</div>

<!--
Swap emoji for real product imagery from squareup.com/us/en/hardware.
SEVEN targets — never say eight.
Scale framing: keep the automatable automated; manual testing focuses on
new features + hardware-specific work automation can't reach yet.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# Ten years on this problem

- **2016 · Droidcon NYC** — the robot pattern + why screenshots matter
- **2025 · Droidcon NYC** — the Trailblaze agent loop internals (with Brian)
- **2026 · today** — 1.5 years in production at Square & Cash, and where it's going

<div class="pt-8 opacity-70">

*By the end you'll see what the map is — spoiler: you're already building it.*

</div>

<!--
Title seed #1. Credentials in one slide, no dwelling.
HAND-OFF: "Our answer is a thesis about natural language and determinism."
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1 · The thesis · 4:00–9:00</div>

# Blaze once, trail forever.

<div class="pt-6 text-xl opacity-80">

Agent drives the real app from natural language →
saved as a **trail** → replayed deterministically, **zero LLM calls** in CI.

</div>

<!--
THE REFRAIN. Introduce here, echo at the close.
Quick refresher for anyone who missed last year — 90 seconds max.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# The LLM is the compiler

| | |
|---|---|
| Natural language | the **source** |
| Trail YAML | the **bytecode** |
| Your device | the **runtime** |

<div class="pt-6 opacity-80">

Compile once. Replay needs no LLM.

</div>

<!--
Day-one framing from the very first devlog — never abandoned.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# YAML is the shared surface

- The **LLM constructs** it
- The **human edits** it — and **accepts** it
- The **commit blesses** it
- **Git remembers** how every test evolved

<!--
The commit means: "yes — this is how this natural-language case materializes on this platform."
The acceptance gate: AI authors (and heals) without human interaction,
but a human approves before commit. Agent-authored, human-approved.
This is the answer to "do you just trust the AI?"
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# Self-heal: the hinge

- Recording fails → LLM re-solves **just that step** from the natural language
- **Off by default** — failures stay actionable
- **% passed via self-heal** = your recording-staleness health signal

<!--
Self-heal is the hinge between the two threads: when determinism breaks, the NL rescues it.
A* cost model (recording=1.0, AI=5.0) lives on a BACKUP slide.
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# 📼 ASSET A · part 1

## Zero-LLM trail replay

<div class="pt-4 opacity-60">~45s pre-recorded clip · replay of a real trail, no AI in the loop</div>

<!--
Pre-recorded. Part 2 (the report it generated) plays in Act 3.
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# Natural language on top.
# Determinism underneath.

<div class="pt-8 text-xl opacity-80">

Every architecture decision that follows defends one of those two things.

</div>

<!--
THE SPINE — state it once, plainly. Reprised on the recipe slide.
HAND-OFF: "That's the thesis. Production scale is what stress-tested it."
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2 · One journey, one file · 9:00–15:30</div>

# Scale found our design flaw

- v1: one trail file **per platform**
- It worked. Then:
  - **file explosion**
  - the natural language **drifted** between copies
- The "one business case" promise quietly broke

<!--
Filenames to say aloud: android-phone.trail.yaml, ios-iphone.trail.yaml, ...
Real production drift: credentials diverged, steps added to one file and not others.
The recording pipeline couldn't fix it — each device recorded in isolation.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# The unified trail format

```yaml
trailhead:
  step: Sign in as the QE sender
  recording:
    android-phone: { myapp_signInViaUI: { email: "{{memory.account_email}}" } }
    ios-iphone:    { myapp_ios_signInViaUI: { email: "{{memory.account_email}}" } }

trail:
  - step: Add a latte to the cart and open checkout
    recording:
      android:        # broad family
        - tapOn: { text: Latte }
      android-phone:  # most-specific wins
        - tapOn: { id: menu_latte }
  - verify: The cart shows 1 item
```

**One file = the user journey.** NL exists exactly once. Recordings per classifier, closest-wins.

<!--
Show verify: here — closes the assertions thread from Act 0.
Q&A hedge: this multi-platform shape = the spec + Block production; the public repo's
committed unified trails are single-platform so far.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# Parameterize, don't hardcode

- `{{memory.var}}` — seeded at the trail level
- `--secret` — redacted, only the key recorded
- A recording **never hardcodes** a stale credential

<!--
60-second beat. Closes the loop on "credentials diverged" from the drift story.
Mechanism (say aloud if useful): reverse-substitution — recordings swap captured
literals back to {{key}} using a per-step memory snapshot.
Guaranteed Q&A topic otherwise (test accounts in CI).
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# Trailheads: deterministic starts

- A **trailhead is a tool** — the designated start
- Clear app data · launch args · **known state**
- Deterministic setup → deterministic replay

<div class="pt-6 text-xl opacity-80">

Most flaky tests die right here.

</div>

<!--
HONEST BEAT after this slide: trail YAML v2 was fully designed and never shipped —
reality overrode a finished design doc on the way to unified. (One sentence, self-deprecating.)
HAND-OFF: "Unified files fixed drift. Duplication was next — and I'd seen that problem ten years ago."
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3 · What vs How · 15:30–21:00</div>

# 2016: the robot pattern

## Separate **what** you test from **how** you test it.

<div class="pt-6 opacity-70">

Still running in Square's Espresso suites today.

</div>

<!--
The 10-year callback — a beat, not the spine. One slide of nostalgia, then the twist.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3</div>

# The LLM twist

- An LLM decides **what** to do next
- Force it to figure out **how** → its context explodes

<!--
"How" = raw taps, "just explore" — many decisions that should have been ONE decision.
-->

<div class="pt-8">

**Custom tools = robot methods.** &nbsp; `login` · `addItemToCart`

**Your app has its own tools.**

</div>

<!--
Implementation changes → fix the tool once; the trails on disk never change.
At hundreds → thousands of trails, this is what makes maintenance survivable.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3</div>

# A screenshot was worth a thousand words

Per step, the report captures:

- 📸 the **screenshot**
- 🌲 the **view hierarchy**
- 📜 the **logs**
- 🤖 the **LLM transcript** (when AI was involved)

<div class="pt-6 text-xl opacity-80">

Now the LLM has **all the context it needs** to solve failures and iterate.

</div>

<!--
The second 2016 thread (screenshots), upgraded for the LLM era.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3</div>

# Reports with no strings attached

- **Standalone** report — servable from any CDN
- Viewable in CI · downloadable as a **zip**
- Analyzable by you… **or by an agent**
- **No SaaS backend required**

<!--
Kept tight — one slide.
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3</div>

# 📼 ASSET A · part 2

## Walking the report that replay generated

<div class="pt-4 opacity-60">screenshots · hierarchy · logs · served static, zipped for agents</div>

<!--
Same session as part 1 — its own report. One recording serves two acts.
HAND-OFF: "Tools are the unit of reuse. Two questions decided everything:
who gets to write them, and how do agents reach them?"
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1: who writes tools · 21:00–24:00</div>

# Tools wanted out of the binary

1. **Kotlin**, compiled in → fork to extend
2. Ship a **binary** → nobody can extend
3. **The inversion:** tools on the filesystem — the **trailmap**

<!--
1: a wall for external teams, too slow for agents iterating.
3: trailmap = your target + tools + toolsets + trails, distributed as plain npm packages.
-->

<div class="pt-6 opacity-70">

*Note the name — **trailmap**. We'll come back to why it's a map.*

</div>

<!--
Title seed #2.
Runtime internals = ONE LINE if asked: "runs in an embedded JS engine, identical
on host and device." QuickJS/Bun/codegen details → backup slides.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1</div>

# TypeScript: the types ARE the schema

```ts
export interface AddItemArgs {
  /** Item name as shown on the menu. */
  itemName: string;
}

/**
 * Add an item to the cart from the menu screen.
 * Use for "add X to the cart", "put X in the basket", ...
 */
export const myapp_addItemToCart = trailblaze.tool<AddItemArgs>(
  { supportedPlatforms: ["android", "ios"] },
  async (input, ctx) => {
    await ctx.tools.tapOn({ text: input.itemName });
    return `Added ${input.itemName} to cart.`;
  },
);
```

One `.ts` file = one tool · types → schema · TSDoc → the LLM-facing description

<!--
Type-safe, and the language LLMs edit best. Wrong tool name or args = compile error.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 2: how agents reach tools · 24:00–26:30</div>

# The CLI pivot

- **MCP friction:** configure → restart · code change → disconnect
- **CLI:** install and use — *in the same session*
- The fear: do we lose the natural language?

<!--
Early 2026, huge unlock.
One disarming line: we didn't abandon MCP — `trailblaze mcp` still exists as a proxy;
the CLI is the primary surface.
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 2</div>

# Driving the device *is* constructing the trail.

<div class="pt-8 text-lg opacity-80 max-w-3xl mx-auto">

Target = trailmap → CLI exposes its **toolbox** →
every tool call carries its **natural-language step**.

The link? **Never broken.**

</div>

<!--
THE EMOTIONAL PEAK. 60–90 unhurried seconds. This resolves Act 0.
📼 ASSET B plays here or immediately after: agent driving via CLI toolbox,
NL steps attached, ending on the saved trail file.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Guardrails · 26:30–27:30</div>

# Trust, but compile

One param change → **hundreds of YAML files** · no PR checks

- **Gate 1:** strict parsing — unknown fields are **errors**
- **Gate 2:** the **tsc trick** — recordings compiled as TypeScript

<div class="pt-4 opacity-70">

Errors map back to `trail.yaml · step 3 [android-phone]`

</div>

<!--
Gate 2 mechanics (say aloud): every recorded tool call transpiled to a
client.tools.tapOn({...}) line, compiled against the generated typings.
Keep the trick, narrate lightly.
HAND-OFF: "Authoring got fast. The other half of slow was the driver."
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 5 · Owning the driver · 27:30–30:00</div>

# ~3x faster: owning the driver

| | Maestro (before) | Accessibility driver (now) |
|---|---|---|
| Per-action overhead | ~500–700ms | **~100–150ms** |
| Tree capture | lossy, 3-hop conversion | single-pass, native |
| Settle | screenshot poll-and-diff | event-based quiet window |

<div class="pt-4 opacity-70">

Honest: **iOS is still on Maestro** · web was always Playwright

</div>

<!--
iOS replacement is designed, not landed.
Maestro was the right first call — "not a permanent coupling" from day one.
Numbers live on the slide; don't over-narrate.
HAND-OFF: "We'd protected the language; now the determinism underneath it is fast. So — the map."
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 6 · The map · 30:00–34:30</div>

# You've watched this map assemble for half an hour

<div class="pt-6 text-lg">

- **Trails** — your app's well-worn paths
- **Trailheads** — the entry points
- **Custom tools** — your app's verbs
- **The trailmap** — the container that holds it all

</div>

<div class="pt-6 text-xl opacity-80">

Composable — and it's **your** surface exposed to the LLM.

</div>

<!--
Title payoff = a NAMING, not a reveal.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 6 · The future</div>

# Next: waypoints

- **Named, assertable places** · shortcuts between them · a graph
- Scaffolding shipped: matcher · `assertWaypoint` · CLI · graph viewer
- **Not fully proven out yet — and I'm bullish**
- Ahead: `goTo(waypoint)` · one nav fix repairs every trail

<!--
Vision details: pathfinding over the graph, semantic recordings (record
(shortcut, params) instead of raw taps).
-->

<!--
Framed as the future step, not shipped fact. This is the "philosophy of where we're going."
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 6</div>

# 📼 ASSET C

## Waypoint graph viewer flythrough

<div class="pt-4 opacity-60">100 waypoints · 64 shortcuts — mapped on Google Calendar (a fixture app, not ours)</div>

<!--
LABEL THE FIXTURE APP — protects the honest framing.
HAND-OFF: "The whole recipe, one slide."
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 7 · The recipe · 34:30–36:30</div>

# The recipe

**Natural language on top**
trails in git · what-not-how tools · agent-authored, human-approved

**Determinism underneath**
trailheads · compiled recordings · fast native driver

**Self-heal** — the hinge, and your health signal

<div class="pt-6 text-xl">

Keep the automatable automated. Spend humans on what's new.

</div>

<!--
The spine, reprised. Then the refrain on the next slide.
-->

---
layout: center
class: text-center
---

# Blaze once, trail forever.

<div class="pt-8 text-lg">

`brew install block/tap/trailblaze`

grab the **skill** → tell Claude / Codex to use it on *your* app

</div>

<div class="pt-6 opacity-70">**github.com/block/trailblaze** · _[QR code]_ </div>

<!--
CTA: brew → skill → point your agent at your own app.
-->

---
layout: center
class: text-center
---

# 2016 → 2026

<div class="pt-6 text-xl opacity-90">

The robot pattern outlived every framework it was written in.

**Now the robots write the tests.**

</div>

<div class="pt-10 text-2xl">Questions? 🙋</div>

<!--
15-second grace note, then real Q&A (~36:30–40:00).
-->

---
layout: section
---

# Backup slides

<!--
Q&A preempts live behind this divider.
-->

---

# Backup · What does authoring cost?

- **Replay: $0** — zero LLM calls
- LLM spend happens once, at authoring/blazing time (and on opt-in self-heal)
- A\* cost model: recording = **1.0**, self-heal = **5.0** — recordings always win

---

# Backup · "We already have Espresso / Compose tests"

- Trailblaze **complements** them — same robot-pattern lineage
- The NL layer is also your **exit strategy**: the journey survives any framework
- Cross-platform: one trail file covers Android, iOS, web, and Square hardware

---

# Backup · "Did you abandon MCP?"

- No — `trailblaze mcp` remains (STDIO→HTTP proxy to the daemon)
- The **CLI is the primary surface**: same-session install + use, no reconnect churn

---

# Backup · How do 7 targets run in CI?

- **On-demand pipeline**: trigger any set of trails, filtered by **device type**
- Latest build by default · pin an **exact build** for regressions

---

# Backup · Runtime internals

- Scripted tools run **in-process in QuickJS** — same engine on host JVM and Android ART
- Host-only `subprocess` mode (bun, MCP over stdio) for tools needing Node APIs
- "Kotlin canonical, TypeScript derived" — every cross-boundary type is generated from Kotlin
