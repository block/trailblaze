---
title: Getting Started
---

# Getting Started

**Trailblaze is an AI-powered UI testing framework** — platform-native drivers, an agent loop, deterministic trail replay, a desktop app, and a CLI that any LLM can drive. Several ways to use it, all sharing the same daemon, drivers, and session log:

1. **Run Trailblaze's built-in agent** — `trailblaze blaze "<goal>"` runs the `blaze` agent end-to-end against a goal, with no external coding agent in the loop. This is the path the recommended CI workflow takes.
2. **Aim an AI coding agent at the CLI** — Claude Code, Codex, Cursor, Goose, Windsurf, Aider, or any bash-capable agent shells out to the same `tool`, `verify`, `snapshot`, etc. primitives. No SDK to install, no protocol to negotiate, no provider keys to wire on the agent's side.
3. **Run the CLI by hand** — same surface as the agents, you typing.
4. **Open the desktop app** — to browse session reports locally, replay video, edit and re-run trails, or manage multiple devices. CI also exposes the same report inline on every build, so you don't need the desktop app to inspect a CI failure.

## System Requirements

| | macOS | Linux |
|---|---|---|
| **Desktop App (GUI)** | Supported | Not supported |
| **Headless / CLI** | Supported | Supported |

- **JDK 17+** is required on all platforms
- **Android SDK** with `adb` on your PATH for on-device Android testing
- **Xcode + simctl** for iOS simulators
- A Playwright-compatible Chromium (auto-installed) for web

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

Or clone the repo and run from source:

```bash
git clone https://github.com/block/trailblaze.git
cd trailblaze
./trailblaze            # launches the desktop app
./trailblaze --help     # CLI usage
```

## Set Your LLM Provider

Trailblaze ships with built-in support for:

- OpenAI (`OPENAI_API_KEY`)
- Anthropic (`ANTHROPIC_API_KEY`)
- Google (`GOOGLE_API_KEY`)
- OpenRouter (`OPENROUTER_API_KEY`)
- Ollama (no key required)

Set the env var in your shell:

```bash
export ANTHROPIC_API_KEY="sk-ant-…"
```

Pick a default model with `trailblaze config`:

```bash
trailblaze config llm anthropic/claude-sonnet-4-20250514
trailblaze config models     # list everything available
```

For custom endpoints, enterprise gateways, or workspace-level overrides, see [LLM Configuration](llm_configuration.md).

> **Note:** Trailblaze still needs its own LLM provider configured for vision-based primitives (`ask`, `verify`) and for `blaze`. If your agent only shells out to deterministic primitives like `snapshot` and direct `tool` calls (e.g. `tapOnElement`, `inputText`), no Trailblaze-side LLM is required — the agent does the thinking; Trailblaze does the doing. That keeps your agent's setup minimal: most of the time you only need an API key on your agent's side, not on Trailblaze's.

## Path A — Trailblaze's Built-in Agent

The fastest path: hand a goal to `trailblaze blaze` and the built-in `blaze` agent runs end-to-end.

```bash
trailblaze device list
trailblaze device connect android/emulator-5554

trailblaze blaze -d android "Sign in with test@example.com / hunter2 and confirm the welcome screen"
trailblaze session save --title "login_flow"
```

`blaze` plans, calls tools, recovers from popups and stuck states, and produces a recording you can replay deterministically later. It's the same agent that powers `--self-heal` and the recommended CI workflow — no external coding agent required.

## Path B — From an AI Coding Agent

If you'd rather have your AI coding agent (Claude Code, Codex, Cursor, Goose, Windsurf, Aider) do the driving — useful when you're already mid-task in your editor — it shells out to the same CLI. **If your agent can run a shell command, it can drive a device.**

Drop this into a Claude Code, Codex, Goose, or any-bash-agent session:

```
You have access to the `trailblaze` CLI. Use it to drive the connected device:
  - `trailblaze snapshot` — see what's on screen (UI tree with ref IDs)
  - `trailblaze tool <name> <args> -o "<why>"` — take an action; always pass -o
  - `trailblaze verify "<condition>"` — assert a condition (exit 0/1)
  - `trailblaze toolbox` — list available tools for the current platform

Task: Log in with test@example.com / hunter2 and confirm the welcome screen.
When done, run `trailblaze session save --title "login_flow"` to persist the trail.
```

That's the whole integration. No installation steps for the agent. No config files. No protocol plumbing.

### The Killer Flag: `--objective`

Every `trailblaze tool` call takes `--objective` (`-o`) — a natural-language description of *why* the tool was called:

```bash
trailblaze tool tapOnElement ref="Sign In" --objective "Tap sign in"
trailblaze tool inputText text="test@example.com" --objective "Enter email"
```

Objectives are what make agent-authored trails durable. When the UI drifts, **self-heal** uses them to recover (see Path D below).

### Read-Only Primitives (Fast, No Mutation)

| Command | What it does | Needs an LLM? |
|---|---|---|
| `trailblaze snapshot` | Dump the UI tree with ref IDs | No |
| `trailblaze verify "<cond>"` | Pass/fail a condition (exit 0/1) | Yes (vision) |
| `trailblaze ask "<question>"` | Ask a natural-language question about the screen | Yes (vision) |

These are perfect for agent feedback loops: cheap, fast, deterministic where they can be.

## Path C — From the Terminal

You can do everything either agent can do, by hand, from the terminal.

```bash
trailblaze ask -d android "What's the current Wi-Fi network?"
trailblaze verify -d android "The Bluetooth toggle is off"
trailblaze snapshot -d android

trailblaze tool tapOnElement ref="Settings" -o "Open Settings" -d android
```

Save what you just did as a replayable trail:

```bash
trailblaze blaze -d android "Open Settings and toggle Bluetooth off" --save
trailblaze session save --title "toggle-bluetooth"
```

The full CLI reference: [CLI](CLI.md).

## Path D — Run a Saved Trail

A **trail** is a `.trail.yaml` file: a list of natural-language steps with optional recorded tool sequences for deterministic replay.

```yaml
- prompts:
    - verify: the "Sign in" screen is visible
    - step: Sign in as the demo user
    - verify: the home tab is selected
```

Drop the file anywhere in your project. Run it:

```bash
trailblaze trail flows/login.trail.yaml
trailblaze trail "flows/**/*.trail.yaml"        # batch via shell glob
trailblaze trail flows/login.trail.yaml --use-recorded-steps
trailblaze trail flows/login.trail.yaml --self-heal
```

`--use-recorded-steps` replays the recorded tool sequence with no LLM in the loop — fast, deterministic, cheap. `--self-heal` lets the `blaze` agent step back in if a recorded step doesn't match the screen anymore: it patches the failing step and updates the recording on success, so a real UI drift becomes a one-line trail update instead of a broken build. Self-heal is opt-in; the default is fail-loud so flakes don't get silently masked.

No `trails/` directory is required — see [Project Layout](project_layout.md) for the discovery rules.

## Reports — High-Fidelity, Local or CI

Every run — whether driven by an agent, the CLI, or CI — produces a rich report: per-step screenshots, recorded tool calls, hierarchy snapshots, the agent's reasoning, the full LLM transcript, and video replay when capture is enabled. **The same report UI is available three ways:**

- **Inline on every CI build** — share a URL, open in a browser, no Trailblaze install required.
- **In the desktop app** for local sessions — a **Sessions** list across every device and run, live updates while a session is running, one-click "show me the trail YAML" to copy back into your project, and inline trail editing / re-running.
- **On disk** under `~/.trailblaze/logs/<sessionId>/` if you ever need to grep raw artifacts.

Launch the desktop app with `./trailblaze` (or `trailblaze` once installed).

## Active Prototypes Worth Knowing About

These are landing now and will reshape authoring soon — see the [devlog](devlog/index.md) for the latest.

- **Packs** — reusable target-aware capability bundles (tools + waypoints + routes + recorded trails) shipped per app, consumed by humans and agents alike. [Robot pattern + packs](devlog/2026-04-26-robot-pattern-plus-packs.md), [Target Packs](devlog/2026-04-26-target-packs-local-first.md).
- **Scripted tools (JS/TS)** — custom tools written in TypeScript, executed in a QuickJS sandbox or host subprocess, no Kotlin required. [Authoring vision](devlog/2026-04-22-scripting-sdk-authoring-vision.md).
- **Waypoints** — named, assertable app locations the agent can navigate to and land on. [Waypoints + navigation graphs](devlog/2026-03-11-waypoints-and-app-navigation-graphs.md).
- **Trail-as-tool** — expose a saved trail as a tool so other trails (and agents) can call it. [runTrail proposal](devlog/2026-04-21-run-trail-tool-proposal.md).

## Next Steps

- [Android On-Device Testing](android_on_device.md) — instrumentation tests on real Android devices
- [Host JVM Unit Tests](host_jvm_unit_tests.md) — running trails from JUnit
- [Configuration](configuration.md) — workspace-level config and provider overrides
- [Architecture](architecture.md) — how the agent loop, drivers, and recording pipeline fit together
