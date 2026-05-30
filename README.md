<div align="center">

# 🥾 Trailblaze

_**Natural-language device control for your coding agent — across iOS, Android, and web.**_
_Every session is a replayable trail you can run as a test._

<p>
  <a href="https://opensource.org/licenses/Apache-2.0">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg">
  </a>
</p>

</div>

Trailblaze gives your coding agent a single, typed, replayable way to drive any device.
Built-in primitives plus your own typed tools, with a natural-language source of truth
that travels across platforms. Whether you're exploring an app, automating a workflow,
or building a test suite, the artifact is the same: a portable `.trail.yaml` your CI can
replay deterministically from recorded steps with no LLM at replay time.

## What you get

- **Composable, replayable device control for your agent.** Your coding agent drives the
  device through Trailblaze's primitives — plus first-class commands of your own, like
  `login` or `addToCart`, defined in a typed language with type-safe bindings. Each step
  records its objective. The resulting `.trail.yaml` is both a natural-language source of
  truth — *what* you're testing or doing — and a deterministic execution artifact —
  *how* it runs. One trail can describe a flow across iOS, Android, and web — same
  natural-language steps, platform-specific actions or tools captured for each. Agents
  read the what; the framework handles the how.

- **A cross-platform Trace Viewer.** Open any session — yours or your agent's — and
  inspect view hierarchy, screenshots, video, and platform logs at every step. When you
  want a different selector than the one Trailblaze auto-picked for a step, the viewer
  lets you choose from generated alternatives computed against the same captured
  hierarchy — human judgment, no re-recording. Same viewer for iOS, Android, and web.

## Native fidelity on every platform

Most multi-platform testing tools expose the *intersection* of what iOS, Android, and
web can do — a generic API that maps onto all three, at the cost of losing
platform-specific capability. Trailblaze takes the opposite approach: full-fidelity
native semantic surfaces, OS-native primitives, and typed tool sets composed per
`(target, platform)`. Your agent works against each platform's native UI tree —
the accessibility tree on Android, native UI semantics on iOS, the DOM on web —
rather than a flattened cross-platform abstraction.

The agent picks elements semantically — "the Sign in button" — from the native
hierarchy. Trailblaze computes the platform-specific selectors behind the scenes. The
natural-language test stays the same; the execution uses each platform's full power.

This only works because an agent is driving. Exposing full native fidelity to a human is
overwhelming — twenty platform-specific selector strategies per element is no one's idea
of a good testing SDK. Exposing it to an LLM is the point. The agent handles the
complexity; you get the expressive power of native automation with the consistency of a
single natural-language test.

## How Trailblaze grows with you

You can stop anywhere. Tools you add to your agent's surface become first-class
commands the next time your agent drives a device.

1. **Drive a device.** Point your coding agent (Claude Code, Cursor, Codex CLI) at the
   Trailblaze CLI. Natural-language device control across iOS, Android, and web —
   through built-in primitives plus any custom tools your team has shipped.

2. **Save and replay.** Any session becomes a `.trail.yaml` — replay it ad-hoc, commit
   it to your repo as a CI regression test, or open it in the Trace Viewer to see
   exactly what happened. Same artifact, three uses, no LLM at replay.

3. **Compose your own agent surface.** Give your agent first-class commands like
   `login` or `addToCart`, named waypoints for your screens, and trailmaps from other
   teams. Curate exactly what your agent sees: surface your `login` and hide the
   low-level taps; pick four of twenty primitives if that's what your tests need.
   Custom commands are typed and type-safe, with IDE support, replayable capture, and
   LLM-facing descriptions you write for the tool and each parameter — your agent
   reads those descriptions to decide when and how to call them. Every call — yours,
   theirs, or built-in — is a first-class command, recordable and replayable. Your
   agent gets dramatically more capable on every task that uses your composition.¹

## Self-heal

Recorded trails replay deterministically by default — no LLM in the loop, no flake.
When a recorded step genuinely doesn't match the screen anymore, there are two repair
paths:

- **Built-in self-heal handles small drift** — text changes, an unexpected popup, a
  minor reorder within an established flow. Opt in with `--self-heal` and Trailblaze's
  built-in agent patches the failing step against the live screen.
- **Your coding agent handles the larger cases** — anything that needs project
  context, log inspection, or judgment calls about intent. The trace session (view
  hierarchies, screenshots, video, platform logs, the trail YAML itself) is the
  diagnosis surface. Claude Code, Cursor, or Codex read the trace, compare what the
  step intended (its natural-language objective) to what the app now does, and propose
  a fix. You review and commit.

The natural-language objectives are what make this work. They tell the agent *what the
step was trying to do*, so repair is a matter of updating the *how* against the current
app — not re-deriving intent from a broken selector.

## Built for an evolving ecosystem

