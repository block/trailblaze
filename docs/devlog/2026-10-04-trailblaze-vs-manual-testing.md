---
title: "Trailblaze vs Manual Testing: The Remaining Gaps"
type: devlog
date: 2026-10-04
---

# Trailblaze vs Manual Testing: The Remaining Gaps

Trailblaze keeps the lights on: it runs every scheduled case on its schedule and records what it sees.
As we run it today, it doesn't replace a manual tester. Our trails check only what they name, and
miss much of what needs eyes, judgment or hands.

None of these gaps is a limit of AI. Each one can close; the difference is cost: authoring, model
spend, devices or hardware, and people's review time. The table shows how. Until a row closes, keep a person on it.

> **A snapshot, not a verdict.** This is where things stand in October 2026. It isn't a full audit;
> it names the gaps we can see so we can track them as they close.

**Legend:** ✅ covered · 🟡 partial · ❌ not covered

## Tester vs Trailblaze

| Capability | Tester | Trailblaze today | How it closes | Cost |
|---|:---:|:---:|---|---|
| Runs every scheduled case, on schedule | ❌ | ✅ | | |
| Records every step: element tree and text; screenshots and video when enabled | 🟡 | ✅ | | |
| Measures speed every run | 🟡 | ✅ | | |
| Catches speed regressions vs earlier runs | 🟡 | 🟡 | Timings vs earlier runs; time-to-react from video | Rules |
| Checks every expected result, every time | 🟡 | 🟡 | Flag empty verify steps; break the feature, confirm red | Authoring |
| Checks the whole screen: copy, errors, empty states | ✅ | 🟡 | LLM review against a written app standard | Batch LLM + tester |
| Layout, color, wrong language | ✅ | 🟡 | Snapshot diffs; rules over recorded positions; text vs the app's translations | Rules + batch LLM |
| Intuition: "something's off" | ✅ | 🟡 an LLM can review a run; no regular process yet | LLM flags what differs from earlier runs and the app standard | Batch LLM + people |
| UX judgment: "this is confusing" | ✅ | 🟡 an LLM can review a run; no regular process yet | LLM reviews sessions as a new user, ranks the oddest | Heavy LLM + people |
| Questions the test case | ✅ | 🟡 an LLM can compare a case with its runs; no regular process yet | LLM compares the case with what runs show, proposes edits | Batch LLM + tester |
| Unexpected usage | ✅ | 🟡 an LLM can drive it off-script; no regular process yet | Schedule LLM runs that go off-script from a trail's end state | Live LLM on devices |
| Physical hardware | ✅ | 🟡 | Emulated peripherals; lab devices on a schedule | Hardware + people |
| Decides what to test; signs off releases | ✅ | 🟡 an LLM can draft a plan; no regular process yet | AI drafts the plan from what changed and what no trail touches | LLM; a person signs off |
| **Fully covered** | 🟩🟩🟩🟩🟩🟩🟩🟩 **8/13** | 🟩🟩🟩 **3/13** | | |

**Why the gaps today:** most trails check a few elements on a screen with dozens. A trail records
today's behavior as correct, routes past problems a tester would stop at when self-heal is on, and
walks the path and data it was given on emulators and simulators.

## Gaps by kind of app

| Kind of app | What trails miss most |
|---|---|
| **Runs on dedicated hardware** | Peripherals such as readers, printers and scanners · second screens · docking, power and network changes · the physical setting it's used in |
| **Phone app** | Real device models · notifications and links from outside the app · camera and biometrics · interruptions mid-flow |
| **Web app** | Browser differences · screen sizes a trail doesn't set · data that must add up over time · roles and permissions |

## Replays check, batches judge

A replay has no model in the loop, so it's fast, repeatable, and blind to anything it wasn't told
to check. Judgment can run later: every run records the element tree, text and logs (network,
screenshots and video where enabled), and tools can add their own events. That lets a check look
past the screen, the way a tester opens dev tools: a request that failed, an event that never
fired, a total the server disagrees with. An LLM can review all of it in batches, nightly or per
release. Tools mix code and LLM steps, so each check can be code where a rule works and LLM
judgment where it doesn't. Batch review reports findings, not failures, until testers trust it.

Self-heal works the same way: a healed run shows what the model did, not a fix. Landing heals
automatically produced bad recordings, so failures are triaged first and only test drift gets a
rerun to heal or re-record, reviewed by a person.

## Find gaps early

Tag every bug the trails missed with the gap it slipped through, run trails beside manual testing
for several releases, and close the gaps with the most escapes first. Pass rates can't show where
the gaps are; escapes can.
