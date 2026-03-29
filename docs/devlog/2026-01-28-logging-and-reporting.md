---
title: "Logging and Reporting Architecture"
type: decision
date: 2026-01-28
---

# Logging and Reporting Architecture

Designing structured logging that works across agent runs, CI, and desktop.

## Background

AI agents are notoriously difficult to debug. When a test fails, understanding *why* requires visibility into:

- What the agent "saw" (screen state, view hierarchy)
- What the LLM was asked and what it responded
- Which tools were executed and their results
- The sequence of events leading to failure

Without detailed logging, debugging becomes guesswork. Additionally, we need to present this information in ways that are accessible during development (desktop app) and in CI results (web reports).

## What we decided

**Trailblaze implements a structured logging system (`TrailblazeLog`) that captures detailed agent activity, which powers both the desktop app's real-time view and generated web reports.**

### Structured Log Events

All agent activity is captured as typed log events that inherit from `TrailblazeLog`. Each log includes:

- **Session ID**: Groups logs for a single test execution
- **Timestamp**: Precise timing for event ordering

Key log types capture different aspects of agent behavior:

| Log Type | Purpose |
| :--- | :--- |
| `TrailblazeSessionStatusChangeLog` | Test lifecycle (started, completed, failed) |
| `TrailblazeLlmRequestLog` | LLM prompts, responses, tool calls, and cost |
| `TrailblazeToolLog` | Tool execution results and timing |
| `MaestroDriverLog` | Low-level device interactions |
| `MaestroCommandLog` | Maestro command execution details |
| `ObjectiveStartLog` / `ObjectiveCompleteLog` | Test step progress |
| `TrailblazeSnapshotLog` | User-initiated screen captures |
| `TrailblazeAgentTaskStatusChangeLog` | Agent task state transitions |

### Rich Context Capture

Logs capture rich context for debugging:

- **Screenshots**: Screen captures at key moments (LLM requests, tool execution)
- **View Hierarchies**: Full and filtered UI tree for element inspection
- **LLM Messages**: Complete conversation history with the model
- **Tool Options**: Available tools at each decision point
- **Usage/Cost**: Token counts and estimated costs per LLM request
- **Durations**: Timing for each operation

### Log Storage

Logs are written to disk as JSON files organized by session:

```
logs/
└── 2026-01-28_14-30-00_LoginTest/
    ├── 001_TrailblazeSessionStatusChangeLog.json
    ├── 002_TrailblazeLlmRequestLog.json
    ├── 002_screenshot.png
    ├── 003_TrailblazeToolLog.json
    ├── 004_MaestroDriverLog.json
    └── ...
```

This file-based approach enables:

- Persistence across restarts
- Easy sharing of debug artifacts
- Simple archiving in CI systems
- Reactive file watching for live updates

### Desktop App Integration

The desktop app uses `LogsRepo` to provide a real-time view of test execution:

- **Live updates**: File watchers detect new logs and update the UI immediately
- **Session list**: Browse all test sessions with status indicators
- **Log timeline**: Step through events chronologically
- **Screenshot viewer**: See exactly what the agent saw
- **View hierarchy inspector**: Explore the UI tree at any point
- **LLM conversation viewer**: Review prompts and responses

This makes the desktop app an essential development tool—engineers can watch tests execute in real-time and immediately understand failures.

### Web Report Generation

The `trailblaze-report` module generates static HTML/WASM reports from log data:

1. **Log collection**: Gather logs from test execution (local or CI)
2. **Report generation**: Bundle logs with a WebAssembly-based viewer
3. **Static output**: Single-file HTML that can be viewed in any browser

Reports provide the same inspection capabilities as the desktop app but as a shareable artifact. This is critical for CI pipelines where:

- Test failures need investigation without access to the original machine
- Results must be archived for compliance or historical analysis
- Multiple team members need to review the same failure

### Why Custom Logging (Not Standard Logging Frameworks)

We chose structured `TrailblazeLog` events over traditional logging (Log4j, SLF4J) because:

1. **Type safety**: Sealed class hierarchy ensures all logs have required fields
2. **Rich data**: Screenshots and view hierarchies can't be captured in text logs
3. **Queryable**: Logs can be filtered by type, searched, and analyzed programmatically
4. **UI-friendly**: Typed events map directly to UI components
5. **Cross-platform**: Same log format works on Android, desktop, and web

Traditional logging is still used for framework-level debugging, but `TrailblazeLog` captures the semantically meaningful agent events.

## What changed

**Positive:**

- Debugging agent failures becomes tractable with full context
- Desktop app provides immediate feedback during development
- CI reports enable async investigation of failures
- Screenshots and hierarchies make visual debugging possible
- Structured format enables tooling (analysis, comparison, search)

**Negative:**

- Log files can become large (especially with screenshots)
- Disk I/O overhead during test execution
- Custom log viewer required (can't use standard log tools)
- Log format changes require updates to viewers
