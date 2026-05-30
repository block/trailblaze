# @trailblaze/scripting

TypeScript SDK for authoring Trailblaze MCP tools.

Wraps `@modelcontextprotocol/sdk` so author code doesn't re-do the same
`McpServer` + `StdioServerTransport` ceremony in every toolset file, and
surfaces the Trailblaze-injected envelope (`_meta.trailblaze`) as a typed
`TrailblazeContext` inside each tool's handler.

## Install

The recommended path is a Trailblaze workspace: `trailblaze check` extracts the SDK's
rolled-up declaration bundle into `<workspace>/.trailblaze/sdk/dist/index.d.ts` AND writes
each trailmap's `tools/tsconfig.json` as a framework-managed self-contained file whose `paths`
mapping points at that bundle. Per-trailmap `package.json` and `bun install` are not required
in that path, and authors don't hand-author the per-trailmap tsconfig either. See *Typed
bindings* below for the per-trailmap wiring.

For ad-hoc / non-Trailblaze-workspace consumption (e.g. you're vendoring `tools.ts`
into a stand-alone bun project that does not run `trailblaze check`), install the
package the usual way:

```bash
cd path/to/your-mcp-project   # wherever your tools.ts lives
bun install
```

Trailblaze targets the [bun](https://bun.sh) runtime — it's the only JavaScript
runtime required to author, build, and execute scripted tools.

## Usage

```typescript
import { trailblaze } from "@trailblaze/scripting";

trailblaze.tool(
  "generateTestUser",
  { description: "Returns a fresh random {name, email} pair.", inputSchema: {} },
  async (_args, ctx, client) => {
    // ctx:    injected TrailblazeContext (device, sessionId, memory, ...) — `undefined`
    //         only when the tool is invoked outside a live Trailblaze session.
    // client: always provided; exposes the typed `client.tools.<name>(args)` namespace
    //         for composing other Trailblaze tools (see "Composing tools" below).
    return {
      content: [{ type: "text", text: JSON.stringify({ name: "Sam", email: "sam@example.com" }) }],
      isError: false,
    };
  },
);

await trailblaze.run();
```

Wire the file from your trailmap manifest:

```yaml
# trailmaps/myapp/trailmap.yaml
id: myapp
target:
  display_name: My App
  tools:
    - findUser
```

Pair the `.ts` source with a sibling `<name>.yaml` descriptor under
`<trailmap>/tools/` declaring the tool's `name:`, `script:`, and `inputSchema:`.

## Composing tools — `client.tools.<name>(args)`

The third handler argument is a `TrailblazeClient`. Its `tools` namespace
dispatches any Trailblaze tool (framework tools, trailmap-scripted tools, sibling
tools registered in the same file) against the live session and returns the
result. Each property is a typed method — autocomplete on the tool name, args
type-checked against the tool's declared schema.

```typescript
trailblaze.tool(
  "signUpNewUser",
  { description: "Generates a user and signs them up.", inputSchema: {} },
  async (_args, _ctx, client) => {
    const userResult = await client.tools.generateTestUser({});
    const user = JSON.parse(userResult.textContent) as { name: string; email: string };

    // Drive the UI with built-in Trailblaze tools — same typed surface.
    await client.tools.tapOnElementWithText({ text: "Sign up" });
    await client.tools.inputText({ text: user.email });
    await client.tools.tapOnElementWithText({ text: "Continue" });

    return {
      content: [{ type: "text", text: JSON.stringify(user) }],
      isError: false,
    };
  },
);
```

`client.tools.<name>` throws on any non-success outcome (tool failure,
timeout, reentrance cap hit, transport error) — so the happy path is a plain
sequence of awaits, no success-flag branching. Only tools declared in
`TrailblazeToolMap` are reachable; the SDK ships built-ins (`inputText`,
`tapOnPoint`, `pressKey`, …) and the per-trailmap `trailblaze-client.d.ts` files written
by `trailblaze check` augment `TrailblazeToolMap` for every scripted tool
declared in a trailmap's `target.tools:` plus tools transitively inherited via
`dependencies:` `exports:` (see *Typed bindings* below).

The lower-level `callTool` dispatcher still exists on the runtime object —
it's how the `tools` Proxy actually dispatches and how the on-device QuickJS
bundle calls back — but it's hidden from the public `TrailblazeClient` type
so author code can't bypass the typed surface.

Working examples live in
`examples/wikipedia/trails/config/trailmaps/wikipedia/tools/` (e.g.
`wikipedia_web_searchAndVerify.ts`) — typed-overload scripted tools that compose
each other via `client.tools.<name>(args)` over the callback channel.

### Typed bindings

Each trailmap that ships scripted tools gets a generated `tools/trailblaze-client.d.ts`
that augments `TrailblazeToolMap` with one entry per tool the trailmap's TS authors can
dispatch — the trailmap's own `target.tools:` plus every scripted tool transitively
inherited through `dependencies:` via the dep's `exports:` field, plus the Kotlin
tools resolved from the trailmap's own platform `tool_sets:`. The file is regenerated on
every `trailblaze check` (and on every daemon-aware command via the bootstrap), and
is content-stable across restarts of the same toolset. Commit it (treat it as an API
contract) or `.gitignore` it (treat it as derived output) — both choices are supported.

The per-trailmap `tools/tsconfig.json` is also framework-managed — `trailblaze check`
writes it (and adds `tools/tsconfig.json` + `tools/.trailblaze/` to a `.gitignore` at
the trailmap root). Authors don't author or maintain it. The file is fully self-contained:
every compiler option is inlined, and the only workspace-relative reference is the
`paths` mapping pointing at the SDK declaration bundle at
`<workspace>/.trailblaze/sdk/dist/index.d.ts` — a single rolled-up `.d.ts` with the
zod types the SDK re-exports inlined into the same file. No `extends:` chain, no
`node_modules`, no workspace `tsconfig.base.json`. That self-contained shape is what
lets a trailmap be npm-distributed and installed into a different workspace's `node_modules/`
— the next `trailblaze check` re-derives the `paths` mapping at the new location.
If you're upgrading from an older Trailblaze that expected you to hand-author the
tsconfig, delete your existing `tools/tsconfig.json` and re-run `trailblaze check` —
the emitter preserves files without its banner so it won't silently destroy custom
overrides.

## Runtimes — the same TS runs in two places

Identical author code runs in two Trailblaze runtimes; the SDK transparently
picks the right dispatch transport. Authors should never need to branch on
which — but it helps when debugging:

| Runtime      | When                                                                                           | Dispatch transport                                                | `ctx.baseUrl` | `ctx.runtime`  |
|--------------|------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|---------------|----------------|
| **Host**     | Tool runs as a subprocess spawned by the Trailblaze daemon (local dev, host JVM tests).        | HTTP POST to `${baseUrl}/scripting/callback`.                     | set           | absent         |
| **On-device**| Tool runs inside an Android QuickJS bundle on-device (instrumented tests, cloud device farm).  | In-process `globalThis.__trailblazeCallback` binding — no HTTP.   | absent        | `"ondevice"`   |

Error wording from `client.tools.<name>` surfaces the transport source (the
HTTP URL or `__trailblazeCallback`) so you can tell at a glance which path
failed.

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

## Error handling — how failures become session-log entries

Every dispatch of your tool produces a `TrailblazeToolLog` entry in the session's log
directory (`*ToolLog.json`). When something fails, that log's `exceptionMessage` is the
**only** thing the developer reading the trail report sees — there's no separate stderr
file the report falls back to. The quality of your error messages directly determines
whether your tool is debuggable from logs alone.

**Where to find the logs.** Sessions are written to `<gitRoot>/logs/<sessionId>/` when
running from a git checkout, or to `<defaultAppDataDirectory>/logs/<sessionId>/` otherwise.
Each session directory contains one `*ToolLog.json` per dispatched tool plus the
`subprocess_stderr_<script>.log` files described below. In CI builds the directory is
captured as a build artifact — check your CI's artifact UI for `logs/`.

Four failure shapes, four different log outcomes:

| What happens                                                | Log shape                                                                | Tip                                                                                  |
|-------------------------------------------------------------|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| Handler returns `{ isError: true, content: [{ text }] }`    | `exceptionMessage = <your text>`, `successful = false`                   | **Most common**. You control the message — make it actionable.                       |
| Handler `throw new Error("...")` (sync or async-reject)     | `exceptionMessage = "Error: <message>\n<stack>"`, `successful = false`   | The SDK catches throws and preserves the JS stack — author file + line is visible.   |
| Handler hangs longer than the dispatch timeout              | `exceptionMessage` includes `timed out after Nms`, `successful = false`  | Default 120 000 ms (`DEFAULT_CALLBACK_TIMEOUT_MS`); tune with `-Dtrailblaze.callback.timeoutMs=N` on the daemon. |
| Subprocess process dies (handler `process.exit`, OOM, etc.) | `exceptionMessage` includes exit code + stderr tail, `successful = false`| Reach for `console.error` liberally; the stderr tail is what saves you here.         |

**What happens to the trail when your tool fails.** A tool whose log carries
`successful = false` (any of the four shapes above) does **not** auto-retry. The trail
runner records the failure in the action log and the LLM sees the `exceptionMessage` in
its next prompt context — the LLM may choose to try a different approach, retry the same
tool (if it judges that worthwhile), or eventually give up. For deterministic retry
behavior, make your tool idempotent and rely on the LLM's natural re-call cycle; the
framework does not provide a step-level retry primitive today.

### Authoring rules of thumb

**Use `isError: true` for expected business-logic failures** — credentials missing,
prerequisite not met, server-side rejection. The text content IS the contract; include
everything the next developer needs to act:

```typescript
trailblaze.tool("fundAccount", { description: "...", inputSchema: {} }, async (args, ctx) => {
  if (!ctx?.memory.customer_token) {
    return {
      isError: true,
      content: [{
        type: "text",
        text: "fundAccount requires customer_token in session memory. " +
              "Call createStagingAccount first, or set 'customer_token' in your trail's memory block.",
      }],
    };
  }
  // ... happy path ...
});
```

**Let unexpected errors throw** — network failures, JSON parse errors, anything that
indicates a bug. The SDK catches and preserves the stack. You don't have to manually wrap
every `JSON.parse` in a try/catch just to surface line numbers; the SDK does it for you.

```typescript
// This is fine — if the parse throws, the log shows the file/line.
const parsed = JSON.parse(response.body);
```

**Avoid swallow-and-ignore.** Don't `catch (e) { /* ignored */ }` unless you genuinely
mean it. Every silent catch is a future "the tool said success but nothing happened"
debugging session that costs an order of magnitude more than including the error in
the result message.

**Use `console.error` for diagnostic context**, even on the happy path. `console.error`
output lands in `subprocess_stderr_<script>.log` next to your `*ToolLog.json` — it
shows up in session reports as a sibling artifact. Reach for it whenever you have
"would-be-nice-to-see" info that doesn't belong in the tool result.

### What the framework does for you

After PRs #2941 / #2942 / #2943 (in-process QuickJS, subprocess, on-device bundle):

- Handler throws are caught at the SDK boundary and surfaced as `isError: true` with
  the original `Error.name`, `message`, AND `stack`. No author action needed — the
  fix is universal across all three scripted-tool runtimes.
- Non-`Error` throws (`throw "string"`, `throw 42`) fall back to `String(e)` rather
  than producing `"undefined"` envelopes.
- `CancellationException` (Kotlin side) is rethrown so structured concurrency for
  session teardown / agent abort isn't swallowed.

### Subprocess-specific debugging tips

- **Subprocess died but you can't tell why.** Check the session log directory for
  `subprocess_stderr_<script>.log` — that's where your `console.error` output goes,
  and the most recent N lines of it are tail-included in the `FatalError.errorMessage`
  when the framework detects the subprocess crash (N is `StderrCapture.DEFAULT_TAIL_LINES`,
  currently 64).
- **Tool succeeded but the result is empty.** Check that you returned
  `{ content: [{ type: "text", text: ... }] }`, not `{ content: ... }` (missing
  `type: "text"` causes the MCP SDK to serialize a content variant that some downstream
  consumers ignore). The framework's `toTrailblazeToolResult` mapper reads the first
  `TextContent` block; if your handler returned image/audio/resource_link variants only,
  the log's success message will be empty.
- **Reentrance: `client.tools.<name>` hits a depth cap.** Composing tools is supported, but
  the framework caps recursion depth at `MAX_CALLBACK_DEPTH = 16` to defend against
  infinite loops. When exceeded, the dispatcher refuses further dispatch and the error
  message includes the current depth. If you legitimately need a deeper chain, file an
  issue with the loop's purpose; the cap exists because every prior unintentional loop
  has been a bug.
- **Translating a CI stack trace to a local repro.** Stack frames include absolute paths
  (e.g. `/home/runner/work/...` in CI). IDE `goto-file` actions won't resolve those paths
  on your local machine — strip the prefix and use a path-relative search (e.g.
  `rg ':42:' tools.ts`) to land on the line. Session logs are assumed to stay within the
  dev/CI trust boundary; this code does not redact paths.

### Debugging recipes

Copy-pasteable one-liners to skip the "what do I run?" step on a CI failure (replace
`<session-dir>` with the actual session directory under `logs/`):

```bash
# Find every failed tool dispatch in a session and show its name + error.
jq -r 'select(.successful == false) | "\(.toolName): \(.exceptionMessage)"' \
  <session-dir>/*ToolLog.json

# Tail the most recent stderr file for a specific script.
ls -t <session-dir>/subprocess_stderr_*.log | head -1 | xargs tail -100

# Find every dispatch of a specific tool by name.
jq -r 'select(.toolName == "fundAccount") | {successful, exceptionMessage}' \
  <session-dir>/*ToolLog.json
```

## Public surface

| Export                                | Kind      | Notes                                                                |
|---------------------------------------|-----------|----------------------------------------------------------------------|
| `trailblaze.tool(name, spec, handler)`| namespace | Register a tool. Also exported as a named `tool`.                    |
| `trailblaze.run(opts?)`               | namespace | Start the MCP server. Also exported as a named `run`.                |
| `fromMeta(meta)`                      | function  | Parse `_meta.trailblaze` into a `TrailblazeContext` (for custom paths; `trailblaze.tool` handlers already receive the parsed context as `ctx`). |
| `TrailblazeContext`                   | type      | Injected envelope: `{ sessionId, invocationId, device, memory, baseUrl?, runtime? }`. |
| `TrailblazeClient`                    | type      | Third handler arg. Exposes the typed `client.tools.<name>(args)` namespace. |
| `TrailblazeToolMap`                   | type      | Open interface mapping tool name → arg shape. Augmented by built-ins + per-trailmap `.d.ts` codegen. |
| `TrailblazeCallToolResult`            | type      | Resolved value from any `client.tools.<name>(args)` call. `{ success: true, textContent, errorMessage }`. |
| `TrailblazeDevice`                    | type      | `ctx.device`: `{ platform, widthPixels, heightPixels, driverType }`. |
| `RunOptions`, `TrailblazeToolHandler`, `TrailblazeToolSpec` | types | Supporting types for `run` / `tool`. |

## Consuming the package

Marked `private: true` while the SDK surface stabilizes — `package.json`'s
`main` / `exports` / `types` point at `./src/index.ts` and the package has
no build step. Authors consume via bun (the only supported JavaScript runtime);
in a Trailblaze workspace, `trailblaze check` materializes the SDK's `.d.ts`
rollup into `<workspace>/.trailblaze/sdk/dist/` and writes each trailmap's
`tools/tsconfig.json` to point at that bundle — no `bun install` step on the
consumer side. Publishing a proper `dist/` build with `.js` + `.d.ts` outputs
(and flipping `private` off) is a follow-up once the surface stabilizes.

## References

- Envelope + callback contract devlog:
  `docs/devlog/2026-04-22-scripting-sdk-envelope-migration.md`
- On-device transport devlog:
  `docs/devlog/2026-04-23-on-device-callback-channel.md`
- Conventions devlog:
  `docs/devlog/2026-04-20-scripted-tools-mcp-conventions.md`
