---
title: Author Your First Scripted Tool
---

# Author Your First Scripted Tool

This guide walks through authoring a custom Trailblaze tool as a TypeScript file that runs
in-process inside the Trailblaze daemon — no Kotlin, no Gradle build, no MCP subprocess.
Drop a `.ts` file into your pack, declare a sibling YAML descriptor, reference it from
`pack.yaml`, and the tool is callable from any trail the next time the daemon starts.

For the conceptual background on why scripted tools exist and how they fit into the agent
loop, see the [`@trailblaze/scripting` Authoring Vision](devlog/2026-04-22-scripting-sdk-authoring-vision.md)
devlog. This page is the operational walkthrough.

## What you'll write

A scripted tool is **three files** spread across two locations inside your repo:

```
trails/
└── config/
    ├── packs/
    │   └── myapp/
    │       ├── pack.yaml                      # references the descriptor
    │       └── tools/
    │           └── myapp_login.yaml           # the descriptor (name, schema)
    └── tools/
        └── myapp_login.ts                     # the implementation (TS source)
```

Trailblaze sees them as one tool:

1. **`pack.yaml`** lists the descriptor's pack-relative path under `target.tools:`.
2. **The descriptor YAML** points at the `.ts` source, declares the tool's `name:`, and
   defines its parameter contract.
3. **The `.ts` source** exports a function under the same name as the descriptor.

Both files live **co-located inside the owning pack** at
`trails/config/packs/<pack>/tools/`. The descriptor's `script:` field is resolved against
the JVM's working directory (the repo root), not the pack directory — so the conventional
path is the long form `./trails/config/packs/<pack>/tools/<tool>.ts`. Pack-relative
`script:` paths would force the daemon to materialize each pack to a scratch directory
before bundling, which it doesn't do today; the long path is the workaround until that
changes.

## 1. Write the `.ts` source

Per-file scripted tools are evaluated by an in-process QuickJS host, with esbuild
bundling each file before evaluation. The host imports your **named export** by the same
name you put in the YAML descriptor's `name:` field:

```ts
// trails/config/packs/myapp/tools/myapp_login.ts

const DEFAULT_APP_ID = "com.example.myapp";

/**
 * Sign into MyApp with the supplied credentials.
 *
 * Registered as `myapp_login` by the workspace `myapp` pack.
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

  // Compose with framework tools via `client.callTool(name, args)`. Anything in
  // the global tool registry is reachable — `adbShell`, `tapOnElementBySelector`,
  // `inputText`, etc. The shim throws on `{isError:true}` envelopes so failures
  // bubble out via try/catch; you don't have to inspect every result.
  await client.callTool("adbShell", {
    command: `am force-stop ${appId}`,
  });
  await client.callTool("adbShell", {
    command: `am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${appId}`,
  });

  await client.callTool("inputText", { text: args.email });
  await client.callTool("inputText", { text: args.password });
  await client.callTool("tapOnElement", { ref: "Sign In" });

  return `Signed in as ${args.email} on ${appId}.`;
}
```

A few hard rules the runtime enforces:

| Rule | Why |
|---|---|
| File extension is `.ts`, not `.js` / `.mjs` / `.cjs` | The daemon routes `.ts` to in-process QuickJS; the legacy extensions go through the MCP subprocess path (different transport, different lifecycle). |
| **Use JSDoc-only types**, never `:` parameter annotations or `import type` | Per-file scripted tools are evaluated as raw ECMAScript — no transpile step strips TS syntax today. Annotations get types in your editor without breaking the runtime. |
| The exported function name must match the descriptor's `name:` field exactly | The synthesized wrapper does `__userModule[<name>]` to find your handler. A mismatch surfaces a clear "must export a function with that exact name" error at first dispatch. |
| Return a string, an object, or `undefined` | The runtime normalizes string returns into the `{content: [{type:"text",...}]}` envelope automatically. Returning a function, BigInt, or circular structure throws a non-JSON-serializable error. |
| `throw new Error(...)` for failures | The thrown message becomes the tool's `errorMessage` field. The agent sees it; you see it in the trail report. |

## 2. Write the YAML descriptor

The YAML file declares the tool's name, description, and JSON-Schema-shaped parameter
contract. The pack loader translates a flat `inputSchema:` map into a fully conformant
JSON Schema for you — you don't write the `{type: object, properties: {...}, required: [...]}`
ceremony by hand.

```yaml
# trails/config/packs/myapp/tools/myapp_login.yaml
script: ./trails/config/packs/myapp/tools/myapp_login.ts
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
- **`script:` is resolved from the JVM working directory** (typically the repo root), not
  the pack directory. Write paths from the repo root.
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

See [`PackScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/PackScriptedToolFile.kt)
for the source of truth on field-level conventions.

