---
name: trailblaze
description: |
  Use when working with Trailblaze — natural-language device control
  for coding agents across iOS, Android, and web, with replayable
  `.trail.yaml` files as the artifact. Trigger on mentions of
  Trailblaze, the `trailblaze` CLI, `.trail.yaml` files, trailmaps,
  waypoints, or requests to drive / author / debug / run UI tests on
  iOS / Android / web — including authoring a custom typed scripted
  tool, composing an agent's tool surface, or writing a trailmap.
---

# Trailblaze

**Natural-language device control for your coding agent — across iOS,
Android, and web. Every session is a replayable trail you can run as
a test.**

Trailblaze gives your coding agent a single, typed, replayable way to
drive any device. Built-in primitives plus your own typed tools, with
a natural-language source of truth that travels across platforms.
Whether you're exploring an app, automating a workflow, or building
a test suite, the artifact is the same: a portable `.trail.yaml` whose
recorded steps your CI can replay deterministically with no LLM at
runtime.

## What you get

- **Composable, replayable device control for your agent.** Your
  coding agent drives the device through Trailblaze's primitives —
  plus first-class commands of your own, like `login` or `addToCart`,
  defined in a typed language with type-safe bindings. Each step
  records its objective. The resulting `.trail.yaml` is both a
  natural-language source of truth — *what* you're testing or doing —
  and a deterministic execution artifact — *how* it runs. One trail
  can describe a flow across iOS, Android, and web — same
  natural-language steps, platform-specific actions or tools captured
  for each. Agents read the what; the framework handles the how.

- **A cross-platform Trace Viewer.** Open any session — yours or your
  agent's — and inspect view hierarchy, screenshots, video, and
  platform logs at every step. When you want a different selector
  than the one Trailblaze auto-picked for a step, the viewer lets you
  choose from generated alternatives computed against the same
  captured hierarchy — human judgment, no re-recording. Same viewer
  for iOS, Android, and web.

## Native fidelity on every platform

Most multi-platform testing tools expose the *intersection* of what
iOS, Android, and web can do — a generic API that maps onto all
three, at the cost of losing platform-specific capability. Trailblaze
takes the opposite approach: full-fidelity native semantic surfaces,
OS-native primitives, and typed tool sets composed per
`(target, platform)`. Your agent works against each platform's
native UI tree — the accessibility tree on Android, native UI
semantics on iOS, the DOM on web — rather than a flattened
cross-platform abstraction.

The agent picks elements semantically — "the Sign in button" — from
the native hierarchy. Trailblaze computes the platform-specific
selectors behind the scenes. The natural-language test stays the
same; the execution uses each platform's full power.

This only works because an agent is driving. Exposing full native
fidelity to a human is overwhelming — twenty platform-specific
selector strategies per element is no one's idea of a good testing
SDK. Exposing it to an LLM is the point. The agent handles the
complexity; you get the expressive power of native automation with
the consistency of a single natural-language test.

## The two routing keys: device and target

Before you drive anything, know the two routing keys — they're the
foundation every rung's commands build on:

- **Device** — a connected runtime instance. The device specifier
  format is `<platform>/<id>` (e.g. `android/emulator-5554`,
  `ios/E5BDD6FB-…`, `web/playwright-native`). Most commands accept
  either the full `<platform>/<id>` form or just `<platform>` when
  only one device of that platform exists. `trailblaze device list`
  prints each connected device embedded in a copy/pastable example,
  like `trailblaze snapshot --device android/emulator-5554  (…) — …`,
  so the right `--device` argument is ready to paste into subsequent
  commands.
- **Target** — the app context being driven. This is the **discovery
  surface for app-specific tools**: a trailmap published for an app
  (e.g. `<your-app-target>`, or any app-specific trailmap your team ships)
  exposes its custom typed tools through that target. `default` is
  the universal target — what you use when you're not exercising a
  specific app's vocabulary. Many commands accept `--target <name>`
  (or `-t <name>`) in addition to `--device`. If a target is
  configured at the workspace level (in `trailblaze.yaml`), commands
  pick it up automatically; you only pass `--target` explicitly when
  you want to override or when running against multiple targets in
  parallel.

