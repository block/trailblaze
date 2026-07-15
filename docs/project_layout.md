---
title: Project Layout
---

# Project Layout

Trailblaze imposes almost no structure on your project. Put trail files wherever makes sense — pass them to the CLI by path or shell glob; the desktop UI auto-discovers trails below your workspace root.

This page is about trail/workspace discovery and how trail files run. For the detailed
shape of `trails/config/` itself — trailmaps, legacy targets, toolsets, tools, provider
caveats, and MCP script placement — use the generated [External Config](generated/external-config.md)
reference.

## The simplest project

A single trail file is a complete Trailblaze project:

```
my-project/
└── login.trail.yaml
```

Run it with:

```bash
trailblaze run login.trail.yaml
```

No config, no directory layout, no setup.

## A configured project

When you want project-level config (LLM defaults, target definitions, custom tools), put it
under `trails/config/`:

```
my-project/
└── trails/
    ├── config/
    │   └── trailblaze.yaml  ← project config (workspace manifest)
    ├── login/
    │   └── trail.yaml       ← unified trail: NL steps + per-device recordings in one file
    ├── flows/
    │   └── checkout/
    │       └── trail.yaml
    └── catalog/
        └── overlay/
            └── trail.yaml   ← NL-only until a recording is saved into it
```

`trails/` is the workspace anchor. Trailblaze walks up from the current directory (or the
directory containing the trail you invoke) until it finds `trails/config/trailblaze.yaml`;
the owning `trails/` directory becomes the workspace root. Trails live under that
directory, and config refs inside `trails/config/trailblaze.yaml` resolve relative to
`trails/config/`. The authored unit inside that config directory is now usually a
`trailmaps/<id>/trailmap.yaml`; flat `targets/*.yaml` files still work as a compatibility path.

See [Trailmaps](trailmaps.md) for the trailmap manifest schema, per-file scripted-tool YAMLs, and the
workspace-vs-classpath precedence rule. For the binary-friendly config bundle story inside
`trails/config/`, see [External Config](generated/external-config.md).

### Workspace defaults

`trailblaze.yaml` can declare a committed, team-wide default target so everyone in the
workspace targets the same app without per-machine setup:

```yaml
defaults:
  target: my-app
```

