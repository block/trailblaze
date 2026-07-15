---
name: trailblaze-author
description: |
  Use when turning a captured human demonstration (a Trail Runner
  demonstration bundle: demo.yaml + actions.ndjson + per-action
  screenshots and view hierarchies) into a durable, independently
  runnable Trailblaze trail. Trigger when a prompt hands you a
  demonstration bundle directory and asks you to author, generate,
  or produce the trail for it - or to add another platform's
  recordings to an existing trail from a new demonstration.
---

# Author a trail from a demonstration bundle

A human demonstrated a flow on a live device. Every interaction was
captured with evidence. Your job is to produce a trail that runs on
its own: deterministic where possible, resilient where the screen is
dynamic, and **proven by you actually running it** before you call it
ready. You are not transcribing clicks; you are authoring a test that
validates what the human said they were validating.

Work in explicit phases, in order. Do not skip the audit passes and
do not claim `ready` without a passing verification run.

## The demonstration bundle

The launching prompt gives you the bundle directory. A bundle holds
ONE platform's demonstration - bundles are keyed by platform (the
directory is named like `demos/iphone/`, `demos/android/`,
`demos/android-tablet/`), and sibling platform bundles from earlier
sessions may sit beside it. Inside:

| File | What it is |
|------|------------|
| `demo.yaml` | Manifest: target, platform, device classifiers, the trailhead the human picked (name + args) or `manual: true`, and the human's stated objective + notes. |
| `actions.ndjson` | One JSON line per interaction, in order. `phase: "setup"` lines are how the human positioned the app before pressing Start; `phase: "step"` lines are the demonstrated flow itself. Each line carries the gesture (kind, coordinates or text), the hit-tested element, recorded tool YAML, ranked selector candidates, and evidence file names. |
| `start-state.png` / `start-state-hierarchy.txt` | The screen at the moment the human pressed Start. This is what the trailhead must reach. |
| `<seq>-before.png`, `<seq>-after.png`, `<seq>-*-hierarchy.txt` | Per-action evidence. The hierarchy text has one line per element: bounds, type, label, id, interactive flag. |
| `events/*.ndjson`, `network.ndjson` | Captured app event streams and network traffic, when available. Each line has `timeMs`; correlate to actions by time window (between one action's `timeMs` and the next). |

## Working method

Prefer the Trailblaze CLI and its MCP tools over shell spelunking. To learn
what a target supports or how a selector resolves, drive the CLI (and the MCP
device tools) and read this skill's `references/`, rather than grepping the
framework source. When you do need to explore the codebase or read many files
at once, hand that read-only legwork to a cheaper-model subagent and keep the
authoring and verification on yourself.

After every change you make to a trail file, emit a `trail_output` so Trail
Runner can show the current file. Tool calls that need a human decision pause
in Trail Runner until the human approves them, so a slow tool call is waiting on
a person, not hung; keep working the plan and it will resume once approved.

## Phase 1 - Understand

1. Read `demo.yaml` and all of `actions.ndjson` first. Reconstruct the
   story in one or two sentences: where the flow starts, what the human
   did, what the objective says it validates.
2. Read the `start-state-hierarchy.txt` and the before/after hierarchy
   of each `step` action. You need these to ground selectors and to
   pick assertions later; do not author from screenshots alone.
3. If event streams exist, slice them per step by `timeMs` and note
   which steps fired meaningful app events. These tell you what the
   app itself considered to have happened; use them to decide what is
   worth asserting on screen.
4. List the proof points: the on-screen facts that, if visible, prove
   the objective was met. A trail that taps every button but asserts
   nothing has validated nothing.

## Phase 2 - Author

Write a **unified-format** trail: `config:` + `trailhead:` + `trail:`.
Read one existing unified trail from the library for the exact schema
before writing yours. The trailhead is a first-class block, never an
ordinary first step.

**Trailhead:**
- If `demo.yaml` names a picked trailhead, use it verbatim (name +
  args) as the `trailhead:` recording for the demonstrated platform.
- If positioning was manual, inspect the `setup` actions. Prefer a
  durable route: if the target's trailmap has a deeplink-style or
  bootstrap trailhead tool that reaches the observed start state,
  use it (check the toolbox before naming any tool - never invent
  one). Only if nothing durable exists, write the trailhead as a
  descriptive step ("Start at <screen>") so agent-mode execution can
  reach it, and say so in your final summary - it is a weakness the
  human should know about.

**One trail step per demonstrated action**, plus assertions:
- Step text is a natural-language direction a human could follow
  ("Tap the Pay button", not "tapOnElementBySelector ..."). This is
  the cross-platform source of truth and the agent-mode fallback.
- The `recording:` for the demonstrated platform's classifier comes
  from the action's recorded tool YAML and ranked selector candidates.
  Apply the selector rules below; do not blindly copy the default.
- Coalesce noise: a mis-tap the human immediately corrected, or a
  scroll that was purely exploratory, does not deserve a step. The
  trail is the intended flow, not the raw motion log.
- Insert `verify: true` steps at the checkpoints where the objective
  is actually proven (usually after the last action, often mid-flow
  too). Each verify step asserts a proof point from Phase 1, grounded
  in an element you saw in the captured hierarchy - typically a
  recorded visibility assertion on that element plus step text that
  states the expectation in plain language.

**Selector rules (durability order):**
1. Semantic and unique: visible text, content description, or
   resource/accessibility id that appears exactly once in the
   captured hierarchy. Prefer these; they survive layout changes.
2. Structural (child/containment patterns) only when nothing semantic
   is unique.
3. Index-based only as a last resort, with a comment-worthy reason.
4. Raw coordinates: never, unless the ranked candidates offer nothing
   else at all - and then flag it in your summary as fragile.
- Selector strings match the WHOLE property value: `Save` matches only
  a node whose text is exactly `Save`. To match a substring write the
  regex explicitly: `.*Save.*`.
- Dynamic content (prices, dates, counters, usernames) must not be
  pinned exactly. Use a pattern that captures the stable part:
  `.*\$\d+\.\d\d.*` style regex, or assert the stable neighboring
  label instead.
- Keep any `\Q...\E` escaping the recorder emitted; it exists because
  the value contains regex metacharacters.

## Phase 3 - Refine (two mandatory audit passes)

**Selector audit.** For every step, check the selector against the
captured hierarchies: is it unique on the screen where it fires (the
before-hierarchy of its own action)? Does it accidentally also match
something on an earlier screen (which would break a retry or a slow
transition)? Tighten anything ambiguous.

**Validation audit.** Re-read the human's objective, then read your
draft top to bottom and answer: if every step passes, is the objective
actually proven, or did the trail merely navigate? Add or strengthen
verify steps until the answer is yes. Also check the opposite: remove
assertions on incidental content that would make the trail fail for
reasons unrelated to the objective. Test the what, not the how.

## Phase 4 - Prove it

Run the trail yourself with the trail MCP tool, `action=RUN`, against
the same device. This executes the recorded steps deterministically,
runs the trailhead first, and returns per-step pass/fail.

- On failure: diagnose from the returned step results and the
  session's artifacts. Distinguish a bad selector (fix the selector)
  from a timing issue (the screen was not settled; prefer asserting a
  landmark of the new screen in the prior step over sleeps) from a
  wrong expectation (fix the assertion).
- Fix and re-run. Budget: three verification runs. Never weaken an
  assertion just to get green; if an assertion is genuinely wrong,
  fix it, and if the flow itself cannot pass (environment or data
  problems), stop and report honestly.
- Do not run the trailblaze CLI through your shell tool; use the MCP
  trail tool.

## Adding a platform to an existing trail

When the launching prompt says the trail already exists and names the
platform being added, you are extending it, not re-authoring it:

- Read the existing trail first. Its step structure and step text are
  the contract; do not restructure or reword them.
- For each step, add a `recording:` for the new platform's classifier,
  built from this bundle's demonstrated actions under the same selector
  rules. Leave every other platform's recordings untouched.
- Add the new platform's trailhead recording the same way.
- If this platform's flow genuinely differs (an extra screen, a field
  that does not exist), keep the shared step text platform-neutral;
  add a platform-specific step only when unavoidable and call it out
  in your final summary.
