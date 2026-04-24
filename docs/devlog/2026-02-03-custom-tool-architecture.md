---
title: "Custom Tool Architecture"
type: decision
date: 2026-02-03
---

# Trailblaze Decision 029: Custom Tool Architecture

## Context

[Decision 010: Custom Tool Authoring](2026-01-28-custom-tool-authoring.md) documented the current Kotlin-based approach and its limitations. Today, custom tools for Trailblaze (e.g., `myapp_launchAppSignedIn`, `otherapp_scrollUntilTextIsVisible`) must be:

1. **Written in Kotlin** with access to `TrailblazeToolExecutionContext`
2. **Compiled into** the Trailblaze distribution or test APK
3. **Forked** if you're an external team wanting custom behavior

This creates a barrier for external adoption:

- External teams (Acme, ExampleCorp, etc.) can't extend Trailblaze without forking
- Non-Kotlin teams (Python, TypeScript) have no path to custom tools
- The current tool API is tightly coupled to internal implementation details

### Requirements

Based on discussions with potential adopters and internal teams:

1. **Teams MUST be able to add tools without forking** — non-negotiable
2. **Broad accessibility** — not just Kotlin developers; Python, TypeScript, Go teams need a path
3. **Type safety and refactoring support** — we don't want to maintain an untyped API surface
4. **On-device support** — device farms (Firebase Test Lab, AWS Device Farm) only allow app APK + test APK
5. **Future extensibility** — web (Playwright), desktop control are on the roadmap
6. **Cross-platform tools** — one tool should work across Android, iOS, Web where possible
7. **Tool set management** — ability to filter/group tools to reduce LLM context window

## Decision

Implement a **multi-path custom tool architecture** with:

1. **Wire/Proto-defined API** — Kotlin-first with Wire, generates proto for external consumption
2. **MCP for tool discovery** — external tools run their own MCP servers
3. **RPC for execution** — host-driven tools call Trailblaze via generated clients
4. **Library dependency for on-device** — Kotlin tools compiled into test APK
5. **Central tool registry** — metadata exposed via MCP resources
6. **Layered command interfaces** — core + platform-specific backends

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Outer Agent                                 │
│               (Claude, Goose, Cursor, Desktop App)                  │
└─────────────────────────────────────────────────────────────────────┘
        │               │               │               │
        │ MCP           │ MCP           │ MCP           │ MCP
        ▼               ▼               ▼               ▼
┌───────────────┐ ┌───────────────┐ ┌───────────────┐ ┌───────────────┐
│  Trailblaze   │ │  MyApp Tools  │ │OtherApp Tools │ │  Acme Tools   │
│    (core)     │ │  (example)    │ │  (example)    │ │  (External)   │
│               │ │               │ │               │ │               │
│ Primitives:   │ │ App-specific: │ │ App-specific: │ │ App-specific: │
│ - tap         │ │ - login       │ │ - transfer    │ │ - login       │
│ - inputText   │ │ - checkout    │ │ - banking     │ │ - acceptRide  │
│ - launchApp   │ │ - scanBarcode │ │               │ │               │
│               │ │               │ │               │ │               │
│ Registry: ✓   │ │ Registry: ✓   │ │ Registry: ✓   │ │ Registry: ✓   │
└───────────────┘ └───────────────┘ └───────────────┘ └───────────────┘
        │               │               │               │
        └───────────────┴───────┬───────┴───────────────┘
                                │
                    TrailblazeCommands (RPC)
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Trailblaze RPC Server                            │
│                      (localhost:52525)                              │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  TrailblazeCommands (core interface)                        │   │
│  │  - tap(), inputText(), launchApp(), clearAppData()          │   │
│  │  - captureScreen(), waitUntilVisible(), assertVisible()     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│        ┌─────────────────────┼─────────────────────┐               │
│        ▼                     ▼                     ▼               │
│  ┌───────────┐         ┌───────────┐         ┌───────────┐        │
│  │  Maestro  │         │ Playwright│         │  Desktop  │        │
│  │ Commands  │         │ Commands  │         │ Commands  │        │
│  │           │         │           │         │ (future)  │        │
│  │ - adb.*   │         │ - navigate│         │           │        │
│  │ - swipe   │         │ - fill    │         │           │        │
│  │ - scroll  │         │ - click   │         │           │        │
│  └───────────┘         └───────────┘         └───────────┘        │
│        │                     │                     │               │
└────────┼─────────────────────┼─────────────────────┼───────────────┘
         │                     │                     │
         ▼                     ▼                     ▼
    Mobile Device         Web Browser            Desktop App
```

## Wire/Proto: Kotlin-First API Definition

We use [Wire](https://github.com/square/wire) (a Kotlin-first proto library by Block) to define the API in Kotlin and generate proto for external consumption:

```kotlin
// Defined in Kotlin using Wire annotations
// Wire generates proto schema for interop with other languages

interface TrailblazeCommands {
    val platform: TrailblazeDevicePlatform
    
    // ═══════════════════════════════════════════════════════════════
    // ACTIONS (perform operations)
    // ═══════════════════════════════════════════════════════════════
    suspend fun tap(text: String? = null, id: String? = null, index: Int? = null): ActionResult
    suspend fun inputText(text: String): ActionResult
    suspend fun launchApp(packageId: String): ActionResult
    suspend fun clearAppData(packageId: String): ActionResult
    
    // ═══════════════════════════════════════════════════════════════
    // QUERIES (return values for conditionals - don't throw on "not found")
    // ═══════════════════════════════════════════════════════════════
    suspend fun isVisible(text: String? = null, id: String? = null, timeoutMs: Long? = null): Boolean
    suspend fun hasText(text: String): Boolean
    suspend fun getElementText(id: String): String?
    suspend fun getElementCount(text: String? = null, id: String? = null): Int
    suspend fun captureScreen(): ScreenState
    
    // ═══════════════════════════════════════════════════════════════
    // ASSERTIONS (throw/fail if condition not met)
    // ═══════════════════════════════════════════════════════════════
    suspend fun assertVisible(text: String? = null, id: String? = null, timeoutMs: Long? = null): ActionResult
    suspend fun waitUntilVisible(text: String, timeoutMs: Long): ActionResult
    
    // Platform-specific backends (nullable, check availability)
    val maestro: MaestroCommands?      // Mobile (Android/iOS)
    val playwright: PlaywrightCommands? // Web
    val desktop: DesktopCommands?       // Desktop (future)
}

// Mobile-specific (Maestro backend)
interface MaestroCommands {
    // Android
    suspend fun adbShell(command: String): ShellResult
    suspend fun grantPermission(packageId: String, permission: String): ActionResult
    suspend fun pressBack(): ActionResult
    suspend fun pressHome(): ActionResult
    
    // Gestures
    suspend fun swipe(direction: SwipeDirection, durationMs: Long? = null): ActionResult
    suspend fun scroll(direction: ScrollDirection, amount: Int? = null): ActionResult
    suspend fun pinch(scale: Float): ActionResult
}

// Web-specific (Playwright backend)
interface PlaywrightCommands {
    suspend fun navigate(url: String): ActionResult
    suspend fun click(selector: String): ActionResult
    suspend fun fill(selector: String, value: String): ActionResult
    suspend fun waitForSelector(selector: String, timeoutMs: Long? = null): ActionResult
    suspend fun evaluateJs(script: String): Any?
}
```

### Code Generation

From the Wire/Kotlin definitions, we generate:

| Generated Artifact | Language | Usage |
|--------------------|----------|-------|
| `TrailblazeCommands` interface | Kotlin | In-process (host + on-device) |
| `trailblaze-api.proto` | Proto | External language interop |
| `TrailblazeClient` | Python | External MCP servers |
| `TrailblazeClient` | TypeScript | External MCP servers |
| gRPC/Connect stubs | Multiple | RPC communication |

## Deployment Paths

### Path 1: Host-Driven (MCP + RPC)

**Audience**: Most external users (Python, TypeScript, Go teams)

External teams write their own MCP server that:
1. Exposes custom tools via MCP protocol
2. Uses generated RPC client to call Trailblaze commands
3. Runs as a separate process

```python
# acme_tools_server.py
from mcp import Server
from trailblaze_client import TrailblazeClient  # Generated from proto

tb = TrailblazeClient("localhost:52525")
server = Server()

@server.tool("acme_driver_login")
async def login(email: str, password: str) -> dict:
    """Log in to Acme Driver app with credentials."""
    await tb.clear_app_data(package_id="com.acme.driver")
    await tb.launch_app(package_id="com.acme.driver")
    await tb.tap(text="Sign in")
    await tb.input_text(text=email)
    await tb.input_text(text=password)
    await tb.tap(text="Submit")
    await tb.wait_until_visible(text="Go Online", timeout_ms=10000)
    return {"success": True}

if __name__ == "__main__":
    server.run()
