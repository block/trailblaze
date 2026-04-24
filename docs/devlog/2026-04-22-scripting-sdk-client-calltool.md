---
title: "Scripting SDK — client.callTool Round-Trip"
type: decision
date: 2026-04-22
---

# Scripting SDK — `client.callTool` Round-Trip

This lights up the callback round-trip from TypeScript back into Trailblaze.
The initial SDK landing already shipped the Kotlin-side endpoint, the envelope
injection, and the authoring surface; this landing adds the matching TS client
and the daemon-side guardrails ([envelope-migration devlog](2026-04-22-scripting-sdk-envelope-migration.md)
flagged four concerns for the callback landing to pin down).

## Scope

- `client: TrailblazeClient` becomes the third handler argument on
  `trailblaze.tool(name, spec, handler)`. Exposes a single method,
  `callTool(name, args): Promise<CallToolResult>`.
- Hand-written TS types mirror the Kotlin `@Serializable` shapes in
  [`JsScriptingCallbackContract.kt`](https://github.com/block/trailblaze). No
  codegen (D2 stays locked — proto deferred until action surface growth
  or a second language consumer justifies it).
- Daemon-side timeout + reentrance-depth cap enforced on
  `/scripting/callback`. Both have tests.
- Cached-vs-fresh `screenState` behaviour documented.
- One SDK-authored sample tool (`signUpNewUserSdk`) composes
  `generateTestUserSdk` via a callback end-to-end; CI exercises it.

Out of scope:

- Typed wrappers (`client.tap(...)`, `client.inputText(...)`). A future landing
  is locked as "deferred indefinitely" — the untyped `callTool(name, args)`
  surface is the lowest-common-denominator shape that works identically for
  Python / QuickJS consumers later.
- Proto / Wire / buf codegen — stays deferred.
- Cross-machine callbacks — the endpoint's loopback gate stays as-is.
- SDK registry publishing — `"private": true` stays for now.

## Design concerns pinned in this PR

### 1. Callback-dispatch timeout (daemon-side)

**Decision: 30 s default, overridable via the
`-Dtrailblaze.callback.timeoutMs=<ms>` JVM system property.**

Read at endpoint-register time. The endpoint wraps
`tool.execute(entry.executionContext)` in `withTimeout(...)`; a
`TimeoutCancellationException` converts to `CallToolResult(success =
false, errorMessage = "Callback timed out after ${N}ms")` instead of
letting the coroutine tree hang.

Why 30 s and not something tighter: a legitimate callback can dispatch
a `tapOnElementWithText` that waits for the UI to settle, which on
flaky emulators already eats 10–15 s. 30 s is generous enough that a
real tool won't hit it while still bounding a pathological hang into
something the outer agent timeout absorbs. Tests override via a lower
constant to keep the test runtime sane.

Why a system property and not a `register` parameter: every `register`
call site would otherwise have to plumb the value through. The
property keeps the contract easy to override in CI (`-D...`) and in
tests (`System.setProperty`) without touching the public
`register(routing: Routing)` shape. An env var was considered and
rejected — tests can't set env vars in-JVM on most platforms.

### 2. Reentrance depth cap

**Decision: cap at 16 per chain, matching Decision 038's script
execution limit.**

Tracked on `JsScriptingInvocationRegistry.Entry.depth`. The outer
invocation (registered from `SubprocessTrailblazeTool.execute` with no
parent) gets depth 0. When the callback endpoint dispatches a tool,
the depth-1 environment is propagated via a
`JsScriptingCallbackDispatchDepth` `CoroutineContext` element; if the dispatched
tool is itself a `SubprocessTrailblazeTool` that registers its own
invocation, it reads the element and stores `depth = parent + 1`.

Before dispatching, the endpoint rejects any callback whose resolved
`entry.depth >= MAX_CALLBACK_DEPTH` with
`CallToolResult(success=false, errorMessage = "Callback reentrance
depth ${d} exceeds max ${MAX}")`. That caps a buggy
`signUpNewUserSdk → generateTestUserSdk → signUpNewUserSdk` loop at 16
steps before the daemon refuses, long before the outer agent timeout
or stack exhaustion would fire.

Why 16 and not a smaller number: Decision 038's execution-model devlog
uses 16 for in-process QuickJS callbacks and the rationale (a realistic
composable tool rarely exceeds 3–4 levels; 16 is 4× over-provisioned
against that) is identical here. Keeping the two caps the same means
authors who move a tool between runtimes don't hit a surprise.

Why a depth cap and not a timeout-only approach: a fast-looping
reentrance cycle can burn thousands of iterations per second inside
the 30 s timeout before it fires. The depth cap surfaces the runaway
as an actionable error immediately, not after 30 s of log spam.

### 3. Cached-vs-fresh `screenState` on callbacks

**Decision: cached. The callback dispatches against the same
`TrailblazeToolExecutionContext` (and therefore the same cached
`screenState`) the outer handler is working with.**

Matches the outer handler's observation of the screen at the moment
of the subprocess `tools/call` — predictable, and composition chains
like "tap this → input that → tap again" already interleave their own
tool-side re-captures. No implementation change is needed for this
decision (the endpoint already passes `entry.executionContext`
unchanged); documenting it here so a future author doesn't assume a
fresh capture and get bit.

Fresh captures can be added later via an explicit
`trailblaze/refreshScreenState` tool authors opt into. Not in this PR.

### 4. Thread safety

No change required. The endpoint already dispatches against the
`entry.executionContext`, which owns its own memory + screen-state
mutability semantics from the outer dispatch. Reentrance depth is
propagated via `CoroutineContext` (structured, scoped to the dispatch
coroutine) rather than thread-local state.

## TS `TrailblazeClient` surface

```typescript
trailblaze.tool(
  "signUpNewUserSdk",
  { description: "..." },
  async (_args, ctx, client) => {
    const raw = await client.callTool("generateTestUserSdk", {});
    const user = JSON.parse(raw.textContent) as { name: string; email: string };
    // ... compose more callTool invocations ...
    return { content: [{ type: "text", text: `Signed up ${user.email}` }] };
  },
);
```

`client` is always passed (never `undefined` even when `ctx` is — an
undefined `ctx` means "no envelope", so a `callTool` will fail the
precondition check and throw a clear error). Authors that never
callback just don't reference the arg; the shape stays backward
compatible with handlers from the initial landing because TypeScript
lets handlers ignore trailing parameters.

The `client.callTool(...)` method:

- Reads `baseUrl`, `sessionId`, `invocationId` from the envelope-capturing
  closure in the SDK (captured at handler-entry via the same
  `extractMeta` path the `ctx` argument uses).
- POSTs JSON to `${baseUrl}/scripting/callback` with the
  `JsScriptingCallbackRequest` shape. 30 s fetch `AbortSignal` on the client side
  matches the daemon's default so a hung endpoint doesn't leak.
- Parses the response. On `JsScriptingCallbackResult.Error` throws a plain
  `Error(message)`. On `JsScriptingCallbackResult.CallToolResult(success: false, ...)`
  also throws (tool-level failure — subprocess authors almost always
  want this to bubble). On `success: true` returns the
  `{ success, textContent, errorMessage }` record so authors can read
  the tool's text output directly.

### Error semantics

Two failure modes map onto thrown JS errors:

1. **Protocol errors** (unknown invocation id, session mismatch,
   malformed body, unsupported version) → `JsScriptingCallbackResult.Error` from
   the daemon. Client throws with the daemon's message.
2. **Tool execution failures** (deserialization failure, tool threw,
   `TrailblazeToolResult.Error.*`) → `JsScriptingCallbackResult.CallToolResult(
   success = false, errorMessage = "...")`. Client throws with
   `errorMessage` so authors don't have to branch on a `.success`
   flag they'll forget about.

Success returns the `CallToolResult` record; the author reads
`result.textContent` and parses it however the tool it called
produces output. No typed wrapper on purpose (see scope).

## References
- Envelope migration & callback transport — [devlog](2026-04-22-scripting-sdk-envelope-migration.md)
- Authoring pitch — [authoring vision & roadmap](2026-04-22-scripting-sdk-authoring-vision.md)
- Execution model (reentrance cap lineage) — [scripted tools execution model](2026-04-20-scripted-tools-execution-model.md)
- Synchronous tool execution from JS — [devlog](2026-04-20-scripted-tools-a2-sync-execute.md)
