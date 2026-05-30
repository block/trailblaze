---
title: Scripted Tools — Legacy YAML + `export async function` Reference
---

# Scripted Tools — Legacy YAML + `export async function` Reference

**This is the legacy path.** New scripted tools should be authored against the typed
surface documented in [Typed Authoring for Scripted Tools](scripted-tools-typed-authoring.md):
declare inputs and result shapes as TypeScript interfaces via
`trailblaze.tool<I, O>(handler)`, and the analyzer derives the schema and description from
the `.ts` source directly. This page describes the older shape — one full `.yaml` descriptor
(carrying `name:`, `description:`, `inputSchema:`) paired with an `export async function`
handler — which continues to work for trailmaps that haven't been migrated, but is no
longer the recommended starting point. Read [Typed Authoring](scripted-tools-typed-authoring.md)
first if you're starting fresh.

The legacy shape still walks through everything that's true for any scripted tool —
trailmap layout, the `client.tools.<name>(args)` composition surface, the per-trailmap
typed bindings, unit testing with the mock client, the QuickJS vs subprocess runtime
choice. The parts that differ from the typed path are the handler signature
(`export async function foo(args, ctx, client)` instead of
`export const foo = trailblaze.tool<I, O>(...)`) and the YAML descriptor being the
source of truth for `name:` / `description:` / `inputSchema:` (instead of being derived
from the `.ts` declaration).

For the conceptual background on why scripted tools exist and how they fit into the agent
loop, see the [`@trailblaze/scripting` Authoring Vision](devlog/2026-04-22-scripting-sdk-authoring-vision.md)
devlog.

## What you'll write

A scripted tool is **three files** co-located inside one trailmap directory:

```
trails/
└── config/
    └── trailmaps/
        └── myapp/
            ├── trailmap.yaml                      # references the descriptor
            └── tools/
                ├── myapp_login.yaml               # the descriptor (name, schema)
                └── myapp_login.ts                 # the implementation (TS source)
```

Trailblaze sees them as one tool:

1. **`trailmap.yaml`** lists the descriptor's trailmap-relative path under `target.tools:`.
2. **The descriptor YAML** points at the `.ts` source, declares the tool's `name:`, and
   defines its parameter contract.
3. **The `.ts` source** exports a function under the same name as the descriptor.

