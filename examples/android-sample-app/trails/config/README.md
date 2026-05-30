# Trailblaze Config — Sample App

This directory is a focused example of the canonical `trails/config/` workspace layout.
Run any `trailblaze` command from `examples/android-sample-app/` (or any
subdirectory) and walk-up discovery finds this workspace automatically — no
`TRAILBLAZE_CONFIG_DIR` env var required.

## Quick start (one-time after clone)

```bash
./trailblaze check --workspace examples/android-sample-app
```

Then open any `.ts` file under `trailmaps/*/tools/` in your editor — autocomplete on
`@trailblaze/scripting` imports and `client.tools.*` is alive. `./gradlew build` from the
repo root runs the same compile chain automatically via the
`:examples:android-sample-app:check` task wiring; the explicit CLI form above is for
editor-first contributors who haven't run Gradle yet.

`TRAILBLAZE_CONFIG_DIR` still works as an explicit override when you want to point a run at
some other config directory, but the normal project layout is:

```text
trails/
├── config/
│   ├── trailblaze.yaml
│   ├── trailmaps/
│   │   └── sampleapp/
│   │       └── trailmap.yaml
│   └── mcp/
│       └── tools.ts
└── ... trail files ...
```

Optional directories under `config/`:

- `targets/*.yaml` — legacy flat targets; still supported in parallel while trailmaps roll out
- `toolsets/*.yaml` — custom toolset groupings your targets reference via `tool_sets:`
- `tools/*.yaml` — per-tool YAML definitions for class-backed tools
- `providers/*.yaml` — reserved location for provider YAMLs; today provider loading still
  comes from the `llm:` block in `trailblaze.yaml` plus built-in classpath metadata

## How Trailblaze discovers this

Trailblaze resolves project config in this order:

1. `TRAILBLAZE_CONFIG_DIR` env var — explicit override, useful for CI and scripting.
2. Workspace walk-up — Trailblaze walks up from the current working directory until it
   finds `trails/config/trailblaze.yaml`, then uses that owning `trails/config/`
   directory.
3. Bundled classpath config only — if neither of the above resolves, Trailblaze falls
   back to its built-in defaults.

The effective discovery order is:

1. Bundled classpath config
2. Workspace `trails/config/`
3. Workspace `trails/config/trailblaze.yaml`

Later layers win on collision, so `trailblaze.yaml` can override a sibling `targets/`
entry by id, and explicit `trailmaps:` entries can layer trailmap-owned targets/toolsets/tools
through the same resolution path.

> Discovery runs once per daemon startup. If you change `trailblaze.yaml`, edit YAML under
> `trails/config/`, or switch `TRAILBLAZE_CONFIG_DIR`, restart the Trailblaze daemon
> (`./trailblaze app --stop`, then rerun the command) to pick up the change.

## Running this example

Walk-up discovery does the work — just `cd` into the example root and run trailblaze:

```bash
cd examples/android-sample-app
trailblaze toolbox --device android --target sampleapp
```

The daemon log will show `Loaded trailblaze.yaml from
.../android-sample-app/trails/config/trailblaze.yaml` — confirming walk-up resolved to
this workspace.

To run a trail end-to-end:

```bash
trailblaze run trails/mcp-tools-demo/mcp-tools-demo.trail.yaml --device android/emulator-5554
```

`TRAILBLAZE_CONFIG_DIR=<path>` still works as an explicit override for CI/scripting paths
that can't `cd` into the workspace.

## Testing your tools

Per-trailmap unit tests are part of `./trailblaze check` — its third phase runs `bun test`
across every trailmap's `.test.ts` files after materialize + tsc. From the repo root, run
`./trailblaze check sampleapp` to validate the trailmap end-to-end; the test step shells
out to `bun test` against the mock client + mock context from
`@trailblaze/scripting/testing`, so tests run without a daemon or a device. Requires
`bun` on PATH (install from https://bun.sh).

- `tools/sampleapp_writeArtifact.test.ts` — exercises the host-only `sampleapp_writeArtifact` tool
  against real `node:fs` writes under the OS tmpdir; demonstrates the per-session
  sandbox layout, sessionId sanitization, and the `..`/absolute-path validation errors.

For the `client.tools.X(...)` dispatch and `client.stub(name, response)` patterns, see
the playwright-native trailmap's `.test.ts` samples — `sampleapp` only ships one host-only
tool, so those patterns are demonstrated where they apply.

## Installing the MCP tools' dependencies

The TypeScript MCP server (`mcp/tools.ts`) uses `@modelcontextprotocol/sdk`. Install once:

```bash
cd examples/android-sample-app/trails/config/trailmaps/sampleapp/tools/mcp
bun install
```

`node_modules/` is gitignored. The Kotlin session-startup path runs the script via
`bun` — the only supported JavaScript runtime; see `mcp/tools.ts` for the contract.

## Script path resolution

The `script:` path inside `trailmaps/sampleapp/trailmap.yaml` is resolved against the JVM's current
working directory, not the target YAML's location. The sample uses explicit repo-relative
paths so it works when launched from the repo root. Until the loader becomes file-relative,
stick with repo-relative `script:` paths.

## Copying this for your own app

1. Put your trails under a `trails/` directory.
2. Copy this `config/` directory into that `trails/` directory.
3. Rename `trailmaps/sampleapp/` to match your app and update the nested `target:` block in
   `trailmap.yaml`.
4. Add or remove tool descriptors under `trailmaps/<your-trailmap>/tools/`, and list each tool's
   `name:` under `target.tools:` in `trailmap.yaml`. Drop the `tools:` block entirely if you
   don't need custom scripting.
5. Run Trailblaze from somewhere inside that workspace so walk-up finds
   `trails/config/trailblaze.yaml`.

That is enough to use custom targets and scripted tools without rebuilding Trailblaze.
