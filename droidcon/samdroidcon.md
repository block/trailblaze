---
theme: seriph
title: 'Trailblaze: Map Your App for AI'
info: |
  Droidcon USA 2026 · Jul 17 · W222 B · Sam Edwards
  Block's open-source CLI for driving real apps with AI. Blaze once, trail forever.
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
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# An LLM on every run?

<div class="pt-6 text-2xl">

**Slow.** &nbsp; **Expensive.** &nbsp; **Non-deterministic.**

</div>

<div class="pt-8 text-lg opacity-70">

Great for exploring. Brutal for CI.

</div>

<!--
The economic hook from the abstract — land it before the seven targets multiply it.
Deterministic replay answers this, and replay alone is table stakes (Maestro has it too).
Our leg up: natural language FIRST + custom tools in TypeScript + full per-platform
fidelity — that's the positioning (backup slide has the head-to-head).
ASSET A pt 1 is the visual proof: side-by-side wall clocks.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# The missing link

- AI + mobile: **no canonical, natural-language way** to interact
- Existing solutions are **platform-specific** — or…
- …they **lose the link** between the objective and the actions
- The objective is the **what**. Most tools throw it away.

<!--
This is the differentiating problem statement. "Losing the link" is the phrase to hit.
Expand aloud: the "how" matters mainly for assertions you DELIBERATELY want mechanical
and handwritten — that thread returns in Act 2 (verify: steps). Don't leave dangling.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# One login flow.

<div class="pt-8 text-2xl opacity-70">

You write it **once**.

</div>

