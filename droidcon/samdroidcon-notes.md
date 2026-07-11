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

## Ideas

_(brainstorm below — promote the keepers into the deck)_

## Parked / rejected

_(keep the trail of what we decided against and why)_