The AI agent ecosystem is moving fast. Whatever it looks like in a year — or five, or
ten — your natural-language tests will come with you. Trailblaze captures *what* you're
testing as portable prose; the *how* (selectors, recordings, agent harness, framework
version) adapts as the landscape changes.

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

**Required:** `curl`, `java 17+`.

**Optional (Homebrew users: `brew install bun esbuild ffmpeg`):**
- `bun` + `esbuild` — needed only when authoring or running trailmap-defined scripted
  tools (on disk under `trails/config/trailmaps/<trailmap>/tools/*.ts`). Without these,
  `./trailblaze run …` will fail at dispatch with `Unsupported tool type for RPC
  execution` for any trail that uses a `target:` trailmap with `.ts` tool descriptors.
- `ffmpeg` — needed only for trail video capture / sprite extraction (visual playback
  artifacts under `~/.trailblaze/logs/<session>/`). Trails still run without it; only
  the rendered video and sprite-strip outputs are missing.

## Quickstart

```bash
# List connected devices (Android emulator, iOS simulator, or web browser)
trailblaze device list

# Pin this shell to a device + target so subsequent calls inherit both from the env
eval $(trailblaze device connect android --target default)

# Read the screen — returns a view hierarchy with refs (e.g. ab42) the agent can target
trailblaze snapshot

# Act on a referenced element — your agent picks the element; Trailblaze picks the
# platform-specific selector strategy. Every action takes a --step for self-heal.
trailblaze tool tap ref=ab42 -s "Tap sign in"

# Done — release the device + clear TRAILBLAZE_DEVICE from this shell
eval $(trailblaze device disconnect)
```

Paste those into Claude Code, Codex, Cursor, Goose, or anything that can run bash and
you're already authoring trails. For CI / scripts that prefer determinism over shell
state, every device-acting command (`snapshot`, `tool`, `step`, `ask`, `verify`,
`session start/stop`, `run`) also accepts `-d <platform>` (and `--target <app>` where
supported — `tool`, `step`, `session start`, `mcp`) as a per-call override.
(`blaze` remains accepted as a deprecated alias of `step`.)

## Active Prototypes

Trailblaze is moving fast. These are landing now and are worth knowing about even if
they're not stable yet:

- **Trailmaps** — reusable target-aware capability bundles (tools + waypoints + routes
  + recorded trails) shipped per app, consumed by humans and agents alike. Designed to
  distribute via npm so other teams (and eventually the broader community) can install
  an agent-ready vocabulary for your app.
  ([devlog](docs/devlog/2026-05-12-npm-distribution-for-trailmaps.md))
- **Scripted Tools** — write custom tools with the `@trailblaze/scripting` SDK in a
  typed language with type-safe bindings. Tools execute in a QuickJS sandbox on-device
  or in a host subprocess. After clone, run
  `./trailblaze check --workspace examples/playwright-native` once to materialize the
  workspace SDK + per-trailmap typed bindings.
  ([devlog](docs/devlog/2026-04-22-scripting-sdk-authoring-vision.md))
- **Waypoints** — named, assertable app locations defined structurally (element
  identity, stable labels), never by content. Agents can ask "am I on the Inbox?",
  land on a waypoint after a step, or use waypoints as trail checkpoints.
  ([devlog](docs/devlog/2026-03-11-waypoints-and-app-navigation-graphs.md))
- **Trail-as-Tool** — expose a saved trail as a tool so other trails (and agents) can
  call it. A `loginAsTestUser` trail becomes a one-line setup step inside any other
  test. ([devlog](docs/devlog/2026-04-21-run-trail-tool-proposal.md))

## Desktop App & Reporting

```bash
trailblaze app        # Launch the desktop app for visual trail authoring and report browsing
```

The desktop app, HTML reports, and session browser all work the same regardless of how
the trail was authored.

## Documentation

Full docs at **[block.github.io/trailblaze](https://block.github.io/trailblaze)**.

- [CLI Reference](docs/CLI.md) — Every command and flag
- [Tool Authoring](docs/tools.md) — Add your own tools
- [Configuration](docs/configuration.md) — Providers, devices, target apps

(A longer Getting Started walkthrough is being rewritten to match the new direction.
Until that lands, the README above is the canonical starting point.)

## What Trailblaze is not

Trailblaze is not a full coding agent. It ships a functioning agent focused on the
natural-language step it's currently executing, with vision into the current screen
and past steps — fine for many flows. What it doesn't try to be is a Claude Code,
Cursor, or Codex: those have your entire codebase in context, can query logcat at
runtime, and bring far more project context to bear. For serious authoring work, you
want one of them driving Trailblaze. And Trailblaze is not a SaaS test platform —
the trail YAML lives in your repo, you own it, you can read and edit it.

---

¹ Cross-team and cross-repo trailmap sharing today; npm-based distribution for
community-published trailmaps is in active development.