Effective-target precedence (highest first): an explicit per-run target (`--target`, a
trail's `config.target`, or an active session override) → the persisted user selection
(picking a target in the app, or `trailblaze config target`) → this workspace default → the
neutral built-in target. The id must match a loaded target (case-sensitive); an unknown or
blank id is logged and skipped rather than failing the run.

One nuance: a persisted selection of `default` (the neutral built-in target's own id) does
NOT outrank the workspace default — older CLI versions wrote it automatically without any
user intent, so it can't be told apart from a fabricated value. It still applies when the
workspace declares no default, and `--target default` remains the explicit per-run way to
force the neutral target.

If you author scripted tools, the framework also lays down generated files under
`trails/config/trailmaps/` and `trails/.trailblaze/` (typed bindings, a per-trailmap
`tsconfig.json`, the vendored SDK bundle). For what each one is and which to commit, see
[Scripted Tools — Project Layout & Generated Files](scripted-tools-project-layout.md).

## Trail file names

These filenames are recognized as trails, matched case-sensitively on the exact name:

| Filename         | Purpose                                                                 |
|------------------|-------------------------------------------------------------------------|
| `trail.yaml`     | The **unified trail file** — one per trail directory. Natural-language steps written once, with each step's per-device tool recordings nested under a `recording:` block keyed by device classifier (`android`, `ios-iphone`, `web`, …). Use this for new trails. |
| `*.trail.yaml`   | Any other file ending in `.trail.yaml`. A file named after a device classifier (e.g., `android-phone.trail.yaml`, `ios-iphone.trail.yaml`) is the **legacy** per-platform recording shape — still replayable everywhere, superseded by the unified file. |
| `blaze.yaml`     | **Legacy** natural-language-only trail definition (no recordings). A unified `trail.yaml` with no recording slots covers the same case. |

A unified `trail.yaml` is one trail covering every platform: a device with a matching recording slot replays it deterministically, and a device without one runs the step's prose through the agent. It may also open with an optional `trailhead:` — the deterministic step 0 that reaches a starting state (launched, signed in) via a specialized bootstrap tool before the first `trail:` step. Directories that still hold legacy files keep working — one `blaze.yaml` alongside one or more per-platform `*.trail.yaml` recordings. Each legacy file is its own trail from the runner's point of view when passed explicitly or matched by a shell glob (`trailblaze run flows/**/*.trail.yaml`) — every matched file runs. The desktop UI's Trails browser groups files in one directory into a single row with a variant chip per file for browsability — that's a UI affordance, not a runtime rule.

### The workspace-anchor rule

`trailblaze.yaml` is the workspace config filename, not a trail file. Trailblaze treats the
copy at `trails/config/trailblaze.yaml` as project config and never runs it as a trail. Use
`trail.yaml` for trail definitions.

## What gets excluded

Trailblaze prunes six directory names at any depth during discovery, matching by exact name:

- `build/`
- `.gradle/`
- `.git/`
- `node_modules/`
- `.trailblaze/` (Trailblaze's own state / cache convention)
- `.claude/` (AI-assistant metadata directory)

This applies to any directory with that name in the tree, not just the top level — so `features/checkout/build/cached.trail.yaml` is pruned the same as `build/cached.trail.yaml`. Generated files under build output never accidentally run as tests.

`.gitignore` is **not** currently honored. If you have a custom convention like `out/` or `.cache/`, discovery will see trail-shaped files in it. Either rename your directory to one of the excluded names above, or watch for `.gitignore` support to land. Until then, keep generated output under `build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`, or `.claude/` and it will be ignored automatically.

## Symlinks

Discovery does **not** follow symbolic links:

- A symlinked scan root (`trailblaze run ./symlinked-workspace`) returns no trails. Pass the real path instead.
- A symlink inside the workspace that points at a file or directory outside the workspace is not traversed, and is not discoverable by name. This is the defense against a trail-shaped symlink escaping the workspace tree.

If you rely on symlinked layouts and need to re-enable link traversal, please [open an issue on GitHub](https://github.com/block/trailblaze/issues/new) with your use case.

## Running trails

`trailblaze run` takes one or more files, shell globs, OR directories, and runs them sequentially against the connected device. Directory arguments are expanded recursively to one trail per containing directory: the most-specific legacy classifier recording wins (kept for back-compat), then the unified `trail.yaml`, then the legacy `blaze.yaml`. If no argument is given, `trailblaze run` errors as a misuse — name a trail file or a directory (e.g. `trailblaze run trails/`) to opt into fan-out explicitly.

### A single file

```bash
trailblaze run flows/login/trail.yaml
```

### Multiple explicit files

```bash
trailblaze run flows/login/trail.yaml flows/checkout/trail.yaml
```

### A directory (recursive expansion)

```bash
trailblaze run flows/
```

The CLI walks `flows/` recursively, applies the [exclude list](#what-gets-excluded), and runs at most one trail per containing directory — resolving in priority order: the device-classifier-matched legacy recording (e.g. `android-phone.trail.yaml`, kept so unmigrated directories run unchanged), then the unified `trail.yaml`, then the legacy NL `blaze.yaml`. Use `--device <platform>` to pin which device's recording slot (or legacy recording) wins.

### A whole tree via shell glob

```bash
# bash (with globstar enabled), zsh
trailblaze run flows/**/trail.yaml

# plain bash
trailblaze run $(find flows -name 'trail.yaml')
```

Your shell expands the glob into a file list before the CLI sees it. Trailblaze runs each matched file and reports a pass/fail total at the end. Exit code is non-zero if any trail fails. Note that `*.trail.yaml` does **not** match the unified `trail.yaml` filename — glob for `**/trail.yaml`, or prefer directory mode below, which resolves both shapes.

### Directory mode vs. shell globs

Both work. Pick based on intent:

- **Directory mode** (`trailblaze run flows/`) — one trail per containing directory, with classifier-aware resolution (legacy classifier recording → unified `trail.yaml` → legacy `blaze.yaml`). Best when each trail lives in its own directory under `flows/` and you want the canonical file picked automatically — this is the natural fit for unified trails. The exclude list (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`, `.claude/`) keeps generated output from accidentally running.
- **Shell glob** (`trailblaze run flows/**/trail.yaml`) — every matched file runs. Best when you want explicit, predictable expansion that `find` / `xargs` / CI scripting can compose cleanly with. In a tree that still holds legacy per-platform recordings, glob `flows/**/*.trail.yaml` as well (or instead) to match them.

### From a running daemon vs. in-process

```bash
trailblaze run flows/**/trail.yaml               # delegates to the running daemon if one exists
trailblaze run flows/**/trail.yaml --no-daemon   # always runs in-process
```

Both paths run the same files in the same order.

## Common questions

**Do I need a `trails/` directory?**
No for a one-off trail file you run directly by path. Yes if you want the current
project-level workspace model: `trails/` is the anchor directory, and
`trails/config/trailblaze.yaml` is how Trailblaze discovers project config automatically.

**Can I organize by feature?**
Yes, and it's often clearer. Put a trail next to the code it covers (e.g., `features/checkout/trail.yaml`) and run it with `trailblaze run features/checkout/trail.yaml`, point the CLI at the directory (`trailblaze run features/checkout/`), or glob the whole tree with `trailblaze run features/**/trail.yaml`.

**Can I run a trail by bare name from the CLI?**
No — `trailblaze run` takes file paths, shell globs, or directory paths, not names, and errors out if an argument isn't an existing file or directory. A unified trail takes its identity from its directory, so give each trail a unique directory name (e.g. `flows/login/trail.yaml` vs `flows/login-alt/trail.yaml`) and path-based runs stay unambiguous.

**Why doesn't my `.cache/mytrail.trail.yaml` get picked up?**
It's not an excluded directory (`.cache` isn't in the hardcoded list), so it should be picked up. If it isn't, check that the file actually ends in `.trail.yaml` or is named exactly `trail.yaml` or `blaze.yaml`, and that no ancestor directory is one of the excluded names (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`, `.claude/`).

**Where do recordings get saved?**
By default, alongside the trail they replay. For a unified `trail.yaml`, a passing run's recording merges into that same file as the device's classifier slot — while the unified save default rolls out, opt in with `trailblaze config unified-recordings true` (or `TRAILBLAZE_UNIFIED_RECORDINGS=1`). Legacy trails get a per-device `<classifier>.trail.yaml` sibling. Pass `--no-record` to skip.
