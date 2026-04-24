# Trailblaze Config — Sample App

This directory is a working example of the **per-app Trailblaze config layout** — everything
Trailblaze needs to know about the sample Android app, packaged as a single self-contained
directory that sits alongside the app's source.

Copy this shape for your own app and Trailblaze will discover it automatically once you point
the `TRAILBLAZE_CONFIG_DIR` env var at your copy.

## Layout

```
trailblaze-config/
├── targets/
│   └── sample-app.yaml      # Your app's target definition (id, app IDs, mcp_servers, etc.)
├── mcp/                     # Your custom MCP tools (runnable TypeScript/JavaScript)
│   ├── package.json
│   ├── tsconfig.json
│   └── tools.ts
└── README.md                # (this file)
```

Optional directories Trailblaze also discovers under this root (not used by the sample):

- `toolsets/*.yaml` — custom toolset groupings your targets reference via `tool_sets:`
- `tools/*.yaml` — per-tool YAML definitions for **class-backed** tools (Kotlin classes
  annotated with `@TrailblazeToolClass`). Pure YAML-composed tool definitions (no class
  backing) dropped here are **not** yet resolved from filesystem config; that's a
  follow-up and classpath-bundled YAML-composed tools continue to work.
- `providers/*.yaml` — custom LLM provider configurations

## How Trailblaze discovers this

Trailblaze looks for a per-project `trailblaze-config/` directory in this order:

1. **`TRAILBLAZE_CONFIG_DIR` env var** — explicit override, great for CI / scripting.
2. **`trailblazeConfigDirectory` saved setting** — edit `~/.trailblaze/trailblaze-settings.json`
   directly. A dedicated toggle in the desktop Settings tab is planned but not yet wired.
3. **Auto-sibling convention** — if your `trailsDirectory` setting points at
   `/path/to/project/trails/`, Trailblaze looks for `/path/to/project/trailblaze-config/`
   automatically. Drop the directory in the right place and it just works.

Whatever resolves gets layered on top of the framework's built-in classpath config.
User entries win on filename collision — handy for locally overriding bundled
targets/toolsets.

> ⚠️ **Discovery runs once per daemon startup.** The resolved target set is cached for the
> JVM's lifetime. If you change `trailblazeConfigDirectory`, add/edit a YAML under your
> config directory, or drop a new `trailblaze-config/` sibling, **restart the Trailblaze
> daemon** (`./trailblaze app --stop`, then re-run any command) for the change
> to take effect. This will become automatic when the settings repo grows a reactive
> invalidation path.

### Running this example

The sample app's trails live at `examples/android-sample-app/trails/`, and this
directory is at `examples/android-sample-app/trailblaze-config/`. So the sibling
convention picks it up automatically as long as your `trailsDirectory` points at
`.../android-sample-app/trails/`.

If you haven't set `trailsDirectory` yet, just use the env-var override for a one-shot run:

```bash
export TRAILBLAZE_CONFIG_DIR=$PWD/examples/android-sample-app/trailblaze-config
./trailblaze trail \
  examples/android-sample-app/trails/mcp-tools-demo/mcp-tools-demo.trail.yaml \
  --device android/emulator-5554
```

Verify discovery without running a trail:

```bash
./trailblaze config show     # "Targets:" section should list `sampleapp`
```

## Installing the MCP tools' dependencies

The TypeScript MCP server (`mcp/tools.ts`) uses `@modelcontextprotocol/sdk`. Install once:

```bash
cd examples/android-sample-app/trailblaze-config/mcp
npm install     # or: bun install
```

`node_modules/` is gitignored. The Kotlin session-startup path runs the script via `bun`
(preferred) or `tsx` — see `mcp/tools.ts` header for the runtime contract.

## Script path resolution — today

The `script:` path inside `targets/sample-app.yaml` is resolved against the JVM's current
working directory (where you invoked `./trailblaze`), **not** the target YAML's location. The
sample-app uses an explicit repo-relative path so it works when launched from the repo root. A
future iteration of the loader will anchor filesystem-sourced YAMLs against their own
directory so a plain `./mcp/tools.ts` would resolve correctly from anywhere — until then,
stick with full repo-relative paths in `script:`.

## Copying this for your own app

1. Copy the whole `trailblaze-config/` directory into your app's repo root, alongside
   wherever your trail YAMLs live (e.g. `your-app/trails/` and `your-app/trailblaze-config/`).
2. Rename `targets/sample-app.yaml` to match your app — update `id:`, `display_name:`, and
   `platforms.android.app_ids:` accordingly.
3. Replace `mcp/tools.ts` with your own tools (or delete the `mcp_servers:` block from the
   target YAML if you don't need any).
4. Point Trailblaze at your trails directory the usual way — once `trailsDirectory` is set,
   the sibling `trailblaze-config/` gets picked up automatically.

That's it — no Kotlin, no Gradle, no framework rebuild.
