---
title: "Scripted Tools — MCP Server Integration Patterns (forward-looking)"
type: decision
date: 2026-04-21
---

# Scripted Tools — MCP Server Integration Patterns

Follow-up to the scope devlog
[Host-Side Subprocess MCP Toolsets](2026-04-20-scripted-tools-a3-host-subprocess.md)
dated yesterday. That devlog covers what the first host-subprocess
landing actually ships (`mcp_servers:` at target root, `script:` entries
only, tool registration, `_trailblazeContext` envelope). This devlog
captures the broader MCP-server integration shape that emerged in
subsequent conversation but is **explicitly deferred beyond that first
landing** — the schema reserves room for it; no runtime is implemented
yet.

This devlog is **forward-looking design**, not a merged contract. When
pieces land, they'll get their own scope devlog(s) that supersede this
one in part.

## Why this matters

Trailblaze's scripted-tools story starts with TypeScript `.ts` files
because that's the ergonomic authoring surface — authors get typed
access to the `_meta["trailblaze/*"]` namespace via
`@trailblaze/scripting`, and the same source compiles both for a host
subprocess and (later) for an on-device QuickJS bundle. But under the
hood, each `.ts` file is **just a standard MCP server over stdio**.
Nothing about the protocol restricts the server's implementation
language, runtime, or provenance.

Once the pattern is "spawn an MCP server, read its tools, register
them," the obvious next step is: let authors plug in **any** MCP
server. Python, compiled binaries, `npx`-vendored npm packages,
vendored 3rd-party servers. That's this devlog.

## The two tiers of MCP server integration

### Tier 1: first-party Trailblaze-aware `.ts`

- Declared via `mcp_servers: [{ script: ... }]` at target root.
- Author uses `@trailblaze/scripting` TypeScript types to get type-safe
  access to `_meta["trailblaze/*"]` inline in the server source.
- Dual-target: host subprocess today, bundled-for-on-device-QuickJS
  later (via a Gradle plugin at app build time).
- This is what the host-subprocess landing implements.

### Tier 2: pure 3rd-party MCP servers

- Declared via `mcp_servers: [{ command: ..., args: [...], env: {...} }]`
  at target root, **or** inside a toolset YAML (see below).
- Host-only — can't be bundled for on-device because there's no guarantee
  a Python/Go/arbitrary interpreter exists there.
- The server doesn't know about Trailblaze, so its tools lack
  `_meta["trailblaze/*"]` keys. Defaults apply (`isForLlm=true`,
  `isRecordable=true`, `requiresHost=false`,
  `supportedPlatforms/Drivers=all`) — but those defaults are wired for
  first-party Trailblaze-authored tools and are often wrong for
  3rd-party servers.
- The `default_meta:` / `tool_meta:` overlay (below) lets authors
  attach the metadata at the declaration site instead of modifying the
  server.

## Two declaration sites

### Target root — for sources that contribute broadly

```yaml
# trails/config/targets/myapp.yaml
id: myapp
display_name: Example App

mcp_servers:
  # Tier 1: first-party .ts, Trailblaze-aware
  - script: ./tools/myapp/login.ts

  # Tier 2: pure MCP server (future — schema reserved)
  - command: python
    args: [./tools/myapp/validators.py]
    env:
      API_BASE_URL: https://api.example.com
```

Tools from target-root sources enter the global session registry under
their advertised names. They're picked up by whatever toolset
YAMLs reference them (via `tools: List<String>`) or by
`_meta["trailblaze/toolset"]` tags in the source.

### Toolset YAML — for curated subsets with metadata overlay

When a 3rd-party MCP server exposes many tools but you only want some,
and you need to attach Trailblaze metadata the server doesn't ship
with, the toolset YAML becomes the right home. The toolset owns the
source, filters by `tools:`, and overlays annotations:

```yaml
# trailblaze-config/toolsets/slack.yaml
id: slack
description: Slack integration (curated subset of @mcp/slack)

# Toolset declares its own MCP server source:
mcp_servers:
  - command: npx
    args: [-y, "@mcp/slack"]
    env:
      SLACK_BOT_TOKEN_ENV_VAR: SLACK_BOT_TOKEN

# Existing pull-list — acts as a filter against the server's tools/list.
# Only these register; other @mcp/slack tools are ignored.
tools:
  - sendMessage
  - listChannels

# Overlay: metadata the 3rd-party server doesn't ship with.
# Applied at registration time before the tool enters the session registry.
# Keys match the MCP `_meta` wire format (vendor-prefixed).
default_meta:
  "trailblaze/requiresHost": true       # python/node servers are host-only
  "trailblaze/isRecordable": false

tool_meta:
  listChannels:
    "trailblaze/isRecordable": true     # override: this tool is cache-safe

# Existing fields govern toolset applicability (unchanged semantics):
drivers:
  - android-ondevice-accessibility
  - ios-host
```

**Spawn scoping for toolset-level sources.** A toolset's `mcp_servers:`
spawn **only if the toolset is referenced by the active platform's
`tool_sets:` list**. No speculative spawns. A `slack` toolset that no
platform pulls in never spawns.

**Target-level sources always spawn for their target's sessions** (once
session context — target + driver + agent-mode — is resolved).

Both sites coexist; authors choose based on whether the tools are
first-party (target root + annotations = easy) or curated 3rd-party
(toolset-level + overlay = honest).

## Spawn scope rule (universal)

**No MCP server spawns outside a resolved (target, driver, agent-mode)
session context.** Concretely:

- **Trail runs** resolve the session and then spawn the target's
  `mcp_servers:` entries plus any toolset-level `mcp_servers:` whose
  toolset is referenced.
- **Tool discovery** (e.g., a `toolbox` CLI lister, or LLM-tool
  introspection for a debug UI) passes the same session context. It
  spawns, reads `tools/list`, may invoke zero or more tool calls
  (typically zero for a pure lister), and tears down. No
  "just-enumerate-everything-globally" mode.

This keeps host resources idle when no session is active and prevents
ambiguous filter state (which driver's annotations apply? which agent
mode?).

## Annotation-overlay semantics

When toolset-level `default_meta:` / `tool_meta:` are set alongside a
`tools/list` response, Trailblaze applies them at registration time in
this precedence order:

1. Per-tool `_meta["trailblaze/*"]` keys the MCP server ships (inside
   its own `tools/list` result) — highest priority.
2. Per-tool overrides from the toolset's `tool_meta:`.
3. `default_meta:` on the toolset — applied to every tool from that
   source that didn't specify the key itself.
4. Trailblaze global defaults (for any key still unset).

So authors can fill gaps left by 3rd-party servers without editing the
server code, and can also override a server's own metadata when needed
(e.g., a Slack server might mark `sendMessage` as not-recordable but the
author wants recording for test fidelity).

## Compatibility using existing toolset fields

`ToolSetYamlConfig` already has `platforms: List<String>?` and
`drivers: List<String>?`. When a toolset declares its own
`mcp_servers:`, those existing fields scope the toolset's applicability.
A `slack` toolset with `drivers: [android-ondevice-accessibility,
ios-host]` is irrelevant under `playwright-native`; Trailblaze won't
include it — and won't spawn its server — for sessions targeting that
driver.

Narrowing semantics: if a toolset says `drivers: [android-ondevice-*]`
and a tool inside it is tagged
`_meta: { "trailblaze/supportedDrivers": ["ios-host"] }`, the tool's
constraint has no overlap with the toolset's envelope, so the tool
silently doesn't register in any session. That's a config error the
author introduced; Trailblaze's job is to be honest about what's
registered, not to validate the full graph at load time.

## Vocabulary (locked for this devlog and forward work)

The three orthogonal axes of a Trailblaze session:

- **Target** — which app (`myapp`, `sample`, etc.). Primary grouping of
  `AppTargetYamlConfig`.
- **Driver** — the automation mechanism (a
  `TrailblazeDriverType.yamlKey`: `android-ondevice-accessibility`,
  `ios-host`, `playwright-native`, …). The driver's name encodes the
  platform; platform is a derived/coarser grouping, not an independent
  axis.
- **Agent execution mode** — host-agent vs on-device-agent. Determined
  by `TrailblazeConfig.preferHostAgent`. Independent of driver; an
  `android-ondevice-*` driver can run under either agent mode.

Driver capability (`TrailblazeDriverType.requiresHost`) describes
whether the driver itself needs a host-resident process to function —
**not** whether the agent runs on host. Tool-level
`_meta["trailblaze/requiresHost"]: true` gates tools by the agent-mode
axis, not the driver axis.

## What lands when

| Piece | When |
|---|---|
| Target-root `mcp_servers:` with `script:` entries (subprocess spawn, MCP handshake, tool registration, `_trailblazeContext` envelope) | This landing — see the scope devlog |
| Target-root `command:` / `args:` / `env:` entries (pure MCP server subprocess spawn) | Follow-up |
| Toolset-level `mcp_servers:` | Follow-up |
| `default_meta:` / `tool_meta:` overlay | Follow-up |
| On-device QuickJS bundling of `script:` entries (Gradle plugin + in-process MCP transport) | Separate landing (the on-device bundle path) |
| Reciprocal `trailblaze.execute()` callback channel (subprocess → Trailblaze primitives) | Later still; design in the subprocess-impl devlog |

## Open questions for the follow-ups

1. **Cross-toolset spawning efficiency.** If two different toolsets
   declare identical `mcp_servers:` entries (same `command:` + `args:`),
   does Trailblaze spawn twice or dedupe? Leaning dedupe by canonicalized
   spawn tuple; decide when implementation lands.
2. **Overlay field shapes.** `default_meta:` and `tool_meta:` above are
   sketched as `Map<String, JsonElement>`
   and `Map<String, Map<String, JsonElement>>` respectively. Confirm
   that shape (vs. typed schemas) when the follow-up lands — the typed
   route is safer but more code.
3. **Credential / secret flow for 3rd-party servers.** The `env:` field
   accepts strings today. Should we support `env_from: [SECRET_NAME]`
   resolved from the host environment with better diagnostics for
   "token not set"? Useful, not MVP.
4. **Discovery flow UX.** What exactly does a `toolbox`-style CLI
   lister output? Tool names + annotations? Tools grouped by toolset?
   Tools grouped by source? Design when the CLI lister PR lands.

## References

- [Host-Side Subprocess MCP Toolsets (scope)](2026-04-20-scripted-tools-a3-host-subprocess.md)
  — what the first landing actually ships. This devlog is the
  forward-looking extension.
- [Scripted Tools — MCP Extension Conventions](2026-04-20-scripted-tools-mcp-conventions.md)
  — annotations, envelope, result shapes, tool naming.
- [Scripted Tools — Toolset Consolidation & Revised Sequencing](2026-04-20-scripted-tools-toolset-consolidation.md)
  — Option-2 amendment that collapsed host-subprocess and on-device
  bundle into parallel landings.
- [Decision 014: Tool Naming Convention](2026-01-14-tool-naming-convention.md)
  — authoritative tool-name structure.
- [Decision 029: Custom Tool Architecture](2026-02-03-custom-tool-architecture.md)
  — RPC architecture the future reciprocal callback channel will adopt.
- `TrailblazeDriverType` enum in `:trailblaze-models`
  (`xyz.block.trailblaze.devices.TrailblazeDriverType`) — canonical
  vocabulary for the driver axis.
- `TrailblazeConfig.preferHostAgent` in `:trailblaze-models`
  (`xyz.block.trailblaze.model.TrailblazeConfig`) — agent-execution-mode
  setting.