## 3. Wire it into `pack.yaml`

Reference the descriptor from your pack manifest's `target.tools:` list:

```yaml
# trails/config/packs/myapp/pack.yaml
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

Each entry is a **pack-relative path** to the descriptor YAML — `tools/<name>.yaml` lives
inside the pack directory next to `pack.yaml`. The pack loader walks each
entry, decodes the descriptor, runs name validation, and registers the tool with the
target's tool registry at session start. A duplicate `name:` across two descriptors in the
same pack is a load-time error.

That's it — restart the daemon and the tool is dispatch-able from any trail that targets
the `myapp` pack.

## 4. IDE setup: typed bindings

Trailblaze auto-generates a per-target TypeScript declaration file that types every
`client.callTool(name, args)` call your scripted tool makes. Configure your IDE to pick it
up and you'll get autocomplete + compile-time error checking on tool names and arg shapes
— without any manual codegen step.

### Where the bindings come from

Every time the daemon starts (or you run `trailblaze compile`), Trailblaze emits one
`.d.ts` file per resolved target into `<workspace>/trails/config/tools/.trailblaze/`:

```
trails/config/tools/.trailblaze/
├── client.myapp.d.ts          # tools available to the `myapp` target
├── client.someothertarget.d.ts
└── ...
```

Each file declares typed overloads for:

- **Every framework tool** on the JVM classpath (`adbShell`, `tapOnElementBySelector`,
  `inputText`, `assertVisible`, etc.) — the same set across all targets, since these are
  the primitives any scripted tool can compose with.
- **The target's own scripted tools**, transitively resolved through the pack's
  `dependencies:` graph. So `client.myapp.d.ts` includes `myapp`'s scripted tools plus
  any tool it inherits from a library pack listed under `dependencies:` (e.g.
  framework-bundled `trailblaze` tools).

The split is intentional: per-target slicing keeps cross-target tool autocomplete
pollution out of your IDE (someone authoring `myapp` doesn't see `otherapp`'s tools), but
within a target's binding **every platform/driver variant of a tool is visible** so a
cross-platform conditional tool (`if (ctx.target.platform === "android") ...`) gets full
autocomplete on each branch.

### Wiring up your editor

Each pack that ships scripted tools should have a `package.json` next to its `tools/` dir
that depends on `@trailblaze/scripting` via a workspace-relative `file:` link to the SDK
that `trailblaze compile` extracts into `.trailblaze/sdk/typescript/`:

```json
// trails/config/packs/myapp/tools/package.json
{
  "name": "trailblaze-myapp-tools",
  "private": true,
  "type": "module",
  "dependencies": {
    "@trailblaze/scripting": "file:../../../tools/.trailblaze/sdk/typescript"
  },
  "devDependencies": {
    "@types/node": "^25",
    "typescript": "^6"
  }
}
```

And a `tsconfig.json` that includes the per-target binding:

```json
// trails/config/packs/myapp/tools/tsconfig.json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true,
    "types": ["@types/node"]
  },
  "include": [
    "**/*.ts",
    "../../../tools/.trailblaze/client.myapp.d.ts"
  ]
}
```

Replace `myapp` with your target's pack id. The `include` path walks up to the workspace's
generated bindings dir and pulls in just the slice for this pack's target.

### Setup gesture: one command

After cloning a workspace (or creating a new one with the templates above):

```bash
trailblaze compile
```

This single command:

1. Resolves the pack graph and emits `dist/targets/<id>.yaml` (existing behavior).
2. Extracts the framework's `@trailblaze/scripting` SDK into `.trailblaze/sdk/typescript/`
   (vendored from the trailblaze JAR).
3. Emits per-target `client.<id>.d.ts` typed bindings.
4. Runs `bun install` in every pack's `tools/` dir that has a `package.json` — populating
   `node_modules/@trailblaze/scripting` via the workspace-relative `file:` link from step 2.

After this, your IDE has full typing on `client.callTool(name, args)` and on any
`@trailblaze/scripting` imports. Re-run `trailblaze compile` whenever you upgrade trailblaze
or change your pack manifests.

**Requires bun.** Install via `curl -fsSL https://bun.sh/install | bash`. Bun is the
in-repo standard; using one supported tool keeps docs and error messages clean. If you
genuinely don't want bun involved (CI containers that handle `node_modules/` separately,
etc.), set `TRAILBLAZE_SKIP_NPM_INSTALL=1` to skip the install step — the SDK extraction
and bindings generation still run.

### Regeneration on every daemon command

