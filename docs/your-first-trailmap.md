---
title: Your First Trailmap
---

# Your First Trailmap

This is the **third rung of the adoption ladder** — composing your own agent surface. The
first two rungs (drive a device, save and replay) work on the framework primitives alone.
The third gives your agent first-class commands like `login` or `addToCart` that record,
replay, and type-check the same as `tap` or `inputText`.

This page walks one tool — `myapp_login` — from an empty workspace to a recorded trail
that replays it. It uses the **typed scripted-tool surface**
([reference](scripted-tools-typed-authoring.md)) — TypeScript interfaces describe inputs,
TSDoc on the exported `const` becomes the LLM-facing description. The legacy
`export async function` shape in [Scripted Tools](scripted_tools.md) still works but the
typed surface is the recommended starting point for new tools.

For the trailmap-manifest schema, per-suffix file conventions, and dep-graph discovery
rules, this page links out to [Trailmaps](trailmaps.md).

## What you'll create

```text
my-workspace/trails/config/
├── trailblaze.yaml                          # workspace anchor — names the trailmap
└── trailmaps/
    └── myapp/
        ├── trailmap.yaml                    # the manifest
        └── tools/
            ├── myapp_login.ts               # typed tool source
            └── myapp_login.yaml             # minimal descriptor (anchors the .ts)
```

Plus a smoke trail somewhere under your workspace:

```text
my-workspace/trails/myapp/login/android.trail.yaml
```

That's the whole footprint. No `package.json`, no `tsconfig.json` — `trailblaze check`
materializes the workspace SDK and per-trailmap typed bindings for you.

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
    - myapp_login                             # the name your .ts exports, see Step 3
  platforms:
    android:
      app_ids: [com.example.myapp]
      tool_sets:
        - core_interaction
        - verification
```

Two things to notice:

- `dependencies: [trailblaze]` brings the framework's standard tool sets and defaults
  into scope. Without it you get a minimal target with no built-in interaction tools.
- `target.tools:` lists tool **names** (not file paths). The loader auto-discovers every
  `tools/*.yaml` descriptor and matches by `name:`.

## Step 3 — Write the typed `.ts` source

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

What the typed surface gives you:

- `MyappLoginArgs` becomes the tool's JSON Schema. Per-field TSDoc becomes each
  parameter's LLM-facing description.
- The TSDoc on the exported `const` becomes the tool's description — what the agent reads
  when deciding whether to call this tool.
- `ctx.tools.<name>(args)` is the typed composition surface. Every framework tool, every
  sibling trailmap-local tool, and every tool inherited via a dep's `exports:` is a
  method on it. Unknown names are `tsc` errors.

The export name (`myapp_login`) is the load-bearing identifier — the descriptor YAML
points at this file and the manifest's `target.tools:` names this export. See
[Typed Authoring → The shape](scripted-tools-typed-authoring.md#the-shape) for the full
`trailblaze.tool<I, O>()` reference.

## Step 4 — Write the descriptor YAML

```yaml
# trails/config/trailmaps/myapp/tools/myapp_login.yaml
script: ./myapp_login.ts
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
```

The descriptor anchors the `.ts` and carries platform metadata. The runtime registers from
the YAML; the per-trailmap `trailblaze-client.d.ts` codegen upgrades the typed surface from the
analyzer reading the `.ts`. (When the build-time bundler is wired to read the analyzer,
this YAML collapses to `script:` + `_meta:` only — see
[Typed Authoring → Two authoring modes](scripted-tools-typed-authoring.md#two-authoring-modes)
for the migration shape.)

## Step 5 — Materialize the workspace SDK

```bash
./trailblaze check --workspace .
```

This is the same command the README calls out for `examples/playwright-native`. It
resolves the trailmap graph, vendors the `@trailblaze/scripting` `.d.ts` into
`.trailblaze/sdk/`, emits per-trailmap typed bindings at
`trailmaps/myapp/tools/trailblaze-client.d.ts`, and writes a framework-managed
`tools/tsconfig.json` so your IDE picks up the SDK types with no hand-authored config.
The daemon re-runs it automatically on every aware command; the output is idempotent.

## Step 6 — See the tool in the agent's toolbox

```bash
trailblaze toolbox -d android --target myapp --search myapp_login
```

`--device` (`-d`) is required for `toolbox` unless you're asking about a single tool by
`--name`. The target's `platforms.<p>` map decides which tools are applicable to which
device, so the listing always resolves against a real platform.

Your tool appears in the listing alongside the framework primitives that `dependencies:
[trailblaze]` brought in. The agent sees the same description and parameter docs your
TSDoc wrote.

## Step 7 — Smoke trail

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
              password: hunter2
    - verify: The home screen is visible
```

Run it:

```bash
trailblaze run trails/myapp/login/android.trail.yaml -d android
```

The recording block pins the tool call so replay runs deterministically with no LLM in
the loop. The `verify:` step uses Trailblaze's vision-based assertion to confirm the
post-state. Drop `--use-recorded-steps` for the recorded path, or let the runner
auto-detect from the `recording:` block.

That's the loop: edit `.ts` → `trailblaze check` → `trailblaze run`. Repeat until the
tool's behavior matches what your agent should see at the top of `toolbox`.

## Where to go next

- **Compose multiple tools.** Sibling tools call each other through `ctx.tools.<name>(args)` —
  see the `contacts_ios_searchAndVerify` worked example linked from
  [Typed Authoring → What this looks like in practice](scripted-tools-typed-authoring.md#what-this-looks-like-in-practice).
- **Test your tools without a device.** See
  [Scripted Tools → Testing your tool](scripted_tools.md#testing-your-tool) for the mock
  client + context helpers.
- **Ship the trailmap to other teams.** See [Publishing a Trailmap](publishing-a-trailmap.md)
  for the distribution tiers.
