---
title: Scripted Tools — Legacy Reference
---

# Scripted Tools — Legacy Reference

> **Authoring a *new* tool?** Close this tab and read
> **[Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md)** instead. This
> page is only useful if you're maintaining trailmaps that still ship the older YAML
> + `export async function` pair, or migrating one to the typed surface (see
> [Migrating to the typed shape](#migrating-to-the-typed-shape) at the bottom).

**This page documents the legacy authoring shape** — one full `.yaml` descriptor
(carrying `name:`, `description:`, `inputSchema:`) paired with an `export async function`
handler in TypeScript. New scripted tools should be authored against the typed surface
documented in **[Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md)** —
declare inputs and result shape as TypeScript interfaces via
`trailblaze.tool<I, O>(spec, handler)`, the analyzer derives schema + description from
the `.ts` source, and no per-tool YAML is needed.

The legacy shape continues to work unmodified for trailmaps that haven't been migrated.
This page exists so authors maintaining those trailmaps have a self-contained reference.
The [migration recipe](#migrating-to-the-typed-shape) at the bottom is the path forward.

## The three files

A legacy scripted tool is a `(yaml, ts)` pair co-located inside one trailmap directory:

```
trails/config/
└── trailmaps/
    └── myapp/
        ├── trailmap.yaml                     # references the descriptor by name
        └── tools/
            ├── myapp_login.yaml              # the descriptor (name, schema)
            └── myapp_login.ts                # the implementation (TS source)
```

Trailblaze treats them as one tool:

1. **`trailmap.yaml`** lists the descriptor under `target.tools:` by **bare tool name**.
2. **The descriptor YAML** points at the `.ts` source, declares the tool's `name:`, and
   defines its parameter contract via `inputSchema:`.
3. **The `.ts` source** exports a function under the same name as the descriptor.

The descriptor's `script:` field is resolved **relative to the directory containing the
descriptor YAML** (see
[`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt)),
so the conventional value is `script: ./<tool>.ts` — sibling-relative.

## The `.ts` source

```ts
// trails/config/trailmaps/myapp/tools/myapp_login.ts

const DEFAULT_APP_ID = "com.example.myapp";

/**
 * Sign into MyApp with the supplied credentials.
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

Hard rules the runtime enforces:

| Rule | Why |
|---|---|
| File extension is `.ts`, not `.js` / `.mjs` / `.cjs` | The bundler hard-errors on non-`.ts` files under `tools/`. TypeScript is the only authoring language. |
| **Use JSDoc-only types**, never `:` parameter annotations or `import type` | Per-file scripted tools are evaluated as raw ECMAScript — no transpile step strips TS syntax. JSDoc annotations give types in your editor without breaking the runtime. |
| The exported function name must match the descriptor's `name:` exactly | A mismatch surfaces a `must export a function with that exact name` error at first dispatch. |
| Return a string, an object, or `undefined` | String returns are normalized into the `{content: [{type:"text",...}]}` envelope. Returning a function, BigInt, or circular structure throws a non-JSON-serializable error. |
| `throw new Error(...)` for failures | The thrown message becomes the tool's `errorMessage` field. The agent sees it. |

## The YAML descriptor

```yaml
# trails/config/trailmaps/myapp/tools/myapp_login.yaml
script: ./myapp_login.ts          # descriptor-relative — resolves to the sibling .ts
name: myapp_login
description: Sign into MyApp with the supplied credentials.
_meta:
  trailblaze/supportedPlatforms: [android]
  trailblaze/requiresContext: true
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

Field-level rules:

- **`name:` must match `^[A-Za-z_][A-Za-z0-9_.\-]*$`** (letters, digits, `_`, `-`, `.`,
  starting with a letter or `_`).
- **`script:` is resolved relative to the directory containing this descriptor**.
  Absolute paths pass through unchanged.
- **`_meta.trailblaze/supportedPlatforms:`** is the platform gate. Case-insensitive
  (`web`, `WEB`, `Web` all collapse to canonical form).
- **`inputSchema:` defaults to empty** when omitted. The trailmap loader translates the
  flat map into a `{type: object, properties: {...}, required: [...]}` JSON Schema.
- **`required: true` is the per-property default.** Set `required: false` to make a
  parameter optional.
- **`enum: [a, b, c]`** constrains a string parameter to a fixed set.

See [`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt)
for the field-level source of truth.

## Wire it into `trailmap.yaml`

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
    - myapp_login                 # bare tool name (matches descriptor `name:`)
```

A duplicate `name:` across two descriptors in the same trailmap is a load-time error.
See [Trailmaps](trailmaps.md) for the full manifest schema.

## Calling your tool from a trail

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
              password: Password123!
```

Or invoke it from the CLI:

```bash
trailblaze tool myapp_login \
  email=test@example.com \
  password=Password123! \
  -s "Sign in"
```

## Composing other tools via `client.tools.<name>(args)`

The `client` argument exposes typed access to every tool the trailmap can dispatch — the
trailmap's `platforms.<p>.tool_sets:` Kotlin primitives, sibling scripted tools listed
on `target.tools:`, and scripted tools inherited from `dependencies:` via their
`exports:` field.

```ts
await client.tools.android_adbShell({
  command: ["am", "force-stop", appId],
});

await client.tools.tapOnElementBySelector({
  selector: { textRegex: "Sign In" },
});
```

The shim throws on failure, returns the result envelope on success, and catches unknown
names at compile time via the per-trailmap `trailblaze-client.d.ts`. `client.callTool(...)`
exists as a wire-protocol primitive but is hidden from the public `TrailblazeClient`
type so author code can't bypass the typed surface.

## IDE typings, testing, runtime engine selection

These three topics are identical between the legacy and typed authoring paths — the
typed-authoring doc is the canonical reference:

- **IDE typings** — `trailblaze check` writes `<trailmap>/tools/trailblaze-client.d.ts`
  + a framework-managed `tsconfig.json` so `client.tools.<name>` autocompletes. See
  [Scripted Tools (TypeScript) — IDE typings](scripted-tools-typed-authoring.md#ide-typings-trailblaze-check).
- **Testing** — pair each tool with a sibling `*.test.ts` and run through the mock
  client + context. The legacy `(args, ctx, client)` signature is transparent to the
  test helpers. See [Scripted Tools (TypeScript) — Testing your tool](scripted-tools-typed-authoring.md#testing-your-tool).
- **Runtime engine** — QuickJS in-process is the default; set `_meta.trailblaze/requiresHost: true`
  on the descriptor (or use `runtime: subprocess`) to opt into the Bun subprocess
  runtime for Node-flavored APIs. See [Scripted Tools (TypeScript) — Runtime](scripted-tools-typed-authoring.md#runtime-quickjs-in-process-by-default).

## Common errors specific to the legacy shape

| Error | What's wrong |
|---|---|
| `Invalid scripted-tool name 'foo bar' …` | Your descriptor's `name:` violates `^[A-Za-z_][A-Za-z0-9_.\-]*$`. |
| `Scripted tool myapp_login must export a function with that exact name. Found: undefined.` | Your `.ts` doesn't export a function under the descriptor's `name:`. Rename either side to match. |
| `Tool not registered: foo (registered tools: alpha, beta)` | The dispatched name isn't in the runtime registry. Re-run `trailblaze check` to regenerate the typed surface and the registry. |

For broader errors (validation envelopes, missing typings, `esbuild` failures), see
[Scripted Tools (TypeScript) — Common errors](scripted-tools-typed-authoring.md#common-errors).

## Migrating to the typed shape

The typed surface is the recommended target for new tools, and existing legacy tools
flip file-by-file. The mechanical conversion:

**Before** (legacy):

```ts
import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

export interface MyappLoginArgs { /* ... */ }

export async function myapp_login(
  args: MyappLoginArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  if (!ctx) throw new Error("myapp_login requires a session.");
  // ... uses client.tools.X(args)
}
```

**After** (typed):

```ts
import { trailblaze } from "@trailblaze/scripting";

export interface MyappLoginArgs { /* unchanged */ }

export const myapp_login = trailblaze.tool<MyappLoginArgs>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (input, ctx) => {
    // requireSessionContext drops out — typed ToolContext is always provided.
    // ... uses ctx.tools.X(args)
  },
);
```

Three mechanical changes go with the rewrite:

- **The `.yaml` descriptor goes away.** `name:`, `description:`, `inputSchema:`, and
  `_meta:` are all derived from the typed `.ts` (interface fields → schema, TSDoc on
  `export const` → description, spec object → `_meta`).
- **Shared helpers take `ctx: ToolContext` directly.** No `requireSessionContext` guard
  needed; the typed context is always provided.
- **Existing tests keep working.** The adapter returned by `trailblaze.tool<I>` is still
  a 3-arg callable, transparent to test helpers that call
  `myTool(args, ctx, client)`.

See [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the full
authoring reference; the iOS Contacts and Wikipedia examples there each went through
this migration and are reference shapes to copy from.
