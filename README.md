<div style="text-align: center;">

# 🧭 Trailblaze

_**An AI-powered UI testing framework for iOS, Android, and Web.**_
_Platform-native drivers, an agent loop, deterministic replay, a desktop app, and a CLI any LLM can drive._

<p style="text-align: center;">
  <a href="https://opensource.org/licenses/Apache-2.0">
    <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg">
  </a>
</p>
</div>

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

## Two Ways to Drive — Same CLI

Trailblaze ships its own agent and also plays nicely with any AI coding agent:

- **`trailblaze blaze "<goal>"`** — runs Trailblaze's built-in agent end-to-end. Plans, acts, recovers from popups
  and stuck states, and produces a recording. No external coding agent required; this is what powers the CI
  pipeline and `--self-heal`.
- **Claude Code, Codex, Cursor, Goose, Aider, Continue, your own homegrown agent, a CI runner, a bash script** —
  they all invoke the same CLI primitives the same way. No SDK to install, no protocol to negotiate, no provider
  keys to wire up on the agent's side.

## Quickstart

```bash
# List devices, then connect one (iOS, Android, or Web)
trailblaze device list
trailblaze device connect android

# Drive end-to-end with Trailblaze's built-in agent
trailblaze blaze -d android "Sign in as test@example.com and confirm the welcome screen"

# Or drive primitives directly — by hand or from any AI coding agent
trailblaze snapshot -d android                                              # See what's on screen
trailblaze tool tapOnElement -d android ref="Sign In" -o "Tap sign in"      # Act, with intent
trailblaze verify -d android "Welcome screen is visible"                    # Pass/fail (exit 0/1)
```

Paste those into Claude Code, Codex, Goose, or anything that can run bash and you're already authoring tests.

## Prompts That Work With Any Agent

Drop this into a Claude Code, Codex, or Goose session to let it drive:

```
You have access to the `trailblaze` CLI. Use it to drive the connected device:
  - `trailblaze snapshot` — see what's on screen (returns a UI tree with ref IDs)
  - `trailblaze tool <name> <args> -o "<why>"` — take an action; always pass -o
  - `trailblaze verify "<condition>"` — assert a condition (exit 0/1)
  - `trailblaze toolbox` — list available tools for the current platform

Task: Log in with test@example.com / hunter2 and confirm the welcome screen.
When done, run `trailblaze session save --title "login_flow"` to persist the trail.
```

That's the whole integration. No installation steps for the agent. No config files. No protocol plumbing.

## The Full CLI Surface

```
Blaze:
  blaze        Drive a device with AI — describe what to do in plain English
  ask          Ask a question about what's on screen (uses AI vision, no actions taken)
  verify       Check a condition on screen and pass/fail (exit code 0/1, ideal for CI)
  snapshot     Capture the current screen's UI tree (fast, no AI, no actions)
  tool         Run a Trailblaze tool by name (e.g., tapOnElement, inputText)
  toolbox      Browse available tools by target app and platform

Trail:
  trail        Run a trail file (.trail.yaml) — execute a scripted test on a device
  session      Every blaze records a session — save it as a replayable trail
  report       Generate an HTML or JSON report from session recordings

Setup:
  config       View and set configuration (target app, device defaults, AI provider)
  device       List and connect devices (Android, iOS, Web)
  app          Start or stop the Trailblaze daemon (background service that drives devices)
```

### The Killer Flag: `--objective`

Every `trailblaze tool` call takes an `--objective` (`-o`) flag that captures the natural-language intent
alongside the mechanical action:

```bash
trailblaze tool tapOnElement ref="Sign In" --objective "Tap sign in"
trailblaze tool inputText text="test@example.com" --objective "Enter email"
```

Objectives are what make agent-authored trails durable. When the UI drifts, **self-heal** uses them to recover.

### Self-Heal

Recorded trails replay deterministically by default — no LLM in the loop, no flake. When a recorded step
genuinely doesn't match the screen anymore, opt in to self-heal and the `blaze` agent steps back in to patch the
failing step and update the recording on success:

```bash
trailblaze trail flows/login.trail.yaml --self-heal
```

A real UI change becomes a one-line trail update instead of a broken build. Self-heal is opt-in: the default is
fail-loud, so a flake or regression doesn't get silently masked.

### Read-Only Primitives

Fast, deterministic, and perfect for agent feedback loops:

| Command                  | What it does                                       | Needs an LLM?          |
|--------------------------|----------------------------------------------------|------------------------|
| `trailblaze snapshot`    | Dump the current UI tree with ref IDs              | No                     |
| `trailblaze verify`      | Pass/fail a condition — exit code 0 or 1           | Yes (vision)           |
| `trailblaze ask`         | Ask a natural-language question about the screen   | Yes (vision)           |

## Trails: Natural Language as Source of Truth

Whatever drives the CLI — you, an agent, or CI — the output is the same portable `.trail.yaml` file. That trail
gets the same desktop app, HTML reports, session history, and deterministic replay across Android, iOS, and Web.

### Blaze Once, Trail Forever

```
┌─────────────────────────────────────────────────────────────┐
│                    First Run: BLAZE                          │
│  Agent explores → Records actions → Generates .trail.yaml   │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                   Future Runs: TRAIL                         │
│  Replay recordings → Zero LLM cost → Fast CI/CD execution   │
└─────────────────────────────────────────────────────────────┘
```

- **Blaze Mode**: An agent explores the app to achieve an objective, discovering the path dynamically
- **Trail Mode**: Replay recorded actions deterministically with **zero LLM cost**
- **Hybrid Mode**: Use recordings where available, fall back to AI when the UI drifts

## Core Features

- **Built-in `blaze` agent**: `trailblaze blaze "<goal>"` plans, acts, and self-corrects end-to-end. The same
  agent powers self-heal and the recommended CI workflow.
- **AI coding agent integration**: Every tool is a shell command. Any agent that can run bash can drive a device
  through the same CLI — no SDK, no protocol, no provider keys on the agent's side.
- **Self-heal on replay**: opt in (`--self-heal`) and `blaze` patches a failing recorded step and updates the
  recording on success. Default is fail-loud.
- **Cross-Platform, Platform-Native**: Android, iOS, and Web from the same CLI with the same commands.
  Each driver speaks its host platform's native vocabulary (Android UiAutomator, iOS XCUITest, Playwright DOM)
  — no flattening to a lowest-common-denominator abstraction.
- **Resilient by Design**: Natural-language `--objective`s capture intent so recorded trails can recover against
  UI changes instead of breaking on brittle selectors.
- **Custom Tools**: Extend the tool surface with app-specific `TrailblazeTool`s — automatically available to the
  CLI and any agent calling it.
- **Multi-Device Sessions**: Drive Android + iOS + web from the same shell, in parallel, each bound to a
  specific device.
- **High-Fidelity Reporting**: Rich reports with screenshots, per-step timing, recorded tool calls,
  full LLM transcripts, and video replay. CI exposes the report inline on every build; the desktop app shows the
  same UI for local sessions.

## Active Prototypes

Trailblaze is moving fast. These are landing now and are worth knowing about even if they're not stable yet:

- **Packs** — reusable target-aware capability bundles (tools + waypoints + routes + recorded trails) shipped
  per app, consumed by humans and agents alike. Think Robot Pattern, generalized and shippable.
  ([devlog](docs/devlog/2026-04-26-target-packs-local-first.md))
- **Scripted Tools (JS/TS)** — write custom tools in TypeScript with the `@trailblaze/scripting` SDK. No Kotlin,
  no Gradle build. Tools execute in a QuickJS sandbox on-device or in a host subprocess.
  ([devlog](docs/devlog/2026-04-22-scripting-sdk-authoring-vision.md))
- **Waypoints** — named, assertable app locations defined structurally (element identity, stable labels), never
  by content. Agents can ask "am I on the Inbox?", land on a waypoint after a step, or use waypoints as trail
  checkpoints. ([devlog](docs/devlog/2026-03-11-waypoints-and-app-navigation-graphs.md))
- **Trail-as-Tool** — expose a saved trail as a tool so other trails (and agents) can call it. A
  `loginAsTestUser` trail becomes a one-line setup step inside any other test.
  ([devlog](docs/devlog/2026-04-21-run-trail-tool-proposal.md))

## Desktop App & Reporting

```bash
trailblaze app        # Launch the desktop app for visual trail authoring and report browsing
```

The desktop app, HTML reports, and session browser all work the same regardless of how the trail was authored.

## Documentation

Full docs at **[block.github.io/trailblaze](https://block.github.io/trailblaze)**.

- [CLI Reference](docs/CLI.md) — Every command and flag
- [Tool Authoring](docs/tools.md) — Add your own `TrailblazeTool`s
- [Getting Started](docs/getting_started.md) — Longer walkthrough
- [Configuration](docs/configuration.md) — Providers, devices, target apps
