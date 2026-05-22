# Trailblaze Config — Playwright Native Example

This directory demonstrates the single-file TypeScript `tools:` authoring flow for web
targets. Each tool is a `.ts` file with an exported async function whose name matches the
YAML `name:`; Trailblaze wraps it into an MCP server at session start.

## Quick start (one-time after clone)

```bash
./trailblaze check --workspace examples/playwright-native
```

Then open any `.ts` file under `packs/*/tools/` in your editor — autocomplete on
`@trailblaze/scripting` imports and `client.tools.*` is alive. `./gradlew build` from the
repo root runs the same compile chain automatically via the `:examples:playwright-native:check`
task wiring; the explicit CLI form above is for editor-first contributors who haven't run
Gradle yet.

## Layout

```text
trailblaze-config/
├── README.md
├── targets/
│   └── playwright-sample.yaml
└── tools/
    ├── playwrightSample_shared.ts
    ├── playwrightSample_web_incrementCounterToValue.ts
    ├── playwrightSample_web_openDuplicateProductDetail.ts
    ├── playwrightSample_web_openFixtureAndVerifyText.ts
    ├── playwrightSample_web_openFormIfNeeded.ts
    ├── playwrightSample_web_searchProductsAndOpenResult.ts
    └── playwrightSample_web_submitContactForm.ts
```

## TypeScript IDE Setup

The `tools/` directory carries a `package.json` and `tsconfig.json` so VS Code, IntelliJ, and
other TypeScript-aware IDEs surface autocomplete and type information for `args`, `ctx`, and
`client` in the `.ts` tool files. To enable it:

```bash
cd examples/playwright-native/trailblaze-config/tools
npm install   # or `bun install`
```

That symlinks `@trailblaze/scripting` into `node_modules/` from the local SDK source at
`sdks/typescript/`. No registry account or auth required — the dependency uses an
npm `file:` link, not a published package. After installing, open any `.ts` file in this
directory and hover `ctx`, `client`, or `args` to confirm autocomplete resolves against the
SDK's typed interfaces. The reference file `_example_typescript_tool.ts` is a runnable template
demonstrating the pattern.

**Path note for adopting this pattern in another pack:** the `file:` link in `package.json`
is relative to the `tools/` directory. The number of `..` segments depends on how deep your
`tools/` sits relative to the repo root. Walk up from your `tools/` to the repo root,
then descend into `sdks/typescript/`. For this example the path is
`file:../../../../sdks/typescript` (4 levels up: `tools/` → `trailblaze-config/` →
`playwright-native/` → `examples/` → repo root, then down to `sdks/typescript`). Adjust
the depth to match your own pack's location before copying.

This setup is validated in CI on every PR. To run the same validation locally before pushing:

```bash
cd examples/playwright-native/trailblaze-config/tools
npm install   # or `bun install`
npx tsc --noEmit
```

One expected error surfaces from `sdks/typescript/src/tool.ts` — a known SDK
type-tightening tracked by an open issue. Every other tsc error is real and should be fixed
before merging.

## Running the example

Point Trailblaze at this config directory, restart the daemon, and select the `playwrightsample`
target:

```bash
export TRAILBLAZE_CONFIG_DIR=$PWD/examples/playwright-native/trailblaze-config
TRAILBLAZE_PORT=42424 ./trailblaze app --stop
TRAILBLAZE_PORT=42424 ./trailblaze app --headless & disown
TRAILBLAZE_PORT=42424 ./trailblaze toolbox --device web --target playwrightsample --search fixture
TRAILBLAZE_PORT=42424 ./trailblaze tool playwrightSample_web_openFixtureAndVerifyText --device web --target playwrightsample -o "Open the sample page and verify the heading"
```

After editing any file in `targets/` or `tools/`, restart the daemon again. Discovery happens at
daemon startup.

The example target currently exposes six inline tools:

- `playwrightSample_web_openFixtureAndVerifyText`
- `playwrightSample_web_incrementCounterToValue`
- `playwrightSample_web_submitContactForm`
- `playwrightSample_web_openFormIfNeeded`
- `playwrightSample_web_openDuplicateProductDetail`
- `playwrightSample_web_searchProductsAndOpenResult`

