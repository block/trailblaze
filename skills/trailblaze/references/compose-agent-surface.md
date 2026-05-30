# Rung 3 — Compose your own agent surface

Once the user wants to author tests for *their* app — not just drive a
generic device — Trailblaze's value comes from the agent seeing the
app's vocabulary instead of only the framework's primitives. Custom
typed tools (`login`, `addToCart`), named waypoints (`Inbox`,
`CartReview`), and a curated toolset together form a **trailmap** — a
target-aware capability bundle the agent loads when it drives that
app.

A trailmap isn't a separate runtime mode. It's a directory of YAML +
TypeScript files the workspace declares, and the next time the agent
runs against that target it sees the composed surface as first-class
tools. The agent gets dramatically more capable on every task that
uses the composition.

Load this reference when the task is to author a typed scripted tool,
define a waypoint, curate which tools the agent sees, write a
`trailmap.yaml`, or debug "my custom tool isn't showing up in
`toolbox`". Prerequisites in [`drive-device.md`](drive-device.md)
(device + target routing keys, the `toolbox` command).

## Contents

- [What you compose](#what-you-compose)
- [Directory layout](#directory-layout)
- [Authoring the smallest useful tool](#authoring-the-smallest-useful-tool)
- [Materializing the workspace](#materializing-the-workspace)
- [When the tool doesn't appear in `toolbox`](#when-the-tool-doesnt-appear-in-toolbox)
- [Inspecting your composed surface](#inspecting-your-composed-surface)
- [Using your tool in a trail](#using-your-tool-in-a-trail)
- [Distribution](#distribution)
- [What's mature vs. still landing on this rung](#whats-mature-vs-still-landing-on-this-rung)

## What you compose

| Layer | What it is | Where it lives |
|---|---|---|
| **Typed scripted tool** | A TypeScript function the agent sees as a named tool, with typed inputs, a typed result, and an LLM-facing description. Replayable like any built-in. | `trails/config/trailmaps/<id>/tools/<tool>.ts` (+ sibling YAML descriptor) |
| **Waypoint** | A named, assertable location in the app, defined structurally (element identity, stable labels), never by content. Used as a checkpoint, target, or "am I on the Inbox?" predicate. | `trails/config/trailmaps/<id>/waypoints/<id>.waypoint.yaml` |
| **Toolset selection** | The curated list of which primitives + custom tools the agent actually sees per platform. Surfaces your `login` and hides the low-level taps when that's what the test needs. | `trailmap.yaml` → `target.platforms.<platform>.tool_sets` |

## Directory layout

```text
<repo>/
└── trails/
    └── config/
        ├── trailblaze.yaml                    # workspace anchor
        └── trailmaps/
            └── myapp/
                ├── trailmap.yaml              # the manifest
                ├── tools/
                │   ├── myapp_login.ts         # typed scripted tool
                │   └── myapp_login.yaml       # `_meta:` anchor
                └── waypoints/
                    └── myapp-home.waypoint.yaml
```

`trailblaze.yaml` lists the trailmap:

```yaml
trailmaps:
  - trailmaps/myapp/trailmap.yaml
```

## Authoring the smallest useful tool

Recommended path: declare inputs as a TypeScript interface and export
the handler via `trailblaze.tool<I>(handler)`. The framework derives
the JSON schema, the LLM description (from TSDoc), and the typed
binding for sibling tools.

```ts
// trails/config/trailmaps/myapp/tools/myapp_login.ts
import { trailblaze } from "@trailblaze/scripting";

export interface LoginArgs {
  /** Email to sign in with. */
  email: string;
  /** Password for that account. Passed via --secret at runtime. */
  password: string;
}

/**
 * Sign into MyApp with the supplied credentials. Use this whenever the
 * task is to authenticate as a known user before exercising other flows.
 */
export const myapp_login = trailblaze.tool<LoginArgs>(
  async (input, ctx) => {
    await ctx.tools.tapOnElementWithText({ text: "Sign in" });
    await ctx.tools.inputText({ text: input.email });
    await ctx.tools.inputText({ text: input.password });
    await ctx.tools.tapOnElementWithText({ text: "Continue" });
    return `Signed in as ${input.email}.`;
  },
);
```

Pair it with a sibling YAML descriptor pointing at the source. Today's
analyzer still wants `name:` + `description:` + `inputSchema:` on the
descriptor — the analyzer derives the typed bindings, but the runtime
loader reads the YAML. (A future "mode 3" collapses the descriptor to
`script:` + `_meta:` only; that's on the roadmap, not wired yet — see
the typed-authoring devlog in this repo's `docs/` for the contract.)

```yaml
# trails/config/trailmaps/myapp/tools/myapp_login.yaml
script: ./myapp_login.ts        # descriptor-relative: resolves next to this YAML
name: myapp_login
description: Sign into MyApp with the supplied credentials.
_meta:
  trailblaze/supportedPlatforms: [android, ios, web]
  trailblaze/requiresContext: true
inputSchema:
  email:
    type: string
    description: Email to sign in with.
  password:
    type: string
    description: Password for that account. Pass via --secret at runtime.
```

`script:` is **resolved relative to the directory containing this
YAML**, not the workspace root — so co-locate the `.ts` next to the
descriptor (the conventional layout) and write `./<tool>.ts`. Absolute
paths also work and pass through unchanged.

And list it in `trailmap.yaml`:

```yaml
id: myapp
dependencies:
  - trailblaze
target:
  display_name: My App
  tools:
    - tools/myapp_login.yaml
  platforms:
    android:
      app_ids: [com.example.myapp]
    ios: {}
    web: {}
```

> The legacy authoring path (full YAML descriptor + `export async
> function`) still works for older trailmaps but is not the recommended
> shape for new tools. See the [Scripted Tools](https://block.github.io/trailblaze/scripted_tools/)
> guide for the contrast and migration notes.

## Materializing the workspace

After authoring or editing a trailmap, run `check` once. It scaffolds
the workspace's SDK (`@trailblaze/scripting` + the per-trailmap
generated typed bindings), type-checks every `.ts` source against it,
and runs any `*.test.ts` unit tests via `bun test`:

```bash
trailblaze check                # auto-detects workspace from cwd
trailblaze check --all          # type-check every trailmap
trailblaze check --show-typed-tools   # diagnostic: list discovered typed tools + schemas
```

`check` is the right command to point the user at when they hit
"my tool isn't showing up" — its `--show-typed-tools` output names
every typed tool the analyzer discovered, with a one-line schema
summary, so a missing-from-toolbox bug becomes a missing-from-check
finding.

**When `check` fails before it can report anything**, the message in
the structured envelope usually names the cause. The four common ones:

- *bun/esbuild not on PATH* — install via Homebrew (`brew install bun esbuild`)
  or follow the workspace's bootstrap docs.
- *missing or stale `node_modules/`* — run `npm install` (or `bun install --frozen-lockfile`)
  inside the workspace's TypeScript root.
- *`@trailblaze/scripting` import won't resolve* — the workspace SDK
  wasn't materialized; rerun `trailblaze check` after the dependency
  install above so the SDK files land under the workspace's `node_modules`.
- *`*.test.ts` unit test failure* — `check` runs your tests via
  `bun test`; a failing test blocks the type-check pass. Fix the test
  (or temporarily mark it `.skip` if it's a known unrelated breakage)
  and re-run.

## When the tool doesn't appear in `toolbox`

After `check` reports clean, if the tool still isn't visible to the
agent, walk the four checkpoints in order:

1. **Run `trailblaze check --show-typed-tools`** — if the tool is in
   that output, it's analyzer-visible; the bug is downstream (steps 2–4).
   If it's not, the analyzer didn't see your `.ts` source; jump to 3.
2. **Confirm the trailmap declares the tool** in `trailmap.yaml`'s
   `target.tools:` list (matching the sibling YAML's filename or the
   bare tool name, depending on which authoring path you're on).
3. **Confirm the sibling YAML's `script:` path resolves.** The path
   is resolved against the JVM's working directory (repo root), not
   the trailmap directory — the conventional shape is
   `./trails/config/trailmaps/<id>/tools/<tool>.ts`.
4. **Confirm the trailmap is loaded** by the workspace anchor — the
   `trailmaps:` list in `<workspace>/trailblaze.yaml` must include
   this trailmap's manifest. If it's missing, the trailmap is on
   disk but invisible.

## Inspecting your composed surface

Use the same discovery commands from rung 1, now scoped to your
target:

```bash
trailblaze toolbox --target myapp --device android        # full toolset for myapp on Android
trailblaze toolbox --target myapp --name myapp_login      # detail for one tool
trailblaze waypoint list                                  # all waypoints from active trailmaps
```

The agent's next session against `--target myapp` sees the composed
surface — your `myapp_login` lands next to the platform primitives.

## Using your tool in a trail

In a `.trail.yaml`, custom tools are called the same way as
primitives — by name, with typed arguments:

```yaml
target: myapp
steps:
  - description: Sign in as the test user
    tools:
      - name: myapp_login
        arguments:
          email: { memory: TEST_EMAIL }
          password: { secret: TEST_PASSWORD }
```

To verify the resulting screen state, follow the custom-tool step
with a built-in assertion against the live view hierarchy — e.g.
`assertVisible` with a ref captured from a `snapshot` step (refs are
content-hashed, so they're frozen at record time per rung 1's design
note), or `assertNotVisibleWithText` to check that a known-blocking
element is gone. (Step-level waypoint matching as a single
`matchWaypoint` tool is on the roadmap — see the "active prototype"
partition below — but isn't yet a runnable primitive in the toolbox.
Run `trailblaze toolbox --target myapp --device <platform>` to see
the actual assertion verbs available on your platform.)

Pass values at runtime with `--memory` / `--secret`:

```bash
trailblaze run trails/smoke.trail.yaml -d android \
  --memory TEST_EMAIL=test@example.com \
  --secret TEST_PASSWORD=hunter2
```

**Debugging an authored tool at runtime.** When a custom tool
misbehaves during `run` (throws, returns the wrong message, hangs),
the session log is the primary inspection surface — generate the HTML
report with `trailblaze report --id <session-id>` (the session ID is
printed when `run` starts; `trailblaze session list` also surfaces
it). The report shows the exact tool invocation, the resolved
arguments, the structured error envelope, and the captured screen
state at the point of failure. Diagnose authoring bugs there before
re-editing the `.ts`. See [`save-and-replay.md`](save-and-replay.md)
for more on the report surface.

## Distribution

Today, a trailmap is a directory in the user's repo. Cross-team
sharing happens by copying the directory or referencing it as a
checked-in git path. **npm distribution for community-published
trailmaps is in active development** — when it lands, installing a
trailmap for someone else's app will look like `npm install
@org/myapp-trailmap`.

## What's mature vs. still landing on this rung

Be honest with the user about which corners are still being shaped:

- **Mature:** the typed scripted-tool authoring surface
  (`trailblaze.tool<I>(handler)`), per-trailmap typed bindings via
  `trailblaze check`, structural waypoint definitions plus the
  `trailblaze waypoint locate/validate` discovery commands, and
  trailmap (`trailmap.yaml`) composition via `dependencies:`.
- **Active prototype:** trail-as-tool (expose a saved trail as a tool
  callable from other trails), the waypoint navigation graph and
  shortcut routing (`trailblaze waypoint graph` and `trailblaze
  waypoint shortcut`), step-level waypoint matching as a single
  `matchWaypoint` tool, and npm-based trailmap distribution.

When the user hits an edge case in one of the "active prototype"
areas, point them at the [devlog index](https://block.github.io/trailblaze/devlog/)
rather than guessing — those features evolve faster than this skill
can track.