## The three rungs — and which reference to load

Trailblaze is structured as an adoption ladder. Tools you add to your
agent's surface become first-class commands the next time your agent
drives a device. Most teams get value at rung 1, climb to rung 2 once
a flow is worth committing, and reach rung 3 when their agent has a
real app vocabulary to compose. Each rung has its own deep reference;
load whichever matches the task at hand.

<!--
  Maintainer note. Adding a new rung is "drop a `references/<name>.md`
  + add the bullet + `→ Load` line below"; the base file is sized to
  comfortably hold ~5 rungs of this shape (~280 lines). If we add a
  4th or 5th rung, this routing section stays the dominant page, the
  adoption-ladder framing still reads. Beyond ~6 rungs the "three
  rungs" heading becomes stale and the routing section dominates —
  at that point split this base into adoption-overview + a
  separate rung-routing doc rather than letting it sprawl.
-->


1. **Drive a device.** Point your coding agent at the Trailblaze
   CLI. Natural-language device control across iOS, Android, and
   web — through built-in primitives plus any custom tools your team
   has shipped.

   → **Load [`references/drive-device.md`](references/drive-device.md)**
   when the task is to explore an app, take a UI action, see what's on
   screen, or discover the verbs available on a connected device.
   Covers `device list`, `snapshot`, `tool`, `toolbox`, the basic loop,
   and recoverable failure modes.

2. **Save and replay.** Any session becomes a `.trail.yaml` — replay
   it ad-hoc, commit it to your repo as a CI regression test, or open
   it in the Trace Viewer to see exactly what happened. Same artifact,
   three uses, no LLM at replay.

   → **Load [`references/save-and-replay.md`](references/save-and-replay.md)**
   when the task is to save a trail, replay one, run a suite, inspect
   a session (HTML report or desktop trace viewer), or look up the
   results of a past CI run. Covers `session save`, `run` (and its
   `--self-heal` / `--memory` / `--secret` flags), `report`, `app`,
   and `results show`.

3. **Compose your own agent surface.** Give your agent first-class
   commands like `login` or `addToCart`, named waypoints for your
   screens, and trailmaps from other teams. Curate exactly what your
   agent sees: surface your `login` and hide the low-level taps;
   pick four of twenty primitives if that's what your tests need.
   Custom commands are typed and type-safe, with IDE support,
   replayable capture, and LLM-facing descriptions written for the
   tool and each parameter — your agent reads those descriptions to
   decide when and how to call them. Every call — yours, theirs, or
   built-in — is a first-class command, recordable and replayable.

   → **Load [`references/compose-agent-surface.md`](references/compose-agent-surface.md)**
   when the task is to author a typed scripted tool, define a
   waypoint, curate which tools the agent sees, write a `trailmap.yaml`,
   or debug "my custom tool isn't showing up in `toolbox`". Covers
   `trailblaze.tool<I>(handler)` authoring, sibling YAML descriptors,
   trailmap composition via `dependencies:`, and the four-checkpoint
   workflow for diagnosing a missing tool.

## Companion mode

An agent-attached authoring session: your coding agent is the single writer of a trail folder's
files, and Trail Runner opens a read-only live view of that folder for the human to watch and
steer. Start one with `trailblaze companion start --folder <rel> --title "<what you're building>"
--agent claude|codex`, then tail what the human does with `trailblaze companion listen <runId>`.

Steer the window with standing directives - `banner`, `checklist`, `actions` (quick-reply chips),
`select-app-target`, `select-device`, `arm-recording`, and the one-shot `navigate`. Each is
latest-per-name state that survives window reloads; re-send one with no fields to retract it.

