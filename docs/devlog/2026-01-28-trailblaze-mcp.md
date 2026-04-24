---
title: "Trailblaze MCP"
type: decision
date: 2026-01-28
---

# Trailblaze Decision 008: Trailblaze MCP

## Context

Trailblaze provides LLM-driven UI automation for mobile applications.

Historically, single-agent approaches to UI automation required the agent to maintain screen state (view hierarchies, screenshots) within its own conversation. This caused two problems:

1. **Context window bloat**: Each step added more screen state to the conversation, eventually exhausting the context limit
2. **LLM confusion**: Multiple screen states in the same conversation led to the model reasoning about outdated UI or conflating different screens

Trailblaze addresses this with a **subagent architecture**: each step is handled by a fresh agent conversation that only receives the current screen state. The orchestrating layer maintains continuity while subagents operate statelessly on the latest UI.

Multiple audiences have expressed interest in integrating with Trailblaze via MCP for device control:

- **Mobile engineers and AI coding assistants (e.g. Firebender, Claude Code)**: Automate mobile UI interactions to remove human-in-the-loop friction during development—typically throwaway trails for quick validation of a flow
- **Test authoring, execution, and infrastructure**: Enable developers and QE to create, run, and manage persistent UI tests that run continuously
- **General device control**: Provide MCP-based mobile device control for any agent or tool that needs to interact with mobile applications

A key principle: **author once, run deterministically**. While the subagent approach is used during initial authoring (exploring the UI, figuring out the right steps), the result is a recorded trail. Subsequent runs use the trail deterministically without LLM reasoning—fast, predictable, and cost-free.

**Trail recording** works through sessions: a new session starts automatically when interactions begin, and everything within that session is recorded. Users explicitly indicate when they want to finalize a trail from their actions, allowing them to review in the Trailblaze desktop app before sending it for automated execution.

**Trail storage**: Trails are persisted as `trail.yaml` files on disk, stored in a project-level directory and referenced by path. Downstream test infrastructures may additionally generate trails from natural language when one doesn't exist on disk.

**AI fallback** can recover from trail failures due to UI changes, but is disabled by default. This preserves determinism and avoids LLM costs. When a trail step fails, Trailblaze reports the failure to the MCP client, which can then decide whether to invoke AI-assisted recovery using natural language prompts.

**Custom tools** are a key benefit of Trailblaze. By specifying a target app, teams get access to app-specific tools that expose functionality beyond standard UI interactions. For example, an app target can provide a tool for quickly logging into staging or test accounts, providing the same access as debug menus without navigating through the UI.