The naming convention is `{target}_{platform}_{tool}` with underscores separating the parts and
camelCase inside each part, for example `playwrightSample_web_openFixtureAndVerifyText`.

## Minimal authoring loop

1. Add a `.ts` file under `tools/`.
2. Add a matching `script:` + `name:` entry under `tools:` in `targets/playwright-sample.yaml`.
3. Stop and restart the Trailblaze daemon.
4. Run `toolbox` or call the new tool directly.

## How Registration Works

- The target YAML's `name:` is the registered Trailblaze tool name.
- The `script:` path points at a TypeScript module Trailblaze imports at session start.
- Trailblaze calls the exported function whose name matches the YAML `name:` with
  `(args, ctx, client)`. The `client.tools.<name>(args)` namespace is the typed dispatch
  surface — the low-level `client.callTool` primitive is intentionally hidden from the public
  type so authors land on the autocomplete-friendly entry point.

Minimal target entry:

```yaml
tools:
  - script: ./trailblaze-config/tools/myTarget_web_myTool.ts
    name: myTarget_web_myTool
    description: Do one useful thing.
    _meta:
      trailblaze/supportedPlatforms: [WEB]
      trailblaze/toolset: web_core
      trailblaze/requiresContext: true
    inputSchema:
      type: object
      properties:
        relativePath:
          type: string
```

Minimal tool file:

```ts
import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

interface MyToolArgs {
  relativePath?: string;
}

export async function myTarget_web_myTool(
  args: MyToolArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  if (!ctx) {
    throw new Error("This tool requires a live Trailblaze session context.");
  }

  await client.tools.web_navigate({
    action: "GOTO",
    url: args.relativePath ?? "./sample-app/index.html",
  });
  await client.tools.web_verify_text_visible({
    text: "Trailblaze Test Fixture",
  });

  return "Opened the page and verified the heading.";
}
```

These example tools import plain sibling ESM modules such as `./playwrightSample_shared`, so
common validation and default logic can stay in one place.

## Testing your tools

From the repo root, run `./trailblaze test playwrightsample` to execute the `.test.ts`
files next to each tool. The runner shells out to `bun test` against the mock client +
mock context from `@trailblaze/scripting/testing`, so tests run without a daemon or a
device. Requires `bun` on PATH (install from https://bun.sh) and `tools/tsconfig.json`
(regenerated by `./trailblaze check` — runs automatically as part of `./gradlew check`,
which `build` depends on). See the existing samples for patterns:

- `tools/playwrightSample_web_openFixtureAndVerifyText.test.ts` — single-tool sequence,
  asserts the order of `client.tools.*` dispatches and the args of each call.
- `tools/playwrightSample_web_searchProductsAndOpenResult.test.ts` — five-call sequence
  with arg-shape assertions on interpolated selectors.
- `tools/playwrightSample_web_openFormIfNeeded.test.ts` — stub pattern: `client.stub(
  toolName, { errorMessage })` drives the recovery branch via an injected failure, and
  the test asserts on `client.calls` to confirm the catch-block ran.

## Conditional Pattern

Branching on screen state is one of the main reasons to write a scripted tool. This example
ships a real registered tool, `playwrightSample_web_openFormIfNeeded`, in
`tools/playwrightSample_web_openFormIfNeeded.ts`. It uses the current lowest-common-denominator
probe pattern: try a deterministic verification, then fall back when that inner tool throws.

```ts
export async function playwrightSample_web_openFormIfNeeded(args, ctx, client) {
  try {
    await client.tools.web_verify_text_visible({ text: "Contact Form" });
    return "The form section was already visible.";
  } catch {
    await client.tools.web_click({ ref: "css=a[href='#form']" });
    await client.tools.web_verify_text_visible({ text: "Contact Form" });
    return "Opened the form section.";
  }
}
```

You can run it directly with:

```bash
TRAILBLAZE_PORT=42424 ./trailblaze tool playwrightSample_web_openFormIfNeeded --device web --target playwrightsample -o "Open the form section if needed"
```

## Current Limitation

These examples keep the scripted tool registration inside the target YAML because reusable
filesystem `trailblaze-config/tools/*.yaml` script manifests are not wired into the runtime yet.
The JS files themselves are still reusable today via sibling helper imports; moving the registration
shape to standalone tool YAML is the next architectural step rather than something this example can
paper over.
