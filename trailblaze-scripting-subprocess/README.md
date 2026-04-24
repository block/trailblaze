# trailblaze-scripting-subprocess

**Out-of-process subprocess (real MCP SDK over stdio).** Spawns a bun/node
subprocess per Trailblaze session, speaks MCP JSON-RPC over stdio using the
official `io.modelcontextprotocol:kotlin-sdk`, and registers the subprocess's
advertised tools into the global `TrailblazeToolDescriptor` registry.

Authors write a standard MCP server in TypeScript with
`@modelcontextprotocol/sdk`. Trailblaze is the MCP client. No QuickJS, no
in-process JS — the author's `.ts` file runs under its own runtime and
communicates back over stdio.

This is the sibling runtime to `:trailblaze-scripting`. Pick between them based
on execution model:

| | `:trailblaze-scripting-subprocess` | `:trailblaze-scripting` |
|---|---|---|
| Execution | Out-of-process bun/node subprocess | In-process QuickJS |
| Transport | MCP JSON-RPC over stdio | Direct Kotlin ↔ JS binding |
| JS runtime | Real `@modelcontextprotocol/sdk` | `dokar3/quickjs-kt` |
| Host requirement | `bun` or `node`+`tsx` on `PATH` | Just the JVM classpath |
| Can run on-device | No (can't spawn subprocesses) | Yes (planned — PR A5 bundle path) |

## How it's wired

- Target YAMLs declare scripts via `mcp_servers:` on `AppTargetYamlConfig` (see
  `:trailblaze-models`). Each entry points at a `.ts` file.
- At session start, Trailblaze spawns the subprocess, runs the MCP handshake
  (`initialize` → `tools/list`), and registers each returned tool under the name
  it advertised — no prefixing or renaming (per conventions § 4).
- Every `tools/call` carries a `_trailblazeContext` envelope in `arguments`
  (device + memory snapshot), and the subprocess receives `TRAILBLAZE_*` env
  vars at spawn.
- Subprocess lifecycle is per-session: spawn on session start, graceful shutdown
  on teardown, stderr captured to the session log directory.

## Status

Shipped by Decision 038 PR A3. The reciprocal `trailblaze.execute()`
callback channel (subprocess → Trailblaze primitives) is deferred to a
follow-up; until then, Tier 2 tools read the `_trailblazeContext` snapshot
rather than mutating Trailblaze state.

## References

- `docs/devlog/2026-04-20-scripted-tools-a3-host-subprocess.md` — PR A3 scope.
- `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md` — shared
  conventions (annotations, `_trailblazeContext` envelope, naming + collisions).
- `docs/devlog/2026-04-20-scripted-tools-toolset-consolidation.md` —
  Option-2 amendment that consolidated the former PR A3/A4 onto this module.
