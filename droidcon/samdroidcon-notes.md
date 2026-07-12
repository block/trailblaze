# Trailblaze talk — brainstorming notes

Working notes for the Droidcon 2026 talk (Jul 17, 40 min, W222 B).
Deck lives in `samdroidcon.md` — this file is scratch space so ideas don't taint the slides.

## Portable session files (take these to any machine)

- `samdroidcon.md` — the Slidev deck (render with `bunx slidev samdroidcon.md`; theme seriph)
- `samdroidcon-notes.md` — this file: Sam's brainstorm capture + decisions + open questions
- `samdroidcon-research.md` — topic digests from deep-reading github.com/block/trailblaze
- `samdroidcon-repo-map.md` — where everything lives in the trailblaze repo (navigation aid)
- Setup for live preview: `.claude/launch.json` (runs local slidev binary, port 3030); a
  local untracked `.npmrc` holds any machine-specific registry config.
  Fresh machine: `bun add @slidev/cli @slidev/theme-seriph`, then `bunx slidev samdroidcon.md`.

## Talk reframe (2026-07-11 brainstorm)

The auto-generated deck reads like an intro/pitch. Sam's actual goal: **how Block is using
Trailblaze in production (Square + Cash, 1.5+ years) and the philosophy of where it's going.**

Continuity with past talks:
- **Droidcon NYC 2025** — Sam + Brian covered the original technical details: the agent loop
  (natural language + screen state → tool selection → drive the UI). This talk should NOT
  re-cover that; it's the sequel.
- **Droidcon NYC 2016** — exactly 10 years ago, Sam presented the **robot pattern** for UI
  testing (what-vs-how separation). Still used in Square's Espresso tests today. Perfect
  callback: Trailblaze custom tools are the robot pattern reborn for the LLM era.

## Core points from Sam (raw capture, organized)

### 1. The scale problem: the platform matrix
- Square targets **Android, iOS, and proprietary devices**, across multiple form factors and
  OS versions each.
- A single scenario — even simple login — needs coverage on **7 targets** today:
  `android-phone`, `android-tablet`, `ios-phone`, `ios-ipad`,
  `square-t2`, `square-t3`, `square-x2`.

### 2. Old format: platform-specific trail files (and why it hurt)
- One trail per platform (Android-phone trail, iPhone trail, ...). It worked, but:
  - **File explosion.**
  - **Natural-language steps drifted out of sync** between platform versions.
- Original value prop of Trailblaze: represent a *business case* (steps a user goes through),
  materialize it per platform, run via LLM, **self-heal via LLM** when the app changes.
  Platform-specific files undermined the "single business case" part.

### 3. New unified trail format (the breakthrough)
- **One file = the user journey** (single natural-language source of truth) **with all
  platform-specific recordings embedded within it.**
- Runs exactly the same way as before — just one file now.
- "Obvious in retrospect" — but not while the YAML spec and CI pipelines were still evolving.
  (Honest-lessons material.)

### 4. Why it gets unwieldy anyway
- 5+ platform variants per test; differences can be small but real:
  tablet vs phone navigation, scrolling behavior, generally different experiences.

### 5. Robot pattern → custom tools (what vs how)
- Hundreds of tests → massive duplication. The 2016 answer was the robot pattern:
  **separate WHAT you test from HOW you test it.**
- This separation is *incredibly important when communicating with an LLM*:
  - The LLM wants to decide **what** to do; forcing it to also figure out **how**
    (via skills / "just figure it out") explodes the context window on decisions
    that should have been one decision.
- **Custom tool = robot method** (`login`, `addItemToCart`). What, not how.
- When the implementation changes under the hood, you adapt the tool programmatically —
  **trails on disk don't change.** At thousands of trails, this is what makes
  maintenance survivable.

### 6. Maintenance safeguards at scale
- One parameter change on a tool can ripple across **hundreds of YAML files**; these tests
  are slow and don't run on PR checks, so a bad check-in surfaces late and expensively in CI.
- Safeguard: **strict YAML parsing** — no fields that don't exist in the schema of your
  defined tools.
- Mechanism: YAML file → **generated TypeScript version** → run through a **compilation
  step** as validation.

### 7. The TypeScript decision (tools-as-code story)
- Tools were originally **Kotlin**. Fine when it was Android-on-device, then iOS via
  source-based compilation.
- Problem: agent harnesses (Codex, Claude) need to **iterate fast**; Kotlin tooling requires
  forking Trailblaze and full compilation. Arduous.
