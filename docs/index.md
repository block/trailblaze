---
title: Introduction
---

# 🧭 Trailblaze

[Trailblaze](https://github.com/block/trailblaze) is an **AI-powered UI testing framework** for iOS, Android, and web. It includes platform-native device drivers (Android UiAutomator/Compose, iOS XCUITest, web Playwright), an agent loop, a recording/replay system that turns successful runs into deterministic `.trail.yaml` files, a desktop app, and a CLI — `blaze`, `ask`, `verify`, `snapshot`, `tool`, `trail` — that any LLM can drive. Two ways to drive it:

- **Trailblaze's built-in agent.** `trailblaze blaze "<goal>"` runs the `blaze` agent end-to-end against a natural-language goal — it plans, calls tools, recovers from popups and stuck states, and produces a recording. No external coding agent required.
- **Your AI coding agent.** Claude Code, Codex, Cursor, Goose, Windsurf, Aider, a CI runner, a homegrown bash agent — they all invoke the same CLI primitives. No SDK to install. No protocol to negotiate. No provider keys to wire up on the agent's side.

Your agent already knows how to use a CLI; that's all Trailblaze needs.

## Built-in Agent or Any Agent. One CLI.

```bash
trailblaze device list
trailblaze device connect android

# Drive end-to-end with Trailblaze's own agent
trailblaze blaze -d android "Sign in as test@example.com and confirm home"

# Or call primitives directly — by hand or from any AI coding agent
trailblaze snapshot -d android                                              # See what's on screen
trailblaze tool tapOnElement -d android ref="Sign In" -o "Tap sign in"      # Act, with intent
trailblaze verify -d android "Welcome screen is visible"                    # Pass/fail (exit 0/1)
```

Paste those into a Claude Code, Codex, Cursor, or Goose session and the agent is already authoring tests.

## Why an Agent Loop at the Core

The LLM is what does the heavy lifting of driving an app: planning steps, picking selectors, recovering from popups. Trailblaze keeps that loop first-class — Trailblaze's built-in `blaze` agent runs it natively, and external coding agents can plug into the same primitives. Trails are the artifact the agent leaves behind. Humans step in to author goals, review recordings, debug failures, and ship deterministic replays to CI.

That gives you three modes from one stack:

1. **Drive a device with an agent** — `trailblaze blaze "<goal>"` runs Trailblaze's own agent, or your AI coding agent shells out to the same primitives. Every step records a screenshot, the UI hierarchy, the tool call, and the agent's reasoning.
2. **Save what worked as a trail** — a `.trail.yaml` file. Re-run it later with no LLM in the loop, in CI, on emulators, simulators, or browsers. Opt into **self-heal** (`--self-heal`) and `blaze` steps back in to patch a step and update the recording when the UI has actually drifted; default is fail-loud.
3. **Inspect every run in high-fidelity reports** — per-step screenshots, hierarchies, recorded tool calls, the full LLM transcript, and video replay. CI exposes the report inline on every build (open in a browser, no Trailblaze install needed); the desktop app shows the same UI for local sessions plus inline trail editing.

## Platform-Native, Not Lowest-Common-Denominator

Trailblaze does *not* flatten platforms into a single abstraction. Each driver speaks its host platform's native vocabulary:

| Platform | Driver | Hierarchy |
|---|---|---|
| Android | UiAutomator / Compose / on-device instrumentation | `Button`, `EditText`, `RecyclerView`, `Switch` |
| iOS | Native Accessibility / XCUITest | `UIButton`, `UITextField`, `UITableView` |
| Web | Playwright | ARIA roles, full DOM, network, console |

The `tap`/`assertVisible`/`inputText` tools work everywhere, but the things they *see* are real platform elements — not a stripped-down subset that throws away platform information. Selectors stay stable, assertions stay precise, and the agent's grounding stays trustworthy.

Drivers are decoupled, web is first-class through Playwright, Android has a custom on-device driver, and the agent loop, recordings, packs, scripted tools, and waypoints are all Trailblaze's own.

## Core Capabilities

- **Built-in `blaze` agent** — `trailblaze blaze "<goal>"` runs Trailblaze's own agent end-to-end: planning, tool calls, popup recovery, stuck-state detection, recording. The same agent powers `--self-heal`.
- **[CLI any agent can drive](CLI.md)** — `blaze` to drive a device, `ask` to query the screen, `verify` for CI assertions, `trail` to run recorded YAML, `tool` to fire individual tools, `snapshot` for the UI tree. Every capability is a shell subcommand — Claude Code, Codex, Goose, etc. invoke them the same way you do.
- **[`--objective` on every tool call](CLI.md#trailblaze-tool)** — capture *why* alongside *what*. When the UI drifts, recorded trails self-heal against the objective instead of breaking on a brittle selector.
- **[Self-heal](architecture.md#execution-modes)** — opt in (`--self-heal`) and the `blaze` agent patches a failing recorded step and updates the recording on success. Default is fail-loud, so flakes don't get silently masked.
- **[Trails](project_layout.md)** — drop a `.trail.yaml` anywhere in your project. No `trails/` directory required. Run by path or shell glob; auto-discovered.
- **High-fidelity reporting** — every run produces a rich report (per-step screenshots, hierarchies, recorded tool calls, LLM transcripts, video replay). CI exposes it inline on every build; the desktop app shows the same UI for local sessions.
- **[External config bundles](generated/external-config.md)** — layer app targets, YAML toolsets, and JS/TS scripted tools on top of the binary without rebuilding Trailblaze.
- **Multi-device CLI sessions** — drive Android + iOS + web from the same shell, in parallel, each with its own bound device.

## Active Prototypes

Trailblaze is moving fast. These are landing now and are worth knowing about even if they're not stable yet:

### Packs

A **pack** is a reusable bundle of target-aware capabilities — tools, waypoints, navigation routes, and recorded trails — that an app team publishes once and that both human authors and live agents consume. Think of it as *the Robot Pattern, generalized and shippable*: not just a bag of helper methods inside one test suite, but a published library plus a navigation model plus runnable proof that the model still works.

See: the [Packs guide](packs.md) for the manifest schema, per-file scripted tools, and the workspace-vs-classpath precedence rule. Background: [Target Packs: Local-First Packaging](devlog/2026-04-26-target-packs-local-first.md), [Trailblaze as the Robot Pattern — and More](devlog/2026-04-26-robot-pattern-plus-packs.md).

### Scripted Tools (JS/TS)

Custom tools, written in TypeScript, that drop into a pack with no Kotlin or Gradle build. The `@trailblaze/scripting` SDK gives typed access to device context (`platform`, `memory`, `sessionId`) and lets a scripted tool call back into Trailblaze primitives via `client.callTool()` to compose higher-level behavior. Tools execute in a QuickJS sandbox on-device or in a host subprocess.

See: [@trailblaze/scripting Authoring Vision](devlog/2026-04-22-scripting-sdk-authoring-vision.md), [Scripted Tools Execution Model](devlog/2026-04-20-scripted-tools-execution-model.md).

### Waypoints

A **waypoint** is a named, assertable location in the app — defined structurally (element identity, stable labels), never by content. Waypoints power the agent's mental map of an app: it can ask "am I on the Inbox?", land on a waypoint after a step, or use waypoints as checkpoints for trails. The `matchWaypoint` tool runs against captured session state and returns clean matches plus near-misses (off by one assertion), so authors iterate without staged pipelines.

See: [Waypoints and App Navigation Graphs](devlog/2026-03-11-waypoints-and-app-navigation-graphs.md), [Waypoint Discovery via matchWaypoint](devlog/2026-04-21-waypoint-discovery-and-matching.md).

### Trail-as-Tool

A trail can itself be exposed as a tool, so an agent (or a higher-level trail) can call it like any other capability. This makes flows composable: a `loginAsTestUser` trail becomes a one-line setup step inside any other test.

See: [runTrail Trail-as-Tool Primitive](devlog/2026-04-21-run-trail-tool-proposal.md).

## Multi-Agent V3 Features

Trailblaze implements features from the [Mobile-Agent-v3](https://arxiv.org/abs/2508.15144) research line:

- **Exception handling** — popups, ads, loading states, errors handled automatically
- **Reflection & self-correction** — detects stuck states, backtracks
- **Task decomposition** — breaks complex objectives into subtasks
- **Cross-app memory** — remembers information across app switches
- **Enhanced recording** — captures pre/post conditions for robust replay
- **Progress reporting** — real-time progress events for IDE integration

See [Architecture / Multi-Agent V3](architecture.md#multi-agent-v3-architecture).

## Where to Go Next

- **New here?** Start with [Getting Started](getting_started.md).
- **Wiring an agent over the CLI?** See the [CLI reference](CLI.md) and the [README](https://github.com/block/trailblaze#readme).
- **Authoring trails?** See [Project Layout](project_layout.md) and [Configuration](configuration.md).
- **Customizing the LLM?** See [LLM Configuration](llm_configuration.md) and [Built-in Models](generated/LLM_MODELS.md).
- **Going deep?** See [Architecture](architecture.md) and the [devlog](devlog/index.md).

## License

Trailblaze is licensed under the [Apache License 2.0](LICENSE).