- Both audit passes and the verification run still apply - and the
  run must pass on THIS platform's device. A pass recorded earlier on
  another platform does not count.

## Deliver

Write the trail into the destination folder the launching prompt gave
you, then declare the result with exactly one standalone line:

```
TRAILRUNNER_UI {"version":1,"action":"trail_output","trailId":"0/<area>/<slug>","message":"<one line: what the trail validates and its verification status>","params":{"status":"ready","files":"<files you wrote>"}}
```

- `status:"ready"` is allowed ONLY after a verification run passed in
  this conversation. Otherwise emit `status:"draft"` and say plainly
  in `message` what still fails and why.
- Your final text summary must include: the platform this demonstration
  covered, the trailhead choice (and why, if the demonstration was
  positioned manually), any fragile selectors you could not avoid,
  which steps carry assertions and what they prove, and the
  verification outcome (runs attempted, final result).

## Anti-patterns

- Claiming ready without a passing run in this conversation.
- Coordinates when a semantic selector existed.
- Exact-matching dynamic text (prices, timestamps, counts).
- One assertion at the very end of a long flow when the objective has
  intermediate proof points.
- Inventing tool names that are not in the target's toolbox.
- Transcribing every raw gesture, including mistakes, instead of
  authoring the intended flow.
- Weakening or deleting an assertion to make verification pass.