- Long-standing idea: scripted tools. First thought: **JavaScript** via a QuickJS↔Kotlin
  binding, run in-process (same approach as Cash App's **Redwood** library).
- But: wanted **type safety** + a language **LLMs are good at editing** → **TypeScript**.
- Wins: type-safe errors; tooling that **generates bindings** for your custom tools so you
  *and your agents* can code up custom logic for your use cases.

### 8. Distribution + the tool-registration inversion
- Running from source (forking Trailblaze) was a barrier to adoption and slow.
  → Now shipping a **binary via Homebrew**.
- But a binary made contributing your own tools impossible.
  → **Inverted tool registration**: tools live **on the filesystem** in a **"trail map"** —
  TypeScript-defined tools that use framework tools under the hood.
- Trail map + per-target configuration (e.g. Cash App) = fast iteration on trails with
  your coding agent (or by hand).

### 9. Maestro → accessibility driver: ~3x faster
- Migrated off Maestro to Trailblaze's own accessibility-based driver.
- **Nearly 3x speed improvement.** (Pull exact architecture + numbers from the repo.)

### (more to come — Sam stopped here, has more points)

## Core points round 2 (2026-07-11, after outline v1)

### 10. The REAL problem statement (talk should open with this)
- Not just the device matrix — the bigger question: **how do we get good test coverage now
  that AI is prevalent and at our disposal?**
- AI + mobile still has **no canonical way of interacting that can be expressed in natural
  language.** Many AI solutions exist, but they're platform-specific, or they **lose the link
  between the natural-language objective and the actions that actually got performed.**
- That link is critical: **the test case is WHAT should happen.** The "how" really only
  matters for assertions, where you may want to be mechanical/handwritten.
- **YAML as the surface**: the LLM can construct it AND the human can come in and edit it.
  **Git** gives the historical perspective on how a test evolves.
- Custom tools complete it: **your app has its own tools.**
- Differentiator summary (Sam's words, lightly compressed): custom tooling + composable
  tools + cross-platform & cross-form-factor representation + maintained natural language =
  what sets Trailblaze apart for offering AND maintaining automation.

### 11. Trailheads — deterministic setup (missing from outline v1)
- A **trailhead is just a tool, designated as a trailhead** — effectively the test-setup
  method: "I start in this certain place."
- Why it matters: tests commonly fail because setup isn't deterministic — clearing app data,
  closing apps, launching with correct arguments. You need that for **deterministic replay**,
  which is the whole point.
- Lives inside the trailmap concept / new unified trail format story.

### 12. The CLI pivot (early 2026) — huge unlock, must be in the talk
- Trailblaze became a **CLI** because **MCP setup was a big friction point**: users had to
  configure the MCP then start a NEW session; and while *developing* the MCP, any code change
  disconnected it → more problems.
- With a CLI: someone with a skill (or just awareness of the binary) can install and start
  using it **within the same session**.
- The fear: how do we expose all the tools and still keep natural language? Answer emerged
  from targets: **each target is basically a trailmap**, so the CLI exposes a **toolbox** of
  available tools for that target; the agent calls tools via the CLI and **provides the
  natural-language step alongside each tool call** — so as it drives the device it's
  simultaneously constructing the trail with its NL steps. Exactly what we wanted to save.

### 13. Reporting approach — standalone, CDN-servable, no SaaS
- From the start: a **standalone report** servable from a CDN — full-fidelity capture
  viewable in CI, downloadable as a **zip for analysis by you or by an agent**.
- "Incredibly powerful" — and **no software-as-a-service backend needed**.

### Corrections
- **AndroidWorld benchmarks: DELETED from the repo — do not mention in the talk.** Not
  invalid, just never leaned into; the original motivation (building a great custom agent)
  faded because the simple agent + leaning on Claude Code / Codex won.

## Core points round 3 (2026-07-11)

### 14. The acceptance gate — AI authors, human approves, git blesses
- AI can author trails and heal them **without human interaction** — but the current
  process has a **human accept what was generated** before it's committed.
- Once committed: "we now have a deterministic way of saying — YES, this is how this
  natural-language case materializes on this platform."
- The commit IS the acceptance gate; git history = the record of blessed materializations.
  (Pairs with the repo's "agent-authored, human-readable" philosophy devlog.)

### 15. CI: the on-demand pipeline
- On-demand pipeline triggers **any number of trails**, filtered by **device type**.
- Defaults to the **latest build**; can pin an **exact build** (e.g. for a regression).
- This is public/shareable — it answers the "how do 7 targets run in CI" Q&A preempt.

## Core points round 4 (2026-07-11, reacting to compiler slide)

- **Self-heal = recompilation.** Because the NL source exists, we can *recompile* a step
  against the app as it works RIGHT NOW: the LLM navigates the app to meet the laid-out
  steps/objective — and appropriately reports if it cannot. (Extends the compiler metaphor;
  self-heal slide should sit adjacent to the compiler slide.)
- **Deep execution context is a key differentiator.** Per trail run Trailblaze captures
  network calls, logcat, analytics events, screenshots, hierarchy, etc. — "deep detailed
  context of the execution." That context is what lets the LLM recover well during
  self-heal, not just try again blind.
- **Source-code trace-back.** If the LLM also has your source, it can trace a failure back,
  put in a fix or a new version, and continue. Block mostly tests **binary builds**, but
  still traces issues back to source and finds the commits that caused them.

## Core points round 5 (2026-07-11)

- **Not a test — a user journey / job-to-be-done.** The NL is "the thing that should be
  possible that we need to ensure is possible." It *materializes* as a test. Today each
  platform buries its own implementation deep in its codebase — you can never connect
  "this iOS test IS this Android test" without some mapping. A trail + per-platform
  recordings = the materialization of the NL succeeding → **unified validation of a user
  journey**. Focus shifts to what the user should experience.
- **Recordings are disposable; the journey is not.** Sourced in git, you can drop the
  recordings for one platform (or all) and re-materialize them — the LLM has the past
  recording + all its context to do so.
- **Archived runs = "the last run down that trail."** (Terminology: always "run," NEVER
  "hike" — Trailblaze never uses that word.) A trail alone loses the deep
  execution context (hierarchies, logs, network, analytics). In CI every run's zip is
  archived — at any point, pull a test's **latest successful run** for diagnostics: all the
  pictures and logs from the last time somebody went down that trail. The trail = the steps
  to follow; along the way you might find an overflowing creek and route around it
  (self-heal).
- **Sam asked for a test-lifecycle diagram** in the deck.

## Core points round 6 (2026-07-11)

- **Conditionals are a real problem.** Examples: "if you're on a tablet, dismiss this
  window"; "if there's a new notification, dismiss it"; "if the price is above $20 do X,
  under $20 expect Y." Current answer: write a TypeScript custom tool that does the
  computation + conditional. A teammate added a **`runIf` tool** — people use it; Sam has a
  knee-jerk reaction against ANY logic living in a trail. His acceptance criterion:
  **"as long as the LLM could construct it, I'm okay with it."** (Ties to day-one devlog
  rule: recordings are strictly linear; conditionals belong in tools.)
- **Typed tools return structured JSON, not just text.** Via the type-safe TS bindings
  (`trailblaze.tool<In, Out>`): e.g. list installed applications on a device, grab a user's
  profile information — results the next step can consume. Super helpful in practice.
- **"Could the LLM construct it?" — confirmed important, with a sharp nuance.** It means
  the LLM *selecting a tool out of the target's toolbox* while actually driving — NOT
  handwriting the YAML. Sam actively discourages LLMs handwriting trail YAML: validation
  would catch malformed YAML, but you'd still be **locking in a recording that never
  actually ran**. The hope: recordings are generated by the agent actually driving and
  successfully completing the run. (Claude's compression: "recordings are earned, not
  written" — Sam-approved phrasing pending.)
- **Tools have properties — LLM visibility among them.** Underlying utility tools exist
  purely to be called by other tools; a tool decides whether it's exposed to the LLM.
  "An LLM needs to pick what's going on, but that doesn't mean monoliths — we still break
  things down into smaller, reusable pieces." Only the right surface is advertised.

## Core points round 7 (2026-07-11)

- **Trailblaze is not only for test authoring.** It gives agents-with-an-objective the
  ability to interact with devices. A trailmap for an app on that device = the agent
  navigates more effectively — fewer decisions, tools doing the work under the hood.
- **"You never, ever, ever need a trail to use Trailblaze."** Immediate value = driving
  UIs with an agent, period. No prerequisite. Deliberately designed so that all the data
  is already being captured — so the moment you decide something's worth doing repeatedly,
  you save it as a trail. Device control first; trails when you're ready.
  (2026 version of 2025's closing message "incremental adoption is key.")

## Answers locked in (2026-07-11)

- **Talk ethos:** developer conferences are about sharing technical thought process and
  discussion, NOT sales pitches. Main goal = the thinking; getting people to investigate
  Trailblaze is the natural side effect.
- **Waypoints positioning (Sam's words):** something he heavily wanted to invest in and
  work HAS been done, but it's "an idea that hasn't been fully proven out yet." He's
  bullish and wants to talk about where it could take us — but clearly framed as **the
  future step, not right now**. (Matches repo reality: README calls it an active prototype;
  matcher/assertWaypoint/CLI/viewer shipped, goTo/pathfinding not built.)

- **Targets (7):** android-phone, android-tablet, ios-phone, ios-ipad, square-t2, square-t3, square-x2.
- **Audience assumption:** most have heard of Trailblaze (or will get it fast); include a
  *quick refresher* in the first couple of minutes, but don't re-teach the 2025 agent-loop talk.
- **The takeaway sentence:** the audience walks away with a snapshot of Block's *current
  positioning* on representing user journeys/trails to effectively test the app — via the
  unified trail format and everything inside Trailblaze — as a **general recipe and vision**
  they can adopt (use Trailblaze, or apply the patterns in their own systems).
- **Trailmap** is a real spec in the repo (github.com/block/trailblaze) — cloned to scratchpad,
  research agents digesting devlogs + code.

## Clarifying answers round 2 (2026-07-11) — TALK IS IN 5 DAYS (Jul 17 → prep by Jul 16)

1. **Demos: pre-recorded only.** Bad live-demo experience last year; heavy travel/work before
   the talk — everything must be done ahead of time. Videos and/or generated reports.
2. **Scale numbers OK to share:** trails are "in the hundreds" today, projecting to
   thousands. Framing: keep the automatable automated, so manual testing focuses on new
   features + hardware-specific things automation can't reach yet.
3. **Robot-pattern callback = a beat, not the spine.** Mention 10+ years in this space; 2016
   talk covered (a) what-vs-how separation and (b) the importance of screenshots. Both
   concepts survived and shaped Trailblaze: what-vs-how → custom tools & LLM context
   discipline; screenshots → reporting that captures screenshot + view hierarchy + logs.
   Punchline: "a screenshot was worth a thousand words — now the LLM has ALL the context it
   needs (screen, hierarchy, logs) to solve failures and iterate."
4. **Title interpretation locked:** "Map Your App for AI" = building your app's trailhead
   tools + custom tools — the composable shortcuts you expose to the LLM to reach states and
   do things the fastest way. Tools + a large trail corpus = the map of all your trails.
   Waypoints = the NEXT step (visualizing how it all interconnects). Future-framed.
5. **Square hardware — use product names + public imagery** from squareup.com/us/en/hardware:
   T2 = **Square Terminal**, T3 = **Square Handheld**, X2 = **Square Register**. Show
   alongside iPhone/iPad/Android phone/Android tablet for the 7-target matrix slide.
6. **Openness:** everything in the repo/devlogs is 100% public, deliberately. Wants honesty
   about challenges BUT don't confuse people with every wrong turn — frame as "we evolved and
   why." (Claude: no red flags found in the public repo; watch: don't present AndroidWorld
   70% as a result — it's a target; don't conflate the 135x web benchmark with the driver 3x.)
7. **Call to action:** `brew install` → grab the Trailblaze skill (repo `skills/trailblaze/`)
   → tell Claude/Codex to use it on your own app.

## Emerging themes (Claude's read, for discussion)

- The through-line candidate: **"what vs how" is the load-bearing idea** — it explains the
  unified format (what = journey, how = embedded recordings), custom tools (robot pattern),
  and LLM context discipline. The 10-years-later robot-pattern callback could be the spine.
- The talk is really a **scale + production-lessons talk**: 8 targets × hundreds of tests ×
  thousands of trails. Each architecture decision (unified format, TS tools, homebrew +
  trail map) is an answer to a scale pressure.

## Open questions

- **Demo**: which app, which platform? Backup video still to record.
- **Honest limits**: what goes in the "where it doesn't fit" bullet?
- **YAML examples** on slides 6 & 10 are stale vs. the new unified format — sync before the talk.
- QR code for the repo on the closing slide.
- How much of the auto-generated deck's waypoints/edges/nav-graph content survives the reframe?
- "Trail map" (tool registry on disk) vs. the talk title "Map Your App for AI" — same map,
  different map, or intentional double meaning?

## Sam's slide style (extracted from the 2016 + 2025 decks — apply to this deck)

Content density & type:
- **Many slides, each light** — 2016 was 102 slides, 2025 was 63 for ~40 min. Sam advances
  fast; one idea per slide beats one slide with five bullets.
- **Build sequences across consecutive slides**, not v-clicks on a dense slide: the 2016
  What/How robot diagram evolved over ~10 slides; the 2025 architecture diagram added one
  layer per slide. Repetition-with-addition is his reinforcement mechanic.
- **Cumulative lists**: "1. See what's tested" → next slide adds "2. Diagnose failures" →
  next adds "3. Share your tests."
- **Real, verbatim code in small doses**: the do/while agent loop, a single data class, one
  robot one-liner. Code is shown to be *read aloud*, not studied.
- **Numbered evolution storytelling**: "1st Attempt → 2nd Attempt → 3rd Impl → Final" —
  wrong turns narrated as a sequence, not a confession.
- **Dated milestone timeline slides** (2025 origin: DragonCrawl Apr 2024 → ai-test-agent
  Nov 2024 → Trailblaze Mar 2025).
- **Headlines are statements or questions** ("What does 'AI Driven' mean?", "Oops, Your
  Test Failed" → "Ahh, I See Why"), not topic labels.
- **Punchline slides with 2–3 words** ("1,000 Words") for the big beats.
- Agenda slide up front; explicit **Call To Action** close; occasional audience poll opener.

Deck implication: prefer SPLITTING a bulleted slide into 2–3 light slides / a build
sequence over cramming one slide. The 38-slide skeleton will likely grow to 55–65 slides
at Sam's natural density — expected, not scope creep.

## Proposed outline v3 (2026-07-11 — after step-back + subagent review; v2 superseded)

**The spine (state at end of Act 1, reprise on recipe slide):** two braided threads —
**protect the natural language** (the WHAT) and **make everything beneath it deterministic**
(the HOW). Every architecture decision in the talk defends one of the two. Self-heal is the
hinge: when determinism breaks, the NL rescues it.
**The refrain:** **"Blaze once, trail forever."** Introduce in Act 1, echo at close.
**The map assembles progressively** — trails (1–2), trailheads (2), your tools (3–4), the
trailmap container (4) — so Act 6 *names* what the audience watched get built. Seed it twice:
Act 0 ("by the end you'll see the map — spoiler: you're already building it") and Act 4
("note the name trailmap; we'll come back to why it's a *map*").

Changes from v2 (review-driven): Act 4 split into two labeled beats with internals cut to
one-liners (protect the CLI climax); "blaze once, trail forever" refrain added; A* numbers →
backup slide; assets 1+2 merged into one video; memory/`--secret` beat added to Act 2;
title seeded; Act 5 compressed; real Q&A time recovered (~3.5 min); closing grace note added.

### Act 0 — The problem: coverage in the AI era · 0:00–4:00
- Open on the question: **AI is at our disposal — so how do we get good test coverage
  with it?**
- AI + mobile has **no canonical, natural-language way of interacting.** Existing AI
  solutions: platform-specific, or they **lose the link between the NL objective and the
  actions actually performed.**
- Why the link matters: **the test case is WHAT should happen.** The "how" matters mainly
  for assertions you deliberately want mechanical/handwritten. (Assertions thread returns
  in Act 2 with `verify:` steps — don't leave it dangling.)
- Our scale makes it concrete: the 7-target matrix with product imagery — Android
  phone/tablet, iPhone, iPad, **Square Terminal, Square Handheld, Square Register**.
  One login flow, seven targets, hundreds of trails (projecting thousands). Say SEVEN, not 8.
- Who I am: 10+ years in UI testing; Droidcon NYC 2016 (robot pattern + screenshots);
  Droidcon NYC 2025 with Brian (agent loop internals).
- Title seed: "by the end you'll see what the map is — spoiler: you're already building it."
- Hand-off: "Our answer is a thesis about natural language and determinism."

### Act 1 — The thesis: blaze once, trail forever · 4:00–9:00
- Refresher in one slide: agent drives the real app from NL → saved as a **trail** (YAML) →
  **deterministic replay, zero LLM calls** in CI. **"Blaze once, trail forever."**
- "LLM as compiler": NL = source, trail = bytecode, device = runtime.
- **YAML is the shared surface**: LLM constructs it, humans edit it, **git gives the
  historical perspective** on how a test evolves.
- **The acceptance gate:** AI authors (and heals) without human interaction — but a human
  **accepts** what was generated, and the **commit blesses it**: "yes, this is how this
  natural-language case materializes on this platform." Agent-authored, human-approved.
- Self-heal = the hinge between the threads: recording fails → LLM re-solves *just that
  step* from the NL. Off by default (failures stay actionable); **% passed via self-heal =
  your recording-staleness health signal**. (A* cost numbers 1.0/5.0 → backup slide.)
- State the spine: "natural language on top, determinism underneath — everything that
  follows defends one of those two."
- 📼 ASSET A (first half): zero-LLM replay clip.
- Hand-off: "That's the thesis. Production scale is what stress-tested it."

### Act 2 — One journey, one file: unified format + trailheads · 9:00–15:30
- v1 world: one trail file per platform. Worked, then broke: file explosion + **NL drift**
  between platform copies — the "one business case" value prop quietly broke.
- The fix: **unified trail format** — ONE file = the user journey; NL exists once;
  per-device recordings under `recording:` keyed by classifier; **closest-wins** lineage
  (`android-phone` → `android`). Show the real YAML, incl. a `verify:` step (assertions
  callback). Honesty hedge ready for Q&A: multi-platform example is the spec + Block
  production shape; public repo's committed unified trails are single-platform so far.
- **Memory/parameterization (60s, closes the drift anecdote):** `{{memory.var}}` seeding,
  `--secret` redaction, and **reverse-substitution** so recordings never hardcode stale
  credentials. (Guaranteed Q&A topic otherwise: test accounts/secrets in CI.)
- **Trailheads**: a tool designated as the trail's starting point — the test-setup method.
  Deterministic setup (clear app data, right launch args, land in a known state) is the
  precondition for deterministic replay; most flaky tests die right here.
- Honest beat (concrete > generic): **trail YAML v2 was fully designed and never shipped**
  — reality overrode a finished design doc on the way to unified.
- Hand-off: "Unified files fixed drift. Duplication was next — and I'd seen that problem
  ten years ago."

### Act 3 — What vs How: the principle that survived 10 years · 15:30–21:00
- 2016 callback: robot pattern — separate WHAT you test from HOW. Still running in Square's
  Espresso suites today.
- The LLM twist: an LLM wants to decide **what** to do next; making it figure out **how**
  explodes its context on decisions that should be one decision.
- **Custom tools = robot methods**: `login`, `addItemToCart`. **Your app has its own
  tools.** Implementation changes → fix the tool once; trails on disk never change.
- The screenshots thread from 2016, upgraded: per-step **screenshot + view hierarchy +
  logs**. "A screenshot was worth a thousand words — now the LLM has ALL the context it
  needs to solve failures and iterate."
- Reporting architecture (kept tight): **standalone report, servable from any CDN**, zip
  downloadable for you *or an agent*. **No SaaS backend.**
- 📼 ASSET A (second half): the replay's own report walked through — screenshots,
  hierarchy, logs, LLM transcript. One video serves Acts 1+3 and shows the CDN/zip point.
- Hand-off: "Tools are the unit of reuse. Two questions decided everything: who gets to
  write them, and how do agents reach them?"

### Act 4 — Two beats: tools as code, then the CLI pivot · 21:00–27:30
- **Beat 1 — WHO writes tools (21:00–24:00):** Kotlin, compile-time registration → fork +
  rebuild = a wall for external teams, too slow for agents. Ship a binary (Homebrew) →
  nobody can compile tools in → **inversion: tools live on the filesystem in a trailmap**
  (npm packages). TypeScript because **types ARE the schema, TSDoc IS the description** —
  and it's the language LLMs edit best. (Engine/runtime internals = one line: "runs in an
  embedded JS engine, identical on host and device." QuickJS/Bun/codegen → backup slides.)
  Title seed #2: "note the name — *trailmap*. We'll come back to why it's a map."
- **Beat 2 — HOW agents reach tools (24:00–26:30):** the CLI pivot (early 2026). MCP setup
  friction: configure → restart session; dev-loop change → disconnect. As a CLI: install
  and use in the SAME session. The fear — losing NL while exposing tools — resolved:
  each target is a trailmap, the CLI exposes its **toolbox**, and the agent **attaches the
  NL step to every tool call**. Land it unhurried (60–90s): **"driving the device IS
  constructing the trail"** — this resolves Act 0's problem statement. (One line so it
  doesn't read as trashing MCP: `trailblaze mcp` still exists as a proxy; the CLI is the
  primary surface.)
- **Guardrails (26:30–27:30):** one param change ripples across hundreds of YAMLs that
  don't run on PR checks. Strict YAML parsing + the **tsc trick** — recorded calls
  transpiled to `client.tools.x({...})` and compiled. (Keep the trick; remapping mechanics
  live on the slide, not in the narration.)
- Hand-off: "Authoring got fast. The other half of slow was the driver."

### Act 5 — Going ~3x faster: owning the driver · 27:30–30:00 (compressed)
- Maestro first (right call; "not a permanent coupling" from day one) → the walls:
  ~500–700ms/action "screenshot sandwich", lossy tree normalization, private-API reflection.
- Our Android **AccessibilityService** driver: single-pass native tree, event-based settle,
  stable refs → **~100–150ms/action, ~3x end-to-end**. Numbers live on the slide.
- Honest beat: iOS still on Maestro (replacement designed, not landed); web always Playwright.
- Hand-off: "We'd protected the language; now the determinism underneath it is fast.
  So — the map."

### Act 6 — The map, and where it goes next · 30:00–34:30
- Title payoff = a *naming*, not a reveal: "you've watched this map assemble for half an
  hour" — trails (well-worn paths), trailheads (entry points), custom tools (your app's
  verbs), the **trailmap** container. Composable, and it's YOUR surface exposed to the LLM.
- Future step: **waypoints** — named assertable places, shortcuts between them, the
  interconnection graph. Scaffolding shipped (schema, matcher, assertWaypoint, CLI, graph
  viewer); honestly framed: an idea not fully proven yet. "I'm bullish; here's where it
  could take us": `goTo(waypoint)` via pathfinding, semantic recordings, one nav fix
  repairing every trail that used that edge.
- 📼 ASSET: waypoint graph viewer flythrough — **label the calendar trailmap as a Google
  fixture app, not a Block app** (protects the honest framing).
- Hand-off: "The whole recipe, one slide."

### Act 7 — The recipe + CTA · 34:30–36:30
- Recipe slide reprises the spine: NL on top (trails in git, what-not-how tools) ·
  determinism underneath (trailheads, typed + validated recordings, native driver) ·
  self-heal as the hinge and health signal → keep the automatable automated; spend humans
  on new features and hardware. Refrain: **"Blaze once, trail forever."**
- Walk away: `brew install block/tap/trailblaze` → grab the Trailblaze skill → tell
  Claude/Codex to use it on YOUR app. QR to repo.
- Closing grace note (15s): the robot pattern outlived every framework it was written in —
  **now the robots write the tests.**
- Real Q&A: ~36:30–40:00.

### Pre-recorded assets to produce (by Jul 16) — now 3 (was 5)
1. **ASSET A (two-part):** zero-LLM trail replay → then walk its generated report
   (screenshots/hierarchy/logs/CDN/zip). Serves Acts 1 + 3.
2. **ASSET B:** agent driving via CLI toolbox with NL steps attached (`step --save` flow),
   ending with the saved trail file on screen. Serves Act 4.
3. **ASSET C:** waypoint graph viewer flythrough (calendar fixture app). Serves Act 6.
   (Nice-to-have if time: self-heal run showing SucceededWithSelfHeal.)

### Q&A preempts (backup slides)
- LLM token cost of authoring/blazing a run; replay = $0.
- "We already have Espresso/Compose tests — why this?" (positioning: complements; robot
  pattern lineage; NL layer = exit strategy).
- "Did you abandon MCP?" (no — proxy remains; CLI is primary).
- How do 7 targets actually run in CI? ANSWERED: on-demand pipeline, any set of trails
  filtered by device type; latest build by default, exact build pinnable for regressions.
  (Whether to name the device-farm vendor still Sam's call.)
- Secrets/test accounts (covered in Act 2, backup detail: --secret, sensitiveMemoryKeys).
- A* cost model + execution modes detail. QuickJS/Bun runtime detail. Codegen pipeline.

## Demo app decision (slide 12, ASSET A — side-by-side CLI blaze vs. recording replay)

Sam's constraints: no toy demos (wikipedia/calculator/contacts), no other company's app
(affiliation/bias), must be committable to block/trailblaze as a real example trail —
"not smoke and mirrors, literally something sitting out there." Gmail-compose is a great
personal-automation STORY but Google Auth + affiliation make it a bad committed demo —
keep as a spoken anecdote.

Options (original ranking; statuses from Sam's 2026-07-12 answers):

**A. The droidcon conference app itself — ❌ REJECTED by Sam** ("we should use a real
app"). Was: Touchlab's DroidconKotlin (github.com/touchlab/DroidconKotlin), trail =
"find this talk and bookmark it."

**B. Company-product b-roll — ❌ REJECTED by Sam** (representing the company/product;
can't clear it — and not committable as an example anyway). Direction instead: general
user/developer experiences for developer education.

**C. Mastodon (official app, OSS).** Matches Sam's personal use case — content that lives
only in a mobile app. Browse feed / post / verify. Messy real-world UI (infinite scroll,
pull-to-refresh) shows off scrolling + assertions. Committable. Needs a test account.

**D. Thunderbird for Android (OSS, ex-K-9).** The email-compose use case WITHOUT Google
Auth: compose → send → verify in sent folder against a test IMAP account. Committable.

**E. Goose desktop (Block's own OSS agent).** Trails already exist in the repo;
"Trailblaze testing an AI agent" is memorable — but desktop, not mobile, for a Droidcon room.

**Round 2 (Sam's direction, 2026-07-12): a REAL app on a stock Android device.**
- Gmail — ❌ privacy (people would see his email; Google Auth pain).
- Google Calendar — "great because we already have it."
- **Contacts (Sam's new idea)** — "a great use case of being able to define a unified
  trail with one goal and have it implemented in both places [Android + iOS]. It's also
  not completely trivial, so it could be the one we end up using."
- Sam: "We have the power to do both. So maybe I could get some agents working on both."
- usa.droidcon.com — Sam's concern: **ephemeral** — the site changes next year, so the
  committed example rots; "only useful at this one moment, but it still shows off
  everything and I kind of like that."

**Round-2 repo audit (2026-07-12, verified on main):**

*Calendar trailmap* (`trails/config/trailmaps/calendar/`, target Google Calendar,
`com.google.android.calendar`):
- **Exactly 100 waypoints + 64 shortcuts committed** — the "100/64" ASSET C numbers ARE
  this corpus. Quality is high: waypoints carry `required` + `forbidden` selectors with
  prose descriptions (day_view forbids the multi-day "Open Day View" cell — real
  discriminators, not toy matchers); ~70 have example.json + example.webp screenshots.
- Coverage includes the notoriously painful corners: recurring-event edit/delete dialogs,
  custom recurrence, timezone/repeat/notification pickers, Google sign-in flow, permission
  dialogs (notification/location/photos), notification-fired + snooze, share intents,
  widget config, quick-create in ~10 state variants.
- Gaps: **zero committed calendar trails** (it's a map with no journeys), tools = 2 thin
  launch tools only, Android-only (ios/web platform stubs empty).
- Verdict: complex enough to be impressive **as the map** (ASSET C ready essentially
  today); ASSET A's journey must be authored (which is fine — authoring is the demo).

*Contacts* — richer than assumed, and asymmetric in a USEFUL way:
- `trails/config/trailmaps/contacts/`: ONE trailmap already targeting BOTH platforms —
  Google Contacts (android) + Apple Contacts (`com.apple.MobileAddressBook`, ios).
  ~103 iOS waypoints + 81 iOS shortcuts committed; Android side has none.
- `examples/ios-contacts/` = the repo's self-described "canonical mobile reference":
  10 typed TS tools WITH unit tests (createContact, deleteContact, openContact,
  searchContacts, searchAndVerify, verifyContactStructure, addPhoneNumber,
  dismissKeyboardIfPresent, openApp, shared.ts utilities) + a per-target system prompt.
- `trails/ios-contacts/`: 18 committed iOS trails; `trails/contacts/`: 3 baby Android
  trails. **All in the OLD per-platform format** (`ios-iphone.trail.yaml`,
  `android.trail.yaml`) with `blaze.yaml` (NL-only source) sitting next to each —
  source + bytecode as a literal directory listing.
- The unified-contacts demo therefore re-enacts Act 2 with the repo's real files: take
  the journey that already exists on iOS ("create contact 'Trailblaze Demo', verify in
  list" — recording = contacts_ios_openApp + contacts_ios_createContact), materialize
  the SAME file with an android-phone recording → one unified trail, committable, and
  it upgrades the repo's own examples off the v1 shape. Slide 17's fictional
  `myapp_signInViaUI` YAML can later be swapped for this real file.

*Proposed asset division (both apps, per Sam's "power to do both"):*
- ASSET A (side-by-side + report walk): **Calendar** — "Add 'Trailblaze: Map Your App
  for AI' — Jul 17, W222 B — with a reminder"; verify = event on the day grid.
- ASSET B (CLI authoring): **Contacts** — blaze the Android half into the unified file
  that already carries the iOS recording; end-card = one file, two `recording:` blocks.
- ASSET C (graph flythrough): **Calendar's 100/64 map**; optional 5-second flash of
  contacts-iOS (103/81) as a second map — proves pattern, not one-off.

*Agent workstreams for Sam to kick off:*
1. **Calendar journey** — author + record the add-event trail on a stock emulator with a
   fresh test Google account; pin the Calendar app version; archive the run (ASSET A
   needs the replay + its report). Acceptance: trail replays zero-LLM; report zip opens.
2. **Contacts unification** — write the unified trail (ios-iphone recording ported from
   test-create-contact-basic; android-phone recording blazed fresh via CLI, captured on
   video for ASSET B). Acceptance: one file, both classifiers, both replay green.
   Stretch: backfill a starter set of Android contacts waypoints so the map story isn't
   iOS-only.

*usa.droidcon.com framing (Claude's take):* the ephemerality is an ARGUMENT, not just a
flaw — "this site will be different next year" is literally the staleness thesis
(recordings rot; the NL journey survives; re-blaze re-materializes). Use as a
moment-in-time flourish (b-roll, or committed as a dated `examples/droidcon-2026/` with
a README saying it's a snapshot that will rot by design). Not the anchor — Sam's own
read: web is less impressive for this room.

## Cross-app demo scenarios (2026-07-12, Sam + Claude design session)

> **STATUS 2026-07-11 (round 3): calendar half PARKED.** Sam: "Let's just do
> contacts then. Let's just ship an example. Time's running out." Scenario B and
> the Etar work below stay as reference/backup narrative; the shipping demo is
> the contacts-with-photo trail — see "Round 3: contacts-only pivot" below.

**Decision: focus = Calendar + Contacts.** Sam wants COMPLEX, CROSS-APP use cases
("that usually works out pretty well") showing multiple layers. His sketch: start from
a message → create contact → add picture → save number → back to the message → send to
the new contact. His observation to honor: with `target: contacts`, only the contacts
custom tools load — the other apps run on CORE tools (tapOn, inputText, launchApp,
assertVisibleWithText, assertWithAi…). That texture change at the app boundary is a
TEACHING moment, not a bug. Docs also list **cross-app memory** as a built-in agent
feature (docs/index.md, Mobile-Agent-v3 lineage) — grounding for the narration.

### Scenario A — "Unknown number → teammate" (flagship wow b-roll; ASSET B candidate)
```yaml
- config:
    title: "Cross-app: turn an unknown number into a contact, then reply by name"
    target: contacts
- prompts:
    - step: Open Messages and find the conversation from the unknown number ending in 0134
    - step: Start creating a contact for that number from the conversation
    - step: Name the contact "Casey Trailblaze" and save the number as mobile
    - step: Add a photo to the contact using the camera
    - verify: The contact card shows the name, mobile number, and photo
    - step: Return to the conversation in Messages
    - verify: The conversation now shows "Casey Trailblaze" instead of the raw number
    - step: Reply "Welcome to the team!" and send it
    - verify: The sent message appears in the thread
```
Layers: 3 apps (Messages → Contacts → Camera → Messages) · custom tools ONLY inside
contacts (createContact/addPhoneNumber/verifyContactStructure) · core-tool fallback
outside · the thread-renames-itself assertion = observable cross-app side effect ·
same NL on iOS/Android with genuinely divergent recordings (Google Messages "Add
contact" chip vs iOS thread-header → Info → Create New Contact) · iOS photo leg lands
on committed waypoints (camera_capture, visual_identity_photo, photo crop).
**Feasibility traps:** Android emulator — inbound SMS seedable (`adb emu sms send
<num> <text>`; candidate trailhead tool via host-side exec), outbound shows in-thread
w/o delivery = verify against the thread, camera = emulated scene. **iOS Simulator has
NO SMS path (no simctl sms verb)** → record the iOS variant on a REAL iPhone, or demote
iOS to the contacts-only core (create + photo + verify). NOT CI-safe as committed
example because of the SMS dependency — treat as recorded b-roll.

### Scenario B — "Invite the new teammate" (Calendar × Contacts; committable + ASSET A candidate)
**UNIFIED — same NL on Android AND iOS (Sam confirmed wanting the iOS stock version).
Steps name the JOB, not the platform's UI vocabulary** (Android says "Add people,"
iOS says "Invitees" — the recordings diverge, the NL never does):
```yaml
- config:
    title: "Cross-app: create a contact, then invite them to the talk"
    target: contacts   # or a composed demo trailmap — open question below
- prompts:
    - step: Create a contact "Casey Trailblaze" with email casey.trailblaze.demo@example.com
    - verify: The contact shows the name and email
    - step: Open the calendar app and go to July 17
    - step: Create an event "Trailblaze: Map Your App for AI" at 1:00 PM in room "W222 B"
    - step: Invite Casey Trailblaze to the event
    - step: Set a 30-minute reminder
    - step: Save the event
    - verify: July 17 shows the event with Casey invited and a reminder set
```
**Android — two tracks (post clean-CI probe, see subsection below):**
- *Recorded b-roll track (Sam's own Pixel, Google Calendar):* every calendar beat lands
  on a COMMITTED waypoint: quick_create_expanded, add_location, add_people +
  add_people_typing (guest autocomplete pulling from Contacts = the cross-app payoff),
  notification_picker, event_detail_with_attendees. ASSET C can light up BOTH graphs
  and trace this trail across them. Stretch beat (timing-fiddly): let the reminder fire
  and snooze from the shade — event_notification_fired + snooze_options waypoints exist.
- *Committed CI track (clean AOSP image, per Sam's constraint):* the committed calendar
  trailmap targets `com.google.android.calendar` resource-ids — NOT present on the clean
  image, and the stock AOSP Calendar is view-only (stripped editor). CI runs on sideloaded
  **Etar** with a trailhead-seeded local calendar (full recipe below). Etar waypoints
  would be NEW (`ws.xsoh.etar` ids) — which is a feature, not a bug: blazing them fresh
  is exactly what ASSET B shows.

**iOS (stock Apple Contacts + Apple Calendar):** beat-for-beat mirror; Invitees
autocomplete searches Contacts. **GATE — now governing under the clean-CI constraint:
the Invitees row only appears on an invite-capable calendar (iCloud/Google/Exchange);
a bare simulator's local "On My iPhone" calendar hides it, and Sam ruled out signing
in.** So the accountless iOS variant REPLACES the invite beat. Candidates (agent spike
to pick): (a) give Casey a **birthday** during contact creation → the stock Birthdays
calendar shows it — still a genuine Contacts→Calendar cross-app payoff, zero accounts
(verify Birthdays calendar appears accountless on a fresh simulator); (b) event with
location + 30-min reminder only, verify: on the event detail. Contacts half is iOS's
STRONG side (typed tools + 103 waypoints) and already runs accountless in
ios-contacts-trails.yml (macos-26 / iPhone 17 Pro).

**Symmetry to narrate:** Android = calendar mapped / contacts thin; iOS = contacts
mapped / calendar unmapped. Same trail runs on both — closest-wins recordings +
core-tool fallback absorb it. Stage line: "maps mature independently; the journey
doesn't care."

**Trailhead detail (feeds slide 19's narration):** Calendar's first Contacts-autocomplete
triggers a one-time permission dialog → replay non-determinism. Pre-grant in the
trailhead: `xcrun simctl privacy booted grant contacts com.apple.mobilecal` /
`adb shell pm grant`. A concrete trailhead story instead of generic "clear app data."

**Caveat, resolved by the clean-CI design:** on a LOCAL (sync-adapter-less) calendar
with no account on the device, saving never emails anyone — the invite is a provider
row, not an outbound message. Belt-and-suspenders: the contact uses **@example.com**
(reserved domain, no real mailbox). The old "designated test address" worry only
applies to the Google-Calendar b-roll track on Sam's own device.

### Clean-CI probe results (2026-07-11, live on disposable AVD `tb-probe-clean-34`)

**Sam's governing constraint (verbatim intent):** run from a **clean stock emulator or
simulator**, **no sign-in at all**, so the demo can run in the open-source CI.

Probed empirically on `system-images;android-34;default;x86_64` (the same family
clock-trails.yml uses — reactivecircus/android-emulator-runner, api-level 34):

1. **What the clean image ships:** com.android.calendar / contacts / dialer / messaging
   all present. CalendarProvider has **ZERO calendars** out of the box.
2. **Stock AOSP Calendar is view-only.** Its editor is stripped from the APK —
   INSERT intent crashes internally (`ClassNotFoundException:
   com.android.calendar.event.EditEventActivity`) and bounces to week view. Dead end
   for any create-event demo. (Google Calendar is not on `default` images.)
3. **Stock AOSP Contacts editor fully works accountless.** Contact "Casey Trailblaze"
   + email created via UI, lands in ContactsProvider (raw_contacts _id=1) on the
   device-local account.
4. **Fix: sideload Etar** (`ws.xsoh.etar`, F-Droid, OSS fork of the *unstripped* AOSP
   Calendar, ~8.4 MB, apk cached fine for CI). Full accountless editor: title, date
   (Jul 17 2026 reachable), time, **Guests**, location, reminder, recurrence.
5. **Cross-app payoff VERIFIED end-to-end:** typing "Casey" in Etar's Guests field
   pops ContactsProvider autocomplete → "Casey Trailblaze
   casey.trailblaze.demo@example.com" → tap → chip → DONE saves → provider shows
   `attendees: event_id=3, attendeeName=Casey Trailblaze,
   attendeeEmail=casey.trailblaze.demo@example.com`. That `content query` on
   `content://com.android.calendar/attendees` is a deterministic `verify:` hook.

**Trailhead recipe (all adb, all CI-safe, each line has a why):**
```bash
# 1. Seed a LOCAL calendar — provider ships empty; editor needs a writable calendar.
#    URI must be single-quoted through adb shell (the & otherwise splits the command).
adb shell 'content insert --uri "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=local&account_type=LOCAL" --bind account_name:s:local --bind account_type:s:LOCAL --bind name:s:Local --bind calendar_displayName:s:Local --bind calendar_color:i:-16776961 --bind calendar_access_level:i:700 --bind ownerAccount:s:local --bind visible:i:1 --bind sync_events:i:1'
# 2. Install Etar (full calendar editor).
adb install etar.apk
# 3. Disable the stripped stock Calendar → Etar becomes the ONLY calendar app:
#    no app-chooser dialog ever appears, and "open the calendar app" is unambiguous.
adb shell pm disable-user --user 0 com.android.calendar
# 4. Pre-grant runtime permissions — kills two replay-nondeterminism landmines:
#    without READ_CONTACTS the guest autocomplete SILENTLY shows nothing;
#    without exact-alarm access Etar's first editor open bounces to Settings.
adb shell pm grant ws.xsoh.etar android.permission.READ_CONTACTS
adb shell appops set ws.xsoh.etar SCHEDULE_EXACT_ALARM allow
```

**Etar facts for the waypoint/tool authors:**
- `EditEventActivity` is **not exported** → launch via implicit INSERT intent only
  (`am start -a android.intent.action.INSERT -t "vnd.android.cursor.item/event"`),
  never by explicit component. With stock Calendar disabled there's no chooser.
- Attendees row sits below the fold → dismiss keyboard + scroll before it's visible.
  Resource-ids: `ws.xsoh.etar:id/attendees`, `add_attendees_row`, `attendees_icon`.
- The autocomplete suggestion popup renders in a separate window that a plain
  uiautomator main-window dump misses (screenshot sees it) — nice concrete beat for
  the driver/a11y-tree discussion if it comes up in Q&A.
- Force-stop before re-firing INSERT: a foregrounded Etar swallows the intent into the
  existing task ("intent delivered to top-most instance") without opening the editor.

**NL stays unified across all three calendars.** "Open the calendar app / create an
event / invite Casey" runs against Etar (CI), Google Calendar (Sam's Pixel b-roll),
and Apple Calendar (iOS) — recordings and waypoints diverge per app/platform, the
journey doesn't. (Don't over-claim on stage: recordings are keyed by platform
classifier, so an Etar recording doesn't replay on Google Calendar — it's the NL
source that's shared.)
Share the finished contact card from Contacts into Messages (share sheet = OS
connective tissue; Android intents vs iOS share sheet, same NL).

### Open technical question for the agent workstreams (answer FIRST)
Sam's wrinkle — "we'd only get contacts tools" — may have a better answer than "that's
fine": trailmaps compose via `dependencies:` (docs/trailmaps.md: transitive, field-level
closest-wins; waypoints wired through loadResolvedRuntime). **Empirically check whether a
tiny `trailmaps/droidcon-demo/` with `dependencies: [trailblaze, contacts, calendar]`
surfaces BOTH apps' tools + waypoints in one run.** If yes: the demo trailmap IS the
composition story on stage ("maps compose"). If no: run Scenario B with target:
contacts and narrate the boundary honestly (calendar side on core tools — still lands
on calendar waypoints? verify; waypoints may load only for the active trailmap).

### Layer inventory the pair covers (for slide narration)
custom tools ↔ core tools boundary · waypoint assertions inside the map · cross-app
side-effect verification (thread rename, guest autocomplete) · camera/permission
interruptions · unified NL + divergent per-platform recordings · trailmap composition
(if dependencies pan out) · zero-LLM replay of a 3-app journey.

Format note: the side-by-side IS the thesis — left (blaze, LLM thinking, slow) still
working while right (replay, zero LLM) finishes. Let the speed gap be the punchline.
Could show a wall-clock/step counter on each side.

## Round 3: contacts-only pivot (2026-07-11) — THE SHIPPING DEMO

**Sam's call:** drop the calendar half, contacts only, ship an example now, and
make it richer than create-basic — "maybe one that takes a photo of the contact."

**Shipped trail: `trails/contacts/create-contact-with-photo/blaze.yaml`** — one
unified NL source, both platforms, no `driver:`/`platform:` pinned (CLI `-d`
decides; config driver is optional — TrailblazeDesktopApp.kt resolves request
driver first). Target `contacts` already declares BOTH `com.android.contacts`
(clean AOSP image) and `com.google.android.contacts`, plus iOS — no trailmap
changes needed. The photo step is the one-line cross-platform philosophy:

```yaml
- step: Add a profile photo to the contact — take a new photo if there's a
        camera, otherwise pick the first photo in the library
```

Android emulators have a camera; iOS Simulators don't (library-pick wins there,
after `xcrun simctl addmedia booted <photo>.jpg` seeds it). Same NL, honestly
divergent recordings.

### Android photo flow — probed end-to-end on clean AOSP api-34 (`default`)

**FOUR app surfaces, zero accounts, provider-verifiable** — richer cross-app
story than the calendar version:
1. Contacts editor ("Saving to **Device**" — accountless local save); camera icon
   on the avatar → "Change photo" dialog: **Take photo / Choose photo / CANCEL**
2. Take photo → `com.android.camera2` CaptureActivity, live **virtualscene**
   feed (the low-poly-cat living room) → shutter → review with
   `com.android.camera2:id/{done_button,retake_button,cancel_button}`
3. done → `com.android.gallery3d` filtershow **CropActivity** → SAVE
   (`com.android.gallery3d:id/filtershow_done`)
4. back in the editor with the photo as header → SAVE →
   `content query --uri content://com.android.contacts/contacts --projection
   display_name:photo_uri` → `photo_uri=content://com.android.contacts/display_photo/1`
   = the deterministic verify hook.

**Device/CI facts:**
- AVD camera: `hw.camera.back=virtualscene` (pretty) or `emulated` (avdmanager
  default, test-pattern) — BOTH capture fine. CLI flag `-camera-back virtualscene`
  works too. reactivecircus runner: supplying `emulator-options` REPLACES its
  defaults (`-no-window -gpu swiftshader_indirect -no-snapshot -noaudio
  -no-boot-anim`) — repeat them and append `-camera-back virtualscene`.
- "Choose photo" on a clean image = EMPTY gallery → the NL conditional
  naturally resolves to camera-on-Android, library-on-iOS.
- No permission dialogs anywhere in the Android flow (system apps).
- No trailhead seeding needed at all for contacts (unlike calendar).

### Android recording — EARNED (2026-07-11, blaze on clean AVD, 6m 41s, Succeeded)

`trails/contacts/create-contact-with-photo/android.trail.yaml`, driver
`ANDROID_ONDEVICE_ACCESSIBILITY`. Every beat pinned to deterministic selectors —
zero per-run LLM: `contacts_android_launchApp` (custom tool) → notification-
permission ALLOW recovery → FAB → name/phone/email inputs → photo hop
(`photo_touch_intercept_overlay` → "Take photo" → `camera2:id/shutter_button` →
`done_button` → `gallery3d:id/filtershow_done`) → save → 3-assert verify (incl.
`contentDescriptionRegex: Call Mobile 555-0134` and the photo overlay).
The agent saved early on the email step, then RE-ENTERED edit mode for the photo
— recorded honestly, replays consistently. "Recordings are earned, not written"
now has a live exhibit. `session save` gotchas: requires `--title`, and it
slugifies the title (colon included) into the output dir — move the file next
to blaze.yaml after. Shared-machine gotcha: the user-level daemon
(~/.trailblaze) is a singleton — a concurrent trailblaze session (a separate
nightly-validation session was live on this machine) can stop/replace it
mid-run: replay attempt 1 died "Daemon unreachable", attempt 3 delegated to THE
OTHER session's daemon (wrong config dir → `contacts_android_launchApp` "not
registered in this session's tool repo"). **Escape hatch: `trailblaze run
--no-daemon`** — in-process, immune to daemon contention; use it for local
replays whenever another session might be active.

### iOS probe results (2026-07-11, agent, 3 fresh accountless sims) — PHOTO BEAT BLOCKED ON iOS 26.x

- Contacts launches accountless fine; fresh sims are NOT empty: stock sample
  contacts (John Appleseed et al.) AND 6 stock photos IMG_0001–0006 — so "pick
  the FIRST photo in the library" is ambiguous there; seeded photo lands as
  IMG_0007.
- `simctl addmedia` seeding works (Photos.sqlite confirmed).
- **BLOCKER: tapping "Photos" in the Add-Photo avatar sheet CRASHES Contacts**
  — SIGABRT `AG::precondition_failure` (SwiftUI AttributeGraph/ViewGraph), 4/4
  reproducible, on iOS 26.5 AND 26.4, via BOTH maestro XCTest taps and axe HID
  taps → OS bug in the simulator runtime, pre-picker, not driver-related.
  Crash reports: `~/Library/Logs/DiagnosticReports/Contacts-2026-07-11-*.ips`.
  (These popped Sam's macOS "Contacts quit unexpectedly" dialogs — ReportCrash
  surfaces simulator crashes like native ones.)
- iOS 26.x avatar sheet has NO Camera option (Photos/Monogram/Memoji/Emoji) —
  as predicted, sims have no camera. iOS 18.6 uses a different poster-based UI
  (Camera tile present but greyed); Photos tap there didn't crash but rendered
  black/empty — unverified further.
- A11y vocabulary harvested for future waypoints: sheet `Choose an Avatar`, ids
  `IdentityTypePicker`, `NewPhotoButton` (`photo.on.rectangle.angled`),
  `NewMonogramButton`, `NewMemojiButton`, `NewEmojiButton`,
  `OnboardingCancelButton`, `SuggestionListItemButton` (labels `,Serif`
  `,Sans-serif` `,Rounded` `,Condensed`), containers
  `VisualIdentityEditorNavigationStack` / `VisualIdentityView`; contacts-list
  add button = a11y text `add`.

**iOS options for the unified trail (Sam to pick):**
1. Earn the iOS recording for everything EXCEPT the photo beat and pin the photo
   step to the explicit per-platform no-op (`ios-iphone: []`) with a comment
   linking the OS bug — the unified format's escape hatch demonstrated for real.
   (Do NOT blaze the photo step on iOS — the agent would tap Photos and crash
   Contacts again.)
2. On iOS let the profile picture resolve to a MONOGRAM (native, no picker, no
   crash — untested past sheet render) — requires rewording the NL to "give the
   contact a profile picture" instead of photo-specific.
3. Talk-level fallback: Android carries the photo journey; iOS story rides the
   existing `trails/ios-contacts/` corpus already green in CI (create-then-delete
   showcase). Zero new iOS work before Jul 16.

### Ship checklist
- [x] blaze.yaml committed (NL source)
- [x] Android recording EARNED (blaze Succeeded, 6m 41s) + saved next to source
- [x] Recording maintenance from replay attempts: pruned ONE dead recorded
      assert (strict two-line email text the blaze itself had immediately
      superseded with a looser one) + widened the launch wait 3s→8s (cold-start
      after `pm clear` on a loaded host)
- [~] Zero-LLM replay: steps 1–3 PROVEN replaying (attempt 4); full-pass
      validation BLOCKED by host contention, not by the trail — the emulator
      ANR'd ("System UI isn't responding") while a separate nightly-validation
      session ran Gradle tests + an eval emulator + 2 iOS sims concurrently.
      Re-run on a quiet machine or let CI be the clean room:
      `adb shell pm clear com.android.contacts && adb shell pm clear
      com.android.providers.contacts`, then
      `TRAILBLAZE_CONFIG_DIR=$(pwd)/trails/config trailblaze run --no-daemon
      --device android/<serial> trails/contacts/create-contact-with-photo/android.trail.yaml`
      (AVD `tb-probe-clean-34` kept; camera already `virtualscene`)
- [x] iOS decision (round 4, Sam): "It's a bug, so let's simplify and do
      something that has parity" → see Round 4 below
- [ ] CI wiring: clone clock-trails.yml shape → contacts trail; only flip
      `docs/showcase-trails.yml` android slug AFTER recording is committed
      (slugs are load-bearing: report-assets bucket + artifact names)

## Round 4: the parity suite (2026-07-11) — CRITICAL JOBS × 2 PLATFORMS

**Sam's framing:** "The goal is to say 'here are X amount of tests that are
critical jobs to do' and we have trails for iOS and Android effectively."
The photo trail STAYS committed as the Android depth-extra (platforms can go
deeper where the OS allows); the HEADLINE becomes the suite.

**The suite — 4 unified NL sources in `trails/contacts/`, zero platform
vocabulary, every beat parity-safe (no photo picker, no accounts):**
1. `create-contact/` — name + phone + email → verify card (photo trail minus photo)
2. `find-contact/` — create, back to list, search "Casey" → verify result
   (fresh iOS sims ship stock sample contacts — unique name keeps it unambiguous)
3. `add-phone-to-contact/` — create name-only, edit, add number → verify
4. `delete-contact/` — create, delete w/ confirmation → verify GONE
   (mirrors the already-CI-green iOS create-then-delete flow)

Self-containment rule: every trail creates what it needs — any subset runs on
a fresh CI device in any order. Slide math: **4 jobs × 2 platforms = 8 green
runs from 4 files.**

### Recording-earning status
- [x] Android × 4 — ALL BLAZED 2026-07-11 evening (AVD `tb-probe-clean-34`,
      `--no-daemon`, pm-clear reset + POST_NOTIFICATIONS pre-grant per trail).
      Sessions: create_contact_b66e486e, find_contact_d11ec5a0,
      add_phone_to_contact_a4f4a625, delete_contact_0482dbea. Recordings moved
      next to their blaze.yaml, android-phone dups dropped. NOT YET COMMITTED —
      replay verification + commit owned by the background chip.
- [ ] iOS × 4 (fresh accountless sim; safe territory — create/edit/search/delete
      never touch the avatar sheet) — chip-owned
- [ ] Replay-verify all Android recordings on a quiet host (or let CI be the
      clean room) before any slide cell is claimed green — chip-owned

## Gap analysis (2026-07-12, Claude + independent reviewer, merged & ranked)

_Round-1 statuses applied 2026-07-12 after Sam's answers. Deck is now **51 slides**
(economics slide, ASSET B slide, positioning backup added). ✅ done · 🔶 open · ❌ dropped._

### Sam's decisions
1. 🔶 **Demo app — STILL OPEN.** DroidconKotlin REJECTED ("we should use a real app").
   Direction: a real app on a stock Android device. Candidates per Sam: Gmail (✗ privacy —
   people would see his email), **Google Calendar** (maybe), usa.droidcon.com (events site —
   "pretty awesome" as a WEB scenario, less impressive on native mobile).
   Fact in Calendar's favor: the public repo already ships a committed Calendar fixture
   trailmap — `trails/config/trailmaps/calendar/` targeting `com.google.android.calendar`,
   waypoint JSONs + screenshots — so ASSET C's map data mostly exists. Privacy fix for any
   Google app: fresh test account, never Sam's.
2. ❌ **Production evidence — OFF THE TABLE** (Sam: it represents the company/product and
   he can't clear it). Show general user/developer experiences for developer education
   only. "1.5 years in production" stays as a spoken statement; no internal artifacts,
   screenshots, or b-roll. Don't re-raise.
3. ✅ **Reliability — verbal answer only, no number exists.** Banked Q&A wording:
   "We're still getting to our 100% success rate — reliability is good and improving,
   and we're seeing **parity across Android and iOS**." Never invent a number.
4. ✅ **"Recordings are earned, not written"** — approved verbatim (Sam: "love it").

### Content gaps → round-1 status
5. ✅ Economic hook — new slide 3 ("An LLM on every run? Slow. Expensive. Non-deterministic.").
   Sam's positioning framing in its note: replay alone is table stakes (Maestro has it);
   the leg up = NL-first + custom tools in TypeScript + full per-platform fidelity.
   ASSET A pt 1 (slide 13) re-specced: side-by-side, agent blaze vs. replay, wall clocks.
6. ✅ verify:-vs-zero-LLM — speaker-note answer on slide 17, verified in UnifiedTrailStep.kt:
   verify steps are assertion-scoped, auto-terminate, NEVER self-healed; recordable like
   any step; NL-only = deliberate per-run LLM assertion, not a leak.
7. ✅ ASSET B slide added (now slide 34, right after the emotional peak).
8. 🔶 Map-as-artifact — REDIRECTED by Sam: show the **waypoint map of the demo app**
   (ASSET C, slide 41, subtitle updated). Sam gets the data; if demo app = Calendar it
   mostly exists in-repo.
9. ❌ Agenda slide — Sam: not needed.
10. ✅ Act 5 cold open — setup line added to slide 36 + **DRIVER TERMINOLOGY CORRECTION
    (Sam):** the before-column is **UiAutomator**, not "Maestro" — Maestro drives Android
    WITH UiAutomator (gRPC → instrumentation APK). iOS = Maestro's on-device **XCTest
    runner** (verified: HostIosDriverFactory.kt imports xcuitest.*; devlog 2026-05-12
    plans the "Maestro divorce" via our own XCTest runner + event-based settle).
    **Guard: name the underlying tech (UiAutomator / XCTest), not the wrapper.**
11. ✅ 39→40 seam — hand-off line in slide 39's note.
12. ✅ Whose-agent — "Driven by your agent: Claude Code · Codex · Goose" on slide 32.
    🔶 Authoring-cost ballpark for backup 46 still needs a number from Sam (or drop).
13. ✅ CLI verbs — real commands on the ASSET B slide (`trailblaze step --save`, `run`).
14. ✅ Slide-22 granularity — verified against CaptureOptions.kt: per-STEP = screenshot /
    hierarchy / LLM transcript; per-RUN = video + device logs (logcat / scoped iOS log
    stream), ON by default with `--no-capture-*` opt-outs. Network + analytics are NOT in
    OSS capture — moved to speaker note as "Block layers more onto the same reports."
15. ✅ Positioning backup added (slide 49) with Sam's framing + lossy-tree ammo.

### Production checklist (by Jul 16)
- Record ASSET A pt1 (side-by-side + wall clocks), A pt2 (report walk), B (CLI toolbox
  authoring w/ NL steps), C (demo-app waypoint map flythrough) — all blocked on demo-app
  decision; durations unbudgeted.
- Square hardware imagery for slide 5 (currently emoji) — squareup.com/us/en/hardware.
- QR code for slide 43.
- Timing pinch points: Act 1 (8 slides + video in 5 min), Act 4 (now 10 slides + peak +
  ASSET B in 6.5 min — recheck after asset durations land). Act 5 has the only slack.
- Density pass (skeleton → Sam's 55–65-slide style) — timebox to ~1 day AFTER ordering freeze.
- ✅ SDK snippet verified against sdks/typescript: `trailblaze.tool<I,O>(spec, handler)`
  overload, `supportedPlatforms` registration-gate field, `ctx.tools.*` all real.

### Verified clean (no action)
All 7 core-points rounds represented · accuracy spot-checks pass (3x conservative, waypoint
counts labeled as fixture, self-heal defaults, A* costs, product names, brew tap, tsc trick)
· waypoints correctly future-framed · "hike" absent · 7 targets never drifts to 8.

## Ideas

_(brainstorm below — promote the keepers into the deck)_

## Parked / rejected

_(keep the trail of what we decided against and why)_