<!--
BUILD 1 of 2 — the setup. Say it and let it hang: "One login flow. You write it once."
Then advance, and it multiplies. (Big beat split across two slides — Sam's move.)
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# Seven targets.

<div class="grid grid-cols-7 gap-3 pt-8 text-center text-sm">
<div><div class="h-20 flex items-end justify-center text-5xl">📱</div><div class="pt-2">Android<br>phone</div></div>
<div><div class="h-20 flex items-end justify-center text-5xl">📱</div><div class="pt-2">Android<br>tablet</div></div>
<div><div class="h-20 flex items-end justify-center text-5xl">📱</div><div class="pt-2">iPhone</div></div>
<div><div class="h-20 flex items-end justify-center text-5xl">📱</div><div class="pt-2">iPad</div></div>
<div><div class="h-20 flex items-end justify-center"><img src="./public/square-terminal.png" class="max-h-20 object-contain" alt="Square Terminal" /></div><div class="pt-2">Square<br><b>Terminal</b></div></div>
<div><div class="h-20 flex items-end justify-center"><img src="./public/square-handheld.png" class="max-h-20 object-contain" alt="Square Handheld" /></div><div class="pt-2">Square<br><b>Handheld</b></div></div>
<div><div class="h-20 flex items-end justify-center"><img src="./public/square-register.png" class="max-h-20 object-contain" alt="Square Register" /></div><div class="pt-2">Square<br><b>Register</b></div></div>
</div>

<div class="pt-10 text-xl opacity-80">

Hundreds of trails today → projecting **thousands**.

</div>

<!--
BUILD 2 of 2 — the multiplication. The grid IS the gut-punch: one flow, seven places.
Square imagery = real product shots (droidcon/public/square-*.png, pulled from
squareup.com/us/en/hardware — Block's own marketing assets). Consumer devices stay
emoji on purpose: everyone knows an iPhone; nobody knows a Square Register.
SEVEN targets — never say eight.
Scale framing: keep the automatable automated; manual testing focuses on
new features + hardware-specific work automation can't reach yet.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 0</div>

# Ten years on this problem

- **2016** — *"Espresso: A Screenshot is Worth 1,000 Words"*
- **2025** — *"AI Driven Mobile Testing"* (with Brian Gardner)
- **2026** — 1.5 years in production, and where it's going

<div class="pt-8 opacity-70">

*By the end you'll see what the map is — you're already building it.*

</div>

<!--
Both prior talks were Droidcon NYC. Title seed #1. Credentials in one slide, no dwelling.
2025's future-work slide is a checklist this talk ticks off: "reuse the same prompts
across platforms" → unified format; "custom app interactions" → custom tools/TS.
HAND-OFF: "Our answer is a thesis about natural language and determinism."
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1 · The thesis · 4:00–9:00</div>

# Blaze once, trail forever.

<div class="pt-6 text-xl opacity-80">

Natural language → agent drives the real app →
saved as a **trail** → replayed with **zero LLM calls**

</div>

<!--
THE REFRAIN. Introduce here, echo at the close.
Quick refresher for anyone who missed last year — 90 seconds max.
-->

---
layout: center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# We're blazing a lot of trails…

## …so we have a **trailmap** 🧭

<div class="pt-6 opacity-70">

your tools · your trails · your app's map

</div>

<!--
Introduce the trailmap BY NAME right here (Sam: "we're doing it, so we have a trailmap").
Unpacked in Act 4 (tools live on the filesystem, in the trailmap);
payoff in Act 6 (you've watched this map assemble).
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# The LLM is the compiler

| | |
|---|---|
| Natural language | the **source** |
| The LLM + agent | the **compiler** |
| Trail YAML | the **bytecode** |
| Trailblaze | the **runtime** |

<div class="pt-6 opacity-80">

Compile once. The runtime needs no LLM.

</div>

<!--
Day-one framing from the very first devlog — never abandoned.
Runtime = Trailblaze's Kotlin engine executing the trail on the device —
NOT the device itself (Kotlin/Java is what actually runs the code on Android).
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# Self-heal = recompilation

- App changed? **Recompile the step** — against the app *as it is now*
- The agent meets the objective… **or says it can't**
- **Off by default** — failures stay actionable
- **% self-healed** = your staleness signal

<!--
The compiler metaphor completing itself: the NL source is still there, so we can recompile.
Recovery works because the LLM gets DEEP EXECUTION CONTEXT for this run — screenshots,
hierarchy, logcat, network calls, analytics events (full list lands in Act 3).
Self-heal is the hinge between the two threads: when determinism breaks, the NL rescues it.
A* cost model (recording=1.0, AI=5.0) lives on a BACKUP slide.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# YAML: the shared surface

- Not plain YAML — the vocabulary is **your custom tools**
- The **LLM constructs** · the **human edits & accepts**
- **Git remembers**

<!--
Your tools, exposed as YAML into the LLM — that's what makes it composable.
The commit means: "yes — this is how this natural-language case materializes on this platform."
The acceptance gate: AI authors (and heals) without human interaction,
but a human approves before commit. Agent-authored, human-approved.
This is the answer to "do you just trust the AI?"
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# The trail lifecycle

<div class="flex justify-center pt-10">

```mermaid {scale: 0.9}
flowchart LR
  NL["🥾 user journey<br/>(natural language)"] -->|"blaze 🤖"| REC["trail +<br/>recordings"]
  REC -->|"accept"| GIT[("git")]
  GIT -->|"replay<br/>zero LLM"| CI["CI run"]
  CI -->|"archive"| ZIP["📦 last<br/>run"]
  CI -.->|"app changed:<br/>self-heal 🤖"| GIT
```

</div>

<!--
The one-diagram version of the whole talk. Walk it left to right:
author once with AI → human accepts → git is the source → CI replays free →
every run archived → when the app changes, recompile and the loop closes.
"Last successful run" gets its own moment in Act 3.
-->


---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 1</div>

# 📼 ASSET A · part 1

## Zero-LLM trail replay

<div class="pt-4 opacity-60">side-by-side: agent blaze vs. recorded replay · wall clocks running</div>

<!--
Pre-recorded. The side-by-side IS the thesis: same journey, LLM-driven on the left,
zero-LLM replay on the right, clocks visible — the economics slide made flesh.
Part 2 (the report it generated) plays in Act 3.
MATERIAL (real, in-repo): use a contacts parity trail (create-contact is the
photogenic one). Reference number: the richer photo-trail blaze took 6m41s
agent-driven end-to-end; its replay is ~1min — pull the parity trail's exact
blaze/replay wall-clocks from the earning session before recording this asset.
PLAN B (no video): paste-ready fallback slide in notes ("Plan B" section) —
real blaze.yaml → earned recording, spoken wall-clock. Talk is DONE without video.
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

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2 · optional</div>

# The natural language drifted

```yaml
# android-phone.trail.yaml
- step: Sign in as the QE sender

# ios-iphone.trail.yaml
- step: Log in as the test sender      # ← drifted — and nothing caught it
```

**Same journey. Two files. The words pulled apart.**

<!--
OPTIONAL — first to cut if the dry run runs long; the story survives without it.
Makes "drift" concrete before the fix lands. Real failure mode: the NL diverged
copy-to-copy ("QE sender" vs "test sender"), each device recorded in isolation, so
nothing flagged it. This is the picture under the Act 2 opener's "drifted" bullet —
and the gut-punch that earns the unified format two slides on.
To cut: delete this whole slide block (--- to ---).
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# Not a test — a user journey

- Today: each platform's version buried **deep in its own codebase**
- *"Is this iOS test the same as this Android test?"* — **unknowable**
- The trail = **the journey** · recordings = its materialization per platform
- Recordings are **disposable** — drop them, re-materialize

<!--
"The thing that should be possible, that we need to ensure is possible."
Unified validation of a user experience. Because it's sourced in git, you can drop the
recordings for one platform (or all) and have the LLM re-materialize — it has the past
recording and all its context to work from.
HAND-OFF: "Here's what that journey looks like as one file."
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
Q&A hedge: this multi-platform shape = the spec + Block production; in the public repo the
contacts parity suite (next slide) is the committed unified corpus — Android recordings
earned, iOS recordings landing before the talk.
verify: semantics, if asked "doesn't that need the LLM at replay?": a verify step is an
assertion — assertion-scoped tool surface, auto-terminates, NEVER self-healed. Record it
and it replays zero-LLM like any step; leave it NL-only (as shown) and you've deliberately
kept the LLM judging that assertion every run. Zero-LLM replay is true for recorded steps;
NL-only steps are a per-step choice, not a leak.
HAND-OFF: "So what do you actually write these for? The jobs your users must always be able to do."
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 2</div>

# Four critical jobs. One file each.

| the job | Android | iOS |
|---|:---:|:---:|
| **Create** a contact | 🥾 | 🥾 |
| **Find** a contact by name | 🥾 | 🥾 |
| **Update** a contact | 🥾 | 🥾 |
| **Delete** a contact | 🥾 | 🥾 |

<div class="pt-4 opacity-70"><code>trails/contacts/</code> — in the open-source repo · same NL source per row · recordings per platform</div>

<!--
The demo corpus, framed as jobs-to-be-done, NOT "tests": the critical things a user
must always be able to do in a contacts app. Each row = ONE blaze.yaml; each cell =
that journey materialized for that platform. The parity claim made concrete:
4 files, 8 green runs. Self-contained by design — each trail creates the contact it
acts on, so any subset runs on a fresh CI device in any order.
Asset hooks: ASSET B blazes one of these; ASSET A replays one.
PRODUCTION NOTE (before talk): recordings are being earned now — Android first, iOS
on a fresh simulator. Verify every cell is green, then consider swapping the boots
for real wall-clock times. If iOS isn't done in time, show the Android column green
and narrate iOS as landing — never claim a green cell that isn't.
There IS a fifth trail — create-contact-WITH-PHOTO, Android-only: the photo hop
crosses four app surfaces (Contacts → camera → crop → back). iOS sims can't run it:
tapping "Photos" in the avatar sheet crashes stock Contacts on iOS 26.x — an OS bug
we found while building this. Great aside if timing allows: parity where the OS
allows, honesty where it doesn't.
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

# A screenshot was worth 1,000 words

- Every **step**: 📸 **screenshot** · 🌲 **view hierarchy** · 🤖 **LLM transcript**
- Every **run**: 🎞️ **video** · 📜 **device logs** — on by default

<div class="pt-6 text-xl opacity-80">

Now it's worth 1,000 **tokens** — the LLM has everything it needs to iterate.

</div>

<!--
Granularity is deliberate: screenshot/hierarchy/transcript are captured per STEP;
video + device logs (logcat on Android, scoped log stream on iOS) are per-RUN streams,
on by default (--no-capture-* to disable). At Block we layer more onto the same reports —
network calls, analytics events. The point isn't the exact list: whatever context you
capture, the agent can use.
"Deep, detailed context of the execution" — this is WHY self-heal recovery works (Act 1
callback) and why agents can diagnose. With source access, the LLM can trace a failure
back to the commit that caused it — we mostly test binary builds at Block, and still
trace issues back to source.
The second 2016 thread, upgraded: the LLM is the NEW AUDIENCE for test reports.
2016 callback pair to narrate: "Oops, Your Test Failed" (wall of hierarchy text) →
"Ahh, I See Why" (add the screenshot). In 2016 that context was for humans;
today the same context is what lets the agent solve failures.
2016: "To them it's just console output" → next slide (reports) answers it forever.
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
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 3</div>

# The last run down that trail

<div class="pt-6 text-lg opacity-80">

The trail is just **the steps to follow**.

CI archives every run — so you can always pull
**the last successful run**: the pictures, the logs, everything.

</div>

<!--
TERMINOLOGY: it's always a "run" — never a "hike."
A trail on disk loses the deep execution context; the archived zip preserves it per run.
At any point: what was the latest successful run of this test? Pull it down, diagnose, debug.
And sometimes on a run you find the creek overflowing and route around it — self-heal.
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
PLAN B (no video): open a real archived report live from disk (zero network) —
command + which session in the notes "Plan B" section. Or narrate over the
capture-granularity slide you just showed; the report exists, say so plainly.
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

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1</div>

# Tools return data, not just text

- Structured **JSON results** — typed by `tool<In, Out>`
- `listInstalledApps()` · `getUserProfile()`
- One step's **result** feeds the next step

<!--
The Out type param means results are structured and consumable, not prose.
Real uses: enumerate installed apps on a device, pull a test user's profile info.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1</div>

# Where do conditionals go?

- *"If a notification appears, dismiss it"*
- *"Tablet? Close that panel first"*
- *"Over $20 → do this · under → expect that"*

<div class="pt-6">

Day one rule: **trails are linear** — logic lives in **tools**

</div>

<!--
The knee-jerk: NO logic in a trail. A teammate added `runIf` and people use it — fine,
because it passes the test that governs everything here:
COULD THE LLM CONSTRUCT IT? If yes, it's allowed in the trail vocabulary.
Conditionals as computation belong in a TypeScript tool (do the check, branch inside).
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1</div>

# "Could the LLM construct it?"

<div class="pt-6 text-xl opacity-80">

By **driving** the app — not by handwriting YAML.

</div>

<div v-click class="pt-6 text-2xl">

Recordings are **earned**, not written.

</div>

<!--
The governing test for the trail vocabulary (runIf passes it).
The nuance: "construct" = the LLM selecting tools out of the target's toolbox while
actually driving. Handwritten YAML would pass validation — but it locks in a recording
that never actually ran. Recordings come from a successful run, full stop.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 1</div>

# Not every tool is for the LLM

- Tools have **properties** — LLM visibility is one
- **Utility tools** underneath · composed by other tools
- The LLM picks the **what** · no monoliths — small, reusable pieces

<!--
A tool decides whether it's exposed to the LLM. Only the right surface is advertised —
keeps the LLM's menu small and decisive while the implementation stays factored.
Same discipline as any good API: public surface vs. internals.
-->

---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 2: how agents reach tools · 24:00–26:30</div>

# The CLI pivot

- **MCP friction:** configure → restart · code change → disconnect
- **CLI:** install and use — *in the same session*
- Driven by **your** agent: Claude Code · Codex · Goose
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
📼 ASSET B is the very next slide — let this one land first.
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 4 · Beat 2</div>

# 📼 ASSET B

## An agent blazing a trail through the CLI toolbox

<div class="pt-4 opacity-60">every tool call carries its NL step · ends on <code>trailblaze step --save</code> → the trail file</div>

<!--
Pre-recorded. The verbs on screen are real: `trailblaze step --save` while driving,
then `trailblaze run` to replay what was just saved. First time the audience sees the
actual CLI — the "blaze" metaphor gets its concrete commands here.
RESOLVED: blaze one of the four parity trails on Android — the exact commands that
earned the committed recordings: `trailblaze run -d android trails/contacts/<job>/blaze.yaml`
(agent drives every step), then `trailblaze session save --title "..."` → the
android.trail.yaml lands NEXT TO the NL source. The clip's final frame: one folder,
blaze.yaml + android.trail.yaml side by side. If the photo trail's camera hop is
wanted as extra cinema, its blaze session already succeeded on camera (virtualscene) —
but the parity trail keeps the asset tight.
PLAN B (no video): paste-ready terminal-transcript slide in notes ("Plan B"
section) — the REAL commands + session id from the night the recordings were earned.
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

<div class="pb-2 opacity-70">Replay was already deterministic — every action still paid a half-second driver tax.</div>

| | UiAutomator (before) | Accessibility driver (now) |
|---|---|---|
| Per-action overhead | ~500–700ms | **~100–150ms** |
| Tree capture | lossy, 3-hop conversion | single-pass, native |
| Settle | screenshot poll-and-diff | event-based quiet window |

<div class="pt-4 opacity-70">

Honest: **iOS still drives through an XCTest runner** (via Maestro) · web was always Playwright

</div>

<!--
Setup line answers "what was slow?" — Act 4 fixed authoring; this is the replay half.
TERMINOLOGY: name the underlying tech, not the wrapper. We drove Android through
Maestro's stack, and Maestro drives Android WITH UiAutomator (gRPC to an instrumentation
APK). iOS: Maestro installs an XCTest runner app on the device and talks gRPC to it —
our own XCTest-based runner with event-based settle is designed (devlog: driver dispatch
& the iOS settle gap), not landed.
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

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 6</div>

# Not just test authoring

- An agent + an objective + a device — that's enough
- With a **trailmap**: fewer decisions, tools do the work underneath
- Everything is **already being captured**

<!--
Trailblaze = device control for agents, full stop. The trailmap makes any agent
more effective at navigating YOUR app — testing is one (great) use of that.
-->

---
layout: center
class: text-center
---

<div class="text-sm opacity-50 absolute top-4 left-4">ACT 6</div>

# You never need a trail

<div class="pt-6 text-xl opacity-80">

Drive UIs with an agent on **day one**.

When something's worth repeating — **save it**. It's all been recorded.

</div>

<!--
Sam: "you never ever ever need a trail to use Trailblaze." Deliberate design:
the data is always captured, so a repeatable moment converts to a trail for free.
2025's close was "incremental adoption is key" — this is that promise, kept.
HAND-OFF: "That's the map as it exists today. What's next is the part I haven't
proven yet — and I'm bullish."
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

<div class="pt-4 opacity-60">the demo app's waypoint map — every named place, every shortcut between them</div>

<!--
Demo app = CONTACTS (round-4 decision). PRIMARY map: the committed contacts-iOS
trailmap — ~103 waypoints + 81 shortcuts (trails/config/trailmaps/contacts/),
required+forbidden matchers. Optional beat: flash the committed Calendar map
(100 waypoints + 64 shortcuts) as the second map — "a pattern, not a one-off."
Android contacts side is waypoint-thin today — if asked, that's the honest
"maps mature independently; the journey doesn't care" line.
Label the app honestly either way — protects the future framing.
PLAN B (no video): the graph viewer runs LOCAL, zero network — open it live on
the committed contacts-iOS map (command in notes "Plan B" section), 30 seconds of
mouse-wandering beats a flythrough anyway. Worst case: speak the numbers —
"~103 named places, 81 shortcuts, committed in the repo today."
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

<div class="pt-8 flex justify-center">
  <img src="./public/trailblaze-qr.svg" class="w-44 h-44" alt="QR: github.com/block/trailblaze" />
</div>

<div class="pt-3 opacity-70"><b>github.com/block/trailblaze</b></div>

<!--
CTA: brew → skill → point your agent at your own app.
QR = white-on-transparent SVG (droidcon/public/trailblaze-qr.svg), generated locally
with slidev's bundled uqr — regenerate: bun -e with renderSVG if the URL ever changes.
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

# Backup · How reliable is it?

- We're **still getting to 100%** — reliability is **good and improving**
- **Parity across Android and iOS** — the same journey validates on both
- Replay is deterministic; flakiness lives at **setup** — which trailheads exist to kill

<!--
Sam's banked wording — NEVER invent a number. "Good and improving, parity across platforms."
The determinism/trailhead line answers "but isn't replay flaky?" without reaching for a stat.
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

# Backup · "How is this different from Maestro / Appium / AI recorders?"

- Deterministic replay is **table stakes** — Maestro has it too
- **Natural language first** — the objective never leaves the artifact
- **Custom tools in TypeScript** — your app's verbs, composable, contributable
- **Full per-platform fidelity** — driver-native trees, no lossy conversion
- One journey → **7 device targets**, plus web via Playwright

<!--
Sam's framing: the economics differentiate the CATEGORY (replay vs pure-AI runners),
but Maestro shares that. The leg up is the NL link + TS custom tools + fidelity.
Fidelity ammo: the generic tree pipeline drops platform detail at every hop (Android
loses package/long-clickable, web collapses to a resource-id string) — ours keeps the
driver-native projection.
-->

---

# Backup · How do 7 targets run in CI?

- **On-demand pipeline**: trigger any set of trails, filtered by **device type**
- Latest build by default · pin an **exact build** for regressions

---

# Backup · Runtime internals

- Scripted tools run **in-process in QuickJS** — same engine on host JVM and Android ART
- Host-only `subprocess` mode (bun, MCP over stdio) for tools needing Node APIs
- "Kotlin canonical, TypeScript derived" — every cross-boundary type is generated from Kotlin