The [Model Context Protocol (MCP)](https://modelcontextprotocol.io) provides a standardized interface for exposing Trailblaze capabilities to external AI systems. Trailblaze uses the **Streamable HTTP** transport, which allows MCP clients to connect via HTTP POST requests to a session-based endpoint. See the [MCP setup guide](../mcp/index.md) for connection details.

## Decision

**Introduce a Trailblaze MCP server with multiple modes that support different integration patterns. Tools are dynamically registered based on the current mode, and clients can switch modes during a session.**

### Operating Modes

The modes are defined by two questions:
1. **Who is the agent?** (Who decides what actions to take)
2. **Where does the LLM come from?** (Who provides the "brain")

---

#### Mode 1: `MCP_CLIENT_LIKE_GOOSE_AS_AGENT` (Dumb Tools)

| Aspect | Value |
|--------|-------|
| **Who's the agent** | MCP client (e.g., Goose, Firebender) |
| **LLM source** | MCP client's LLM |
| **Trailblaze exposes** | Primitive tools only (`tap`, `swipe`, `inputText`, `getScreenshot`, `viewHierarchy`) |
| **Trailblaze role** | Dumb tool executor - no reasoning |

```
Goose: "I see login button" → tap(150, 300)
Goose: "I see text field" → inputText("username")
Goose: "I see password field" → inputText("password")
```

Trailblaze is completely dumb. Just executes what the MCP client tells it.

---

#### Mode 2: `TRAILBLAZE_AGENT_WHILE_LOOP` (Local LLM)

| Aspect | Value |
|--------|-------|
| **Who's the agent** | Trailblaze |
| **LLM source** | Trailblaze's local LLM (configured provider) |
| **Trailblaze exposes** | `runPrompt()` only |
| **Trailblaze role** | Full agent - does all reasoning and execution |

```
Goose: runPrompt("login to the app")
Trailblaze: *thinks using configured LLM* → tap → type → tap → done
Goose: *waits, gets result*
```

The MCP client just kicks off the task. Trailblaze does everything internally.

---

#### Mode 3: `MCP_CLIENT_LIKE_GOOSE_WITH_SAMPLING` (Tunneled LLM)

| Aspect | Value |
|--------|-------|
| **Who's the agent** | MCP client (high-level) + Trailblaze (low-level execution) |
| **LLM source** | MCP client's LLM (tunneled via MCP Sampling) |
| **Trailblaze exposes** | High-level tools (`runPrompt`, `switchToolSet`) |
| **Trailblaze role** | Sub-agent that borrows MCP client's brain |

```
Goose: runPrompt("tap the login button")  ← Goose decides WHAT task
Trailblaze: *needs to think* → asks Goose via sampling: "where is login button?"
Goose's LLM: "it's at (150, 300)"
Trailblaze: *taps* → returns result
Goose: runPrompt("enter username sam")    ← Goose decides NEXT task
```

**Goose drives the conversation** (decides what tasks to do next).
**Trailblaze borrows Goose's brain** for the low-level "how" decisions via MCP Sampling.

---

#### Mode 4: `TRAILBLAZE_AGENT_RECURSIVE_MCP` (Future - Self-Connection)

| Aspect | Value |
|--------|-------|
| **Who's the agent** | Trailblaze |
| **LLM source** | Trailblaze's local LLM (configured provider) |
| **Trailblaze exposes** | `runPrompt()` only |
| **Trailblaze role** | Full agent that calls its OWN MCP tools |

```
Goose: runPrompt("login to the app")
Trailblaze Agent: *thinks using local LLM*
Trailblaze Agent: → calls tap() via MCP (to itself!)
Trailblaze Agent: → calls inputText() via MCP (to itself!)
Trailblaze Agent: → done, returns to Goose
```

Same external interface as Mode 2, but internally the agent uses MCP for tool execution (self-connection). This creates **architectural symmetry** - external MCP clients and internal agent use the exact same tool interface.

---

### Mode Summary Table

| Mode | Agent | LLM Source | Trailblaze Role | Status |
|------|-------|------------|-----------------|--------|
| `MCP_CLIENT_LIKE_GOOSE_AS_AGENT` | MCP client | MCP client | Dumb tool executor | ✅ Implemented |
| `TRAILBLAZE_AGENT_WHILE_LOOP` | Trailblaze | Local (configured LLM) | Full agent | ✅ Implemented |
| `MCP_CLIENT_LIKE_GOOSE_WITH_SAMPLING` | MCP client + Trailblaze | MCP client (tunneled) | Sub-agent, borrows brain | ✅ Implemented |
| `TRAILBLAZE_AGENT_RECURSIVE_MCP` | Trailblaze | Local (configured LLM) | Full agent via self-MCP | 🔮 Future |

**Note on deterministic execution**: Trail recording/playback is orthogonal to these modes. If a trail has recordings, it runs deterministically without LLM calls regardless of mode.

---

### Session State

The MCP server is single-tenant—one session controls one device at a time. Settings like target device, target app, and platform are retained within a session and persist across reconnections.

### Dynamic Tool Management

Tools change based on multiple dimensions:
- **Mode**: Switching between modes changes available tools
- **Target app**: App-specific tools for your configured app targets
- **Target platform**: iOS vs Android may expose different capabilities
- **Tool categories**: Subagents can dynamically swap toolsets to reduce context window usage

Users can configure settings via the Trailblaze desktop app or via MCP tools (e.g., `setMode`, `setTargetApp`).

### Scope

This design assumes **local device control**: the MCP server runs on the same machine as the MCP client, with devices connected directly via ADB (Android) or as physical/simulated iOS devices. Remote device farms and cloud-based device provisioning are out of scope.

## Consequences

**Positive:**
- Single MCP server supports multiple integration patterns
- Client Agent mode requires no Trailblaze LLM configuration
- Dynamic mode switching enables seamless transitions between execution and authoring
- MCP Sampling enables the subagent pattern, preventing context window exhaustion
- Deterministic trail execution by default keeps costs low and behavior predictable

**Negative:**
- TRAILBLAZE_AS_AGENT mode requires LLM configuration
- MCP_CLIENT_AS_AGENT mode with subagent orchestration requires clients that support MCP Sampling
- Single-tenant design limits to one device per session

---

## Implementation Summary

### What Was Built

| Component | Description |
|-----------|-------------|
| **Session Configuration** | `TrailblazeMcpMode`, `ScreenshotFormat`, `ViewHierarchyVerbosity`, `LlmCallStrategy` enums and configurable `TrailblazeMcpSessionContext` |
| **Session Config Tools** | `getSessionConfig`, `setMode`, `setScreenshotFormat`, `setAutoIncludeScreenshot`, `setViewHierarchyVerbosity`, `setLlmCallStrategy`, `configureSession` - all use enum parameters directly for type safety |
| **Dynamic Tool Categories** | `ToolSetCategory` enum with `DynamicToolSetManager` for per-session tool state |
| **Tool Management Tools** | `listToolCategories`, `enableToolCategories`, `addToolCategory`, `removeToolCategory`, `focusOnCategory`, plus presets (`useMinimalTools`, `useStandardTools`, `useTestingTools`) |
| **MCP Sampling Support** | `McpSamplingClient` using MCP Kotlin SDK's `ServerSession.createMessage()` and `SubagentOrchestrator` for multi-step automation |
| **Progress Notifications** | `McpProgressNotifier` bridges LogsRepo events to MCP progress notifications |
| **Multi-Session Support** | New transport + MCP server instance per client, allowing simultaneous connections |
| **Bridge Entry Point** | `runYamlBlocking()` method encapsulates MCP-specific blocking execution with progress callbacks |
| **Cancellation Propagation** | MCP session lifecycle wired to automation cancellation |
| **MCP Tool Executor** | `McpToolExecutor` interface with `DirectMcpToolExecutor` for in-process tool execution |
| **Dual Sampling Source** | `SamplingSource` interface with `LocalLlmSamplingSource`, `McpClientSamplingSource`, and `SamplingSourceResolver` |
| **Koog MCP Agent** | `KoogMcpAgent` using Koog's native `AIAgent` with MCP tools via self-connection |
| **LLM Call Strategy** | `LlmCallStrategy` enum (DIRECT/MCP_SAMPLING) for selecting how LLM API calls are made |
| **Agent Metrics** | `AgentMetricsCollector` tracking success/failure rates, `getAgentMetrics` and `clearAgentMetrics` tools |
| **LLM Wiring** | Optional `llmClientProvider` and `llmModelProvider` in `TrailblazeMcpServer` for local LLM fallback |

### Type-Safe Enum Parameters

MCP tool parameters use **enum types directly** instead of strings. Koog and the MCP SDK serialize enums automatically via kotlinx.serialization.

**Benefits**:
- **LLM visibility**: Enum values are enumerated in the tool schema, so LLMs see all valid options
- **Type safety**: No runtime parsing errors from invalid string values
- **Cleaner code**: No `fromString()` boilerplate in enum companions

**Example**: `setMode(mode: TrailblazeMcpMode)` instead of `setMode(mode: String)`. The LLM sees the schema includes `MCP_CLIENT_AS_AGENT` and `TRAILBLAZE_AS_AGENT` as valid values.

### Two-Tier Tool Management Pattern

For subagents to reduce context window usage:

1. **Parent LLM** selects initial tool categories based on the high-level task
2. **Subagent** can swap categories as it discovers what it needs

This reduces context window usage by 50-80% compared to exposing all tools.

### MCP Logging Infrastructure

Structured `TrailblazeLog` events for MCP agent operations, enabling visibility in the Trailblaze desktop app and debugging:

| Log Type | Purpose |
|----------|---------|
| `McpAgentRunLog` | Full agent run lifecycle - objective, transport mode, iteration count, final result |
| `McpAgentIterationLog` | Per-iteration details - iteration number, LLM completion, tool called, result |
| `McpSamplingLog` | LLM completion requests - messages, model, tokens, duration, strategy |
| `McpAgentToolLog` | Tool execution - tool name, arguments, result, duration, transport mode |

All log types use enum types (`AgentToolTransport`, `LlmCallStrategy`) for type safety, defined in `trailblaze-models` so logs can reference them.

---

## Known Limitations

1. **MCP Sampling**: Most MCP clients (Cursor, Firebender) don't support `sampling/createMessage`. Goose does support it. Use TRAILBLAZE_AS_AGENT mode with DIRECT LLM strategy as the recommended fallback.

2. **Manual Refresh Required After Server Restart**: Sessions are in-memory only. Trailblaze returns HTTP 404 per [MCP spec](https://modelcontextprotocol.io/specification/draft/basic/transports#session-management), but Cursor/Firebender don't auto-reconnect ([known client bug](https://forum.cursor.com/t/mcp-client-wrong-handling-of-http-not-found-in-session-management-stateful-mcp-server/134781)). Manual refresh is required.

3. **Single Device Per Session**: Each MCP session controls one device at a time.

---

## Future Direction: Two-Tier Agent Architecture

> **See [Decision 025: Two-Tier Agent Architecture](./2026-02-04-trail-blaze-agent-architecture.md)** for the next evolution of agent design.

The two-tier architecture separates concerns:
- **Outer Agent** (MCP client like Goose, or Koog in standalone): Planning, replanning, cross-system orchestration
- **Inner Agent** (Trailblaze): Screen understanding, action recommendation, device execution

This enables **model specialization** (cheap vision model for screen analysis, expensive reasoning model for planning) and **cross-system testing** where the outer agent coordinates mobile UI + filesystem + database + API verification.

---

## Architecture: TrailblazeMcpBridge

`TrailblazeMcpBridgeImpl` is the **primary entry point** for all MCP-specific operations, bridging MCP's request/response model and Trailblaze's internal async architecture.

| Aspect | Desktop UI | MCP |
|--------|-----------|-----|
| Execution model | Fire-and-forget | Must block until completion |
| Progress | Shown in UI | Streamed as MCP notifications |
| Session continuity | UI maintains state | Bridge manages per-device sessions |
| Cancellation | User clicks Stop | MCP session close triggers cancellation |

**Bridge Responsibilities:**
- Device selection and session management
- YAML execution (`runYaml()` fire-and-forget, `runYamlBlocking()` for MCP)
- Screen state access and tool execution
- Cancellation propagation