Beyond explicit `trailblaze compile`, the daemon-init bootstrap fires the same setup pipeline
on every daemon-aware command (`trailblaze blaze` / `ask` / `verify` / `trail` / `session` /
`app start`). On a fresh clone it primes everything automatically; on subsequent runs the
hash check makes it a sub-millisecond no-op. Pack-graph drift (an edit to any `pack.yaml` or
the running framework version) trips the hash check and re-runs everything.

The daemon path differs from the explicit-compile path in one ergonomic way: it only runs
`bun install` in pack `tools/` dirs whose `node_modules/` is missing. This keeps subsequent
daemon commands fast — only the first run after a fresh clone pays the install cost.
Explicit `trailblaze compile` always re-runs `bun install` so you can use it to refresh
after a framework upgrade.

Both paths are idempotent: re-running with the same SDK + toolset writes byte-identical
output, so your file watcher and TypeScript language server don't churn.

### Should I commit the bindings?

Either choice works. The `.trailblaze/` directory is named after the framework so a
single `.gitignore` line (`trails/config/tools/.trailblaze/`) opts you out if you treat
the bindings as derived output. Committing them is also reasonable — the file is stable
across daemon restarts of the same toolset, and review can spot unintended API surface
changes.

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

Or invoke it directly from the CLI for a one-off:

```bash
trailblaze tool myapp_login -d android \
  email=test@example.com \
  password=hunter2 \
  -o "Sign in"
```

## Composing with framework tools via `client.callTool`

The `client` argument your handler receives is the bridge back into the framework's tool
registry. Anything Trailblaze knows about is reachable:

```ts
// Tap a Compose element on Android.
await client.callTool("tapOnElement", { ref: "Sign In" });

// Use a framework selector.
await client.callTool("tapOnElementBySelector", {
  selector: { textRegex: "ALARM" },
});

// Send an Android broadcast intent.
await client.callTool("android_sendBroadcast", {
  action: "com.example.RESET",
  componentPackage: "com.example.app",
  componentClass: "com.example.ResetReceiver",
  extras: {},
});
```

The shim:

- **Throws on failure.** If the inner call fails (the inner tool throws, the lookup misses,
  the binding is missing context), `client.callTool(...)` throws an `Error` with the
  underlying message. You can wrap it in `try/catch` if you want to recover.
- **Returns the result envelope on success.** That's `{content: [...]}` for tools that
  produce text, or whatever shape the inner tool defines.

## Framework primitives at a glance

Beyond UI tools (`tap`, `inputText`, `tapOnElementBySelector`, etc.), the framework ships
shell-/process-/device-shell primitives you compose from scripted tools. Three to know:

| Tool | Where it runs | When to use |
|---|---|---|
| **`adbShell`** | **Both — host *and* on-device** | The dual-mode device-shell primitive. Reaches `pm`, `am`, `setprop`, `dumpsys`, `input`, `getprop` — anything you'd type as `adb shell <cmd>`. On host the call routes through ADB; on-device it runs natively inside the instrumentation process. **Default choice** for Android shell composition. |
| **`android_sendBroadcast`** | **Both — host *and* on-device** | Structured `am broadcast` — argv-safe by construction (action / componentPackage / componentClass / extras as fields, no shell escaping). Use when the underlying intent is the load-bearing semantic, e.g. card-reader broadcasts, fake-data injection. |
| **`exec`** | **Host only** | Run a process in the host JVM environment via argv (no shell). Use for host-side scripts that talk to peripherals (card readers, USB devices), build-step invocation, host filesystem access. **Requires** `requiresHost: true` on your scripted tool's descriptor. |

### The host-only vs on-device-safe rule

A scripted tool's descriptor inherits the most restrictive deployment scope of the
framework primitives it composes:

- Composes only `adbShell`, `android_sendBroadcast`, UI tools, etc. → **on-device-safe**
  (no `requiresHost:` field needed; the on-device runner can dispatch your tool when the
  on-device QuickJS path lands).
- Composes `exec` (or any other tool with `@TrailblazeToolClass(requiresHost = true)`) →
  **host-only.** Add `requiresHost: true` to your tool's descriptor (top-level field —
  sugar for `_meta: { trailblaze/requiresHost: true }`). The on-device runner will skip
  the registration; host dispatch is the only path.

If you forget the flag and try to run on-device, the inner `client.callTool(...)` fails
with `Tool not registered: <name> (registered tools: ...)`. The error lists what *is*
available on-device — the host-only tool will be conspicuously missing.

### Picking between `adbShell` and `exec`

Both can run shell commands. The difference is *whose* shell:

- `adbShell` runs in the **device's** shell. It can clear the app's data, send intents,
  dump system services, query packages — anything the device's `pm` / `am` / `dumpsys`
  binaries can do.
- `exec` runs in the **host's** shell environment. It can talk to your Mac's filesystem,
  invoke local CLI tools you've installed (`./scripts/activate-card-reader.sh`), kick off
  a host-side build step.

