# @trailblaze/scripting

TypeScript SDK for authoring Trailblaze MCP tools.

Wraps `@modelcontextprotocol/sdk` so author code doesn't re-do the same
`McpServer` + `StdioServerTransport` ceremony in every toolset file, and
surfaces the Trailblaze-injected envelope (`_meta.trailblaze`) as a typed
`TrailblazeContext` inside each tool's handler.

## Install

```bash
cd <your-target>/trailblaze-config/mcp-sdk   # or wherever your tools.ts lives
bun install   # or `npm install`
```

## Usage

```typescript
import { trailblaze } from "@trailblaze/scripting";

trailblaze.tool(
  "generateTestUser",
  { description: "Returns a fresh random {name, email} pair.", inputSchema: {} },
  async (_args, ctx, client) => {
    // ctx:    injected TrailblazeContext (device, sessionId, memory, ...) — `undefined`
    //         only when the tool is invoked outside a live Trailblaze session.
    // client: always provided; exposes `callTool(name, args)` for composing other
    //         Trailblaze tools (see "Composing tools" below).
    return {
      content: [{ type: "text", text: JSON.stringify({ name: "Sam", email: "sam@example.com" }) }],
      isError: false,
    };
  },
);

await trailblaze.run();
```

Wire the file from your target YAML:

```yaml
# trailblaze-config/targets/myapp.yaml
id: myapp
mcp_servers:
  - script: ./mcp-sdk/tools.ts
```

## Composing tools — `client.callTool`

The third handler argument is a `TrailblazeClient`. Its `callTool(name, args)`
method dispatches any Trailblaze tool (host-side Kotlin tools, other scripted
tools, …) against the live session and returns the result.

```typescript
trailblaze.tool(
  "signUpNewUser",
  { description: "Generates a user and signs them up.", inputSchema: {} },
  async (_args, _ctx, client) => {
    const userResult = await client.callTool("generateTestUser", {});
    const user = JSON.parse(userResult.textContent) as { name: string; email: string };

    // Drive the UI with built-in Trailblaze tools — same callback surface.
    await client.callTool("tapOnElementWithText", { text: "Sign up" });
    await client.callTool("inputText", { text: user.email });
    await client.callTool("tapOnElementWithText", { text: "Continue" });

    return {
      content: [{ type: "text", text: JSON.stringify(user) }],
      isError: false,
    };
  },
);
```

`callTool` throws on any non-success outcome (tool failure, timeout,
reentrance cap hit, transport error) — so the happy path is a plain sequence
of awaits, no success-flag branching.

A working example lives at
`examples/android-sample-app/trailblaze-config/mcp-sdk/tools.ts`
(look for `signUpNewUserSdk`).

## Runtimes — the same TS runs in two places

Identical author code runs in two Trailblaze runtimes; the SDK transparently
picks the right `callTool` transport. Authors should never need to branch on
which — but it helps when debugging:

| Runtime      | When                                                                                           | `callTool` transport                                              | `ctx.baseUrl` | `ctx.runtime`  |
|--------------|------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|---------------|----------------|
| **Host**     | Tool runs as a subprocess spawned by the Trailblaze daemon (local dev, host JVM tests).        | HTTP POST to `${baseUrl}/scripting/callback`.                     | set           | absent         |
| **On-device**| Tool runs inside an Android QuickJS bundle on-device (instrumented tests, cloud device farm).  | In-process `globalThis.__trailblazeCallback` binding — no HTTP.   | absent        | `"ondevice"`   |

Error wording from `callTool` surfaces the transport source (the HTTP URL or
`__trailblazeCallback`) so you can tell at a glance which path failed.

Design record for the on-device transport:
`docs/devlog/2026-04-23-on-device-callback-channel.md`.

## Logging

Use standard `console.*` methods — same idiom as any MCP stdio server. No
Trailblaze-specific logger to import.

Every method — `console.log`, `console.info`, `console.warn`, `console.error`,
`console.debug` — is safe in both runtimes. Where your output goes:

| Runtime      | `console.*` destination                                                                  |
|--------------|------------------------------------------------------------------------------------------|
| **Host**     | stderr → `<session-log-dir>/subprocess_stderr_<script>.log` via `StderrCapture`.         |
| **On-device**| Android logcat, tagged `[bundle] level=<level> msg=<message>` via the prelude's `console` shim. |

**One thing to know about `console.log` on host.** MCP stdio servers reserve stdout for
JSON-RPC protocol messages — a raw `console.log` in Node/bun would corrupt the wire.
`trailblaze.run()` silently redirects `console.log` onto `console.error` at boot so this
isn't something you need to think about; your `console.log` ends up in the same stderr log
as everything else. Authors who already follow the MCP convention and use `console.error`
are unaffected. If you genuinely need to write to stdout, reach for
`process.stdout.write(...)` directly; the redirect only touches `console.log`.

## Public surface

| Export                                | Kind      | Notes                                                                |
|---------------------------------------|-----------|----------------------------------------------------------------------|
| `trailblaze.tool(name, spec, handler)`| namespace | Register a tool. Also exported as a named `tool`.                    |
| `trailblaze.run(opts?)`               | namespace | Start the MCP server. Also exported as a named `run`.                |
| `fromMeta(meta)`                      | function  | Parse `_meta.trailblaze` into a `TrailblazeContext` (for custom paths; `trailblaze.tool` handlers already receive the parsed context as `ctx`). |
| `TrailblazeContext`                   | type      | Injected envelope: `{ sessionId, invocationId, device, memory, baseUrl?, runtime? }`. |
| `TrailblazeClient`                    | type      | Third handler arg. Exposes `callTool(name, args)`.                   |
| `TrailblazeCallToolResult`            | type      | Resolved value from `callTool`. `{ success: true, textContent, errorMessage }`. |
| `TrailblazeDevice`                    | type      | `ctx.device`: `{ platform, widthPixels, heightPixels, driverType }`. |
| `RunOptions`, `TrailblazeToolHandler`, `TrailblazeToolSpec` | types | Supporting types for `run` / `tool`. |

## Consuming the package

Marked `private: true` while the SDK surface stabilizes — `package.json`'s
`main` / `exports` / `types` point at `./src/index.ts` and the package has
no build step. Authors consume via a TS-capable runtime (bun or node+tsx)
through the `file:` link shown in the sample-app example
(`examples/android-sample-app/trailblaze-config/mcp-sdk/package.json`).
Publishing a proper `dist/` build with `.js` + `.d.ts` outputs (and flipping
`private` off) is a follow-up once the surface stabilizes.

## References

- Envelope + callback contract devlog:
  `docs/devlog/2026-04-22-scripting-sdk-envelope-migration.md`
- On-device transport devlog:
  `docs/devlog/2026-04-23-on-device-callback-channel.md`
- Conventions devlog:
  `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md`