```

### Path 2: On-Device (Library + Custom APK)

**Audience**: Teams running tests on device farms (Firebase Test Lab, AWS Device Farm)

Teams depend on `trailblaze-android-ondevice-mcp` and compile their tools into a test APK:

```kotlin
// AcmeDriverLoginTool.kt
class AcmeDriverLoginTool(
    private val commands: TrailblazeCommands  // Same interface as RPC!
) : TrailblazeTool {
    
    override suspend fun execute(args: Map<String, Any>): ToolResult {
        commands.clearAppData("com.acme.driver")
        commands.launchApp("com.acme.driver")
        commands.tap(text = "Sign in")
        commands.inputText(args["email"] as String)
        commands.inputText(args["password"] as String)
        commands.tap(text = "Submit")
        commands.waitUntilVisible(text = "Go Online", timeoutMs = 10000)
        return ToolResult.success()
    }
}
```

**Key insight**: The `TrailblazeCommands` interface is **identical** whether backed by RPC (host) or direct calls (on-device).

### Path 3: Host-Driven Kotlin (In-Process)

**Audience**: Teams already using Kotlin who want in-process tools

Kotlin tools in the same JVM use the interface directly with no RPC overhead.

## MCP Server Organization

### Composable Tool Modules

Tools are organized as **composable modules** that can be combined into MCP servers flexibly:

```
Tool Modules (libraries):
├── shared-common-tools        # Shared across all your apps
├── myapp-tools              # MyApp-specific
├── otherapp-tools            # OtherApp-specific
├── admin-tools              # AdminPanel-specific
└── acme-tools                # External: Acme's tools
```

These modules are **code libraries**, not servers. How they're composed into servers is a deployment decision.

### Deployment Options

**Option A: One server per app** (independence)
```
MCP Servers:
├── myapp-server     → [shared-common-tools, myapp-tools]
├── otherapp-server       → [shared-common-tools, otherapp-tools]
└── admin-server  → [shared-common-tools, admin-tools]
```

**Option B: One superset server** (efficiency)
```
MCP Servers:
└── combined-tools-server → [shared-common-tools, myapp-tools, otherapp-tools, admin-tools]
```

**Option C: Mix based on needs**
```
MCP Servers:
├── mobile-server    → [shared-common-tools, myapp-tools, otherapp-tools]  # Mobile apps together
└── admin-server → [shared-common-tools, admin-tools]           # Web separate
```

### Trade-offs

| Approach | Ports | Processes | Deploy Independence | Failure Isolation |
|----------|-------|-----------|---------------------|-------------------|
| One per app | N | N | High | High |
| One superset | 1 | 1 | Low (coordinated) | Low |

**The overhead difference is minimal** — a few extra processes. The bigger question is organizational:
- **Independence**: Teams deploy their tools without coordinating
- **Efficiency**: Single server, shared caches/state, one port to manage

### Recommendation: Multiple Servers (with Build Considerations)

**Default to one MCP server per app/team.** The process overhead is minimal, and the organizational benefits are significant:

- **Ownership** — Each team owns their server and tools
- **Independence** — Deploy, update, and scale independently  
- **Overlap handling** — Teams can have similar tools without naming conflicts
- **Failure isolation** — One server's issues don't affect others
- **Easier management** — Clear boundaries for what tools live where

**Startup time consideration**: Multiple servers mean multiple process startups.

| Server Type | Build Time | Startup Time | N Servers Impact |
|-------------|-----------|--------------|------------------|
| **Python/TypeScript** | None | 1-3s (imports, init) | N × startup |
| **Kotlin in-process** | Part of Trailblaze | Zero (same JVM) | No impact |
| **Kotlin out-of-process** | Gradle compile | JVM startup + init | N × build + startup |

**For Python/TypeScript**: No build, but starting 3 servers = 3× interpreter + import time. Usually acceptable (a few seconds total), but consider combining if startup latency matters.

**For Kotlin MCP servers**: Combine into **one pre-built artifact** with all tools, to avoid N builds at startup. The single JAR can still organize tools by namespace (`myapp_*`, `otherapp_*`). This gives:
- Build once → start fast
- Logical separation via namespacing
- Single process or spawn multiple from same artifact

### Tool Namespacing (Required)

**Namespacing is critical regardless of deployment model.** Even with one combined server, tools need clear namespaces to avoid conflicts and enable filtering.

Per [ADR 005: Tool Naming Convention](2026-01-14-tool-naming-convention.md), we use **underscores** (not dots) because OpenAI function names don't support dots:

```
myapp_login
myapp_checkout
otherapp_transfer
otherapp_requestMoney
admin_viewAnalytics
```

Benefits:
- **No conflicts** — Multiple apps can have a `login` tool
- **Filtering** — Agent can request only `myapp_*` tools for a MyApp test
- **Discovery** — Clear ownership in tool listings
- **Composability** — Same naming works whether tools are in one server or many

### Recommendation: One Combined Server

For your organization's internal tools, consider using **one combined MCP server** with all app tools:

```
combined-tools-server
├── myapp_*      (MyApp tools)
├── otherapp_*   (OtherApp tools)  
├── admin_*      (AdminPanel tools)
└── shared_*     (Shared utilities)
```

**Why one server for your organization:**
- Single build, fast startup
- Single process to manage
- Shared caches/state when useful
- Namespacing provides logical separation
- **Simpler registry** — one `resources/read("trailblaze://registry")` returns all tool metadata

### Registry with One Combined Server

One combined server **simplifies** the registry endpoint:

| Deployment | Registry Calls | Aggregation |
|------------|---------------|-------------|
| **Multiple servers** | N calls | Trailblaze aggregates |
| **One combined server** | 1 call | None needed |

The registry is a `Map<String, ToolMetadata>` where keys include the namespace:

```kotlin
// One registry, all tools, namespaced
val registry = mapOf(
    "myapp_login" to ToolMetadata(platforms = setOf(ANDROID, IOS), groups = setOf("auth")),
    "myapp_checkout" to ToolMetadata(platforms = setOf(ANDROID, IOS), groups = setOf("payment")),
    "otherapp_transfer" to ToolMetadata(platforms = setOf(ANDROID, IOS), groups = setOf("transfer")),
    "otherapp_requestMoney" to ToolMetadata(platforms = setOf(ANDROID, IOS), groups = setOf("transfer")),
)
```

Filtering by app is simple: `registry.filter { it.key.startsWith("myapp_") }`

### External Teams: Flexible Deployment

External teams can deploy however they prefer:
- **Single-app server** — One server for their app's tools
- **Combined server** — Multiple apps in one (following the combined server pattern)
- **Per-team servers** — Organizational boundaries

The architecture supports all models — namespacing makes tools composable across any deployment.

### Multiple MCP Servers is Standard

Connecting to multiple MCP servers is **standard MCP usage**:

- Claude Desktop, Goose, Cursor all support multiple servers
- Each server provides different capabilities
- This is the intended design pattern

Whether your team uses 1 server or 5, the architecture supports it.

## MCP Server Registration

### Transport Model: stdio vs HTTP

We use a **simple two-tier model** for MCP transport:

| Transport | Lifecycle | When to use |
|-----------|-----------|-------------|
| **stdio** | Trailblaze manages (start/stop) | Default for all managed servers |
| **http** | External (you manage) | Shared team servers, external infrastructure |

**stdio is the default** because:
- **No port management** — communication via stdin/stdout pipes, no port conflicts
- **Parallel-safe** — multiple instances don't conflict (unlike ports)
- **CI-friendly** — parallel jobs just work, no port allocation needed
- **Simple config** — just specify the command to run

### Project Configuration (`trailblaze.yaml`)

Teams configure their MCP servers in their test repository:

```yaml
# trailblaze.yaml - in repo root
target: acme_driver

mcpServers:
  # stdio (managed) - Trailblaze starts and stops this
  - name: acme-tools
    command: ./gradlew
    args: [:acme-tools:runMcp]
    
  # http (external) - already running, we just connect
  - name: shared-team-tools
    transport: http
    url: http://team-tools.internal:8080
```

The "magic" experience:
```bash
$ cd acme-trailblaze-tests
$ trailblaze run
# 1. Reads trailblaze.yaml
# 2. Spawns acme-tools via Gradle (stdio)
# 3. Connects to shared-team-tools (http, already running)
# 4. Loads registries, runs tests
# 5. Stops acme-tools on exit
```

### Config Schema

```yaml
# trailblaze.yaml
target: string              # Which app target (acme_driver, myapp, etc.)
platform: string               # Optional: android, ios, web

mcpServers:
  # stdio server (Trailblaze manages lifecycle)
  - name: string               # Identifier
    command: string            # Command to run
    args: [string]             # Arguments
    workingDir: string         # Optional: working directory
    env:                       # Optional: environment variables
      KEY: value
      
  # http server (externally managed)
  - name: string
    transport: http            # Explicitly http
    url: string                # URL to connect to
    
