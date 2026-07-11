---
theme: seriph
title: 'Trailblaze: Map Your App for AI'
info: |
  Droidcon · Jul 17 2026 · W222 B · Sam Edwards
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
Sam Edwards · Block · Droidcon 2026
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

# AI is at our disposal.

## So how do we get good test coverage with it?

<!--
Open on the question, not the tool. Let it sit for a beat.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# The missing link

- AI + mobile has **no canonical, natural-language way of interacting**
- Plenty of AI solutions exist, but…
  - platform-specific, **or**
  - they **lose the link** between the natural-language objective and the actions actually performed
- **The test case is WHAT should happen**
- The "how" matters mainly for assertions you *deliberately* want mechanical

<!--
This is the differentiating problem statement. "Losing the link" is the phrase to hit.
Assertions thread returns in Act 2 (verify: steps) — don't leave dangling.
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
- The **commit blesses** it:
  *"yes — this is how this natural-language case materializes on this platform"*
- **Git** = the historical record of how every test evolves

<!--
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

- v1: one trail file **per platform** (`android-phone.trail.yaml`, `ios-iphone.trail.yaml`, …)
- It worked. Then:
  - **file explosion**
  - the natural-language steps **drifted** between platform copies
- The "one business case" value prop quietly broke

<!--
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

- `{{memory.var}}` — seed values at the trail level
- `--secret KEY=VAL` — redacted; only the key is recorded
- **Reverse-substitution**: recordings swap literals back to `{{key}}`
  → a recording never hardcodes a stale credential

<!--
60-second beat. Closes the loop on "credentials diverged" from the drift story.
Guaranteed Q&A topic otherwise (test accounts in CI).
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# Trailheads: deterministic starts

- A **trailhead is a tool** — designated as the trail's starting point
- The test-setup method: clear app data · right launch args · land in a **known state**
- Deterministic setup is the **precondition** for deterministic replay

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

- An LLM wants to decide **what** to do next
- Make it figure out **how** — raw taps, "just explore" —
  and you explode its context on decisions that should have been *one decision*

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

1. Tools in **Kotlin**, registered at compile time → fork + rebuild to extend
   <span class="opacity-60">a wall for external teams · too slow for agents</span>
2. Ship a binary (**Homebrew**) → now *nobody* can compile tools in
3. **The inversion:** tools live **on the filesystem**, in a **trailmap**
   <span class="opacity-60">your target + tools + toolsets + trails · plain npm packages</span>

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

- **MCP friction was real:** configure → restart your session;
  a dev-loop code change → disconnected server
- **As a CLI:** an agent with a skill installs it and uses it — *in the same session*
- The fear: exposing all the tools… do we lose the natural language?

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

Each target is a trailmap → the CLI exposes its **toolbox** →
the agent attaches the **natural-language step to every tool call**.

The link from our problem statement? **Never broken.**

</div>

<!--
THE EMOTIONAL PEAK. 60–90 unhurried seconds. This resolves Act 0.
📼 ASSET B plays here or immediately after: agent driving via CLI toolbox,
NL steps attached, ending on the saved trail file.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Guardrails · 26:30–27:30</div>

# Trust, but compile

One param change can ripple across **hundreds of YAML files** — that don't run on PR checks.

- **Gate 1:** strict YAML parsing — unknown fields are errors, not silently dropped
- **Gate 2:** the **tsc trick** — every recorded tool call transpiled to
  `client.tools.tapOn({...})` and compiled against your generated typings

<div class="pt-4 opacity-70">

Errors map back to `trail.yaml · step 3 [android-phone]`

</div>

<!--
Keep the trick, narrate lightly — mechanics live on the slide.
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

Honest: **iOS is still on Maestro** (replacement designed, not landed). Web was always Playwright.

</div>

<!--
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

- **Named, assertable places** in your app · shortcuts between them · the interconnection graph
- Real scaffolding shipped: schema, matcher, `assertWaypoint`, authoring CLI, graph viewer
- Honestly: **an idea we haven't fully proven out yet — and I'm bullish**
- Where it could take us: `goTo(waypoint)` via pathfinding · semantic recordings ·
  one nav fix repairing every trail that used that edge

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

**Natural language on top:**
trails in git · what-not-how tools · agent-authored, human-approved

**Determinism underneath:**
trailheads · typed + compiled recordings · a fast native driver

**Self-heal as the hinge** — and your health signal

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
