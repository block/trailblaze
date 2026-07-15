# Trailblaze Droidcon Deck — Handoff Brief

**For the next agent (on Sam's laptop). This is the single source of truth for the
narrative-reorder work. Supersedes the scattered plane-review notes.**

Written 2026-07-15 during a plane session. The deck has good *content* but reads like
"random notes" — the job is to give it a MEANINGFUL THREAD. The plan below is decided;
two questions (Part 4) are still open and you should confirm them with Sam before the big
structural cuts.

---

## 0. Logistics (read first)

- Repo: `~/Development/trailblaze-droidcon` (a clone of `block/trailblaze`).
- Branch: `2026-droidcon-us`. Deck file: **`droidcon/samdroidcon.md`** (Slidev).
- Serve locally: `cd droidcon && bunx slidev samdroidcon.md --port 3030 --remote`
  (view at `http://localhost:3030/`; footer shows the true slide number).
- **Push to the FORK, not origin:** remote `basementbot` =
  `git@github-basementbot:basementbot/trailblaze.git` (SSH alias `github-basementbot`
  authenticates as basementbot; plain github.com auths as handstandsam and will be denied).
  `origin` (block/trailblaze) is READ-ONLY for us.
- Safety tags exist: `plane-session-work`, `remote-waypoints-preremrge`.
- Commit style: NO AI attribution / `Co-Authored-By` / "Generated with" footers (repo rule
  in CLAUDE.md — attribute to the human).
- Commit + push after each meaningful revision (Sam wants recoverable checkpoints).

---

## 1. The spine (the reframe — most important)

The deck's current backbone is a *mechanism*: "natural language on top, determinism
underneath." Sam's natural telling (Part 5 transcript) kept returning — five times — to a
different idea: **you can't see what you're testing.** So the real spine is
**VISIBILITY / COMPREHENSION**, and the MAP is the destination (it's literally the title):

> Ten years shipping mobile and we still can't *see* what our tests cover or whether they
> match what the product wants. Natural language + AI lets us write journeys we can read,
> capture rich context every run, and finally see the whole app as a **map**.

Arc: **one screenshot** (2016, one test at a time) → **readable journeys (trails)** →
**your whole app as a map** (2026). Determinism/recordability are the ENABLER, not the headline.

---

## 2. The three differentiators (Sam's words — the "why Trailblaze")

1. Natural language — **plus recorded, deterministic** replay.
2. Your **custom tools, provided as first-class citizens** to the LLM.
3. **Consistent across iOS, Android, and any target you choose.**

(CI-cost/determinism is a SUPPORT line under #1, no longer a headline. "Seven targets"
scale folds into #3 — see open Q1.)

---

## 3. Target flow (execute the deck toward this)

1. **Open — who + the 10-year problem.** Sam / Block / Square Android Foundation, ~1.5 yrs
   on Trailblaze full-time. Ten years on, mobile tests are still hard; Espresso is still the
   fastest way to *run* them — but we still can't *see* what we're testing or tie it to what
   the product wants. Callback: *"A screenshot is worth 1,000 words"* (2016) — even Sam
   couldn't tell what the tests did from the filenames.
2. **The unit — user journeys.** What the product wants has a name: what a user must always
   be able to do; release sign-off. The gap: how does a journey map to the test that runs?
3. **AI shows up — but not the way we need.** NL tests exist; lots of tools. AI *could* do
   everything — **but not how we need it** (can't rerun it, can't trust it, throws away the
   link). Collapse the old "AI is supposed to do everything" hype slide + "The missing link"
   into ONE quick turn here. (Keep the cart line lean — see open Q2.)
4. **What makes Trailblaze different** — the three differentiators (Part 2). Quick: open
   source, used by Cash + Square. (This absorbs today's "What is Trailblaze / How we got here
   / And we use it" intro trio — collapse those three into this one beat.)
5. **A trail, concretely — the evolution.** **iOS Contacts is the running example.** Show a
   trail file grow: just natural-language steps → + **iOS** recording → + **Android**
   recording. Same journey, growing fidelity. This is the "what a trail file looks like" beat
   Sam said MUST come before waypoints. (The existing "The same journey, three ways" slide —
   rework it **iOS-first**: currently it leads with Android; flip to iOS first.)
6. **1,000 tokens — the artifacts.** A screenshot was worth 1,000 words; now it's worth
   1,000 *tokens*. Every run captures what we used to dig through by hand — screenshots +
   view hierarchy + logcat + network — so you diagnose failures fast, and so can the coding
   agent. (This is the reports / ASSET A material, reframed as the payoff of the 1,000-tokens
   hook. Open with "words" in §1, pay off with "tokens" here.)
7. **Your app as a map.** Trails for every critical journey — but Espresso-style you still see
   one test at a time. How do you see them ALL together? Waypoints *(experimental)*:
   analytic-worthy points, a screen + an action, shortcuts learned from real runs. Title
   payoff + vision close. NOTE: Sam flagged the transition INTO waypoints was skipped — needs
   a bridge sentence/slide.
8. **Close** — it's real, `brew install block/tap/trailblaze`, go.

### Concrete moves this implies
- **Move "Ten years on this problem"** from the END of Act 0 to the FRONT (right after cover),
  reworked as the hook (add the "still can't see what we test" + "1,000 words" framing).
- **Move "One user journey"** up to right after the opener.
- **Collapse the intro trio** ("First — what is Trailblaze?", "How we got here", "And we use
  it") into ONE compact "what Trailblaze is + 3 differentiators" beat, placed AFTER the
  problem/journey setup (§4), not before it.
- **Collapse** the merged hype slide ("AI is supposed to do everything") + "The missing link"
  into the single §3 turn.
- **Rework "The same journey, three ways"** to be iOS-first (§5).
- **Reframe the reports/artifacts** stretch around the "1,000 tokens" payoff (§6).
- **"Seven targets"** and **"It's not that easy — yet"**: see open questions before cutting.

---

## 4. OPEN QUESTIONS — confirm with Sam before the big cuts

1. **The mechanism middle (~30 slides).** Between the trail format and the map, the deck has
   the 2016 robot pattern, TypeScript tool authoring, tools-return-data, the CLI/MCP pivot,
   and owning the driver. Almost NONE of it was in Sam's 10-min telling. Is it depth Sam still
   covers in a 40-min slot, or does it shrink hard? This is a 25-slide vs 55-slide decision.
   **Do NOT cut it unilaterally.**
2. **§3 — show it or say it?** For "AI could do everything but not how we need it," keep the
   live cart-demo moment (watch an agent drive a phone), or is that just a spoken line on the
   way to the differentiators? (Sam leaned "call it out" = say it, keep lean.)
3. Placement of **"Seven targets"** (scale gut-punch) — fold into differentiator #3, or keep
   as its own beat? And **"It's not that easy — yet"** — keep, or cut now that the spine
   changed? Neither was in Sam's natural telling.

---

## 5. Sam's free-flow narration (2026-07-15, speech-to-text, lightly cleaned) — VOICE SOURCE OF TRUTH

> Hello and welcome to my talk, Trailblaze: Map Your App with AI. I'm Sam Edwards. I work at
> Block, about 3 years. Square Android Foundation team; on Trailblaze ~1.5 years full-time.
>
> Where we are today: still the same place we were 10 years ago on mobile tests — they're hard.
> Espresso tests are still the fastest way to run tests, but we still have the problem of
> insight into what we're testing and tracking that back to what the product actually wants.
> These product flows used for acceptance tests are called **user journeys** — scenarios and
> actions a user must complete for the app to work as expected; the things needed for sign-off
> before releasing.
>
> We're still shipping mobile apps, still need confidence in what we're shipping and what we're
> checking. 10 years ago when I talked about screenshots with Espresso, I found I couldn't
> understand what we were actually testing. Many tests; if you looked at the filenames you
> didn't know what they did. The robot pattern separates why vs how, and if you pointed an LLM
> at robot-pattern Espresso tests it could probably figure out what you're testing. But the
> bigger problem: knowing how all our tests interconnect and what they validate — it's a
> pyramid: product requirements → end-user acceptance tests → smaller unit tests → edge cases.
>
> The key thing is the user journey, in natural language: this is what I want the user to be
> able to do. And: how does that map to the test actually executed? So we took on natural
> language tests like many others — describe what you want, an LLM navigates and does it. Lots
> of tools now. But Trailblaze has a few differentiators. Trailblaze is open source, used by
> Cash and Square…
>
> [where Sam was heading:] transition into all the artifacts we save — view hierarchy,
> networking logs, everything — not just screenshots. Now that we have a user journey defined
> in natural language, we have a trail that can be run with AI. But with the high-fidelity view
> hierarchy info and more, we can create RECORDABLE trails, and as they run we collect the data
> again so you can diagnose failures easily — full context available to you AND the LLM coding
> agent.
>
> I didn't want to go deep into how we collected all this data. Great that we now have a set of
> trails representing our critical jobs-to-be-done and user journeys. But now the problem: how
> do we make AI better understand what we're doing, and how do we see a holistic view of the
> tests? With Espresso screenshots you see one test at a time — same here. How do you see them
> all working together? How do you see your app as a map? Thinking about this, we came up with
> **waypoints**: a point in the app during a user journey that emits an analytic (something
> you'd track to make sure someone gets something done). To get there, a screen is shown
> representing it and an action is performed. We have waypoints, and **shortcuts** between them
> — known ways to get between them from historical runs.
>
> [Sam's own notes: waypoints are experimental; we skipped how we transition to them. We
> didn't talk about what a trail file looks like — that would've been good before this.]

### Sam's verbatim answers (2026-07-15)

**Differentiators:**
> Differentiated because we have natural language, but we have the recorded deterministic
> tools, and the tools are your contributed custom tools provided as first-class citizens to
> the LLM, and it's consistent across iOS and Android and other platforms that you choose the
> target.

**Example + trail evolution:**
> Show the evolution of a trail file: just natural language steps, then the iOS recording,
> then the Android recording. **iOS will actually be the example.**

**What drops (the "AI does everything" beat):**
> We can call out that AI could do everything but it's not going to do it the way we need it to.

**1,000 tokens:**
> I love the 1000 tokens thing. It goes into us collecting all that context during a run —
> logs, screenshots, networking — things we normally manually dug through, but now all part of
> this trail execution.

---

## 6. Standing decisions / constraints from the whole session (honor these)

- **Back-of-room legibility is a hard rule.** Big type, talking-point FRAGMENTS not prose —
  Sam must know what to say without reading paragraphs off the screen. A global style in the
  cover slide already bumps default markdown bullets (`.slidev-layout ul > li` → 1.5rem);
  keep it. Watch for slides that overflow when enlarged.
- **Running example = iOS Contacts** (Google Contacts, public repo corpus: iOS
  `com.apple.MobileAddressBook`, Android `com.google.android.contacts`; real trailhead
  `contacts_android_createContact` = ACTION_INSERT → new-contact editor). The legacy Act 2
  "coffee shop" example ("Sign in as the QE sender" / "Add a latte") should switch to Contacts
  for consistency — flagged, not yet done.
- **Don't say the GitHub star count aloud** (247 is honest but reads as small). Show repo
  chrome instead.
- **Don't state "1.5 years in production"** as a number — the timeline dates already imply it.
- **Don't name the pre-POC predecessor** ("DragonCrawl") on a slide or aloud. Timeline starts
  at Nov 2024 "proof of concept."
- **Waypoints are EXPERIMENTAL** — frame exactly as Act 6 does: not fully proven, but bullish.
- **Two hard problems** framing (device control + protecting user journeys) is now SPOKEN, not
  a bullet — the merged §3 slide dropped the explicit "controlling devices · testing what
  matters" line.
- 40-minute talk. Current on-slide timing markers are STALE (Act 0 grew; markers still sum to
  ~36:30 and overlap) — recompute the whole timing spine AFTER the order settles, not piecemeal.

---

## 7. Where the slides actually stand right now (already applied + pushed, HEAD ~a0542131)

The deck still has the OLD Act order. Applied incrementally this session (all on
`basementbot/2026-droidcon-us`):
- Act 0 intro trio added (What is Trailblaze / How we got here / And we use it) — TO BE
  COLLAPSED into §4.
- "It's not that easy — yet" added (with an experimental-waypoints plant) — placement TBD.
- Legibility passes on slides 5/8/9; deck-wide bullet bump; "Ten years" rebuilt with
  side-by-side talk cards.
- "The same journey, three ways" (Contacts, Android+iOS) added — becomes §5, REWORK iOS-FIRST.
- Act 0 problem beats condensed: merged "AI is supposed to do everything" + "An LLM on every
  run" into one slide; trimmed "It's not that easy."

Nothing above is destructive; git history + the safety tags recover anything.
Start by serving the deck, reading it top to bottom against §3, and confirming Part 4 with Sam.
