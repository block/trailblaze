---
title: "Desktop Application (Moving Away from IDE-based Execution)"
type: decision
date: 2026-01-28
---

# Trailblaze Decision 016: Desktop Application (Moving Away from IDE-based Execution)

## Context

Early Trailblaze prototypes ran as an IntelliJ/Android Studio plugin. This made sense initially: QE engineers and mobile developers already have Android Studio open, and plugins can access IDE features like project context, device management, and integrated tooling.

However, as Trailblaze evolved, the IDE-based approach revealed significant limitations:

### IDE Plugin Constraints

1. **IDE version coupling** — IntelliJ and Android Studio release frequently. Plugin APIs change between versions, requiring continuous maintenance to support the latest IDE releases alongside older versions still in use across teams.

2. **Installation friction** — Users must install the plugin through the IDE's plugin marketplace, manage plugin updates separately from Trailblaze framework updates, and troubleshoot version conflicts with other plugins.

3. **Resource contention** — Running AI-powered test automation within the IDE competes for memory and CPU with the IDE itself, Gradle builds, and other development tasks. Heavy operations can make the IDE sluggish.

4. **Limited audience** — Not all Trailblaze users need or want an IDE. QE engineers authoring tests, CI/CD pipelines executing tests, and MCP clients controlling devices don't require a full IDE.

5. **Platform limitations** — IDE plugins can't easily provide native OS integrations (menu bar apps, system notifications, global hotkeys) that enhance the desktop experience.

6. **Deployment complexity** — Different plugin versions for internal vs. open source builds, plugin signing requirements, and marketplace review processes add distribution overhead.

### Evolving Usage Patterns

Trailblaze usage has shifted toward patterns that don't require IDE integration:

- **Trail authoring via MCP** — Engineers use Cursor, Claude Desktop, or other MCP clients to author trails conversationally (see [Decision 008](2026-01-28-trailblaze-mcp.md))
- **CLI-driven execution** — CI/CD pipelines and local scripts invoke Trailblaze from the command line
- **Standalone test management** — QE teams want a dedicated interface for organizing, running, and debugging trails

## Decision

**Trailblaze is distributed as a standalone desktop application rather than an IDE plugin.**

### Application Architecture

The Trailblaze desktop application is a Compose Multiplatform app built with Kotlin (see [Decision 009](../../devlog/2026-01-28-kotlin-language.md)). It runs as a native application on macOS, with Linux support planned.

```
┌─────────────────────────────────────────────────────────┐
│                 Trailblaze Desktop App                  │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐  │
│  │   Trail     │  │   Device    │  │   Test Run      │  │
│  │   Editor    │  │   Manager   │  │   Dashboard     │  │
│  └─────────────┘  └─────────────┘  └─────────────────┘  │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐│
│  │                MCP Server (embedded)                ││
│  │  - Client Agent mode (default)                      ││
│  │  - Runner mode                                      ││
│  │  - Trailblaze Agent mode                            ││
│  └─────────────────────────────────────────────────────┘│
├─────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────┐│
│  │                Trailblaze Agent Core                ││
│  │  - Tool execution (Maestro, Playwright)             ││
│  │  - Trail recording & replay                         ││
│  │  - Custom tools (app-specific)                      ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
          │                    │
          ▼                    ▼
    ┌──────────┐        ┌──────────────┐
    │ Android  │        │   iOS        │
    │ (ADB)    │        │ (Simulator)  │
    └──────────┘        └──────────────┘
```

### Core Capabilities

| Capability | Description |
| :--- | :--- |
| **Trail management** | Browse, edit, run, and debug trails. View step-by-step execution with screenshots. |
| **Device control** | Connect to Android devices (via ADB) and iOS simulators. Live screen mirroring. |
| **MCP server** | Embedded MCP server for integration with Cursor, Claude Desktop, and other clients. |
| **Test dashboard** | View test results, execution history, and failure analysis. |
| **Settings & configuration** | LLM provider setup, target app selection, platform preferences. |

### Interaction Modes

Trailblaze supports multiple interaction paradigms, all sharing the same underlying agent core:

1. **CLI-driven** — Primary interface for scripting, CI/CD, and terminal workflows (`trailblaze run`, `trailblaze mcp`, etc.)
2. **GUI-driven** — Launch the desktop app (`trailblaze app`) for visual trail editing, debugging, and test management
3. **MCP-driven** — External agents connect via MCP to control devices and author trails (works with both headless and GUI modes)

All three modes can be used interchangeably and share configuration.

### Menu Bar Integration (macOS)

The app runs primarily as a menu bar application, staying out of the way while providing quick access to:

- Device status and connection
- Active MCP sessions
- Quick trail execution
- Recent test results

A full window can be opened for detailed trail editing, test management, and debugging.

### Relationship to IDE Workflows

While Trailblaze no longer runs *within* the IDE, it integrates seamlessly with IDE-based workflows:

- **MCP integration** — Cursor and other AI-enabled editors connect to Trailblaze via MCP
- **File watching** — The app can watch for trail file changes, enabling edit-in-IDE, run-in-Trailblaze workflows
- **Project awareness** — When launched from a project directory, Trailblaze discovers trails and configuration automatically

Developers keep their IDE for code editing; Trailblaze handles UI test automation as a complementary tool.

### Distribution

Trailblaze is distributed as a **CLI tool that bundles the desktop application** (see [Decision 013](2026-01-28-distribution-model.md)):

| Audience | Channel | Command |
| :--- | :--- | :--- |
| **Open source** | Homebrew | `brew install block/tap/trailblaze` |
| **Block internal** | Internal package source | `brew install block-internal/tap/trailblaze` |

The `trailblaze` CLI is the primary entry point. It supports headless operation for CI/CD and scripting, and can launch the desktop GUI when needed:

```bash
# Run a trail headlessly (CI/CD, scripts)
trailblaze run my-trail.yaml

# Start the MCP server (headless)
trailblaze mcp

# Launch the desktop application
trailblaze app
# or simply double-click the app bundle

# Other CLI commands
trailblaze list              # List available trails
trailblaze devices           # Show connected devices
trailblaze config            # Manage configuration
```

This approach provides:

- **Single installation** — One `brew install` gives you both CLI and GUI
- **Terminal-first workflow** — CLI is the default; GUI is available when you need visual debugging or trail editing
- **CI/CD compatibility** — Headless operation works in automated pipelines
- **Version consistency** — CLI and desktop app are always the same version

## Consequences

**Positive:**

- **Decoupled from IDE releases** — No more plugin API compatibility maintenance across IDE versions
- **Simplified installation** — Single package manager command instead of IDE plugin marketplace
- **Better performance** — Dedicated process with its own resources, doesn't compete with IDE
- **Broader audience** — Useful for QE engineers, CI/CD pipelines, and MCP clients without requiring an IDE
- **Native experience** — Menu bar integration, system notifications, and OS-level features
- **Unified distribution** — CLI and desktop app bundled together, always in sync
- **Flexible interaction** — CLI, GUI, and MCP modes all supported from one package

**Negative:**

- **Separate window** — Users must context-switch between IDE and Trailblaze app (mitigated by MCP integration)
- **No IDE project context** — Can't automatically access IDE's understanding of the codebase (partially mitigated by project awareness features)
- **Additional process** — Another application running alongside the IDE
- **macOS-first** — Linux and Windows support requires additional effort (Linux planned, Windows not currently prioritized)