The descriptor and its `.ts` source live side-by-side under the owning trailmap at
`trails/config/trailmaps/<trailmap>/tools/`. The descriptor's `script:` field is resolved
**relative to the directory containing the descriptor YAML** (see
[`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt)
for the resolution rule), so the conventional value is `script: ./<tool>.ts` — sibling-relative,
no long path needed. This keeps each trailmap a self-contained directory that can be zipped or
published as-is. Absolute paths also work and pass through unchanged.

## 1. Write the `.ts` source

Per-file scripted tools are evaluated by an in-process QuickJS host, with esbuild
bundling each file before evaluation. The host imports your **named export** by the same
name you put in the YAML descriptor's `name:` field:

```ts
// trails/config/trailmaps/myapp/tools/myapp_login.ts

const DEFAULT_APP_ID = "com.example.myapp";

/**
 * Sign into MyApp with the supplied credentials.
 *
 * Registered as `myapp_login` by the workspace `myapp` trailmap.
 *
 * @param {{ email: string; password: string; appId?: string }} args
 * @param {import("@trailblaze/scripting").TrailblazeContext | undefined} ctx
 * @param {import("@trailblaze/scripting").TrailblazeClient} client
 * @returns {Promise<string>}
 */
export async function myapp_login(args, ctx, client) {
  if (!ctx) {
    throw new Error("myapp_login requires a live Trailblaze session context.");
  }
  const appId = args.appId || DEFAULT_APP_ID;

  // Compose with framework tools via the typed `client.tools.<name>(args)` surface.
  // Every tool the trailmap can dispatch — Kotlin built-ins, trailmap-local scripted tools,
  // tools inherited via deps' `exports:` — is a method on `client.tools`. The shim
  // throws on `{isError:true}` envelopes so failures bubble out via try/catch; you
  // don't have to inspect every result.
  await client.tools.android_adbShell({
    command: ["am", "force-stop", appId],
  });
  await client.tools.android_adbShell({
    command: [
      "am", "start",
      "-a", "android.intent.action.MAIN",
      "-c", "android.intent.category.LAUNCHER",
      "-p", appId,
    ],
  });

  await client.tools.inputText({ text: args.email });
  await client.tools.inputText({ text: args.password });
  await client.tools.tapOnElement({ ref: "Sign In" });

  return `Signed in as ${args.email} on ${appId}.`;
}
```

A few hard rules the runtime enforces:

| Rule | Why |
|---|---|
| File extension is `.ts`, not `.js` / `.mjs` / `.cjs` | `TrailblazeTrailmapBundler` hard-errors on `.js` / `.mjs` / `.cjs` files under a trailmap's `tools/` — TypeScript is the only authoring language. The runtime (QuickJS in-process by default, Bun subprocess opt-in) is unaffected. |
| **Use JSDoc-only types**, never `:` parameter annotations or `import type` | Per-file scripted tools are evaluated as raw ECMAScript — no transpile step strips TS syntax today. Annotations get types in your editor without breaking the runtime. |
| The exported function name must match the descriptor's `name:` field exactly | The synthesized wrapper does `__userModule[<name>]` to find your handler. A mismatch surfaces a clear "must export a function with that exact name" error at first dispatch. |
| Return a string, an object, or `undefined` | The runtime normalizes string returns into the `{content: [{type:"text",...}]}` envelope automatically. Returning a function, BigInt, or circular structure throws a non-JSON-serializable error. |
| `throw new Error(...)` for failures | The thrown message becomes the tool's `errorMessage` field. The agent sees it; you see it in the trail report. |

## 2. Write the YAML descriptor

The YAML file declares the tool's name, description, and JSON-Schema-shaped parameter
contract. The trailmap loader translates a flat `inputSchema:` map into a fully conformant
JSON Schema for you — you don't write the `{type: object, properties: {...}, required: [...]}`
ceremony by hand.

```yaml
# trails/config/trailmaps/myapp/tools/myapp_login.yaml
script: ./myapp_login.ts        # descriptor-relative — resolves to the sibling .ts
name: myapp_login
description: Sign into MyApp with the supplied credentials.
supportedPlatforms:
  - android
inputSchema:
  email:
    type: string
    description: Email to enter into the login form.
  password:
    type: string
    description: Password to enter into the login form.
  appId:
    type: string
    description: Optional Android package id; defaults to com.example.myapp.
    required: false
```

Field-level rules to know:

- **`name:` must match `^[A-Za-z_][A-Za-z0-9_.\-]*$`** (letters, digits, `_`, `-`, `.`,
  starting with a letter or `_`). Spaces, quotes, control chars, and leading-digit
  identifiers are rejected at YAML decode time with an error pointing at this descriptor.
- **`script:` is resolved relative to the directory containing this descriptor** — i.e.
  trailmap-relative. A descriptor at `trailmaps/myapp/tools/myTool.yaml` declaring
  `script: ./myTool.ts` finds its source at `trailmaps/myapp/tools/myTool.ts`. Absolute
  paths pass through unchanged.
- **`supportedPlatforms:` is a top-level shortcut** — sugar for
  `_meta: { trailblaze/supportedPlatforms: [...] }`. Most authors should write the
  top-level field. The `_meta:` block is still available as an escape hatch for
  arbitrary keys but rarely needed. Values are case-insensitive (`web`, `WEB`,
  and `Web` all collapse to canonical form at parse time).
- **`inputSchema:` defaults to empty when omitted.** Tools that take no arguments
  don't need to write `inputSchema: {}` explicitly.
- **`required: true` is the default** for each `inputSchema` property — set
  `required: false` to mark a parameter optional.
- **`enum: [a, b, c]`** constrains a string parameter to a fixed set. Empty enum arrays are
  rejected (JSON Schema requires at least one value).

See [`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt)
for the source of truth on field-level conventions.

## 3. Wire it into `trailmap.yaml`

Reference the descriptor from your trailmap manifest's `target.tools:` list:

```yaml
# trails/config/trailmaps/myapp/trailmap.yaml
id: myapp
dependencies:
  - trailblaze
target:
  display_name: MyApp
  platforms:
    android:
      app_ids: [com.example.myapp]
  tools:
    - tools/myapp_login.yaml
```

Each entry is a **trailmap-relative path** to the descriptor YAML — `tools/<name>.yaml` lives
inside the trailmap directory next to `trailmap.yaml`. The trailmap loader walks each
entry, decodes the descriptor, runs name validation, and registers the tool with the
target's tool registry at session start. A duplicate `name:` across two descriptors in the
same trailmap is a load-time error.

That's it — restart the daemon and the tool is dispatch-able from any trail that targets
the `myapp` trailmap.

## 4. IDE setup: typed bindings

Trailblaze auto-generates a per-trailmap TypeScript declaration file that types every
`client.tools.<name>(args)` call your scripted tool makes. Configure your IDE to pick it
up and you'll get autocomplete + compile-time error checking on tool names and arg shapes
— without any manual codegen step. An unknown tool name is a compile error.

### Where the bindings come from

Every time the daemon starts (or you run `trailblaze check`), Trailblaze emits one
`trailblaze-client.d.ts` file per trailmap at `<trailmapDir>/tools/trailblaze-client.d.ts`:

```
trails/config/trailmaps/myapp/tools/
└── trailblaze-client.d.ts   # tools available to the `myapp` trailmap's TS authors
```

Each file declares typed overloads for:

- **Kotlin framework tools** resolved from the trailmap's OWN `platforms.<p>.tool_sets:`
  declarations (trailmap-local, not transitive). E.g. a trailmap declaring `web_core` sees
  `web_navigate` / `web_click` / siblings.
- **Trailmap-local scripted tools** declared on the trailmap's own `target.tools:` list.
- **Transitively-inherited scripted tools** that the trailmap's dependencies publish via the
  `exports:` field on their manifests. Tools NOT listed in a dep's `exports:` stay
  internal to that dep — invisible to consumers' typed surface.

Per-trailmap slicing keeps cross-trailmap autocomplete pollution out of your IDE (someone
authoring `myapp` doesn't see `otherapp`'s tools), while still showing every platform/
driver variant within the trailmap's own toolset declarations.

### Wiring up your editor

**Nothing to hand-author.** `trailblaze check` writes the per-trailmap
`tools/tsconfig.json` for you as a framework-managed artifact (and the trailmap-root
`.gitignore` that hides it from `git status`). The generated tsconfig is fully
self-contained — every compiler option is inlined, and the only workspace-relative
reference is the `paths` mapping pointing at the rolled-up SDK declaration bundle
at `<workspace>/.trailblaze/sdk/dist/index.d.ts`. That self-contained shape is
what lets a trailmap be npm-distributed and installed into a different workspace's
`node_modules/` — the next `trailblaze check` re-derives the `paths` mapping
at the new location.

The generated file looks like this — the `// FRAMEWORK-GENERATED` banner is
load-bearing (the emitter uses it to detect framework-owned files vs. hand-authored
overrides on upgrade), and the `include` glob covers both `.ts` and `.js` so
JavaScript tool sources inherit the same typing:

```jsonc
// trails/config/trailmaps/myapp/tools/tsconfig.json
// FRAMEWORK-GENERATED. Author should not edit. Regenerated by `trailblaze check`.
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "lib": ["ES2022"],
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "noEmit": true,
    "allowJs": true,
    "checkJs": false,
    "paths": {
      "@trailblaze/scripting": ["../../../../.trailblaze/sdk/dist/index.d.ts"],
      "@trailblaze/scripting/*": ["../../../../.trailblaze/sdk/dist/*"]
    }
  },
  "include": ["**/*.ts", "**/*.js", ".trailblaze/**/*"]
}
```

The `include` glob also picks up the per-trailmap `trailblaze-client.d.ts` the emitter writes to
`tools/trailblaze-client.d.ts` — that's how the typed `client.tools.<name>(args)`
surface lands in the language server.

**Upgrading from a workspace that hand-authored `tools/tsconfig.json`?** Delete
your existing `tools/tsconfig.json` and re-run `trailblaze check`. The emitter
preserves any file lacking the framework banner verbatim (and logs a one-line
warning per trailmap), so you won't lose customizations until you opt in, but you
also won't get the latest framework wiring until you do.

### Setup gesture: one command

After cloning a workspace (or creating a new one with the templates above):

```bash
trailblaze check
```

This single command:

1. Resolves the trailmap graph and emits `dist/targets/<id>.yaml` (existing behavior).
2. Extracts the framework's `@trailblaze/scripting` declaration bundle into
   `<workspace>/.trailblaze/sdk/dist/index.d.ts` — a single self-contained `.d.ts`
   (zod types inlined) vendored from the trailblaze JAR.
3. Emits per-trailmap typed bindings at `<trailmapDir>/tools/trailblaze-client.d.ts` — one file
   per trailmap, scoped to that trailmap's trailmap-local `tool_sets:` + scripted tools + transitively
   inherited `exports:`.
4. Writes `<trailmapDir>/tools/tsconfig.json` + `<trailmapDir>/.gitignore` as framework-managed
   artifacts so authors don't have to hand-author or maintain TypeScript config files.
   The tsconfig is fully self-contained (compiler options + paths inlined), so a trailmap
   that's npm-installed into a different workspace just needs `trailblaze check` to
   re-derive its `paths` entry — no broken `extends:` chain to repair.

After this, your IDE has full typing on `client.tools.<name>(args)` and on any
`@trailblaze/scripting` imports. Re-run `trailblaze check` whenever you upgrade trailblaze
or change your trailmap manifests.

**No `bun install` required.** The SDK is delivered as a single rolled-up `.d.ts` resolved
through the per-trailmap tsconfig's `paths` mapping, not through `node_modules`. Per-trailmap
`package.json` files are no longer needed for type-checking — and per-trailmap `tsconfig.json`
is now a framework-managed artifact too, so authors have zero TypeScript ceremony files
to maintain.

### Regeneration on every daemon command

Beyond explicit `trailblaze check`, the daemon-init bootstrap fires the same setup pipeline
on every daemon-aware command (`trailblaze step` / `ask` / `verify` / `trail` / `session` /
`app start`). On a fresh clone it primes everything automatically; on subsequent runs the
hash check makes it a sub-millisecond no-op. Trailmap-graph drift (an edit to any `trailmap.yaml` or
the running framework version) trips the hash check and re-runs everything.

Both paths are idempotent: re-running with the same SDK + toolset writes byte-identical
output, so your file watcher and TypeScript language server don't churn.

### Should I commit the bindings?

Either choice works. The `.trailblaze/` directory is named after the framework so a
single `.gitignore` line covers both the workspace-level (`trails/.trailblaze/`) and
per-trailmap (`trails/config/trailmaps/*/tools/.trailblaze/`) outputs if you treat them as
derived. Committing them is also reasonable — the files are stable across daemon
restarts of the same toolset, and review can spot unintended API surface changes.

## Calling your tool from a trail

Use it in a trail's recording block exactly like a built-in tool:

```yaml
# trails/myapp/login/android.trail.yaml
- config:
    id: "myapp/login"
    target: myapp

- prompts:
    - step: Sign in to MyApp
      recording:
        tools:
          - myapp_login:
              email: test@example.com
              password: hunter2
```

Or invoke it directly from the CLI for a one-off (shell pinned via `eval $(trailblaze device connect android --target myapp)`):

```bash
trailblaze tool myapp_login \
  email=test@example.com \
  password=hunter2 \
  -s "Sign in"
```

For CI / scripts that prefer explicit flags, add `-d android` to the call as a per-invocation override.

## Testing your tool

Pair every `.ts` tool with a sibling `*.test.ts` file. Tests run as the third phase of
`trailblaze check` (after materialize + tsc) — there's no separate test subcommand:

```bash
./trailblaze check myapp        # one trailmap: materialize + tsc + tests
./trailblaze check --all        # every trailmap in the discovered workspace
```

The test phase shells out to `bun test` against each trailmap's `tools/` directory, so
tests execute **without a daemon, device, or MCP roundtrip** — they import the tool
function directly and drive it through a mock client. A failing assertion gives you a
sub-second feedback loop that complements the slower "run on a real device"
validation. (If you want only the test phase, pass `--no-typecheck` to skip tsc — the
materialize step plus tests still run.)

### The mock client + context

The companion `@trailblaze/scripting/testing` subpath exports two helpers that satisfy
the `(args, ctx, client)` handler signature:

- **`createMockClient()`** — returns a `MockTrailblazeClient` whose `tools` proxy
  records every `client.tools.X(args)` invocation into `client.calls`. Tests assert
  call order and per-call arg shapes against that array. `client.stub(toolName, {
  textContent, errorMessage })` registers a canned response for a tool name — a
  non-empty `errorMessage` makes the call throw with the same wording the production
  client uses, which is how you exercise `try/catch` recovery branches.
- **`createMockContext({ platform, sessionId?, target?, memory? })`** — returns a
  `TrailblazeContext` with a no-op logger and sensible test defaults. Use it as the
  second argument when the tool reads `ctx.sessionId`, `ctx.device.platform`, or
  similar.

### Minimal example

```ts
// trails/config/trailmaps/myapp/tools/myapp_login.test.ts
import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { myapp_login } from "./myapp_login";

describe("myapp_login", () => {
  test("dispatches tapOnElement then inputText with the email arg verbatim", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "android" });

    await myapp_login(
      { email: "trailblaze@example.com", password: "hunter2" },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "tapOnElement",
      "inputText",
      "inputText",
      "tapOnElement",
    ]);
    expect(client.calls[1]?.args).toMatchObject({ text: "trailblaze@example.com" });
  });

  test("retries with the fallback selector when the email field isn't visible", async () => {
    const client = createMockClient();
    // First `inputText` call throws; the tool's catch branch should fall back to a
    // selector-based tap before retrying.
    client.stub("inputText", { textContent: "", errorMessage: "Element not found" });
    const ctx = createMockContext({ platform: "android" });

    await expect(
      myapp_login({ email: "x@y", password: "z" }, ctx, client),
    ).rejects.toThrow(/tool failed: Element not found/);

    // The recovery branch ran even though the eventual throw propagated.
    expect(client.calls.some((c) => c.tool === "tapOnElementBySelector")).toBe(true);
  });
});
```

### Preconditions

- **`bun` on PATH.** Install from https://bun.sh — `bun test` is the runner.
- **`tools/tsconfig.json`.** Emitted by `./trailblaze check` (runs automatically as
  part of `./gradlew check`, which `build` depends on). Without it, `bun test` fails
  with a module-resolution error on `@trailblaze/scripting/testing`. The CLI's
  pre-flight surfaces a directed error pointing you back to `./trailblaze check`.

### Patterns to copy

The canonical example trailmaps ship working `.test.ts` files next to real tools — copy
the shape, swap in your tool. From the `block/trailblaze` repo:

- [`playwrightSample_web_openFixtureAndVerifyText.test.ts`](https://github.com/block/trailblaze/blob/main/examples/playwright-native/trails/config/trailmaps/playwrightSample/tools/playwrightSample_web_openFixtureAndVerifyText.test.ts)
  — single-tool sequence with order + arg-shape assertions and a defaults test.
- [`playwrightSample_web_searchProductsAndOpenResult.test.ts`](https://github.com/block/trailblaze/blob/main/examples/playwright-native/trails/config/trailmaps/playwrightSample/tools/playwrightSample_web_searchProductsAndOpenResult.test.ts)
  — five-call workflow with interpolated-selector assertions.
- [`playwrightSample_web_openFormIfNeeded.test.ts`](https://github.com/block/trailblaze/blob/main/examples/playwright-native/trails/config/trailmaps/playwrightSample/tools/playwrightSample_web_openFormIfNeeded.test.ts)
  — `client.stub(name, { errorMessage })` driving a `try/catch` recovery branch.
- [`sampleapp_writeArtifact.test.ts`](https://github.com/block/trailblaze/blob/main/examples/android-sample-app/trails/config/trailmaps/sampleapp/tools/sampleapp_writeArtifact.test.ts)
  — host-only tool exercised against real `node:fs` writes; demonstrates the
  client-less `(args, ctx)` signature and argument-default coverage.

For the design rationale and the SDK-internal pieces, see the devlog
[Unit-testing scripted tools without a device](devlog/2026-05-21-scripted-tool-unit-testing.md).

## Composing with framework tools via `client.tools.<name>(args)`

The `client` argument your handler receives is the bridge back into the framework's tool
registry. The trailmap's **typed surface** — its own `platforms.<p>.tool_sets:` plus its
own scripted tools plus the scripted tools its dependencies publish via `exports:` — is
exposed as a method on `client.tools`. (A dep's internal helper that isn't in its
`exports:` lives in the runtime registry but is not on the typed surface, so it can't
be dispatched from this trailmap's authored code.) Example calls:

```ts
// Tap a Compose element on Android.
await client.tools.tapOnElement({ ref: "Sign In" });

// Use a framework selector.
await client.tools.tapOnElementBySelector({
  selector: { textRegex: "ALARM" },
});

// Send an Android broadcast intent.
await client.tools.android_sendBroadcast({
  action: "com.example.RESET",
  componentPackage: "com.example.app",
  componentClass: "com.example.ResetReceiver",
  extras: [],
});
```

The shim:

- **Throws on failure.** If the inner call fails (the inner tool throws, the lookup misses,
  the binding is missing context), `client.tools.<name>(...)` throws an `Error` with the
  underlying message. You can wrap it in `try/catch` if you want to recover.
- **Returns the result envelope on success.** That's `{content: [...]}` for tools that
  produce text, or whatever shape the inner tool defines.
- **Catches unknown names at compile time.** An unrecognised method on `client.tools`
  is a `tsc` error. `client.callTool(name, args)` still exists as the wire-protocol
  primitive the framework dispatches through, but it is hidden from the public
  `TrailblazeClient` type so author code can't bypass the typed surface.

## Framework primitives at a glance

Beyond UI tools (`tap`, `inputText`, `tapOnElementBySelector`, etc.), the framework ships
shell-/process-/device-shell primitives you compose from scripted tools. Three to know:

| Tool | Where it runs | When to use |
|---|---|---|
| **`android_adbShell`** | **Both — host *and* on-device** | The dual-mode device-shell primitive. Reaches `pm`, `am`, `setprop`, `dumpsys`, `input`, `getprop` — anything you'd type as `adb shell <cmd>`. On host the call routes through ADB; on-device it runs natively inside the instrumentation process. **Default choice** for Android shell composition. |
| **`android_sendBroadcast`** | **Both — host *and* on-device** | Structured `am broadcast` — argv-safe by construction (action / componentPackage / componentClass / extras as fields, no shell escaping). Use when the underlying intent is the load-bearing semantic, e.g. card-reader broadcasts, fake-data injection. |
| **`exec`** | **Host only** | Run a process in the host JVM environment via argv (no shell). Use for host-side scripts that talk to peripherals (card readers, USB devices), build-step invocation, host filesystem access. **Requires** `requiresHost: true` on your scripted tool's descriptor. |

### The host-only vs on-device-safe rule

A scripted tool's descriptor inherits the most restrictive deployment scope of the
framework primitives it composes:

- Composes only `android_adbShell`, `android_sendBroadcast`, UI tools, etc. → **on-device-safe**
  (no `requiresHost:` field needed; the on-device runner can dispatch your tool when the
  on-device QuickJS path lands).
- Composes `exec` (or any other tool with `@TrailblazeToolClass(requiresHost = true)`) →
  **host-only.** Add `requiresHost: true` to your tool's descriptor (top-level field —
  sugar for `_meta: { trailblaze/requiresHost: true }`). The on-device runner will skip
  the registration; host dispatch is the only path.

If you forget the flag and try to run on-device, the inner `client.tools.<name>(...)`
dispatch fails with `Tool not registered: <name> (registered tools: ...)`. The error
lists what *is* available on-device — the host-only tool will be conspicuously missing.

### Picking between `android_adbShell` and `exec`

Both can run shell commands. The difference is *whose* shell:

- `android_adbShell` runs in the **device's** shell. It can clear the app's data, send intents,
  dump system services, query packages — anything the device's `pm` / `am` / `dumpsys`
  binaries can do.
- `exec` runs in the **host's** shell environment. It can talk to your Mac's filesystem,
  invoke local CLI tools you've installed (`./scripts/activate-card-reader.sh`), kick off
  a host-side build step.

If your script runs against device state, use `android_adbShell`. If it runs against your dev
machine, use `exec`.

### "Want full Node-compatible APIs?"

When in-process scripted tools aren't enough — you need `node:fs`, `fetch`, persistent
state, or other Node-compatible APIs that Bun exposes — route the tool through a bun
subprocess. Two ways to opt in:

- **Explicit override:** set `runtime: subprocess` on the descriptor. Works for any script
  extension; recommended for `.ts` authors who want Node-compatible APIs.
- **Extension heuristic:** name the entrypoint `.js` / `.mjs` / `.cjs`. The default
  routing sends those extensions to the subprocess runtime; everything else (notably
  `.ts`) defaults to in-process QuickJS.

Authoring stays in TypeScript with the same `@trailblaze/scripting` SDK; only the
dispatch engine changes (QuickJS in-process vs. bun subprocess on host). Pair the
routing choice with `requiresHost: true` if the tool also needs to be hidden from
on-device sessions — `requiresHost` is the on-device visibility gate, not the runtime
selector.

## Common errors and what they mean

| Error | What's wrong |
|---|---|
| `Invalid scripted-tool name 'foo bar' …` | Your descriptor's `name:` violates `^[A-Za-z_][A-Za-z0-9_.\-]*$`. Update it to a supported character set. |
| `Scripted tool myapp_login must export a function with that exact name. Found: undefined.` | Your `.ts` doesn't export a function under that name. Either rename the export or update the descriptor's `name:` to match. |
| `Tool not registered: foo (registered tools: alpha, beta)` | The tool you tried to dispatch via `client.tools.foo(...)` doesn't exist in the runtime registry. The error lists what *is* registered — almost always you've got a typo. (A name not in the typed surface would normally surface as a `tsc` error first; this runtime error means the surface and runtime are out of sync — re-run `trailblaze check`.) |
| `Tool not registered: foo (no tools are registered on this host …)` | The bundle loaded but didn't populate the registry. Verify your `.ts` exports the function under the same name as the descriptor. |
| `client.callTool('android_adbShell') failed: …` | The inner tool ran but failed (non-zero exit code, missing arg, etc.). The error wording carries the wire-protocol name (`callTool`) even though the author surface is `client.tools.android_adbShell({...})`. The message after `failed:` is the inner tool's error. |
| `esbuild failed (exit 1) bundling scripted-tool source /path/to/myapp_login.ts` | Your `.ts` source has a syntax error or unresolved import. The full esbuild stderr follows in the same exception message. |
| `myapp_login requires a live Trailblaze session context.` | Your handler asserted `if (!ctx)` and `ctx` was undefined. This usually means the tool was invoked outside a session (a unit test that doesn't supply context, or a CLI invocation against a non-running session). |

## What's *not* available inside a scripted tool

The in-process QuickJS runtime is intentionally small — that's what makes it fast,
sandboxed, and free of toolchain dependencies. Specifically:

- **No `node:fs`, `node:child_process`, `node:os`, or other Node built-ins.** The QuickJS
  sandbox doesn't ship them. If you need filesystem access, child processes, or anything
  Node-flavored, set `runtime: subprocess` on the descriptor (or use a `.js`/`.mjs`/`.cjs`
  entrypoint) — see the "Want full Node-compatible APIs" note in *Framework primitives at
  a glance* above. The framework spawns a bun subprocess and the handler runs there with
  the full Node-compatible API surface that Bun implements.
- **No `fetch`, `XMLHttpRequest`, `WebSocket`.** No browser/HTTP globals.
- **No top-level `await` in the entry script.** Module-mode evaluation supports it, but
  the bundler emits IIFE format. Top-level `await` is fine inside `async` functions.
- **No `import` of other authored tools by relative path.** Each tool is bundled
  independently. Use `client.tools.<name>(args)` to compose with framework tools (which
  includes other tools registered in the same trailmap).

If your tool needs anything in this list, that's the signal to opt into the subprocess
runtime (via `runtime: subprocess` on the descriptor, or a `.js`/`.mjs`/`.cjs` entrypoint)
instead of the in-process QuickJS runtime. The matrix is honest: in-process for typed
function-shaped composition over framework primitives, host bun subprocess for
everything that needs the full Node-compatible API surface.

## End-to-end example in this repo

The `clock` trailmap ships a worked example that uses every concept in this guide:

- [`trails/config/trailmaps/clock/tools/clock_android_launchApp.ts`](https://github.com/block/trailblaze/blob/main/trails/config/trailmaps/clock/tools/clock_android_launchApp.ts) —
  the implementation, including JSDoc-only types, args resolution, and `android_adbShell`
  composition (the dual-mode primitive — works on host today and on-device when the
  on-device QuickJS path lights up).
- [`trails/config/trailmaps/clock/tools/clock_android_launchApp.yaml`](https://github.com/block/trailblaze/blob/main/trails/config/trailmaps/clock/tools/clock_android_launchApp.yaml) —
  the descriptor (co-located with the `.ts` source under the owning trailmap).
- [`trails/config/trailmaps/clock/trailmap.yaml`](https://github.com/block/trailblaze/blob/main/trails/config/trailmaps/clock/trailmap.yaml) —
  the trailmap manifest entry under `target.tools:`.
- [`trails/clock/set-alarm-730am/android.trail.yaml`](https://github.com/block/trailblaze/blob/main/trails/clock/set-alarm-730am/android.trail.yaml) —
  a trail that calls the tool.

Run the trail end-to-end on a connected emulator:

```bash
./trailblaze run trails/clock/set-alarm-730am/android.trail.yaml -d android
```

Watch the daemon log — you'll see the QuickJS dispatch fire, the inner `android_adbShell` calls
issue, and the trail proceed against the launched app.
