---
title: Getting Started
---

# Getting Started

**Natural-language device control for your coding agent — across iOS, Android, and web.**
Every session is a replayable trail you can run as a test.

Trailblaze gives your coding agent — Claude Code, Cursor, Codex, Goose, Aider, anything
that can run a shell command — a single, typed way to drive any device. The agent reads
the screen, picks elements semantically, and acts through Trailblaze primitives plus
whatever custom commands your team has shipped. Every action records its natural-language
objective. The resulting `.trail.yaml` is both the source of truth — *what* the flow
does — and the deterministic execution artifact — *how* it runs. CI replays the trail
with no LLM in the loop.

Trailblaze is not its own coding agent. Your editor's agent does the planning and the
reading; Trailblaze handles the device.

## What you'll do in this guide

1. Install Trailblaze and point it at a device.
2. Drive the device through primitives (`snapshot`, `tool`) from a shell — or from your
   coding agent shelling out to the same commands.
3. Save what you just did as a `.trail.yaml`.
4. Replay it deterministically with `trailblaze run`.
5. Inspect any session in the Trace Viewer.

This mirrors the adoption ladder Trailblaze is designed around — **drive → save and
replay → compose your own agent surface.** You can stop at any rung.

## System Requirements

| | macOS | Linux |
|---|---|---|
| **Desktop App (GUI)** | Supported | Not supported |
| **Headless / CLI** | Supported | Supported |

- **JDK 17+** on all platforms
- **Android SDK** with `adb` on your PATH for Android devices and emulators
- **Xcode + simctl** for iOS simulators
- A Playwright-compatible Chromium (auto-installed) for web

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

Or clone and run from source:

```bash
git clone https://github.com/block/trailblaze.git
cd trailblaze
./trailblaze --help     # CLI usage
./trailblaze app        # Start the daemon (also opens the desktop app on macOS)
```

**Optional, via Homebrew (`brew install bun esbuild ffmpeg`):**

- `bun` + `esbuild` — needed only when authoring or running trailmap-defined scripted
  tools written in TypeScript.
- `ffmpeg` — needed only for trail video capture and sprite extraction. Trails still run
  without it; only the rendered video and sprite-strip outputs are missing.

## Connect a Device

List what's connected, then pin this shell to one of them:

```bash
trailblaze device list

# Pin: exports TRAILBLAZE_DEVICE into the current shell so every follow-up call
# inherits the device + target without repeating the flags.
eval $(trailblaze device connect android --target default)
```