**Single-writer rule:** the UI never writes trail files back. A human "Save" click or a guided
recording both go through the daemon, which writes the file and then tells every listening
session about it.

Two events matter most on the listen stream:

- **`recording-saved`** - a recording landed in your folder, whether from a companion save or
  Trail Runner's own board record flow; it fans out to every companion session watching that
  folder, not just the one that wrote it.
- **`run-started` / `run-finished`** - a human ran a trail from Trail Runner's UI whose path is
  inside your folder; both carry the run's `sessionId` and your `folder`, and `run-finished` adds
  `status: succeeded|failed|cancelled`. Only Trail Runner's own run endpoints announce, and only
  for primary-root trail/bundle ids - a raw-YAML replay (e.g. via MCP) bypasses that dispatch
  seam and stays silent.

Shared-brain requests: if the human clicks "Review my trail" (or asks for proposed steps) in
Trail Runner while your listen stream is open, the daemon queues the ask on you instead of calling
its own LLM - watch for a `human_action` event titled `agent-request` with `{requestId, kind,
payload}` (kind `review-trail` or `propose-steps`). Do the review by editing the trail folder's
files yourself, then settle it with `trailblaze companion respond <runId> --request <id> --status
done|error`. A request you never answer is cancelled when the session ends.

Companion journals (`.companion/journal-<runId>.jsonl`) age out after 7 days, swept the next time
a session connects to that folder - never at disconnect, since a crashed agent resuming with
`--after` needs its own journal.

`trailblaze companion --agent-help` prints the full event and directive contract; load that
instead of guessing at the wire format.

## Self-heal

Recorded trails replay deterministically by default — no LLM in the
loop, no flake. When a recorded step genuinely doesn't match the
screen anymore, there are two repair paths:

- **Built-in self-heal handles small drift** — text changes, an
  unexpected popup, a minor reorder within an established flow.
  Opt in with `--self-heal` and Trailblaze's built-in agent patches
  the failing step against the live screen.
- **Your coding agent handles the larger cases** — anything that
  needs project context, log inspection, or judgment calls about
  intent. The trace session (view hierarchies, screenshots, video,
  platform logs, the trail YAML itself) is the diagnosis surface.
  You read the trace, compare what the step intended (its
  natural-language objective) to what the app now does, and propose
  a fix. The user reviews and commits.

The natural-language objectives are what make this work. They tell
you *what the step was trying to do*, so repair is a matter of
updating the *how* against the current app — not re-deriving intent
from a broken selector.

## Built for an evolving ecosystem

The AI agent ecosystem is moving fast. Whatever it looks like in a
year — or five, or ten — natural-language tests will come with the
user. Trailblaze captures *what* is being tested as portable prose;
the *how* (selectors, recordings, agent harness, framework version)
adapts as the landscape changes.

---

When any rung in this skill drifts from the CLI — *or* when this
skill is restructured (new reference file, rung added, cross-link
moved) — the sibling `trailblaze-validate-oob` skill encodes the
methodology for catching it: fresh-context subagent, real installed
binary, scenario per rung. Re-run it any time the CLI changes, after
any non-trivial skill edit, or about once a month otherwise. If
validate-oob surfaces a gap, the default fix is **update the CLI to
match the skill** (rather than rewriting the skill to match a quirky
CLI) — the skill encodes the surface we want users to see; the CLI
should grow toward it.

## What Trailblaze is not

Trailblaze is not a full coding agent. It ships a functioning agent
focused on the natural-language step it's currently executing, with
vision into the current screen and past steps — fine for many flows.
What it doesn't try to be is a Claude Code, Cursor, or Codex: those
have the user's entire codebase in context, can query logcat at
runtime, and bring far more project context to bear. For serious
authoring work, those agents drive Trailblaze. And Trailblaze is not
a SaaS test platform — the trail YAML lives in the user's repo, they
own it, they can read and edit it.