trailsDir: string              # Optional: where to find trails (default: ./trails)
```

### Why stdio Avoids Port Problems

**Multiple developers on same machine:**
```bash
# Developer 1
trailblaze run  # Uses stdin/stdout pipe

# Developer 2 (same machine)
trailblaze run  # Uses different pipe, no conflict!
```

**CI parallel jobs:**
```yaml
# All jobs run simultaneously, no port allocation needed
jobs:
  test-driver: { run: trailblaze run trails/driver/ }
  test-rider:  { run: trailblaze run trails/rider/ }
  test-eats:   { run: trailblaze run trails/eats/ }
```

### STDIO Concurrency Limitation

**Important consideration**: STDIO MCP servers typically process requests **sequentially** — read a request, process it, respond, then read the next. A single STDIO connection cannot handle concurrent requests from multiple devices.

### Recommended: HTTP Transport for Multi-Device

For multi-device support, Trailblaze spawns the MCP server in **HTTP mode** rather than STDIO:

```
┌───────────────────────────────────────────────────────────────────────┐
│  Trailblaze spawns HTTP MCP server (single process)                   │
│                                                                       │
│  $ python acme_tools_server.py --transport http --port 52530          │
│                                                                       │
│  Device 1 ──► POST /tools/call ──┐                                    │
│  Device 2 ──► POST /tools/call ──┼──► Concurrent handling            │
│  Device 3 ──► POST /tools/call ──┘                                    │
└───────────────────────────────────────────────────────────────────────┘
```

**Why HTTP is better than STDIO-per-device:**

| Approach | Processes | Memory | State Sharing | Lifecycle |
|----------|-----------|--------|---------------|-----------|
| STDIO per-device | N processes | N × footprint | None (isolated) | Manage N |
| **HTTP (single)** | 1 process | 1 × footprint | Shared caches | Manage 1 |

Spawning one HTTP server with a controlled port is **not more work** than spawning N STDIO processes — and it's cleaner:

- **Single process** — less overhead than N STDIO processes
- **Shared state** — tools can share caches, connections, loaded models
- **Trailblaze controls the port** — no port conflicts
- **Same server code** — MCP SDKs support both transports, tool author changes nothing

**Invocation ID is required** for HTTP transport. With concurrent requests from multiple devices hitting the same server, the invocation ID in `_meta` is how each request routes to the correct device:

```python
@server.tool("analyze_screen")
async def analyze(prompt: str, ctx: Context) -> dict:
    # Multiple devices calling this concurrently!
    # Invocation ID tells us which device context to use
    tb = TrailblazeClient.from_context(ctx)
    
    screen = await tb.capture_screen()  # Routes to correct device
    return await tb.ask_llm(prompt, screen)
```

### Single Device: STDIO Still Works

For the **single device case** (most common during development), STDIO remains simple and works without invocation ID:

```yaml
mcp_servers:
  acme-tools:
    command: python
    args: [./acme_tools_server.py]
    # Single device: STDIO, no port needed
```

When multiple devices connect, Trailblaze automatically switches to HTTP mode, passing a port:

```yaml
# Trailblaze internally does this when multi-device:
# python ./acme_tools_server.py --transport http --port 52530
```

**For in-process Kotlin tools**, concurrency is handled via coroutines in the same JVM — no transport concerns.

### For External MCP Clients (Goose, Cursor, Claude Desktop)

Users configure their MCP client directly using each client's config format. This is separate from `trailblaze.yaml`:

**Goose** (`~/.config/goose/config.yaml`):
```yaml
mcp:
  servers:
    trailblaze:
      command: trailblaze
      args: [mcp]
    acme-tools:
      command: python
      args: [./acme_tools_server.py]
```

### In-Process (Same JVM)

Your app-specific tools don't need separate MCP servers. They're **in-process Kotlin**:

```kotlin
// In-process: tools registered directly, no MCP server spawning
class TrailblazeMcpServer {
    val inProcessTools = listOf(
        MyAppLoginTool::class,
        MyAppCheckoutTool::class,
        OtherAppTransferTool::class,
        // All in-process, same JVM
    )
}
```

External MCP servers (defined in `trailblaze.yaml`) are for **external teams** running separate processes.

## Multi-Device Execution

**Trailblaze supports multiple devices connected simultaneously.** A single Trailblaze instance can orchestrate tests across an iPhone, Android emulator, and physical Android device at the same time. This is a core capability that enables:

- **Cross-platform testing** — Run the same test on iOS and Android in parallel
- **Device farms** — Scale tests across dozens of devices
- **Comparative testing** — Test the same flow on different device configurations

External tools don't need to know about multi-device orchestration — device context flows through automatically.

### How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Trailblaze Desktop App                           │
│                                                                     │
│  Connected devices:                                                 │
│  - Device 1 (Pixel 6, emulator-5554)                               │
│  - Device 2 (iPhone 14, 00008101-...)                              │
│  - Device 3 (Galaxy S23, RF8M...)                                  │
│                                                                     │
│  User: "Run login test on all devices"                             │
│                                                                     │
│  Creates 3 parallel execution contexts:                            │
│  ┌───────────────┐ ┌───────────────┐ ┌───────────────┐             │
│  │ deviceId: d1  │ │ deviceId: d2  │ │ deviceId: d3  │             │
│  │ platform: AND │ │ platform: IOS │ │ platform: AND │             │
│  └───────────────┘ └───────────────┘ └───────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
        │                   │                   │
        │ MCP call with     │ MCP call with     │ MCP call with
        │ device context    │ device context    │ device context
        ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    External MCP Server (Acme Tools)                 │
│                                                                     │
│  @server.tool("acme_driver_login")                                 │
│  async def login(email, password, _trailblaze):                    │
│      # Tool doesn't know about multiple devices                     │
│      # It just operates in the context it's given                   │
│      tb = TrailblazeClient(context=_trailblaze)                    │
│      await tb.tap(text="Sign in")   # Routed to correct device     │
│      await tb.input_text(email)     # Routed to correct device     │
│      ...                                                            │
└─────────────────────────────────────────────────────────────────────┘
        │                   │                   │
        │ RPC with d1       │ RPC with d2       │ RPC with d3
        ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Trailblaze RPC Server                            │
│                                                                     │
│  Routes each request to the correct device based on deviceId       │
│                                                                     │
│  d1 → Pixel 6          d2 → iPhone 14        d3 → Galaxy S23       │
└─────────────────────────────────────────────────────────────────────┘
```

### Device Context Propagation

When Trailblaze calls an external MCP tool, it includes device context:

```json
{
  "method": "tools/call",
  "params": {
    "name": "acme_driver_login",
    "arguments": { "email": "test@example.com", "password": "***" },
    "_trailblaze": {
      "deviceId": "emulator-5554",
      "sessionId": "abc123",
      "platform": "android"
    }
  }
}
```

The external tool uses a client that **automatically includes this context** in RPC calls:

```python
@server.tool("acme_driver_login")
async def login(email: str, password: str, _trailblaze: dict) -> dict:
    # Client initialized with device context
    tb = TrailblazeClient(context=_trailblaze)
    
    # All RPC calls automatically include deviceId
    await tb.clear_app_data("com.acme.driver")  # → routed to emulator-5554
    await tb.launch_app("com.acme.driver")      # → routed to emulator-5554
    await tb.tap(text="Sign in")                   # → routed to emulator-5554
    
    return {"success": True}
```

### Wire API Includes Device Context

The `TrailblazeCommands` interface includes device context in every request:

```protobuf
message TapRequest {
    string text = 1;           // Element text to match
    string id = 2;             // Optional: element ID
    int32 index = 3;           // Optional: index if multiple matches
    string device_id = 4;      // Routing
    string session_id = 5;     // Correlation
}
```

### Tool Perspective

From an external tool's perspective:
- It receives a request with context
- It does its work using the context
- It returns a result

The tool doesn't know or care that:
- There are multiple devices
- Tests are running in parallel
- It's being called multiple times simultaneously

**The device context is transparent to the tool.** Trailblaze handles parallelism and routing.

### Invocation Context for Multi-Device

**To support multiple devices simultaneously, every tool invocation needs execution context.** When an external MCP tool makes RPC calls back to Trailblaze, those calls must route to the correct device — not just any connected device.

The **invocation context** is how we solve this. When Trailblaze calls an external MCP tool, it includes context in `_meta`:
- **Invocation ID** — Correlates RPC callbacks to the originating tool call
- **Device info** — Which device this tool invocation operates on
- **Session info** — Logging and analytics correlation

For **in-process Kotlin tools**, this context is the `TrailblazeToolExecutionContext` (or `TrailblazeContext` in `TrailblazeToolSet`).

