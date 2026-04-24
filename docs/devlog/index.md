# Devlog

This is a chronological record of decisions and development notes for the Trailblaze project. Each entry captures a moment in time — what we were thinking, what we decided, and why.

Entries tagged as **Decision** record significant architectural or technical choices. Other entries are development notes that capture implementation details, debugging sessions, and lessons learned.

## Index

<!-- BEGIN GENERATED DEVLOG INDEX -->
> *Auto-generated. Do not edit manually.*

| Date | Title | Type |
| :--- | :--- | :--- |
| 2026-04-23 | [On-device MCP tool callbacks — direct QuickJS binding](2026-04-23-on-device-callback-channel.md) | Decision |
| 2026-04-22 | [A TrailblazeTool is a function call (MCP tool, RPC request — same thing)](2026-04-22-trailblaze-tool-is-an-rpc-request.md) | Devlog |
| 2026-04-22 | [Scripting SDK — Envelope Migration & Callback Transport (D1 + D2)](2026-04-22-scripting-sdk-envelope-migration.md) | Decision |
| 2026-04-22 | [Scripting SDK — client.callTool Round-Trip](2026-04-22-scripting-sdk-client-calltool.md) | Decision |
| 2026-04-22 | [@trailblaze/scripting — Authoring Vision & Roadmap (for TS authors)](2026-04-22-scripting-sdk-authoring-vision.md) | Decision |
| 2026-04-21 | [Waypoint Discovery via `matchWaypoint` — Agent-Driven, Retroactive](2026-04-21-waypoint-discovery-and-matching.md) | Devlog |
| 2026-04-21 | [Scripted Tools — MCP Server Integration Patterns (forward-looking)](2026-04-21-scripted-tools-mcp-integration-patterns.md) | Decision |
| 2026-04-21 | [runTrail: Trail-as-Tool Primitive (Proposal)](2026-04-21-run-trail-tool-proposal.md) | Devlog |
| 2026-04-21 | [Maestro Scripting & Flow Control — Comparison and Self-Validation](2026-04-21-maestro-scripting-and-control-flow-comparison.md) | Devlog |
| 2026-04-20 | [YAML-Defined Tools (the `tools:` mode)](2026-04-20-yaml-defined-tools.md) | Decision |
| 2026-04-20 | [Scripted Tools — Toolset Consolidation & Revised Sequencing](2026-04-20-scripted-tools-toolset-consolidation.md) | Decision |
| 2026-04-20 | [Scripted Tools PR A5 — MCP Toolsets Bundled for On-Device](2026-04-20-scripted-tools-on-device-bundle.md) | Decision |
| 2026-04-20 | [Scripted Tools PR A3 — MCP SDK Subprocess Toolsets](2026-04-20-scripted-tools-mcp-subprocess.md) | Decision |
| 2026-04-20 | [Scripted Tools — MCP Extension Conventions](2026-04-20-scripted-tools-mcp-conventions.md) | Decision |
| 2026-04-20 | [Scripted Tools Execution Model (QuickJS + Synchronous Host Bridge)](2026-04-20-scripted-tools-execution-model.md) | Decision |
| 2026-04-20 | [Scripted Tools PR A3 Phase 1 — Subprocess MCP Client, Lifecycle, and Registration](2026-04-20-scripted-tools-a3-subprocess-impl.md) | Decision |
| 2026-04-20 | [Scripted Tools PR A3 — Host-Side Subprocess MCP Toolsets (Scope)](2026-04-20-scripted-tools-a3-host-subprocess.md) | Decision |
| 2026-04-20 | [Scripted Tools PR A2 — Synchronous Tool Execution from JS](2026-04-20-scripted-tools-a2-sync-execute.md) | Decision |
| 2026-04-15 | [Ref-Based Tap Replaces Node ID Tap](2026-04-15-ref-based-tap-replaces-node-id.md) | Decision |
| 2026-04-12 | [Platform-Native Hierarchical Snapshots with Stable Element Refs](2026-04-12-platform-native-hierarchical-snapshots.md) | Decision |
| 2026-04-10 | [CLI and MCP Session Management: Device State and Multi-Terminal Behavior](2026-04-10-cli-mcp-session-and-device-state.md) | Devlog |
| 2026-04-07 | [Unified Provider Auto-Detection Across Host and Android](2026-04-07-unified-provider-auto-detection.md) | Decision |
| 2026-04-07 | [Workspace Config Resolution: .trailblaze/ and trailblaze-config/ Conventions](2026-04-07-trailblaze-yaml-config-resolution.md) | Decision |
| 2026-04-07 | [Unified trailblaze-config/ Classpath Layout](2026-04-07-trailblaze-config-classpath-layout.md) | Decision |
| 2026-04-07 | [Support reasoning_effort in LLM Config](2026-04-07-reasoning-effort-config-support.md) | Devlog |
| 2026-04-07 | [CLI-Based SSO/Auth and Dynamic On-Device Instrumentation Args](2026-04-07-cli-sso-auth-and-dynamic-instrumentation-args.md) | Devlog |
| 2026-03-20 | [Screenshot Format Optimization (WebP Everywhere)](2026-03-20-on-device-screenshot-optimization.md) | Decision |
| 2026-03-17 | [MCP API Redesign: verify→blaze, Mode Defaults, iOS launchApp Fix](2026-03-17-mcp-api-redesign-and-ios-fixes.md) | Devlog |
| 2026-03-17 | [iOS TrailblazeNode Support via IosMaestro](2026-03-17-ios-trailblaze-node-detail.md) | Devlog |
| 2026-03-15 | [MCP STDIO-to-HTTP Proxy for Development](2026-03-15-mcp-stdio-http-proxy-architecture.md) | Devlog |
| 2026-03-11 | [Waypoints and App Navigation Graphs](2026-03-11-waypoints-and-app-navigation-graphs.md) | Decision |
| 2026-03-09 | [Recording Optimization Pipeline](2026-03-09-recording-optimization-pipeline.md) | Decision |
| 2026-03-09 | [Agentic Development Loop](2026-03-09-agentic-dev-loop.md) | Decision |
| 2026-03-06 | [Trail YAML v2 Syntax](2026-03-06-trail-yaml-v2-syntax.md) | Decision |
| 2026-03-04 | [TrailblazeNode — Type-Safe Driver-Specific View Hierarchy](2026-03-04-trailblaze-node-view-hierarchy.md) | Decision |
| 2026-02-20 | [Scripted Tools Vision (TypeScript/QuickJS)](2026-02-20-scripted-tools-vision.md) | Decision |
| 2026-02-20 | [Recording Memory Template Substitution](2026-02-20-recording-memory-template-substitution.md) | Decision |
| 2026-02-09 | [Agent Resilience, Maestro Decoupling, and Driver-Specific Hierarchies](2026-02-09-agent-resilience-and-driver-architecture.md) | Decision |
| 2026-02-04 | [Trail/Blaze Agent Architecture](2026-02-04-trail-blaze-agent-architecture.md) | Decision |
| 2026-02-04 | [Mobile-Agent-v3 Integration Plan](2026-02-04-mobile-agent-v3-integration.md) | Decision |
| 2026-02-04 | [LLM Provider Configuration](2026-02-04-llm-provider-configuration.md) | Decision |
| 2026-02-04 | [App Target Configuration](2026-02-04-app-target-configuration.md) | Decision |
| 2026-02-03 | [Custom Tool Architecture](2026-02-03-custom-tool-architecture.md) | Decision |
| 2026-01-29 | [Device-Specific Trail Recordings](2026-01-29-device-specific-trail-recordings.md) | Decision |
| 2026-01-29 | [AI Fallback](2026-01-29-ai-fallback.md) | Decision |
| 2026-01-28 | [Trailblaze MCP](2026-01-28-trailblaze-mcp.md) | Decision |
| 2026-01-28 | [Logging and Reporting Architecture](2026-01-28-logging-and-reporting.md) | Decision |
| 2026-01-28 | [Kotlin as Primary Language](2026-01-28-kotlin-language.md) | Decision |
| 2026-01-28 | [Koog Library for LLM Communication](2026-01-28-koog-llm-client.md) | Decision |
| 2026-01-28 | [Desktop Application (Moving Away from IDE-based Execution)](2026-01-28-desktop-application.md) | Decision |
| 2026-01-28 | [Custom Tool Authoring](2026-01-28-custom-tool-authoring.md) | Decision |
| 2026-01-28 | [Handwritten Agent Loop](2026-01-28-agent-loop-implementation.md) | Decision |
| 2026-01-14 | [Tool Naming Convention](2026-01-14-tool-naming-convention.md) | Decision |
| 2026-01-01 | [Tool Execution Modes](2026-01-01-tool-execution-modes.md) | Decision |
| 2026-01-01 | [Maestro as Current Execution Backend](2026-01-01-maestro-integration.md) | Decision |
| 2025-10-01 | [Trail Recording Format (YAML)](2025-10-01-trail-recording-format.md) | Decision |
| 2025-10-01 | [LLM as Compiler Architecture](2025-10-01-llm-as-compiler.md) | Decision |
<!-- END GENERATED DEVLOG INDEX -->
