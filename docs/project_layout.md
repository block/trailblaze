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
    ├── login.trail.yaml
    ├── flows/
    │   └── checkout.trail.yaml
    └── catalog/
        └── overlay/
            └── blaze.yaml   ← NL-only trail (no platform recording yet)
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

If you author scripted tools, the framework also lays down generated files under
`trails/config/trailmaps/` and `trails/.trailblaze/` (typed bindings, a per-trailmap
`tsconfig.json`, the vendored SDK bundle). For what each one is and which to commit, see
[Scripted Tools — Project Layout & Generated Files](scripted-tools-project-layout.md).

## Trail file names

Two filenames are recognized as trails, matched case-sensitively on the exact name:

| Filename         | Purpose                                                                 |
|------------------|-------------------------------------------------------------------------|
| `*.trail.yaml`   | Any file ending in `.trail.yaml`. Typically a platform-specific recording (e.g., `android-phone.trail.yaml`, `ios-iphone.trail.yaml`). |
| `blaze.yaml`     | Natural-language-only trail definition (no recording). Use this for new trails. |

A single directory can hold one `blaze.yaml` alongside one or more `*.trail.yaml` recordings (e.g., `android-phone.trail.yaml`, `ios-iphone.trail.yaml`). Each file is its own trail from the runner's point of view; there is no CLI-side variant auto-selection. Pass each file explicitly or use a shell glob (`trailblaze run flows/**/*.trail.yaml`) — every matched file runs. The desktop UI's Trails browser does group files in one directory into a single row with a variant chip per file for browsability — that's a UI affordance, not a runtime rule.

### The workspace-anchor rule

`trailblaze.yaml` is the workspace config filename, not a trail file. Trailblaze treats the
copy at `trails/config/trailblaze.yaml` as project config and never runs it as a trail. Use
`blaze.yaml` for natural-language trail definitions.

## What gets excluded

Trailblaze prunes five directory names at any depth during discovery, matching by exact name:

- `build/`
- `.gradle/`
- `.git/`
- `node_modules/`
- `.trailblaze/` (Trailblaze's own state / cache convention)

This applies to any directory with that name in the tree, not just the top level — so `features/checkout/build/cached.trail.yaml` is pruned the same as `build/cached.trail.yaml`. Generated files under build output never accidentally run as tests.

`.gitignore` is **not** currently honored. If you have a custom convention like `out/` or `.cache/`, discovery will see trail-shaped files in it. Either rename your directory to one of the excluded names above, or watch for `.gitignore` support to land. Until then, keep generated output under `build/`, `.gradle/`, `.git/`, `node_modules/`, or `.trailblaze/` and it will be ignored automatically.

## Symlinks

Discovery does **not** follow symbolic links:

- A symlinked scan root (`trailblaze run ./symlinked-workspace`) returns no trails. Pass the real path instead.
- A symlink inside the workspace that points at a file or directory outside the workspace is not traversed, and is not discoverable by name. This is the defense against a trail-shaped symlink escaping the workspace tree.

If you rely on symlinked layouts and need to re-enable link traversal, please [open an issue on GitHub](https://github.com/block/trailblaze/issues/new) with your use case.

## Running trails

`trailblaze run` takes one or more files, shell globs, OR directories, and runs them sequentially against the connected device. Directory arguments are expanded recursively to one trail per containing directory (with the platform-specific recording preferred over `blaze.yaml` when both are present). If no argument is given, the CLI walks up to the workspace root and defaults to its `trails/` directory.

### A single file

```bash
trailblaze run flows/login.trail.yaml
```

### Multiple explicit files

```bash
trailblaze run flows/login.trail.yaml flows/checkout.trail.yaml
```

### A directory (recursive expansion)

```bash
trailblaze run flows/
```

The CLI walks `flows/` recursively, applies the [exclude list](#what-gets-excluded), and runs at most one trail per containing directory — picking the device-classifier-matched recording (e.g. `android-phone.trail.yaml`) over the NL `blaze.yaml` when both exist. Use `--device <platform>` to pin which platform's recording wins.

### A whole tree via shell glob

```bash
# bash (with globstar enabled), zsh
trailblaze run flows/**/*.trail.yaml

# plain bash
trailblaze run $(find flows -name '*.trail.yaml')
```

Your shell expands the glob into a file list before the CLI sees it. Trailblaze runs each matched file and reports a pass/fail total at the end. Exit code is non-zero if any trail fails.

### Directory mode vs. shell globs

Both work. Pick based on intent:

- **Directory mode** (`trailblaze run flows/`) — one trail per containing directory, classifier-aware recording selection. Best when each trail lives in its own directory under `flows/` and you want the canonical recording picked automatically. The exclude list (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`) keeps generated output from accidentally running.
- **Shell glob** (`trailblaze run flows/**/*.trail.yaml`) — every matched file runs, including any per-platform variants in the same directory. Best when you want explicit, predictable expansion that `find` / `xargs` / CI scripting can compose cleanly with.

### From a running daemon vs. in-process

```bash
trailblaze run flows/**/*.trail.yaml               # delegates to the running daemon if one exists
trailblaze run flows/**/*.trail.yaml --no-daemon   # always runs in-process
```

Both paths run the same files in the same order.

## Common questions

**Do I need a `trails/` directory?**
No for a one-off trail file you run directly by path. Yes if you want the current
project-level workspace model: `trails/` is the anchor directory, and
`trails/config/trailblaze.yaml` is how Trailblaze discovers project config automatically.

**Can I organize by feature?**
Yes, and it's often clearer. Put a trail next to the code it covers (e.g., `features/checkout/checkout.trail.yaml`) and run it with `trailblaze run features/checkout/checkout.trail.yaml`, point the CLI at the directory (`trailblaze run features/checkout/`), or glob the whole tree with `trailblaze run features/**/*.trail.yaml`.

**Can I run a trail by bare name from the CLI?**
No — `trailblaze run` takes file paths, shell globs, or directory paths, not names, and errors out if an argument isn't an existing file or directory. If you have two trails with the same base name under the same workspace, prefer unique directory names (e.g. `flows/login/blaze.yaml` vs `flows/login-alt/blaze.yaml`) so that path-based runs are unambiguous.

**Why doesn't my `.cache/mytrail.trail.yaml` get picked up?**
It's not an excluded directory (`.cache` isn't in the hardcoded list), so it should be picked up. If it isn't, check that the file actually ends in `.trail.yaml` or is named exactly `blaze.yaml`, and that no ancestor directory is one of the excluded names (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`).

**Where do recordings get saved?**
By default, alongside the trail they replay — `trailblaze run login.trail.yaml` writes its recording to the same directory. Pass `--no-record` to skip.
