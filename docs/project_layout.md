---
title: Project Layout
---

# Project Layout

Trailblaze imposes almost no structure on your project. Put trail files wherever makes sense — pass them to the CLI by path or shell glob; the MCP server and desktop UI auto-discover trails below your workspace root.

## The simplest project

A single trail file is a complete Trailblaze project:

```
my-project/
└── login.trail.yaml
```

Run it with:

```bash
trailblaze trail login.trail.yaml
```

No config, no directory layout, no setup.

## A configured project

When you want project-level defaults (a target app, an LLM default, custom tools), drop a `trailblaze.yaml` at the project root:

```
my-project/
├── trailblaze.yaml          ← project config (workspace anchor)
├── login.trail.yaml         ← trails live wherever you want them
├── flows/
│   └── checkout.trail.yaml
└── catalog/
    └── overlay/
        └── blaze.yaml       ← NL-only trail (no platform recording yet)
```

The `trailblaze.yaml` file is the **workspace anchor** — the directory that contains it is the workspace root. Trailblaze walks up from the current directory (or the directory containing the trail you invoke) until it finds `trailblaze.yaml`; everything below that directory belongs to the workspace. Trails, recordings, and custom tool scripts referenced from `trailblaze.yaml` are resolved relative to it.

## Trail file names

Two filenames are recognized as trails, matched case-sensitively on the exact name:

| Filename         | Purpose                                                                 |
|------------------|-------------------------------------------------------------------------|
| `*.trail.yaml`   | Any file ending in `.trail.yaml`. Typically a platform-specific recording (e.g., `android-phone.trail.yaml`, `ios-iphone.trail.yaml`). |
| `blaze.yaml`     | Natural-language-only trail definition (no recording). Use this for new trails. |

A single directory can hold one `blaze.yaml` alongside one or more `*.trail.yaml` recordings (e.g., `android-phone.trail.yaml`, `ios-iphone.trail.yaml`). Each file is its own trail from the runner's point of view; there is no CLI-side variant auto-selection. Pass each file explicitly or use a shell glob (`trailblaze trail flows/**/*.trail.yaml`) — every matched file runs. The desktop UI's Trails browser does group files in one directory into a single row with a variant chip per file for browsability — that's a UI affordance, not a runtime rule.

### The workspace-anchor rule

`trailblaze.yaml` is the workspace config filename — it holds project-level defaults (target app, LLM, custom tools), not trail steps. Trailblaze treats the copy sitting **at the workspace root** as the config file and never runs it as a trail. Use `blaze.yaml` for natural-language trail definitions.

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

- A symlinked scan root (`trailblaze trail ./symlinked-workspace`) returns no trails. Pass the real path instead.
- A symlink inside the workspace that points at a file or directory outside the workspace is not traversed, and is not discoverable by name. This is the defense against a trail-shaped symlink escaping the workspace tree.

If you rely on symlinked layouts and need to re-enable link traversal, please [open an issue on GitHub](https://github.com/block/trailblaze/issues/new) with your use case.

## Running trails

`trailblaze trail` takes one or more `.yaml` trail files and runs them sequentially against the connected device. It doesn't accept directories — use your shell's glob to expand a tree into a file list.

### A single file

```bash
trailblaze trail flows/login.trail.yaml
```

### Multiple explicit files

```bash
trailblaze trail flows/login.trail.yaml flows/checkout.trail.yaml
```

### A whole tree via shell glob

```bash
# bash (with globstar enabled), zsh
trailblaze trail flows/**/*.trail.yaml

# plain bash
trailblaze trail $(find flows -name '*.trail.yaml')
```

Your shell expands the glob into a file list before the CLI sees it. Trailblaze runs each matched file and reports a pass/fail total at the end. Exit code is non-zero if any trail fails.

### Why no directory mode?

Explicit globs let you see exactly which files will run, and they compose cleanly with `find`, `xargs`, and CI scripting. A `trailblaze trail flows/` "auto-discover" mode would need hidden exclude rules (what about `flows/build/`? `flows/.gradle/`?), and you'd have to read the docs to predict what runs. The glob is self-documenting.

### From a running daemon vs. in-process

```bash
trailblaze trail flows/**/*.trail.yaml               # delegates to the running daemon if one exists
trailblaze trail flows/**/*.trail.yaml --no-daemon   # always runs in-process
```

Both paths run the same files in the same order.

## MCP integration

The MCP server (`trailblaze mcp`) exposes discovery through two tool actions:

- `trail(action=LIST)` — paginated list of every trail discovered under the MCP server's trails directory, with titles extracted from each trail's YAML header. The trails directory currently defaults to `./trails` relative to where the MCP server was started; launch the server from your project root to scope discovery to your workspace. A Phase 5 settings cleanup will align this with the CLI's walk-up workspace rule; until then, `cd` to the right directory before `trailblaze mcp`.
- `trail(action=RUN, name=<name>)` — MCP-only: resolves a bare name (e.g., `login` or `checkout`) to a specific trail file via `findTrailByName`, which walks the same tree and picks the first match by filename or parent-directory-name. Then runs it.

Both apply the same excludes and anchor rule as the CLI. An agent that asks for "login" won't accidentally find a stale cached copy under `build/`.

## Common questions

**Do I need a `trails/` directory?**
No. Trails can live anywhere under your workspace root — alongside your feature code, under `tests/`, in a single flat directory, wherever. The pre-Phase-3 convention was `trails/` at the project root; that still works, it's just not required.

**Can I organize by feature?**
Yes, and it's often clearer. Put a trail next to the code it covers (e.g., `features/checkout/checkout.trail.yaml`) and run it with `trailblaze trail features/checkout/checkout.trail.yaml` or glob the whole tree with `trailblaze trail features/**/*.trail.yaml`.

**Can I run a trail by bare name from the CLI?**
No — `trailblaze trail` takes one or more file paths, not names, and errors out if an argument isn't an existing file. Bare-name lookup is an MCP-only feature (`trail(action=RUN, name="login")` in the MCP tool set). If an AI agent resolves two files with the same base name under the same workspace, the first match in filesystem-visit order wins; prefer unique directory names (e.g. `flows/login/blaze.yaml` vs `flows/login-alt/blaze.yaml`) so agents land on the trail you expect.

**Why doesn't my `.cache/mytrail.trail.yaml` get picked up?**
It's not an excluded directory (`.cache` isn't in the hardcoded list), so it should be picked up. If it isn't, check that the file actually ends in `.trail.yaml` or is named exactly `blaze.yaml`, and that no ancestor directory is one of the excluded names (`build/`, `.gradle/`, `.git/`, `node_modules/`, `.trailblaze/`).

**Where do recordings get saved?**
By default, alongside the trail they replay — `trailblaze trail login.trail.yaml` writes its recording to the same directory. Pass `--no-record` to skip.
