<div style="text-align: center;">

# 🧭 Trailblaze

_**Blaze your own trails on iOS, Android, and Web.**_
_AI-powered UI testing. Any agent. One CLI. Zero LLM config._

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

## Claude, Codex, Any Agent — All Through the Trailblaze CLI

**If your agent can run a shell command, it can drive a device with Trailblaze.**

Every Trailblaze tool is a CLI subcommand. Claude Code, Codex, Cursor, Goose, Aider, Continue, your own homegrown
agent, your CI runner, a bash script — they all invoke Trailblaze the same way. No MCP server to install.
No provider keys to wire up. No protocols to negotiate. No Trailblaze-side LLM config.

Your agent already knows how to use a CLI. That's all Trailblaze needs.

## Quickstart

```bash
# Connect a device (iOS, Android, or Web)
trailblaze device

# Drive it from any agent — or from your shell
trailblaze snapshot                                              # See what's on screen
trailblaze tool tapOnElement ref="Sign In" -o "Tap sign in"      # Act, with intent
trailblaze verify "Welcome screen is visible"                    # Pass/fail (exit 0/1)
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
When done, run `trailblaze session save login_flow` to persist the trail.
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

When the UI drifts, the recorded trail can self-heal against the objective instead of breaking on a brittle
selector. Objectives are what make agent-authored trails durable.

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

- **Agent-First CLI**: Every tool is a shell command. Any agent that can run bash can drive a device — no MCP,
  no SDK, no protocol
- **Cross-Platform**: Android, iOS, and Web from the same CLI with the same commands
- **Resilient by Design**: Natural-language `--objective`s let recorded trails self-heal against UI changes
- **Custom Tools**: Extend the tool surface with app-specific `TrailblazeTool`s — automatically available to the
  CLI and any agent calling it
- **Detailed Reporting**: Rich HTML/JSON reports with screenshots, per-step timing, and objective-vs-action diffs
- **Desktop App**: Visual trail authoring, replay, and report browsing for humans who want a GUI

## Desktop App & Reporting

```bash
trailblaze app        # Launch the desktop app for visual trail authoring and report browsing
```

The desktop app, HTML reports, and session browser all work the same regardless of how the trail was authored.

## Other Integrations

- **`trailblaze blaze "<goal>"`** — Trailblaze has a built-in agent that can drive end-to-end from a single
  prompt. It needs an AI provider configured (`trailblaze config`). Most users will want to use an external
  agent via the CLI instead — it's simpler and doesn't require Trailblaze-side LLM setup.
- **`trailblaze mcp`** — An MCP server also ships for MCP-native hosts. We no longer recommend it as the default
  integration; the CLI is simpler, works everywhere, and requires zero host configuration.

## Documentation

Full docs at **[block.github.io/trailblaze](https://block.github.io/trailblaze)**.

- [CLI Reference](docs/CLI.md) — Every command and flag
- [Tool Authoring](docs/tools.md) — Add your own `TrailblazeTool`s
- [Getting Started](docs/getting_started.md) — Longer walkthrough
- [Configuration](docs/configuration.md) — Providers, devices, target apps
