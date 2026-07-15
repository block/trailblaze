#!/usr/bin/env node
// MCP-shaped subprocess that OPENS its stdio but NEVER answers the `initialize` handshake.
// Reproduces a real `bun` cold start that parks the handshake — the exact wedge
// McpSubprocessSession.connect's watchdog defends against. Unlike a POSIX `sleep`, this holds
// its pipes like a genuine MCP server would (it drains stdin so the client's request write
// succeeds and no EPIPE fires) yet writes nothing on stdout, and it never exits on its own — so
// a regressed watchdog can't masquerade as "just slow"; connect would hang forever.
process.stdin.resume();
process.stdin.on('data', () => {}); // swallow the initialize request, deliberately never respond
setInterval(() => {}, 1 << 30); // keep the event loop alive indefinitely