For **remote MCP tools**, this context flows via `_meta` and is wrapped by the SDK's `TrailblazeClient`. The client is essentially a remote execution context — every RPC call it makes is scoped to the correct device.

#### Metadata Shape

```json
{
  "_meta": {
    "trailblazeInvocationId": "inv-abc123",
    "trailblaze": {
      "baseUrl": "http://localhost:52525",
      "sessionId": "trail-xyz",
      "device": {
        "id": "emulator-5554",
        "platform": "ANDROID",
        "width": 1080,
        "height": 2400
      },
      "capabilities": {
        "sampling": true
      }
    }
  }
}
```

| Field | Type | Purpose |
|-------|------|---------|
| `trailblazeInvocationId` | string | Correlates callbacks to originating request |
| `trailblaze.baseUrl` | string | Where to call back |
| `trailblaze.sessionId` | string | Trailblaze session for logging |
| `trailblaze.device.id` | string | Device identifier |
| `trailblaze.device.platform` | string | ANDROID / IOS |
| `trailblaze.device.width/height` | int | Screen dimensions |
| `trailblaze.capabilities.sampling` | bool | Whether LLM sampling is available |

#### Single Device Fallback

For single-device scenarios, invocation ID is **optional**. Trailblaze falls back to the single active context when:
- Only one device is connected
- Only one tool invocation is active

Multi-device scenarios require explicit invocation ID propagation. If multiple devices are active and no invocation ID is provided, Trailblaze returns an error explaining the requirement.

#### Static vs Fresh Data

**Static metadata** (included in `_meta` to avoid round-trips):
- Device info (platform, dimensions, ID)
- Session ID
- Callback URL
- Capabilities

**Fresh data** (fetched via RPC on-demand):
- View hierarchy — large, changes constantly
- Screenshot — large (~100KB+), stale immediately
- Current screen state — tool decides when it needs fresh data

This separation ensures tools have immediate access to static context while fetching dynamic data only when needed.

#### Invocation ID Lifecycle

The invocation ID ties together a single external tool call with all the RPC requests that tool makes back to Trailblaze.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           TRAILBLAZE                                    │
│                                                                         │
│  1. Trailblaze calls external MCP tool                                  │
│     → Generate invocationId = "inv-abc123"                              │
│     → Store context: invocations["inv-abc123"] = {device, session, ...} │
│     → Include in _meta: {"trailblazeInvocationId": "inv-abc123", ...}   │
│                                                                         │
│  2. BLOCKING: Wait for tool call to complete                            │
│     ┌─────────────────────────────────────────────────────────────────┐ │
│     │  External tool executes, makes RPC calls back to Trailblaze     │ │
│     │                                                                 │ │
│     │  tb.tap(...)        → RPC includes invocationId                 │ │
│     │  tb.captureScreen() → RPC includes invocationId                 │ │
│     │  tb.inputText(...)  → RPC includes invocationId                 │ │
│     │                                                                 │ │
│     │  Trailblaze receives RPC:                                       │ │
│     │  → Extract invocationId from request                            │ │
│     │  → Lookup context: invocations["inv-abc123"]                    │ │
│     │  → Route to correct device, log against correct session         │ │
│     └─────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│  3. Tool call completes (success or failure)                            │
│     → Remove context: invocations.remove("inv-abc123")                  │
│     → Return result to caller                                           │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key points:**
- **Blocking call**: Trailblaze blocks while waiting for the external tool to complete
- **Context scoped to call**: The invocation context exists only while the tool is executing
- **Automatic cleanup**: Context is removed when the tool call returns (success or failure)
- **RPC routing**: All incoming RPC requests during execution use the invocation ID to find the right context

#### Error Handling

If an RPC request includes an invalid or unknown invocation ID, Trailblaze returns a standard tool call failure:

```json
{
  "error": {
    "code": -32602,
    "message": "Unknown invocation ID: inv-xyz. The tool call may have completed or timed out."
  }
}
```

This propagates back to the external tool as a failed RPC call, which should cause the tool to return a failure to Trailblaze.

## Tool Registry

### Central Registry Per Server

Instead of annotating each tool with metadata, each server has a **central registry**:

```kotlin
// MyAppToolRegistry.kt - Single source of truth
object MyAppToolRegistry {
    val tools: Map<String, ToolMetadata> = mapOf(
        "myapp_launchAppSignedIn" to ToolMetadata(
            platforms = setOf(ANDROID, IOS, DESKTOP),
            groups = setOf("auth", "setup"),
        ),
        "myapp_checkout" to ToolMetadata(
            platforms = setOf(ANDROID, IOS, DESKTOP, WEB),
            groups = setOf("checkout", "payments"),
        ),
        "tapOnElementByNodeId" to ToolMetadata(
            platforms = setOf(ANDROID, IOS, DESKTOP, WEB),
            groups = setOf("core"),
            isRecordable = false,
            isDelegating = true,
        ),
    )
    
    val groups: Map<String, GroupInfo> = mapOf(
        "auth" to GroupInfo("Authentication tools", defaultEnabled = true),
        "checkout" to GroupInfo("Checkout flow", defaultEnabled = false),
    )
}
```

### Registry Data Model (Proto-Generated)

We provide proto-generated data models for the registry:

```protobuf
// trailblaze-registry.proto
message TrailblazeToolRegistry {
    map<string, ToolMetadata> tools = 1;
    map<string, GroupInfo> groups = 2;
    ServerInfo server_info = 3;
}

message ToolMetadata {
    repeated string platforms = 1;
    repeated string groups = 2;
    bool exposed_to_llm = 3;
    bool is_recordable = 4;
    bool is_delegating = 5;
}

message GroupInfo {
    string description = 1;
    bool default_enabled = 2;
}

message ServerInfo {
    string name = 1;
    string version = 2;
}
```

Teams use the generated data models in their language. No base class to maintain.

### Exposed via MCP Resource

The registry is exposed as an **MCP resource** (standard MCP feature):

```
Resource URI: trailblaze://registry
```

```json
{
  "tools": {
    "myapp_launchAppSignedIn": {
      "platforms": ["android", "ios", "desktop"],
      "groups": ["auth", "setup"],
      "exposedToLlm": true,
      "isRecordable": true,
      "isDelegating": false
    },
    "tapOnElementByNodeId": {
      "platforms": ["android", "ios", "desktop", "web"],
      "groups": ["core"],
      "exposedToLlm": true,
      "isRecordable": false,
      "isDelegating": true
    }
  },
  "groups": {
    "auth": { "description": "Authentication tools", "defaultEnabled": true },
    "checkout": { "description": "Checkout flow", "defaultEnabled": false }
  },
  "serverInfo": {
    "name": "trailblaze-myapp-tools",
    "version": "1.2.0"
  }
}
```

### Separation of Concerns

| MCP Feature | Contains |
|-------------|----------|
| `tools/list` | Tool names, descriptions, input schemas (standard MCP) |
| `resources/read(registry)` | Metadata: platforms, groups, flags (our extension) |

The registry **references** tools by name; it doesn't duplicate tool definitions.

### Using the Registry

The Trailblaze central agent reads registries from all connected MCP servers:

```kotlin
class TrailblazeToolRouter {
    private val registries = mutableMapOf<String, TrailblazeToolRegistry>()
    
    suspend fun loadFromMcpServer(serverName: String, client: McpClient) {
        val resource = client.readResource("trailblaze://registry")
        registries[serverName] = Json.decodeFromString(resource.content)
    }
    
    fun filterTools(platform: TrailblazeDevicePlatform, groups: Set<String>): List<ToolInfo> {
        return registries.values.flatMap { registry ->
            registry.tools.filter { (_, meta) ->
                meta.exposedToLlm &&
                meta.platforms.contains(platform) &&
                (groups.isEmpty() || meta.groups.intersect(groups).isNotEmpty())
            }
        }
    }
}
```

## Tool Types

### ExecutableTrailblazeTool

Tools that execute directly:

```kotlin
class InputTextTrailblazeTool(val text: String) : ExecutableTrailblazeTool {
    override suspend fun execute(ctx: TrailblazeToolExecutionContext): TrailblazeToolResult {
        ctx.trailblazeAgent.runMaestroCommands(listOf(InputTextCommand(text)))
        return TrailblazeToolResult.Success
    }
}
```

### DelegatingTrailblazeTool and Recording

Tools that are **exposed to the LLM** but delegate execution to other (recordable) tools.

#### Registry Flags Explained

| Flag | Meaning |
|------|---------|
| `exposedToLlm` | Tool appears in the tool list for LLM to call |
| `isRecordable` | Tool call is captured in trail recording |
| `isDelegating` | Tool converts to other tools before execution |

#### Why Tools Are Non-Recordable

There are two distinct reasons a tool might be `isRecordable=False`:

| Reason | Description | Replay Behavior |
|--------|-------------|-----------------|
| **Delegating** | Tool transforms to stable, recordable tools | Delegates are recorded and replayed |
| **LLM-Dependent** | Tool requires LLM reasoning based on current state | LLM must re-evaluate each replay |

