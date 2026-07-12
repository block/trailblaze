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
objective. The resulting trail YAML is both the source of truth — *what* the flow
does — and the deterministic execution artifact — *how* it runs. CI replays the trail
with no LLM in the loop.

Trailblaze is not its own coding agent. Your editor's agent does the planning and the
reading; Trailblaze handles the device.

## What you'll do in this guide

1. Install Trailblaze and point it at a device.
2. Drive the device through primitives (`snapshot`, `tool`) from a shell — or from your
   coding agent shelling out to the same commands.
3. Save what you just did as a trail file.
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
brew install block/tap/trailblaze
```

Or install from the GitHub release:

```bash
curl -fsSL https://raw.githubusercontent.com/block/trailblaze/main/install.sh | bash
```

Or clone and run from source (`./trailblaze` is the repo-root wrapper that rebuilds and
dispatches to the source-built CLI):

```bash
git clone https://github.com/block/trailblaze.git
cd trailblaze
./trailblaze --help     # CLI usage
./trailblaze app        # Start the daemon (also opens the desktop app on macOS)
```

**Bundled with the Homebrew install:**

- `bun` — the JavaScript runtime Trailblaze uses to type-check and analyze your TypeScript
  scripted tools. `brew install block/tap/trailblaze` pulls it in as a dependency, so
  **authoring typed scripted tools works out of the box** — no `bun install`, no
  `node_modules` (the SDK and analyzer ship inside the CLI). Installing from source or
  `install.sh` instead? Just put `bun` on your `PATH` ([bun.sh](https://bun.sh)).

**Optional, via Homebrew (`brew install esbuild ffmpeg`):**

- `esbuild` — needed only when *running* trailmap-defined scripted tools written in
  TypeScript.
- `ffmpeg` — needed only for trail video capture and sprite extraction. Trails still run
  without it; only the rendered video and sprite-strip outputs are missing.

## Connect a Device

List what's connected, then pin this terminal to one of them:

```bash
trailblaze device list

# Pin this terminal to a device + target. Subsequent calls inherit both,
# so you don't have to repeat -d / --target on every command.
trailblaze device connect android --target default
```

`device list` shows Android emulators (`android/emulator-5554`), iOS simulators
(`ios/<simulator-id>`), and any web targets. For `device connect`, the short form
`android` works when only one Android device is connected; with two or more, pass
the fully-qualified `android/<id>` shown by `device list` (same for `ios/<udid>`).
`web` is always unambiguous.

That's it — you're ready to drive. To swap target without disconnecting, use
`trailblaze device rebind --target <new>`; to release, `trailblaze device disconnect`.

### Device pinning — reference details

You can skip this on first read. It covers what the pin actually does, which commands
respect it, and how to override it in CI.

After `device connect`, every device-acting CLI call (`snapshot`, `tool`, `step`, `ask`,
`verify`, `session start/stop`, `run`) picks up the pinned device automatically — no
`-d` flag needed. Workspace and setup commands (`config`, `app`, `device list`) don't
take `-d`. `mcp` takes `--device` / `--target` at startup to pre-bind the MCP session
so the agent's first tool call already has a device.

**Multiple terminals stay independent.** The pin is per-shell-PID, recorded in
`~/.trailblaze/shell-device-pins-<port>.json`. Pinning device A in one terminal doesn't
leak into another, and the pin survives daemon restarts. For CI scripts (each call is a
fresh shell), pass `--device <id>` on every command — the per-shell pin won't carry.

**Resolution order**, highest priority first:

1. Explicit `--device <id>` flag on the command.
2. `TRAILBLAZE_DEVICE` env var (manual override, mostly for CI).
3. This terminal's file-pin (from `trailblaze device connect`).
4. Autodetect — used when exactly one device is connected.

**If your pinned device goes away** (emulator killed, USB unplugged), the next call
fails with `Device bind failed` and self-evicts the pin; the call *after* that falls
through to autodetect.

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
pin this terminal to a device + target so subsequent calls don't have to repeat the flags:
  - `trailblaze device connect <platform> --target <app>` — pin once at start
  - `trailblaze session start --title "<short_name>"` — start a tracked session
    (captures video/logs, groups the steps for later save)
  - `trailblaze snapshot` — see what's on screen (UI tree with refs)
  - `trailblaze tool <name> <args> -s "<why>"` — take an action
  - `trailblaze toolbox` — list available tools (uses the pinned device automatically)
  - When done, `trailblaze session save` to write the recording out as a trail file,
    then `trailblaze session stop` to end the session. Optionally
    `trailblaze device disconnect` to release the device.
```

