# Plane Review — droidcon deck

**Status: LIVE EDITING. This session is making real changes to `samdroidcon.md`.**

Sam can see the rendered deck as edits land (Slidev live reload, port 3030), so
we edit directly rather than only capturing feedback. This folder is the review
log + scratch assets; the deck itself is the deliverable.

## Base commit — the diff anchor

Everything this session does starts from:

```
d5b973f0  Add android-map-sources: regen recipe, seed script, 34 micro-trails
```

To see exactly what this session changed:

```sh
git diff d5b973f0..HEAD -- droidcon/
```

**Why this commit and not `84f61c17`:** the session opened on `84f61c17`, which
turned out to be **3 commits behind** `origin/2026-droidcon-us`. Those three
commits touched the deck itself (`samdroidcon.md`, +42/−4) as well as adding the
Android contacts trail map and 34 micro-trails. We fast-forwarded to `d5b973f0`
*before* making any edits, so this session's work sits **on top of** that work
rather than diverging from it. Any later merge is additive, not a clobber.

- Branch: `2026-droidcon-us`
- Deck: `droidcon/samdroidcon.md` (1598 lines, ~74 slides)
- Session started: 2026-07-14

---

## FB-1 — Missing: "what even is Trailblaze?" intro

### What Sam said

> We need to give some history/context intro into wtf is Trailblaze. A screenshot
> of the GitHub `block/trailblaze` repo so we can show it's a real thing. Going
> through the slides we jump straight to the problem statement — but I think this
> talk is about **Trailblaze + the problem**, not just the problem.

### The diagnosis

The deck currently opens cover → problem statement. The audience meets the pain
before it meets the thing. That framing quietly makes the talk "here is a problem
in mobile testing," with Trailblaze arriving later as the answer. Sam wants the
talk to be *about Trailblaze* — the problem is the setup, not the subject.

A repo screenshot does specific work here that prose can't: it's **proof of
existence**. Open source, Apache-2.0, under the `block` org, real stars, real
commits, recent activity. It converts "a tool this speaker built" into "a real
project you could go use on Monday."

### Where it goes

New beat in Act 0, **before** the problem statement. Rough shape:

1. Cover
2. **→ NEW: What is Trailblaze / it's real / here's the repo** ← this feedback
3. Problem statement
4. (existing flow continues)

### Asset needed — GitHub repo screenshot

Attempted a live capture on 2026-07-14. **Did not produce a slide-grade image**:
the browser automation viewport is pinned to 784px wide, so github.com renders
its narrow responsive layout (single column, wrapped sidebar). Fine for reading,
wrong for a 16:9 slide.

**To do on the real machine:** capture `github.com/block/trailblaze` at a proper
desktop width (≥1440px) so the README, sidebar, and file tree sit side by side.
Save as `public/asset-d-github-repo.png` and add to `asset-manifest.txt` to match
the existing asset convention.

**Facts verified live from the repo on 2026-07-14** (usable as speaker notes or
on-slide callouts, and as a check that a later screenshot looks right):

| Fact | Value |
| --- | --- |
| Description | "AI-driven UI testing framework." |
| Org / repo | `block/trailblaze` — public |
| Stars | 247 |
| Forks | 19 |
| Watching | 6 |
| Open issues | 21 |
| Branches / tags | 7 branches, 27 tags |
| License | Apache-2.0 |
| Docs site | block.github.io/trailblaze/ |
| Last commit | `handstandsam`, "yesterday" (i.e. ~2026-07-12) |
| Governance | Code of conduct, Contributing, Security policy all present |

Star count is the number that will age fastest — re-check it the morning of the
talk if it's going on the slide at all.

### Open questions for Sam

1. **How much history?** "History/context" could mean one slide (what it is, it's
   real, here's the repo) or a short arc (why Block built it → what it grew into →
   where it is now). The first is ~30 seconds; the second is 2–3 minutes and
   changes the Act 0 budget.
2. **Screenshot content:** plain repo landing page, or scrolled to show the README
   hero? The landing page proves *realness* (stars, license, activity). The README
   proves *what it does*. They're different jobs — possibly two assets.
3. **Do the numbers go on the slide?** 247 stars is honest and real but modest;
   it can read as either "legit open source project" or "small project," depending
   on how it's framed out loud. Might be stronger to show the repo *chrome* (org,
   license, activity, commit history) and not point at the star count.

---

## FB-2 — (awaiting next note)