**Delegating example:** `tapOnElementByNodeId`
- `nodeId=42` is ephemeral (changes between screens)
- Delegates to `tapOnElementWithText(text="Login")` which is stable
- Recording captures the stable delegate

**LLM-Dependent example:** Visual validation
- "Validate that the button is green"
- Requires LLM to interpret screenshot and reason about color
- Cannot be replayed deterministically — LLM must run each time

#### Common Combinations

| Pattern | `exposedToLlm` | `isRecordable` | `isDelegating` | Example |
|---------|----------------|----------------|----------------|---------|
| **Standard tool** | ✅ | ✅ | ❌ | `tapOnElementWithText` |
| **Delegating tool** | ✅ | ❌ | ✅ | `tapOnElementByNodeId` |
| **LLM-dependent tool** | ✅ | ❌ | ❌ | Visual validation, semantic checks |
| **Internal helper** | ❌ | ❌ | ❌ | Internal utility functions |

#### Replay Modes

When replaying a recorded trail:

| Tool Type | Replay Behavior |
|-----------|-----------------|
| `isRecordable=True` | Execute directly, no LLM needed |
| `isDelegating=True` | (Not in recording — delegates were recorded instead) |
| `isRecordable=False, isDelegating=False` | **LLM must run** to evaluate this step |

This means trails with LLM-dependent tools require "LLM-assisted replay" rather than pure deterministic replay.

#### The Delegating Pattern

```kotlin
// LLM calls this with a nodeId (ephemeral, screen-specific)
@TrailblazeToolClass(name = "tapOnElementByNodeId", isRecordable = false)
class TapOnElementByNodeIdTrailblazeTool(
    val nodeId: Long,
    val reason: String,
) : DelegatingTrailblazeTool {
    
    override fun toExecutableTrailblazeTools(ctx: TrailblazeToolExecutionContext): List<ExecutableTrailblazeTool> {
        // Convert nodeId to stable selector
        val element = findElementByNode(nodeId, ctx.screenState)
        
        // Delegate to a RECORDABLE tool with stable properties
        return listOf(TapOnElementWithTextTrailblazeTool(text = element.text, id = element.id))
    }
}
```

#### Recording Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│  LLM decides: "I need to tap the Login button (nodeId=42)"          │
│                                                                     │
│  Calls: tapOnElementByNodeId(nodeId=42, reason="Login button")      │
│                         │                                           │
│                         │ isRecordable=false, isDelegating=true     │
│                         │ → NOT recorded                            │
│                         ▼                                           │
│  Delegates to: TapOnElementWithTextTrailblazeTool(text="Login")     │
│                         │                                           │
│                         │ isRecordable=true                         │
│                         │ → RECORDED in trail                       │
│                         ▼                                           │
│  Trail file captures:                                               │
│  - tapOnElementWithText:                                            │
│      text: "Login"                                                  │
└─────────────────────────────────────────────────────────────────────┘
```

**Why this pattern?**
- `nodeId` is ephemeral — changes between screen captures, can't be replayed
- The delegated tool uses stable properties (text, ID) that work across runs
- Recording captures the replayable tool, not the ephemeral nodeId-based call

#### For External MCP Tools

External tools can also use this pattern via the registry:

```python
@server.resource("trailblaze://registry")
async def get_registry():
    return {
        "tools": {
            # Standard recordable tool
            "acme_driver_login": {
                "exposedToLlm": True,
                "isRecordable": True,
                "isDelegating": False,
            },
            # Delegating tool (converts to recordable primitives)
            "acme_tap_by_screen_coords": {
                "exposedToLlm": True,
                "isRecordable": False,  # Don't record coords-based tap
                "isDelegating": True,   # Converts to stable tap
            },
        }
    }
```

#### Core Recording Principle

**Recording = what Trailblaze invoked. Replay = Trailblaze invokes those same tools.**

This keeps Trailblaze as the controller for both recording and replay. Delegating tools (including external MCP tools) return a list of tools for Trailblaze to execute — they don't execute actions directly.

#### How Delegation Works

**Kotlin (internal):**
```kotlin
class TapOnElementByNodeIdTrailblazeTool : DelegatingTrailblazeTool {
    override fun toExecutableTrailblazeTools(ctx): List<ExecutableTrailblazeTool> {
        // Return what Trailblaze should execute
        return listOf(TapOnElementWithTextTrailblazeTool(text = "Login"))
    }
}
```

**External MCP:**
```python
@server.tool("acme_tap_by_coords")
async def tap_by_coords(x: int, y: int, _trailblaze: dict) -> dict:
    tb = TrailblazeClient(context=_trailblaze)
    
    # Read-only queries are allowed (not recorded)
    screen = await tb.capture_screen()
    element = find_element_at(screen, x, y)
    
    # Return delegate list - TRAILBLAZE will execute and record these
    return {
        "success": True,
        "_trailblaze_delegates": [
            {"tool": "tap", "args": {"text": element.text}}
        ]
    }
```

#### The Delegation Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│ 1. Trailblaze → MCP: acme_tap_by_coords(x=100, y=200)               │
│    (isRecordable=False, isDelegating=True → NOT recorded)           │
│                                                                     │
│ 2. External tool computes, uses read-only queries                   │
│    screen = await tb.capture_screen()  ← read-only, not recorded    │
│                                                                     │
│ 3. External tool returns:                                           │
│    {"_trailblaze_delegates": [{"tool": "tap", "args": {...}}]}     │
│                                                                     │
│ 4. Trailblaze receives response, sees delegates                     │
│                                                                     │
│ 5. Trailblaze executes: tap(text="Login")  ← RECORDED               │
│    (Trailblaze is the invoker)                                      │
│                                                                     │
│ Recording: tap(text="Login")                                        │
│ Replay: Trailblaze executes tap(text="Login")                       │
└─────────────────────────────────────────────────────────────────────┘
```

#### Read-Only vs Action Operations

External delegating tools can use **read-only queries** to compute what to delegate:

```python
@server.tool("acme_smart_tap")
async def smart_tap(description: str, _trailblaze: dict) -> dict:
    tb = TrailblazeClient(context=_trailblaze)
    
    # ═══════════════════════════════════════════════════════════════
    # READ-ONLY QUERIES (allowed, not recorded)
    # ═══════════════════════════════════════════════════════════════
    screen = await tb.capture_screen()
    visible = await tb.is_visible(text="Login")
    count = await tb.get_element_count(id="list_item")
    
    # ═══════════════════════════════════════════════════════════════
    # COMPUTE WHAT TO DELEGATE
    # ═══════════════════════════════════════════════════════════════
    if visible:
        delegates = [{"tool": "tap", "args": {"text": "Login"}}]
    else:
        delegates = [
            {"tool": "scroll", "args": {"direction": "down"}},
            {"tool": "tap", "args": {"text": "Login"}},
        ]
    
    # ═══════════════════════════════════════════════════════════════
    # RETURN DELEGATES - Trailblaze executes and records these
    # ═══════════════════════════════════════════════════════════════
    return {
        "success": True,
        "_trailblaze_delegates": delegates
    }
```

#### Nested Delegation

If a delegate is also a delegating tool, Trailblaze recursively processes until it reaches recordable tools:

```
acme_complex_flow (isDelegating=True, isRecordable=False)
  → returns delegates: [acme_login, acme_checkout]
  
  acme_login (isDelegating=True, isRecordable=False)
    → returns delegates: [tap("Sign In"), inputText(...)]
    
    tap("Sign In") (isRecordable=True) ← RECORDED
    inputText(...) (isRecordable=True) ← RECORDED
    
  acme_checkout (isDelegating=True, isRecordable=False)
    → returns delegates: [tap("Pay")]
    
    tap("Pay") (isRecordable=True) ← RECORDED

Final recording: [tap("Sign In"), inputText(...), tap("Pay")]
```

#### Why This Design

| Aspect | Benefit |
|--------|---------|
| **Trailblaze is always the invoker** | Recording and replay use the same execution path |
| **Clear control flow** | No hidden action execution inside external tools |
| **Deterministic replay** | Recorded tools are exactly what Trailblaze will invoke |
| **Matches Kotlin pattern** | `toExecutableTrailblazeTools()` returns delegates |

#### Response Fields

| Return Field | Behavior |
|--------------|----------|
| `_trailblaze_delegates` | List of tools for Trailblaze to execute (and record) |
| (no delegates) | Tool is not delegating, records itself if `isRecordable=True` |

### Tool Composition

Tools can call other tools:

```kotlin
class MyAppFullCheckoutFlow(
    private val commands: TrailblazeCommands,
    private val loginTool: MyAppLoginTool,  // Same server, direct call
) : TrailblazeTool {
    
    override suspend fun execute(args: Args): ToolResult {
        // Direct call to another tool (same process)
        loginTool.execute(LoginArgs(args.email, args.password))
        
        // Call core primitives (via RPC or direct, depending on mode)
        commands.tap(text = "Shop")
        commands.tap(text = args.itemName)
        commands.tap(text = "Checkout")
        
        return ToolResult.success()
    }
}
```

## Dynamic Tool Reload

For host mode, agents may create new tools at runtime. Explicit reload is required:

```kotlin
@Tool
fun reloadTools(): ReloadResult {
    // Scan tool directories
    // Re-read registries from MCP servers
    // Update tool index
    return ReloadResult(
        added = listOf("new_tool_1"),
        removed = emptyList(),
        total = 47,
    )
}
```

Teams must restart their MCP server (or implement hot-reload) for new tools to be available.

## Cross-Platform Tools

Tools declare supported platforms in the registry:

```kotlin
"acme_login" to ToolMetadata(
    platforms = setOf(ANDROID, IOS),  // Not web
    groups = setOf("auth"),
)
```

Tools can also check platform at runtime:

```kotlin
override suspend fun execute(args: Args): ToolResult {
    val packageId = when (commands.platform) {
        ANDROID -> "com.acme.driver"
        IOS -> "com.acme.AcmeDriver"
        else -> error("Unsupported platform")
    }
    commands.launchApp(packageId)
    // ...
}
```

## Type Safety and Refactoring

### Wire/Proto as Contract

```
Kotlin (Wire) definitions  ←  SOURCE OF TRUTH
       │
       ├── generates → Proto schema
       ├── generates → Kotlin interface
       ├── generates → Python client (typed)
       ├── generates → TypeScript client (typed)
       └── generates → gRPC stubs
```

### Refactoring Support

| Scenario | What Happens |
|----------|--------------|
| Rename command in Kotlin | Wire regenerates proto → regenerate clients → errors everywhere |
| Add parameter | Same flow, clients get new param |
| Remove command | Same flow, compile/lint errors |
| Breaking change | Bump version in proto package |

## What We Maintain vs Don't

| We Maintain | We Don't Maintain |
|-------------|-------------------|
| Wire/Kotlin API definitions | External teams' MCP servers |
| Proto generation pipeline | External teams' tool logic |
| Generated clients (published packages) | External teams' deployment |
| RPC server | External teams' CI/CD |
| `trailblaze-android-ondevice-mcp` module | |
| Registry data models (proto-generated) | |
| Light SDK wrappers (Python, TypeScript) | |
| `TrailblazeToolSet` Kotlin library | |
| Invocation context propagation infrastructure | |

## Consequences

**Positive:**

- Type-safe API via Wire/proto generation
- Refactoring support across all languages
- External teams use any language (MCP + RPC)
- On-device works via library dependency
- Same interface across all deployment modes
- Central registry simplifies tool metadata management
- MCP resources for registry is standard protocol usage
- We don't maintain external teams' code

**Negative:**

- Wire/proto adds indirection (but provides type safety)
- External teams must run their own MCP server
- On-device requires building custom test APK
- Generated clients must be published and versioned
- `TrailblazeToolSet` must be built before full internal validation (but existing tools work unchanged)

## Internal Validation

Your team can use the same architecture internally:

1. **One MCP server per app** (MyApp, OtherApp, AdminPanel)
2. **Same registry pattern** for tool metadata
3. **Same interface** (`TrailblazeCommands`) as external teams
4. **Same SDK patterns** — use `TrailblazeClient.from_context()` even in Kotlin
5. You experience friction before external teams do

### Validating the SDK Pattern

Internal tools should use the **same patterns** you recommend to external teams:

| Execution Mode | Pattern | Multi-Device? |
|----------------|---------|---------------|
| **In-process Kotlin** | `TrailblazeToolSet` with `ExecutionMode.IN_PROCESS` | ✅ Automatic |
| **Out-of-process testing** | `TrailblazeToolSet` with `ExecutionMode.RPC` | ✅ Via invocation ID |
| **Current (legacy)** | `TrailblazeToolExecutionContext` | ✅ Automatic |

Going forward, your team can use `TrailblazeToolSet` — the same thin library published for external teams. This ensures:
- We experience the same developer ergonomics as external teams
- The same tool code works both in-process (production) and out-of-process (testing)
- We catch friction before external teams encounter it

Existing `@TrailblazeToolClass` tools continue to work unchanged.

### Same Code, Multiple Execution Modes

Kotlin tools can be written to work **both in-process and via stdio MCP**:

```
┌─────────────────────────────────────────────────────────────────────┐
│                    MyApp Tools Module (Kotlin)                      │
│                                                                     │
│  - Uses Wire/proto interface (TrailblazeCommands)                  │
│  - Uses Kotlin MCP SDK for tool definitions                        │
│  - Same code for both execution modes                              │
└─────────────────────────────────────────────────────────────────────┘
        │                               │
        │ Production                    │ Development/Testing
        ▼                               ▼
┌───────────────────┐           ┌───────────────────┐
│   In-Process      │           │   Isolated Module │
│                   │           │                   │
│ Direct calls,     │           │ ./gradlew :myapp  │
│ no RPC overhead   │           │   -tools:runMcp   │
│                   │           │                   │
│                   │           │ stdio transport   │
│                   │           │ Verifies MCP path │
└───────────────────┘           └───────────────────┘
```

**Why this matters:**
- Ensures the stdio/MCP path works (we test it ourselves)
- Same tool code can be extracted into a separate process if needed
- Validates the external team experience internally

### New Tool Definition: Kotlin MCP SDK

We will migrate from the current custom annotation system to using a **standard Kotlin MCP SDK**.

**Current (deprecated):**
```kotlin
// Custom Trailblaze annotations
@TrailblazeToolClass(name = "myapp_login", isRecordable = true)
class MyAppLoginTool(
    val email: String,
    val password: String,
) : ExecutableTrailblazeTool {
    override suspend fun execute(ctx: TrailblazeToolExecutionContext): TrailblazeToolResult {
        // ...
    }
}
```

**New (Kotlin MCP SDK with ToolSet pattern):**
```kotlin
class MyAppToolSet(
    private val commands: TrailblazeCommands,
) : ToolSet, HasRegistry by MyAppToolRegistry {
    
    // ═══════════════════════════════════════════════════════════════
    // TOOL NAMES (constants for type safety + refactoring)
    // ═══════════════════════════════════════════════════════════════
    
    object ToolNames {
        const val LOGIN = "myapp_login"
        const val CHECKOUT = "myapp_checkout"
        const val SETUP = "myapp_setup"
    }
    
    // ═══════════════════════════════════════════════════════════════
    // TOOLS (using constants for stable names)
    // ═══════════════════════════════════════════════════════════════
    
    @Tool(customName = ToolNames.LOGIN)
    @LLMDescription("Log in to MyApp with credentials")
    suspend fun login(
        @LLMDescription("User email") email: String,
        @LLMDescription("User password") password: String,
    ): ToolResult {
        setup()  // Direct call to another tool (type-safe)
        commands.tap(text = "Sign in")
        commands.inputText(email)
        commands.inputText(password)
        commands.tap(text = "Submit")
        return ToolResult.success()
    }
    
    @Tool(customName = ToolNames.CHECKOUT)
    @LLMDescription("Complete checkout with current cart")
    suspend fun checkout(
        @LLMDescription("Amount in cents") amount: Int,
    ): ToolResult {
        commands.tap(text = "Checkout")
        commands.waitUntilVisible(text = "Payment Complete", timeoutMs = 10000)
        return ToolResult.success()
    }
    
    @Tool(customName = ToolNames.SETUP)
    @LLMDescription("Clear and launch MyApp")
    suspend fun setup(): ToolResult {
        commands.clearAppData("com.example.myapp")
        commands.launchApp("com.example.myapp")
        return ToolResult.success()
    }
}

// Registry as separate object (delegated to ToolSet)
object MyAppToolRegistry : HasRegistry {
    override val registry = mapOf(
        MyAppToolSet.ToolNames.LOGIN to ToolMetadata(
            platforms = setOf(ANDROID, IOS),
            groups = setOf("auth", "setup"),
            exposedToLlm = true,
            isRecordable = true,
        ),
        MyAppToolSet.ToolNames.CHECKOUT to ToolMetadata(
            platforms = setOf(ANDROID, IOS, WEB),
            groups = setOf("checkout"),
            exposedToLlm = true,
            isRecordable = true,
        ),
        MyAppToolSet.ToolNames.SETUP to ToolMetadata(
            platforms = setOf(ANDROID, IOS),
            groups = setOf("setup"),
            exposedToLlm = false,  // Internal helper
            isRecordable = false,
        ),
    )
}

// Interface for registry aggregation
interface HasRegistry {
    val registry: Map<String, ToolMetadata>
}
```

