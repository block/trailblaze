---
title: Your First Trailmap
---

# Your First Trailmap

This page walks you from an empty workspace to a working custom tool — `myapp_login` —
that your agent can call by name and your CI can replay deterministically.

You'll write one TypeScript file. The TypeScript interface on its input becomes the
JSON Schema the agent sees; the TSDoc above the export becomes the tool's
LLM-facing description; the export name becomes the tool name. No per-tool YAML
descriptor, no `package.json` to hand-author, no `tsconfig.json`. The full per-tool
reference lives at
[Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md); the
[Trailmaps guide](trailmaps.md) covers the surrounding manifest, dependency graph, and
discovery rules.

This is the **third rung of the adoption ladder** from the
[Introduction](index.md): you've driven a device with the CLI, saved and replayed a
session, and now you're giving your agent first-class commands like `login` or
`addToCart` that record, replay, and type-check the same as `tap` or `inputText`.

## Prerequisites

- The **`trailblaze` CLI** on your `PATH`. If you don't have it, follow
  [Getting Started](getting_started.md) first.
- **`bun`** on your `PATH`. Trailblaze runs bun to read the types out of your `.ts` tools
  and generate the IDE bindings, so `trailblaze check` needs it. If you installed
  `trailblaze` via Homebrew you already have it — bun is pulled in as a dependency;
  otherwise install it from [bun.sh](https://bun.sh). You do **not** run `bun install` or
  keep a `node_modules/`: the SDK and the analyzer both ship inside the CLI.
- A device or browser the CLI can drive — an Android emulator, an iOS simulator, or a
  local browser via the Playwright driver. The CLI's `trailblaze device list` confirms
  what's reachable.
- For the smoke trail at the end you'll need a real connected device that runs `MyApp`
  (or substitute your own target's package id when copying).

## What you'll create

```text
my-workspace/trails/config/
├── trailblaze.yaml                          # workspace anchor — names the trailmap
└── trailmaps/
    └── myapp/
        ├── trailmap.yaml                    # the manifest
        └── tools/
            └── myapp_login.ts               # typed tool source — the only file you write per tool
```

Plus a smoke trail somewhere under your workspace:

```text
my-workspace/trails/myapp/login/android.trail.yaml
```

That's the whole footprint. `trailblaze check` materializes the workspace SDK, the
per-trailmap typed bindings, and the framework-managed `tsconfig.json` for you.

## Step 1 — Anchor the workspace

Drop a `trailblaze.yaml` anywhere in your project. Its parent directory becomes the
workspace anchor:

```yaml
# trails/config/trailblaze.yaml
targets:
  - myapp
```

`targets:` names the trailmap ids you want to make runnable. The framework finds each
named trailmap under `<anchor>/trailmaps/<id>/trailmap.yaml` (workspace) or on the
classpath (framework-shipped). See [Trailmaps → Discovery and precedence](trailmaps.md#discovery-and-precedence)
for the full rule.

## Step 2 — Write the trailmap manifest

```yaml
# trails/config/trailmaps/myapp/trailmap.yaml
id: myapp
dependencies:
  - trailblaze                                # pulls in core_interaction, navigation, …
target:
  display_name: MyApp
  tools:
    - myapp_login                             # the name your .ts exports, see Step 4
  platforms:
    android:
      app_ids: [com.example.myapp]
      tool_sets:
        - core_interaction
        - verification
```

Three things to notice:

- `dependencies: [trailblaze]` brings the framework's standard tool sets and defaults
  into scope. Without it you get a minimal target with no built-in interaction tools.
- `target.tools:` lists tool **names** (not file paths). The loader auto-discovers every
  `tools/*.ts` that exports a `trailblaze.tool(...)` declaration and resolves the names
  here into the runtime tool list. When a trailmap ships both iOS and Android tools,
  platform-specific tools go under `platforms.<p>.tools:` instead — see
  [Trailmaps → Per-platform scripted tools](trailmaps.md#per-platform-scripted-tools).
- `tool_sets:` names framework-shipped bundles of Kotlin tools to expose. `core_interaction`
  brings in primitives like `tapOnElement`, `inputText`, `swipe`; `verification` brings in
  `assertVisible` / `assertNotVisible`. See [Trailmaps → Discovery](trailmaps.md) for the
  full catalog and how `dependencies:` controls what's available.

## Step 3 — Bootstrap the typed SDK

Before opening any `.ts` file, run:

```bash
trailblaze check
```

This single command:

1. Resolves the trailmap graph and emits `dist/targets/<id>.yaml`.
2. Vendors the `@trailblaze/scripting` `.d.ts` into `<workspace>/.trailblaze/sdk/dist/`.
3. Emits per-trailmap typed bindings at
   `trailmaps/myapp/tools/trailblaze-client.d.ts`.
4. Writes a framework-managed `trailmaps/myapp/tools/tsconfig.json` (plus a
   `.gitignore` for derived files).

After this, your IDE has full typing on `@trailblaze/scripting` imports and on the
framework primitives (`tapOnElement`, `inputText`, etc.) that you'll compose against in
the next step — so when you write `ctx.tools.` you get real autocomplete, not red
squiggles.

You don't have to re-run it by hand every time you edit: the daemon re-runs the pipeline
automatically on every aware command, and the workspace `package.json` that `check`
drops on first run re-runs it on `bun install` — so a teammate who clones the repo gets
the same typings with a plain install. The output is idempotent.

**What to commit:** `trailmap.yaml`, your `.ts` tools, the per-trailmap `.gitignore`,
and the first-run `package.json`. Everything else (`.trailblaze/`, `dist/`,
`trailblaze-client.d.ts`, `tsconfig.json`) is regenerated and auto-ignored. The full
breakdown is in
[Scripted Tools — Project Layout & Generated Files](scripted-tools-project-layout.md).

## Step 4 — Write the typed `.ts` source

```ts
// trails/config/trailmaps/myapp/tools/myapp_login.ts
import { trailblaze } from "@trailblaze/scripting";

export interface MyappLoginArgs {
  /** Email to type into the login form. */
  email: string;
  /** Password to type into the login form. */
  password: string;
}

/**
 * Sign into MyApp with the supplied credentials. Use this whenever the task is
 * to log in, sign in, authenticate, or otherwise reach a signed-in state.
 */
export const myapp_login = trailblaze.tool<MyappLoginArgs>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (input, ctx) => {
    await ctx.tools.tapOnElement({ ref: "Email" });
    await ctx.tools.inputText({ text: input.email });
    await ctx.tools.tapOnElement({ ref: "Password" });
    await ctx.tools.inputText({ text: input.password });
    await ctx.tools.tapOnElement({ ref: "Sign In" });
    return `Signed in as ${input.email}.`;
  },
);
```

The `ref:` value (`"Email"`, `"Password"`, `"Sign In"`) is what `tapOnElement` matches
against on the live view hierarchy — accessibility label on iOS/Android, ARIA name on
the web. The agent resolves it against the device's native hierarchy each call; you
don't have to compute coordinates.

What the typed surface gives you:

- `MyappLoginArgs` becomes the tool's JSON Schema. Per-field TSDoc becomes each
  parameter's LLM-facing description.
- The TSDoc on the exported `const` becomes the tool's description — what the agent reads
  when deciding whether to call this tool.
- The spec object (`{ supportedPlatforms, requiresContext }`) becomes the runtime's
  registration gates and metadata hints — no separate `_meta:` YAML required.
- `ctx.tools.<name>(args)` is the typed composition surface. Every framework tool, every
  sibling trailmap-local tool, and every tool inherited via a dep's `exports:` is a
  method on it. Unknown names are `tsc` errors.

The export name (`myapp_login`) is the load-bearing identifier — the manifest's
`target.tools:` names this export and the runtime registers a tool by exactly that name.
See [Scripted Tools (TypeScript) — The shape](scripted-tools-typed-authoring.md#the-shape)
for the full reference.

Re-run `trailblaze check` whenever you add a new `.ts` tool so its sibling tools see it
on `ctx.tools.<newName>`. The daemon does this automatically on every device-aware
command, so most of the time you won't think about it.

## Step 5 — See the tool in the agent's toolbox

```bash
trailblaze toolbox -d android --target myapp --search myapp_login
```

`--device` (`-d`) is required for `toolbox` unless you're asking about a single tool by
`--name`. The target's `platforms.<p>` map decides which tools are applicable to which
device, so the listing always resolves against a real platform.

Your tool appears in the listing alongside the framework primitives that `dependencies:
[trailblaze]` brought in. The agent sees the same description and parameter docs your
TSDoc wrote.

## Step 6 — Smoke trail

Write a one-step trail that calls the new tool:

```yaml
# trails/myapp/login/android.trail.yaml
- config:
    id: myapp/login
    target: myapp
    platform: android

- prompts:
    - step: Sign in to MyApp
      recording:
        tools:
          - myapp_login:
              email: test@example.com
              password: Password123!
    - verify: The home screen is visible
```

Three things to know about the trail format:

- The **`recording:` block** under a step pins which tool to dispatch (and with what
  args) when the trail runs in replay mode. With a `recording:` block, replay is
  deterministic — no LLM in the loop. The step's prose (`Sign in to MyApp`) is
  preserved so a future repair flow can re-derive the recording if the UI drifts.
- A bare **`step:`** (without `recording:`) means "ask the agent to figure this out
  against the live device" — the LLM picks tools and resolves selectors at runtime.
  Use bare steps when authoring, recordings when you want CI determinism.
- The **`verify:`** entry is Trailblaze's vision-based assertion — an LLM judges a
  screenshot of the post-state against the prose claim ("The home screen is visible").
  No selector to write, and it covers cases (icons, charts, layout) that DOM/a11y
  selectors can't reach.

Run it:

```bash
trailblaze run trails/myapp/login/android.trail.yaml -d android
```

That's the loop: edit `.ts` → `trailblaze check` → `trailblaze run`. Repeat until the
tool's behavior matches what your agent should see at the top of `toolbox`.

## Where to go next

- **Compose multiple tools.** Sibling tools call each other through
  `ctx.tools.<name>(args)` — see the
  [`contacts_ios_searchAndVerify`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_searchAndVerify.ts)
  worked example.
- **Read a full worked target trailmap.** The
  [iOS Contacts](https://github.com/block/trailblaze/tree/main/examples/ios-contacts)
  and [Wikipedia](https://github.com/block/trailblaze/tree/main/examples/wikipedia)
  examples each ship a complete trailmap (9 tools + system prompt + trails). Copy
  either one as the starting shape for your own target.
- **Test your tools without a device.** See
  [Scripted Tools (TypeScript) — Testing your tool](scripted-tools-typed-authoring.md#testing-your-tool)
  for the mock client + context helpers.
- **Ship the trailmap to other teams.** See [Publishing a Trailmap](publishing-a-trailmap.md)
  for the distribution tiers.