You'll see Android emulators (`android/emulator-5554`), iOS simulators
(`ios/<simulator-id>`), and any web targets. After the pin, device-acting CLI
calls that take a `-d/--device` flag (`snapshot`, `tool`, `blaze`, `ask`, `verify`,
`session start`, `session stop`, `run`) read `TRAILBLAZE_DEVICE` from the env — no
`-d <device>` flag needed. `session save` is implicit (saves the current session)
and doesn't take `-d`. For CI / scripts that prefer determinism, pass `-d <platform>`
(or `-d <platform>/<id>`) on each call as an override; explicit flags win over the
env. `mcp` accepts `--device` / `--target` at startup to pre-bind the MCP session
(so the agent's first tool call already has a device); workspace and setup commands
(`config`, `app`, `device list`) don't take `-d`. `run` reads `TRAILBLAZE_DEVICE`
just like the action commands, but each replay spawns a fresh session rather than
reattaching to the pinned interactive one.

To swap target without disconnecting, use `trailblaze device rebind --target <new>`. To
release, `eval $(trailblaze device disconnect)` — the leading `eval $(...)` also unsets
`TRAILBLAZE_DEVICE` in the parent shell so the next session starts clean.

## Drive the Device

Two primitives are enough to start: `snapshot` to read the screen, `tool` to act.
Wrap them in `session start` / `session stop` so the steps you take are tracked as a
single recording you can save as a trail.

```bash
# Start a tracked session bound to the pinned device — captures video + logs and
# groups the steps you take so `session save` has something to write out.
trailblaze session start --title "login_flow"

# Read the screen — returns a UI tree with refs (e.g. ab42) the agent can target.
trailblaze snapshot

# Act on a referenced element. Every action takes --step.
trailblaze tool tap ref=ab42 -s "Tap sign in"
trailblaze tool inputText text="test@example.com" -s "Enter email"
```

### Why `--step` is mandatory

`--step` (`-s`) is the natural-language description of *what* the step is doing —
not *how*. `"Tap sign in"` survives a redesign; `"tap button at 200,400"` does not.
(`--objective` / `-o` remain accepted as deprecated aliases — no runtime warning, but
new code should write `--step`.)

Step text is what makes agent-authored trails durable. When the UI later drifts,
self-heal patches the failing step against the new screen by re-deriving the *how* from
the recorded *what*. No step text, no self-heal.

### From your coding agent

If you'd rather have your coding agent (Claude Code, Cursor, Codex, Goose, Aider) do the
driving — useful when you're already mid-task in your editor — point it at the CLI. Drop
this into the agent's session:

```
You have access to the `trailblaze` CLI. Use it to drive the connected device. First
pin the shell to a device + target so subsequent calls don't have to repeat the flags:
  - `eval $(trailblaze device connect <platform> --target <app>)` — pin once at start
  - `trailblaze session start --title "<short_name>"` — start a tracked session
    (captures video/logs, groups the steps for later save)
  - `trailblaze snapshot` — see what's on screen (UI tree with refs)
  - `trailblaze tool <name> <args> -s "<why>"` — take an action
  - `trailblaze toolbox -d <platform>` — list available tools (toolbox still wants -d)
  - When done, `trailblaze session save` to write the recording out as a `.trail.yaml`,
    then `trailblaze session stop` to end the session. Optionally
    `eval $(trailblaze device disconnect)` to release the device.
```

If your agent can run a shell command, it can drive a device. No SDK to install, no
protocol to negotiate, no provider keys to wire on the agent's side.

If your agent is an MCP client (e.g. Claude Code via `claude mcp add trailblaze -- trailblaze mcp --device android --target default`), the MCP shim auto-binds device + target on initialize from those flags — your agent doesn't have to call `device(...)` first. See [MCP Integration](mcp/index.md) for the config snippets.

## Save the Session as a Trail

While the session was running, Trailblaze recorded every step. Persist the recording
as a `.trail.yaml` and end the session:

```bash
trailblaze session save                    # uses the title from `session start`
trailblaze session stop
```

The resulting file is a list of natural-language steps with recorded tool sequences for
deterministic replay. Drop it anywhere in your project (a `trails/` directory is
conventional but not required — see [Project Layout](project_layout.md) for discovery
rules).

A minimal example:

```yaml
- prompts:
    - verify: the "Sign in" screen is visible
    - step: Sign in as the demo user
    - verify: the home tab is selected
```

## Replay Deterministically

```bash
trailblaze run flows/login.trail.yaml -d android
trailblaze run "flows/**/*.trail.yaml" -d android        # batch via shell glob
trailblaze run flows/login.trail.yaml -d android --self-heal
```

By default `trailblaze run` replays the recorded tool sequence with **no LLM in the
loop** — fast, deterministic, cheap. This is the path CI takes.

`--self-heal` opts in to small-drift recovery: if a recorded step doesn't match the
screen anymore, Trailblaze's built-in agent patches the failing step against the live
screen and updates the recording on success. Self-heal is opt-in by design; the default
is fail-loud so real flakes don't get silently masked.

When drift is larger than self-heal can handle — anything that needs project context,
log inspection, or judgment about intent — your coding agent does the repair. It reads
the trace session (view hierarchies, screenshots, video, platform logs, the trail YAML
itself), compares what the step intended to what the app now does, and proposes a fix.
You review and commit.

## Inspect Any Session in the Trace Viewer

Every run — driven by you, your coding agent, the CLI, or CI — produces a rich session:
per-step screenshots, recorded tool calls, view-hierarchy snapshots, the full LLM
transcript (when an LLM was involved), and video replay when capture is enabled.

The same Trace Viewer surface is available three ways:

- **Desktop app** — `trailblaze app` opens the Sessions list across every device and
  run, with live updates while a session is running, one-click "show me the trail YAML"
  to copy back into your project, and inline trail editing and re-running.
- **Inline on every CI build** — share a URL, open in a browser, no Trailblaze install
  required.
- **On disk** under `~/.trailblaze/logs/<sessionId>/` if you ever need to grep raw
  artifacts.

When you want a different selector than the one Trailblaze auto-picked for a step, the
viewer lets you choose from generated alternatives computed against the same captured
hierarchy — human judgment, no re-recording. Same viewer for iOS, Android, and web.

## Compose Your Own Agent Surface

The third rung of the adoption ladder. Tools you add to your agent's surface become
first-class commands the next time your agent drives a device.

- **Custom commands** like `login` or `addToCart`, written in a typed language with
  type-safe bindings, with LLM-facing descriptions you write for the tool and each
  parameter. Your agent reads those descriptions to decide when and how to call them.
  Every call — yours, the built-ins, or third-party — is recordable and replayable.
- **Named waypoints** for your screens, so the agent can ask "am I on the Inbox?", land
  on a waypoint after a step, or use waypoints as trail checkpoints.
- **Trailmaps** to bundle tools + waypoints + recorded trails per app, shared across
  teams.

These are active prototypes — landing now, worth knowing about, see the linked devlogs
for current state:

- **Trailmaps** ([devlog](devlog/2026-05-12-npm-distribution-for-trailmaps.md)) —
  reusable target-aware capability bundles, designed to distribute via npm.
- **Scripted Tools** ([devlog](devlog/2026-04-22-scripting-sdk-authoring-vision.md)) —
  custom tools with the `@trailblaze/scripting` SDK, executed in a QuickJS sandbox or
  host subprocess.
- **Waypoints** ([devlog](devlog/2026-03-11-waypoints-and-app-navigation-graphs.md)) —
  named, assertable app locations defined structurally, never by content.
- **Trail-as-Tool** ([devlog](devlog/2026-04-21-run-trail-tool-proposal.md)) — expose a
  saved trail as a tool so other trails (and agents) can call it.

## Built-in Agent (Fallback)

Trailblaze ships a built-in agent — `blaze`, plus the vision primitives `ask` and
`verify` — for cases where you don't have a coding agent in the loop. It's the same agent
that powers `--self-heal` and the recommended CI workflow.

These commands appear under `Built-in agent:` at the bottom of `trailblaze --help`,
below the recommended deterministic primitives. They require an LLM:

```bash
trailblaze config llm anthropic/claude-sonnet-4-20250514
trailblaze config models     # list everything available
```

Set your provider key in your shell:

```bash
export ANTHROPIC_API_KEY="sk-ant-…"
```

Built-in support: OpenAI (`OPENAI_API_KEY`), Anthropic (`ANTHROPIC_API_KEY`), Google
(`GOOGLE_API_KEY`), OpenRouter (`OPENROUTER_API_KEY`), Ollama (no key required). For
custom endpoints, enterprise gateways, or workspace-level overrides, see
[LLM Configuration](llm_configuration.md).

The built-in agent focuses on the natural-language step it's currently executing, with
vision into the current screen and past steps — fine for many flows. For serious
authoring work, you want a real coding agent (Claude Code, Cursor, Codex) driving the
Trailblaze primitives instead — those bring your codebase, log inspection, and project
context to the loop, which the built-in agent can't.

If you're shelling out from a coding agent and only using deterministic primitives
(`snapshot`, `tool`, `run`), you don't need a Trailblaze-side LLM at all. The agent does
the thinking; Trailblaze does the doing.

## Next Steps

- [CLI Reference](CLI.md) — every command and flag
- [Tool Authoring](tools.md) — add your own tools
- [Configuration](configuration.md) — providers, devices, target apps
- [Project Layout](project_layout.md) — where Trailblaze looks for trails and configs
- [Architecture](architecture.md) — how drivers, the recording pipeline, and the
  built-in agent loop fit together
- [Android On-Device Testing](android_on_device.md) — instrumentation tests on real
  Android devices
- [Host JVM Unit Tests](host_jvm_unit_tests.md) — running trails from JUnit