If your script runs against device state, use `adbShell`. If it runs against your dev
machine, use `exec`.

### "Want full Node APIs / a different language / your own deps?"

When in-process scripted tools aren't enough — you need `node:fs`, `fetch`, persistent
state, your own dependencies, or a different language entirely — that's an **MCP
server's** job. Author a real MCP server (Node, Python, Go, anything that emits stdio
MCP), declare it in your pack manifest's `mcp_servers:` block with a `command:` to spawn
it, and Trailblaze talks the standard MCP protocol to it. Your tools become available
alongside framework tools the same way scripted tools do. The boundary is honest: you
own the runtime, we own the wire.

## Common errors and what they mean

| Error | What's wrong |
|---|---|
| `Invalid scripted-tool name 'foo bar' …` | Your descriptor's `name:` violates `^[A-Za-z_][A-Za-z0-9_.\-]*$`. Update it to a supported character set. |
| `Scripted tool myapp_login must export a function with that exact name. Found: undefined.` | Your `.ts` doesn't export a function under that name. Either rename the export or update the descriptor's `name:` to match. |
| `Tool not registered: foo (registered tools: alpha, beta)` | The tool you tried to `client.callTool(...)` doesn't exist in the runtime registry. The error lists what *is* registered — almost always you've got a typo. |
| `Tool not registered: foo (no tools are registered on this host …)` | The bundle loaded but didn't populate the registry. Verify your `.ts` exports the function under the same name as the descriptor. |
| `client.callTool('adbShell') failed: …` | The inner tool ran but failed (non-zero exit code, missing arg, etc.). The message after `failed:` is the inner tool's error. |
| `esbuild failed (exit 1) bundling scripted-tool source /path/to/myapp_login.ts` | Your `.ts` source has a syntax error or unresolved import. The full esbuild stderr follows in the same exception message. |
| `myapp_login requires a live Trailblaze session context.` | Your handler asserted `if (!ctx)` and `ctx` was undefined. This usually means the tool was invoked outside a session (a unit test that doesn't supply context, or a CLI invocation against a non-running session). |

## What's *not* available inside a scripted tool

The in-process QuickJS runtime is intentionally small — that's what makes it fast,
sandboxed, and free of toolchain dependencies. Specifically:

- **No `node:fs`, `node:child_process`, `node:os`, or other Node built-ins.** The QuickJS
  sandbox doesn't ship them. If you need filesystem access, child processes, or anything
  Node-flavored, the path forward is an MCP server (see the "Want full Node APIs" note in
  *Framework primitives at a glance* above).
- **No `fetch`, `XMLHttpRequest`, `WebSocket`.** No browser/HTTP globals.
- **No top-level `await` in the entry script.** Module-mode evaluation supports it, but
  the bundler emits IIFE format. Top-level `await` is fine inside `async` functions.
- **No `import` of other authored tools by relative path.** Each tool is bundled
  independently. Use `client.callTool(...)` to compose with framework tools (which
  includes other tools registered in the same pack).

If your tool needs anything in this list, that's the signal to write an MCP server
instead of an in-process scripted tool. The matrix is honest: in-process for typed
function-shaped composition over framework primitives, MCP server for everything else.

## End-to-end example in this repo

The `clock` pack ships a worked example that uses every concept in this guide:

- [`trails/config/packs/clock/tools/clock_android_launchApp.ts`](https://github.com/block/trailblaze/blob/main/trails/config/packs/clock/tools/clock_android_launchApp.ts) —
  the implementation, including JSDoc-only types, args resolution, and `adbShell`
  composition (the dual-mode primitive — works on host today and on-device when the
  on-device QuickJS path lights up).
- [`trails/config/packs/clock/tools/clock_android_launchApp.yaml`](https://github.com/block/trailblaze/blob/main/trails/config/packs/clock/tools/clock_android_launchApp.yaml) —
  the descriptor (co-located with the `.ts` source under the owning pack).
- [`trails/config/packs/clock/pack.yaml`](https://github.com/block/trailblaze/blob/main/trails/config/packs/clock/pack.yaml) —
  the pack manifest entry under `target.tools:`.
- [`trails/clock/set-alarm-730am/android.trail.yaml`](https://github.com/block/trailblaze/blob/main/trails/clock/set-alarm-730am/android.trail.yaml) —
  a trail that calls the tool.

Run the trail end-to-end on a connected emulator:

```bash
./trailblaze trail run trails/clock/set-alarm-730am/android.trail.yaml -d android
```

Watch the daemon log — you'll see the QuickJS dispatch fire, the inner `adbShell` calls
issue, and the trail proceed against the launched app.
