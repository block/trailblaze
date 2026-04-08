---
title: "MCP STDIO-to-HTTP Proxy for Development"
type: devlog
date: 2026-03-15
---

# MCP STDIO-to-HTTP Proxy for Development

## Summary

Designed and implemented a lightweight STDIO-to-HTTP proxy (`trailblaze mcp-proxy`) that decouples the MCP client connection from the Trailblaze daemon process. This lets developers restart the daemon for code changes without losing the MCP client connection.

## The Problem

Every code change to the MCP server requires a full rebuild and restart. In the current architecture, the STDIO MCP server runs in-process — when it restarts, the stdin/stdout pipe breaks and the MCP client (Claude Desktop, Cursor, etc.) disconnects. MCP clients don't implement reconnection. You have to manually re-add the connection every time. This makes iterative MCP development painfully slow.

We considered whether Streamable HTTP transport (already implemented) would help, but it's the same problem from the client's perspective — the client loses its session and doesn't know how to recover.

## The Design

```
MCP Client <-- STDIO (stable) --> Proxy <-- HTTP (reconnects) --> Daemon
```

The proxy is a long-lived process that:
1. Accepts STDIO from the MCP client (the connection that must not break)
2. Forwards all JSON-RPC to the daemon's `POST /mcp` endpoint
3. Holds a `GET /mcp` SSE connection for daemon-to-client notifications
4. When the daemon dies, queues requests and retries until it comes back
5. On reconnect, replays the `initialize` handshake and `device()` connect call
6. Sends `notifications/tools/list_changed` to the client so it re-fetches tools

There are two sessions: Client-to-Proxy (never breaks) and Proxy-to-Daemon (breaks and reconnects). The client only sees the first one.

## Key Decisions

**Raw HTTP forwarding, not SDK-level proxying.** The Kotlin MCP SDK (v0.8.3) has no built-in proxy mechanism. Its `Server` class interprets messages (parses JSON-RPC, routes to tool handlers). A proxy should forward raw bytes without interpreting them. This makes it simpler, more resilient, and future-proof — it doesn't break when new MCP methods are added. The proxy uses only `java.net.HttpURLConnection` with zero Trailblaze server dependencies.

**Session replay, not session persistence.** When the daemon restarts, all session state is lost (device connections, claims, cached screen state). Rather than persisting state, the proxy replays the setup calls: `initialize` + `notifications/initialized` + the last `device()` connect. Session logs from previous work are durable in the logs repo, so mid-trail work is recoverable.

**Separate command for now, unification later.** The proxy lives as `trailblaze mcp-proxy`. The long-term plan is for `trailblaze mcp` to become the proxy internally — it would auto-start the daemon if none is running (via `ensureServerRunning()`), then proxy to it. This matches how `trailblaze run` already works as a client of the daemon. For now, keeping them separate avoids touching the existing STDIO code path.

**Never auto-kill the daemon.** When the proxy exits (client disconnects), it does not shut down the daemon — even if it could have started one. This avoids the edge case where multiple proxy instances share a daemon and one exiting kills it for the others. `trailblaze stop` is the explicit cleanup.

## Dead Ends Considered

- **Hot-reloading the JVM server** — too complex for a Kotlin/Gradle project, JVM startup cost makes this impractical.
- **Having MCP clients reconnect natively** — they don't, and we can't change them.
- **Using SDK transport primitives to build the proxy** — the SDK's `Server`/`Client` classes parse and interpret messages, which is the opposite of what a proxy wants. Lower-level transport wiring would have been more complex than raw HTTP forwarding for no benefit.

## Development Workflow

```bash
# Terminal 1 (proxy — start once, stays running)
trailblaze mcp-proxy

# Terminal 2 (daemon — restart freely for code changes)
trailblaze

# After code changes:
trailblaze stop
./gradlew :trailblaze-host:classes
trailblaze
# Proxy reconnects automatically, MCP client doesn't notice
```

## Implementation

- `McpProxyCommand.kt` — the proxy command, registered as `trailblaze mcp-proxy`
- Added to `TrailblazeCliCommand` subcommands in `TrailblazeCli.kt`