**Key patterns:**
- **Tool name constants** — `ToolNames.LOGIN` enables refactoring and cross-references
- **Explicit tool names** — `@Tool(customName = ...)` ensures stable names even if function is renamed
- **Colocated registry** — metadata lives with the tools via delegation
- **Direct tool composition** — `setup()` calls another tool directly (type-safe, no MCP round-trip)
- **HasRegistry interface** — enables central aggregation of all registries

**Benefits:**
- **Standard mechanism** — uses Kotlin MCP SDK, same as external teams
- **Portable** — tools work in-process or via stdio without code changes
- **Type-safe** — tool name constants prevent typos and enable refactoring
- **No custom annotation system** — less code to maintain
- **MCP SDK handles** — tool discovery, schema generation, invocation

## Tool Authoring by Language

### Kotlin (Recommended for In-Process)

See the ToolSet pattern above. Key points:
- Use Kotlin MCP SDK (`@Tool`, `@LLMDescription`, `ToolSet`)
- Tool name constants for type safety
- Colocated registry with `HasRegistry` interface
- Direct function calls for internal tool composition

### Python / TypeScript (External Teams)

External teams use:
1. **stdio transport** — Trailblaze spawns and manages the server
2. **Official MCP SDK** — Python (FastMCP) or TypeScript (@modelcontextprotocol/sdk)
3. **Trailblaze Client SDK** — our lightweight wrapper with generated RPC stubs
4. **MCP resource** — expose `trailblaze://registry` with tool metadata

Both Python and TypeScript MCP SDKs provide **built-in access to request context**, which our SDK uses to extract invocation metadata for multi-device support.

**Example (Python):**
```python
# acme_tools.py
from mcp import Server, Context
from trailblaze import TrailblazeClient  # Light SDK wrapper

server = Server()

@server.tool("acme_driver_login")
async def login(email: str, password: str, ctx: Context) -> dict:
    """Log in to Acme Driver app."""
    # Context-aware client - supports multi-device automatically
    tb = TrailblazeClient.from_context(ctx)
    
    await tb.clear_app_data("com.acme.driver")
    await tb.launch_app("com.acme.driver")
    await tb.tap(text="Sign in")
    await tb.input_text(email)
    await tb.input_text(password)
    return {"success": True}

@server.resource("trailblaze://registry")
async def get_registry():
    return {
        "tools": {
            "acme_driver_login": {
                "platforms": ["android", "ios"],
                "groups": ["auth"],
                "exposedToLlm": True,
                "isRecordable": True,
            }
        }
    }

if __name__ == "__main__":
    server.run()
```

**Example (TypeScript):**
```typescript
// acme_tools.ts
import { Server, Context } from '@modelcontextprotocol/sdk';
import { TrailblazeClient } from '@trailblaze/client';

const server = new Server();

server.tool('acme_driver_login', async (args: { email: string, password: string }, ctx: Context) => {
    // Context-aware client - supports multi-device automatically
    const tb = TrailblazeClient.fromContext(ctx);
    
    await tb.clearAppData('com.acme.driver');
    await tb.launchApp('com.acme.driver');
    await tb.tap({ text: 'Sign in' });
    await tb.inputText(args.email);
    await tb.inputText(args.password);
    return { success: true };
});

server.resource('trailblaze://registry', async () => ({
    tools: {
        acme_driver_login: {
            platforms: ['android', 'ios'],
            groups: ['auth'],
            exposedToLlm: true,
            isRecordable: true,
        }
    }
}));

server.run();
```

**Key pattern**: Use `TrailblazeClient.from_context(ctx)` (Python) or `TrailblazeClient.fromContext(ctx)` (TypeScript) instead of a global client instance. This extracts the invocation ID from `_meta` and automatically includes it in all RPC calls, enabling multi-device support with zero additional effort.

Teams can build their own patterns (tool name constants, registries, etc.) on top of these primitives.

## Trailblaze Client SDK

We provide **official lightweight SDK wrappers** for the two officially supported MCP SDK platforms: **Python** and **TypeScript**. 

**The SDK wrapper is a remote execution context.** Just as in-process Kotlin tools receive `TrailblazeToolExecutionContext`, remote MCP tools receive `TrailblazeClient` — both provide the same capability: a device-scoped interface to execute Trailblaze commands.

These wrappers:

1. Extract invocation context from MCP request metadata (`_meta`)
2. Return a client that auto-includes context in all RPC calls (scoped to the correct device)
3. Include generated RPC stubs for `TrailblazeCommands`

Both Python (FastMCP) and TypeScript (@modelcontextprotocol/sdk) provide **built-in access to request metadata** in tool handlers, making context extraction trivial.

### Python

Python's FastMCP provides built-in `Context` injection. The `Context` object gives direct access to request metadata:

```python
from trailblaze import TrailblazeClient
from mcp import Context

@mcp.tool()
def my_tool(param: str, ctx: Context) -> str:
    # from_context extracts _meta.trailblaze from the request
    tb = TrailblazeClient.from_context(ctx)
    tb.tap(100, 200)  # Invocation ID flows automatically
```

The `Context` parameter is automatically injected by FastMCP when present in the function signature.

### TypeScript

TypeScript's official MCP SDK (`@modelcontextprotocol/sdk`) also provides access to request context in tool handlers:

```typescript
import { TrailblazeClient } from '@trailblaze/client';

server.tool('my_tool', async (args, ctx) => {
    // fromContext extracts _meta.trailblaze from the request
    const tb = TrailblazeClient.fromContext(ctx);
    await tb.tap(100, 200);  // Invocation ID flows automatically
});
```

Like Python, the context is automatically available in the tool handler callback.

### Kotlin (Out-of-Process)

**We do not provide an official SDK wrapper for Kotlin out-of-process tools.**

**Why Kotlin is different**: The `ToolSet` pattern (used throughout this document) relies on Koog's annotation-based tool registration. However, Koog's `@Tool` functions only receive the deserialized parameters — **not the raw `CallToolRequest`** that contains `_meta`. This means tools defined via `ToolSet` cannot access invocation context.

For Kotlin MCP servers running out-of-process, use the raw MCP Kotlin SDK directly with `CallToolRequest`:

```kotlin
mcpServer.addTool("my_tool", ...) { request: CallToolRequest ->
    // Extract metadata manually from request.meta
    val invocationId = request.meta?.get("trailblazeInvocationId")
        ?.let { (it as? JsonPrimitive)?.content }
    val baseUrl = request.meta?.get("trailblaze")
        ?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content
        ?: "http://localhost:52525"
    
    // Create client with extracted context
    val tb = TrailblazeClient(baseUrl, invocationId)
    tb.tap(100, 200)
}
```

### Kotlin: TrailblazeToolSet (Required for Kotlin MCP SDK)

**Why this is required**: The Kotlin MCP SDK's `ToolSet` pattern (used by Koog) hides the raw `CallToolRequest` from tool implementations. This means `@Tool` functions only receive deserialized parameters — they cannot access `_meta` to retrieve the invocation context needed for multi-device routing.

If your team wants to write Trailblaze tools using the Kotlin MCP SDK (as recommended in Phase 5.5), you **must** build `TrailblazeToolSet` first. Without it, Kotlin tools cannot:
- Access invocation context for multi-device support
- Route RPC calls to the correct device
- Follow the same pattern as Python/TypeScript tools