If your agent can run a shell command, it can drive a device. No SDK to install, no
protocol to negotiate, no provider keys to wire on the agent's side.

## Save the Session as a Trail

While the session was running, Trailblaze recorded every step. Persist the recording
as a trail file and end the session:

```bash
trailblaze session save                    # uses the title from `session start`
trailblaze session stop
```

> Unified saves are still rolling out as the default. Until the flip lands, turn them
> on once with `trailblaze config unified-recordings true` (or set
> `TRAILBLAZE_UNIFIED_RECORDINGS=1`); without it, saves write the legacy per-device
> `<classifier>.trail.yaml` format instead. Replay accepts both.

The resulting trail holds the natural-language steps, with each step's recorded tool
sequence nested under a `recording:` block keyed by device classifier — so one file
carries the recordings for every platform you've run it on. Drop it anywhere in your
project (a `trails/` directory is conventional but not required — see
[Project Layout](project_layout.md) for discovery rules and file naming).

A minimal example:

```yaml
# flows/login/trail.yaml
config:
  title: login

trail:
  - verify: the "Sign in" screen is visible
  - step: Sign in as the demo user
  - verify: the home tab is selected
```

## Replay Deterministically

```bash
trailblaze run flows/login/trail.yaml -d android
trailblaze run flows/ -d android                         # batch: every trail under flows/
trailblaze run flows/login/trail.yaml -d android --self-heal
```

By default `trailblaze run` replays the recorded tool sequence with **no LLM in the
loop** — fast, deterministic, cheap. This is the path CI takes.

### When does the LLM actually run?

A reasonable question for a cost-conscious team. The short answer:

| Step / mode | LLM called? |
|---|---|
| A step with a `recording:` block (replay) | **No** — deterministic, replays the recorded tool calls. |
| A bare `step:` (no `recording:`) | **Yes** — the agent picks the tools and selectors live. |
| `verify:` (vision assertion) | **Yes** — an LLM judges the screenshot against the prose claim. |
| `--self-heal` (any step that drifted) | **Yes** — only on the failing step, only when `--self-heal` is set. |
| Authoring mode: `trailblaze step "…"`, `trailblaze ask "…"`, `trailblaze verify "…"` | **Yes** — these are the built-in-agent surface. |

So a fully-recorded trail with no `verify:` steps and no `--self-heal` is 100% LLM-free
at replay. Add `verify:` steps when you want vision-grade assertions that survive
selector drift; add `--self-heal` opt-in in CI when you'd rather an agent try to patch a
drifted step than fail the build. The Ollama path is keyless if you want to stand this
up without paying anything.

`--self-heal` opts in to small-drift recovery: if a recorded step doesn't match the
screen anymore, Trailblaze's built-in agent patches the failing step against the live
screen and updates the recording on success. Self-heal is opt-in by design; the default
is fail-loud so real flakes don't get silently masked.

**Self-heal needs an LLM** (it's the LLM that figures out the new selector against the
drifted screen). Set a provider key once (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`,
`GOOGLE_API_KEY`, or `OPENROUTER_API_KEY`) — or run a local model via Ollama with no
key — and you're set. See [LLM Configuration](llm_configuration.md) for the full list
of supported providers and how teams configure shared gateways. Plain `trailblaze run`
without `--self-heal` doesn't touch an LLM at all.

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

- **Custom commands** ([Scripted Tools](scripted-tools-typed-authoring.md)) like `login`
  or `addToCart`, written in TypeScript with type-safe bindings, with LLM-facing
  descriptions you write for the tool and each parameter. Your agent reads those
  descriptions to decide when and how to call them. Every call — yours, the built-ins,
  or third-party — is recordable and replayable. Tools run in an embedded JavaScript
  sandbox shipped inside Trailblaze (no separate Node install, no `node_modules`); the
  rare tool that needs full Node APIs opts into a Bun subprocess with one flag.
- **Named waypoints** for your screens, so the agent can ask "am I on the Inbox?", land
  on a waypoint after a step, or use waypoints as trail checkpoints. Waypoints are an
  active prototype — see the [devlog](devlog/2026-03-11-waypoints-and-app-navigation-graphs.md).
- **[Trailmaps](trailmaps.md)** to bundle tools + waypoints + recorded trails per app,
  shared across teams — the unit of authoring going forward.

## Built-in Agent (Fallback)

Trailblaze ships a built-in agent — `trailblaze step`, plus the vision primitives
`trailblaze ask` and `trailblaze verify` — for cases where you don't have a coding agent
in the loop. It's the same agent that powers `--self-heal` and the recommended CI
workflow. (`trailblaze blaze` remains accepted as a deprecated alias of
`trailblaze step`.)

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