`TrailblazeToolSet` is a thin wrapper that:
- Uses the official Kotlin MCP SDK for tool definitions
- **Intercepts `CallToolRequest`** to extract `_meta` before invoking the tool
- Injects `TrailblazeContext` into tool functions (like FastMCP's `Context`)
- **Preserves `TrailblazeTool` data classes** — no need to abandon our existing type-safe pattern
- Works both **in-process** and **via RPC** with a simple flag
- Can be published as part of our library for external Kotlin teams

```kotlin
// TrailblazeToolSet - supports both patterns

// OPTION 1: Keep using TrailblazeTool data classes (existing pattern)
// The data class is deserialized from args, context is injected separately
@Serializable
data class LoginTool(
    val email: String,
    val password: String,
) : TrailblazeTool

class AcmeToolSet : TrailblazeToolSet {
    @Tool
    @LLMDescription("Log in to Acme Driver app")
    suspend fun login(
        tool: LoginTool,              // TrailblazeTool data class - type-safe!
        ctx: TrailblazeContext,       // Injected for _meta access
    ): ToolResult {
        val tb = ctx.client
        tb.clearAppData("com.acme.driver")
        tb.launchApp("com.acme.driver")
        tb.tap(text = "Sign in")
        tb.inputText(tool.email)      // Access via data class
        tb.inputText(tool.password)
        return ToolResult.success()
    }
}

// OPTION 2: Flat parameters (simpler for small tools)
class SimpleToolSet : TrailblazeToolSet {
    @Tool
    @LLMDescription("Tap a button by text")
    suspend fun tapButton(
        text: String,
        ctx: TrailblazeContext,
    ): ToolResult {
        ctx.client.tap(text = text)
        return ToolResult.success()
    }
}

enum class ExecutionMode {
    IN_PROCESS,  // Direct TrailblazeCommands calls (no RPC overhead)
    RPC,         // Via generated RPC client (for out-of-process/remote)
}
```

**Benefits of this approach:**
- **Preserves TrailblazeTool** — Keep existing type-safe data classes
- **Consistency** — Same context injection pattern as Python/TypeScript SDKs
- **Internal validation** — Use the same library you publish
- **Flexibility** — Same tool code works in-process or out-of-process
- **Gradual migration** — Existing tools continue to work, add context when needed

**Existing tools**: Current `TrailblazeToolClass`/`ExecutableTrailblazeTool` patterns remain supported. Tools can incrementally adopt `TrailblazeContext` injection for multi-device support without rewriting.

### Kotlin (In-Process, Current)

Today, in-process tools have direct access to `TrailblazeToolExecutionContext`. This continues to work and is the current path for in-process Kotlin tools.

**Migration path**: Once `TrailblazeToolSet` is built, new tools should use it. Existing `@TrailblazeToolClass` tools continue to work unchanged, but new tools should follow the `TrailblazeToolSet` pattern for consistency with Python/TypeScript and to enable out-of-process testing.

### SDK Surface Area

The official SDKs (Python and TypeScript) are intentionally minimal:

| Component | Size | Purpose |
|-----------|------|---------|
| `TrailblazeClient.from_context()` | ~10 lines | Factory that extracts invocation ID from `_meta` |
| Generated RPC stubs | (from proto) | `tap()`, `swipe()`, `captureScreen()`, etc. |
| Auto meta injection | ~5 lines | Includes invocation ID in all RPC calls |

**Total**: ~50-100 lines per language. The "SDK" is really just a convenience wrapper around the generated RPC client.

### Officially Supported SDKs

| Language | SDK | Context Access | Status |
|----------|-----|----------------|--------|
| **Python** | `trailblaze` package | FastMCP `Context` injection | ✅ Planned |
| **TypeScript** | `@trailblaze/client` | MCP SDK context in handler | ✅ Planned |
| **Kotlin** | `TrailblazeToolSet` | `TrailblazeContext` injection | ✅ Planned |
| **Other languages** | DIY | Raw `_meta` extraction | Follow pattern |

All three official SDKs (Python, TypeScript, Kotlin) will provide the same developer experience:
1. Context automatically injected into tool handlers
2. `TrailblazeClient` / `TrailblazeCommands` available via context
3. Invocation ID flows automatically through all RPC calls
4. Works for both single-device and multi-device scenarios

For languages without an official SDK, teams can follow the same pattern: extract `trailblazeInvocationId` and `trailblaze` object from `_meta`, then include them in RPC calls.

## Query Commands for Conditionals

Custom tools often need to check screen state before deciding what to do. The API provides **query commands** that return values instead of throwing:

### Actions vs Queries vs Assertions

| Category | Behavior | Example |
|----------|----------|---------|
| **Actions** | Perform operation, return result | `tap()`, `inputText()` |
| **Queries** | Check state, return value (never throw) | `isVisible()`, `hasText()`, `getElementCount()` |
| **Assertions** | Check state, throw if condition not met | `assertVisible()`, `waitUntilVisible()` |

### Using Queries for Conditional Logic

**Python example:**
```python
@server.tool("acme_handle_onboarding")
async def handle_onboarding() -> dict:
    # Check if cookie consent dialog is shown
    if await tb.is_visible(text="Accept Cookies"):
        await tb.tap(text="Accept")
    
    # Check if we need to dismiss a tutorial
    if await tb.is_visible(text="Skip Tutorial"):
        await tb.tap(text="Skip")
    
    # Check which screen we're on
    if await tb.has_text("Welcome back"):
        return {"screen": "returning_user"}
    elif await tb.has_text("Create Account"):
        return {"screen": "new_user"}
    else:
        return {"screen": "unknown"}
```

**Kotlin example:**
```kotlin
@Tool(customName = ToolNames.HANDLE_DIALOGS)
suspend fun handleDialogs(): ToolResult {
    // Dismiss any blocking dialogs
    if (commands.isVisible(text = "Allow notifications")) {
        commands.tap(text = "Not now")
    }
    
    if (commands.isVisible(text = "Rate this app")) {
        commands.tap(text = "Maybe later")
    }
    
    // Check how many items are in a list
    val itemCount = commands.getElementCount(id = "list_item")
    if (itemCount == 0) {
        return ToolResult.failure("No items found")
    }
    
    return ToolResult.success()
}
```

### Retry Patterns

```python
@server.tool("acme_login_with_retry")
async def login_with_retry(email: str, password: str, max_attempts: int = 3) -> dict:
    for attempt in range(max_attempts):
        await tb.tap(text="Sign In")
        await tb.input_text(email)
        await tb.input_text(password)
        await tb.tap(text="Submit")
        
        # Check outcome without throwing
        if await tb.is_visible(text="Dashboard", timeout_ms=5000):
            return {"success": True, "attempts": attempt + 1}
        
        if await tb.is_visible(text="Invalid credentials"):
            # Wrong password, no point retrying
            return {"success": False, "error": "invalid_credentials"}
        
        # Network error or slow response, try again
    
    return {"success": False, "error": "max_attempts_exceeded"}
```

### Key Difference: Queries vs Assertions

```python
# QUERY - returns False, doesn't throw
visible = await tb.is_visible(text="Login Button")  # → False

# ASSERTION - throws/fails if not visible
await tb.assert_visible(text="Login Button")  # → raises AssertionError
```

Use **queries** when you need to branch based on screen state.
Use **assertions** when you're verifying expected state (and want the test to fail if wrong).

## Error Handling

Errors propagate through standard mechanisms at each layer:

| Layer | Error Handling |
|-------|----------------|
| **Tool execution** | Return failure result with error message |
| **MCP protocol** | JSON-RPC error responses |
| **RPC (gRPC/Connect)** | Status codes + error details |

Tools should return meaningful error messages. Protocol-level errors (connection failures, timeouts) are handled by the respective transports. No custom error handling infrastructure required.

## FAQ

### Can external MCP servers make LLM requests through Trailblaze?

**Future: Yes, via MCP Sampling.**

The MCP protocol includes a [Sampling](https://modelcontextprotocol.io/specification/2025-11-25/client/sampling) feature that allows servers to request LLM completions from clients. This enables external tools to "borrow" Trailblaze's configured LLM for tasks like:

- "Is the screen showing an error message?"
- "What color is the button?"
- "Does this screenshot match the expected state?"

**Why MCP Sampling instead of an RPC endpoint?**

| Approach | Who Can Use It | Control |
|----------|---------------|---------|
| **MCP Sampling** | Only servers Trailblaze connects to | Per-connection opt-in |
| **RPC `askLlm()` endpoint** | Anyone who connects to Trailblaze | Globally visible |

MCP Sampling is preferred because:
1. **Trailblaze initiates the connection** — you choose which servers can sample
2. **Per-server opt-in** — enable sampling only for trusted servers
3. **Invocation context flows naturally** — same pattern as other RPC calls
4. **Protocol-compliant** — standard MCP, not a custom API

**Current status**: Not implemented. The invocation context infrastructure (invocation ID, context propagation) provides the foundation. When implemented:

```python
@mcp.tool()
async def verify_screen_color(expected_color: str, ctx: Context) -> dict:
    tb = TrailblazeClient.from_context(ctx)
    
    # Request LLM completion via MCP Sampling
    result = await tb.sampling.create_message(
        messages=[{"role": "user", "content": f"Is the button {expected_color}?"}],
        include_screenshot=True,
    )
    
    return {"matches": "yes" in result.content.lower()}
```

**Note**: Most MCP clients (Cursor, Claude Desktop) don't support sampling. This feature is for external MCP **servers** calling back to Trailblaze, not for clients calling Trailblaze.

## Related Documents

- [010: Custom Tool Authoring](2026-01-28-custom-tool-authoring.md) - Previous decision this supersedes
- [008: Trailblaze MCP](2026-01-28-trailblaze-mcp.md) - MCP server architecture
- [032: Trail/Blaze Agent Architecture](2026-02-04-trail-blaze-agent-architecture.md) - How tools fit in agent architecture

## References

- [MCP Protocol](https://modelcontextprotocol.io/) - Tool discovery and invocation
- [Wire](https://github.com/square/wire) - Kotlin-first proto library by Block
- [Protocol Buffers](https://protobuf.dev/) - API definition
- [gRPC](https://grpc.io/) - RPC framework
- [Connect](https://connectrpc.com/) - Modern proto-based RPC
