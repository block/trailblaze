# Trailmaps

A **trailmap** is a self-contained directory of Trailblaze configuration that wires up a target,
its toolsets, scripted tools, and waypoints together. Trailmaps are the recommended unit of
authoring going forward — a workspace declares which trailmaps it loads, and each trailmap groups
everything needed to test one app surface in one directory.

## Layout

A trailmap lives in a directory and is anchored by a `trailmap.yaml` manifest:

```text
my-workspace/trails/config/
├── trailblaze.yaml                # workspace anchor — declares which trailmaps to load
└── trailmaps/
    └── myapp/
        ├── trailmap.yaml              # the manifest
        ├── tools/
        │   ├── myapp_login.ts            # one scripted tool per .ts file
        │   ├── myapp_navigate.ts
        │   └── myapp_shared.ts           # shared helpers (no `trailblaze.tool` export)
        ├── toolsets/                     # optional trailmap-local toolsets
        │   └── myapp_extras.yaml
        └── waypoints/                    # optional waypoints owned by this trailmap
            └── myapp-home-screen.waypoint.yaml
```

`trailblaze.yaml` references the trailmap:

```yaml
trailmaps:
  - trailmaps/myapp/trailmap.yaml
```

## The `trailmap.yaml` manifest

```yaml
id: myapp
target:
  display_name: My App
  tools:                                      # bare export names from tools/*.ts
    - myapp_login
    - myapp_navigate
  platforms:
    android:
      app_ids:
        - com.example.myapp
      tool_sets:
        - core_interaction
        - verification
        - myapp_extras                        # trailmap-local toolset

toolsets:                                     # trailmap-level file refs
  - toolsets/myapp_extras.yaml

waypoints:
  - waypoints/myapp-home-screen.waypoint.yaml
```

### Field reference

> **Heads up on the two `tools` fields.** Top-level `tools:` (row below) lists
> trailmap-relative *paths* to class-backed or pure-YAML composed tools.
> `target.tools:` (a sub-field of `target:`) lists scripted TypeScript tools by their
> **bare export name**. Same word, different shape — pick by which flavor you're
> registering. See [Tool flavors](#tool-flavors-which-kind-do-i-write) below for the
> rubric.

| Field | Status | Purpose |
| --- | --- | --- |
| `id` | required | Unique trailmap id. Used for shadowing / overrides. |
| `target` | optional | Embeds an `AppTargetYamlConfig` (id is inherited from the trailmap id when omitted). A trailmap without `target:` is treated as a library trailmap — it contributes via `defaults:` / `toolsets:` / `tools:` / `waypoints:` but isn't surfaced as a runnable target. `target.tools:` lists scripted tools by their bare export name (one `.ts` per tool under `<trailmap>/tools/`). |
| `target.system_prompt_file` | optional | Trailmap-relative path to a markdown / text file containing the target's system-prompt template. The trailmap loader (and the build-time generator) reads the file and inlines its content into the generated target YAML's `system_prompt:` field. Lets authors keep multi-paragraph prompts in a standalone editable file rather than an unwieldy YAML string. Inline `system_prompt:` is not supported on `target` — a trailmap manifest declaring it fails the load with a migration message. The file-only shape leaves room for future per-device or per-classifier prompt selection (e.g. `app-tablet.prompt.md`) without an authoring schema change. |
| `dependencies` | optional | Trailmap ids this trailmap depends on. Transitive — depending on a trailmap pulls in its dependencies too. Resolves via closest-to-root-wins inheritance from each dep's `defaults:` (see below). |
| `defaults` | optional | Per-platform defaults this trailmap contributes to consumers via dependency resolution. Same shape as `target.platforms`. Consumers with `dependencies: [<this trailmap>]` inherit any field they leave null. |
| `toolsets` | optional | List of trailmap-relative paths to `ToolSetYamlConfig` files. |
| `tools` | optional | List of trailmap-relative paths to `ToolYamlConfig` files (class-backed or YAML-composed). Distinct from `target.tools:`, which holds scripted-tool refs (`TrailmapScriptedToolFile`). |
| `waypoints` | optional | List of trailmap-relative paths to `WaypointDefinition` files. Wired through `loadResolvedRuntime()` and surfaced to the `trailblaze waypoint` CLI. |
| `trails` | reserved | First-class artifact loading deferred. |

> The `routes:` field was removed in 2026-04-28 — routes were dropped as a separate
> concept in favor of "shortcuts that invoke other shortcuts."

> The legacy reserved-slot fields `use:` / `extend:` / `replace:` were removed in favour
> of the unified `dependencies:` field. Workspace trailmaps that still declare them get a
> one-shot deprecation warning at load time; migrate to `dependencies:` to restore
> composition.

### Composition via `dependencies:` and `defaults:`

A trailmap composes other trailmaps by listing their ids:

```yaml
id: contacts
dependencies:
  - trailblaze
target:
  display_name: Google Contacts
  platforms:
    android:
      app_ids: [com.google.android.contacts]
    ios: {}
    web: {}
    compose: {}
```

The framework ships a single `trailblaze` trailmap that publishes the standard per-platform
defaults. The conventional consumer preamble is `dependencies: [trailblaze]`. Resolution
rules:

- **Field-level closest-wins.** For each platform key the consumer declares, every field
  is resolved independently: the consumer's own non-null value wins; otherwise the
  dep-graph walk picks the value from the closest-depth contributor.
- **No list concatenation.** A consumer that writes `tool_sets:` for a platform
  *replaces* the inherited list entirely. This preserves the per-platform listing as
  visible documentation — authors who want explicit `tool_sets:` listings on every
  platform keep them; authors who want a one-line target file omit and inherit.
- **Tie-break: later-declared at same depth wins.** When two contributors at the same
  depth both supply a field for the same platform, the later one in DFS declaration
  order wins.
- **Platform set comes from the consumer.** Defaults only fill in fields for platforms
  the consumer explicitly declares. A consumer that wants e.g. `ios` with all defaults
  writes `ios: {}` — the empty map is the explicit signal "this platform exists, fill
  in everything from defaults."

The migrated `contacts` trailmap (39 lines → 18 lines) is a worked example — see
`trailblaze-models/src/commonMain/resources/trails/config/trailmaps/contacts/trailmap.yaml`.

## Tool flavors: which kind do I write?

A trailmap can contribute two flavors of custom tool. They share the `<trailmap>/tools/`
directory but bind through entirely different mechanisms — easy to copy-paste-confuse,
so this table is the rubric:

> **Default to scripted TypeScript.** Reach for pure-YAML only when the tool is a
> literal parameter substitution into an existing tool call — anything with a
> conditional, retry, or two-step sequence is a scripted tool.

| | **Scripted (TypeScript)** | **Pure-YAML composed** |
| --- | --- | --- |
| Pick when | You need branching, retries, async, multi-step orchestration | You need a thin wrapper that substitutes parameters into existing tool calls |
| Filename | `<id>.ts` — one file per tool | `<id>.tool.yaml` (note the double suffix) |
| Body | TypeScript in the `.ts`. The `trailblaze.tool<I, O>(spec, handler)` export carries everything (name from the export, schema from `<I>`, description from TSDoc). | `tools:` block — declarative composition of existing tools |
| Manifest entry | Listed by **bare export name** under `target.tools:` in `trailmap.yaml` | **Auto-discovered** — do NOT list in `trailmap.yaml` |
| Surfaced to a target via | Direct `target.tools:` reference | A toolset (`<workspace>/trails/config/toolsets/<id>.yaml` or `<trailmap>/toolsets/<id>.yaml`) that names the tool, declared from `platforms.<p>.tool_sets:` |
| Param schema source | The `<I>` type parameter on the `trailblaze.tool<I, O>` call. Per-field TSDoc becomes per-field JSON Schema `description`. | `parameters:` (list of `{name, type, required?, description?}`) |
| Host vs on-device | `requiresHost: true` on the typed spec — registration-time gate | Workspace tools default to `requires_host: true` (config can't ship on-device); bundled framework `*.tool.yaml` runs on either side because the file is on both classpaths |

> **Older trailmaps still using `<id>.yaml` + `<id>.ts` pairs?** That shape continues to
> work unmodified — see the [Scripted Tools — Legacy Reference](scripted_tools.md). New
> tools should use the `.ts`-only canonical shape above; convert in place when
> convenient.

The two most common mistakes when authoring a trailmap from scratch:

1. **Listing a `.tool.yaml` under `target.tools:` in `trailmap.yaml`.** The framework treats
   `target.tools:` entries as scripted tool descriptors and tries to decode each one as a
   `TrailmapScriptedToolFile` (which needs `script:` and `name:`). The loader now intercepts
   `.tool.yaml` paths and emits an actionable diagnostic that names the offending path
   and points at the auto-discovery convention. If you see
   `Trailmap '<id>': target.tools: listed '<path>.tool.yaml', but .tool.yaml files are pure-YAML composed tools that auto-discover...`,
   delete that entry from the manifest — leave the file in place.

2. **Using `parameters:` (list) in a scripted descriptor or `inputSchema:` (map) in a
   pure-YAML composed tool.** Each flavor uses a different shape; the other shape is
   silently ignored by the YAML decoder. Symptom: the tool registers but params behave
   as if the schema were empty. Check the columns above when params aren't flowing.

## Per-file scripted tools

The canonical shape for scripted tools is a single `.ts` file per tool with a
`trailblaze.tool<I, O>(spec, handler)` export. The export name is the dispatchable tool
name; the TSDoc above it is the LLM-facing description; the `<I>` type parameter is the
input schema. **No sibling YAML descriptor is required.**

See [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the full
authoring reference, the IDE-typings pipeline, the testing helpers, and the worked
iOS Contacts + Wikipedia examples.

Each entry under `target.tools:` is the **bare export name** of a scripted tool:

```yaml
target:
  tools:
    - myapp_login                   # matches `export const myapp_login = trailblaze.tool(...)` in tools/myapp_login.ts
    - myapp_navigate
```

The trailmap loader walks `tools/` for every `.ts` that exports a `trailblaze.tool(...)`
declaration; the names listed under `target.tools:` decide which of those are advertised
to the agent for this target. Files in `tools/` that don't export a tool (shared
helpers, type-only modules) are ignored at registration.

For trailmaps that haven't migrated yet, the legacy YAML-descriptor shape continues to
work — each entry under `target.tools:` is then a bare name (resolved against the
descriptor's `name:` field). See
[Scripted Tools — Legacy Reference](scripted_tools.md) for the full descriptor schema
and the field-level conventions in
[`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt).

## Tool YAML file suffixes — `.tool.yaml`, `.shortcut.yaml`, `.trailhead.yaml`

Each operational class lives under its own trailmap subdirectory and uses a matching filename
suffix. The loader enforces that the file's content matches what the suffix promises — a
`.tool.yaml` file with a stray `shortcut:` block is a load-time error.

| Suffix              | Trailmap subdir   | Class      | Available when                       | Required block       |
| ------------------- | ------------- | ---------- | ------------------------------------ | -------------------- |
| `*.tool.yaml`       | `tools/`      | tool       | a toolset names it under `tools:`    | (none)               |
| `*.shortcut.yaml`   | `shortcuts/`  | shortcut   | current waypoint matches `from`      | `shortcut:`          |
| `*.trailhead.yaml`  | `trailheads/` | trailhead  | always (bootstrap from any state)    | `trailhead:`         |

Subdirectories below each top-level dir are organizational only — the loader walks them
recursively at any depth. A trailmap with multi-platform shortcuts can group them as
`shortcuts/{android,ios,web}/...` (or any other grouping that fits) without changing how
discovery works.

The three classes share one data class (`ToolYamlConfig`) with two optional metadata
blocks (`shortcut`, `trailhead`); they're mutually exclusive — a tool can't be both a
shortcut and a trailhead.

> **Workspace `*.tool.yaml` auto-discovery.** A workspace-authored pure-YAML composed
> tool is discoverable by the resolver at session start (no manifest entry needed), but
> the framework only surfaces it to a target when **some toolset names it under `tools:`**.
> The common shape is one workspace toolset at `<workspace>/trails/config/toolsets/<id>.yaml`
> (or `<trailmap>/toolsets/<id>.yaml`) that lists the tool, and one target referencing that
> toolset under `platforms.<p>.tool_sets:`. Workspace tools default to host-side
> execution (`requires_host = true` implicit) — the framework's bundled `*.tool.yaml`
> resources ride on both host and device classpaths and execute on whichever side runs
> them, but workspace files only live on the host, so the dispatcher routes them locally.
> Authors who explicitly set `requires_host: false` keep that explicit value.

### Shortcut tools

A **shortcut** is a tool with a populated `shortcut: { from, to }` block — an authored
navigation edge between two waypoints with a runtime pre/post-condition contract. Same
file format, same registry, same agent-facing tool descriptor as any other tool; the
framework adds a contextual descriptor filter (only surfaces shortcut tools whose `from`
matches the current waypoint) and a pre/post-condition wrapper at execution time.

```yaml
# trailmaps/clock/shortcuts/clock_create_alarm.shortcut.yaml
id: clock_create_alarm
description: Create an alarm at a given time.
parameters:
  - name: hour
    type: integer
    required: true
  - name: minute
    type: integer
    required: true
shortcut:
  from: clock/android/alarm_tab
  to:   clock/android/alarm_saved
tools:
  - tapElement: { selector: { textRegex: 'Add alarm' } }
  - inputText:  { text: '{{params.hour}}:{{params.minute}}' }
  - tapElement: { selector: { text: 'OK' } }
```

The metadata block adds:

- `from` / `to` — slash-namespaced waypoint ids the framework matches against current
  state. Both are required; the framework refuses to invoke if `from` doesn't match at
  call time and reports failure if the post-state doesn't match `to`.
- `variant` — optional disambiguator when multiple shortcuts share the same `(from, to)`
  pair. Most shortcuts never need it.

### Trailhead tools

A **trailhead** is a tool with a populated `trailhead: { to }` block — a bootstrap
primitive that takes the agent from any state to a known waypoint. Always available
(no `from` precondition). The framework asserts the agent landed at `to` after the
body runs, just like a shortcut's post-condition. Trailheads are the right shape for
"launch the app and reach a logged-in screen", "force-quit and re-sign-in", and similar
reset/genesis moves that need to work regardless of where the agent currently is.

```yaml
# trailmaps/myapp/trailheads/myapp_launchAppSignedIn.trailhead.yaml
id: myapp_launchAppSignedIn
description: Launch MyApp and sign in to the home screen.
parameters:
  - name: email
    default: '{{memory.email}}'
  - name: password
    default: '{{memory.password}}'
trailhead:
  to: myapp/android/home_signed_in
class: com.example.myapp.LaunchAppSignedInTool
```

A trailhead is one reusable atom. Trail-level setup (the trail's own `trailhead.setup:`
section in the v2 trail YAML) composes one or more trailheads alongside other tools —
e.g. setting a feature flag before invoking a launch trailhead. The trailhead is the
atom; the trail's setup is the orchestration.

### Authoring forms

The body of any of the three classes uses one of `class:` (Kotlin-backed) or `tools:`
(declarative YAML composition). The `tools:` form is the happy path — machine-readable,
no conditionals, no loops, covers the majority case. A future `script:` mode for TS/JS
bodies will land when shortcuts/trailheads need real code (branching, retries) the YAML
form can't express.

## Discovery and precedence

Trailmaps come from two sources at runtime:

1. **Workspace trailmaps** declared by the workspace's `trailblaze.yaml` (`trailmaps:` list).
2. **Classpath trailmaps** discovered automatically from any `trails/config/trailmaps/<id>/trailmap.yaml`
   resource shipped on the JVM classpath (framework-bundled examples like `clock`,
   `wikipedia`, plus any dependency that ships its own trailmaps).

When a workspace trailmap and a classpath trailmap share an id, **the workspace trailmap wholesale shadows
the classpath one** — none of the classpath trailmap's target / toolsets / tools / waypoints leak
through. This precedence rule is the operative knob: to override a framework-bundled trailmap,
declare a workspace trailmap with the same id and author whatever subset you need.

If two classpath jars both ship a trailmap with the same id, the loader keeps the first one
discovered and logs a warning identifying both locations — resolve by ensuring only one
bundled jar declares each trailmap id.

## Compile output: `trails/config/dist/`

`trailblaze check` is the trailmap→target compile step (think `javac` for trailmaps). It reads
your workspace's trailmap manifests under `trails/config/trailmaps/<id>/trailmap.yaml`, walks the
dependency graph with closest-wins inheritance, validates every `tool_sets:`,
`drivers:`, `tools:`, and `excluded_tools:` reference against the discovered pool, and
emits one materialized `<id>.yaml` per app trailmap into `trails/config/dist/targets/`.

```
trails/config/
├── trailblaze.yaml                    # workspace anchor
├── trailmaps/                             # SOURCE — author here, commit here
│   ├── myapp/trailmap.yaml
│   └── shared-toolset/trailmap.yaml
└── dist/                              # OUTPUT — generated, NEVER commit
    └── targets/
        └── myapp.yaml
```

The `dist/` directory is in `.gitignore` by default — its contents are a build artifact,
not source. The compile step is idempotent: stale `<id>.yaml` files left in `dist/targets/`
from a previous compile that no longer correspond to a current trailmap are deleted
automatically (orphan cleanup). The compiler only manages files that bear its
`# GENERATED BY trailblaze check. DO NOT EDIT.` banner — hand-authored YAMLs in
`dist/targets/` are left alone.

The framework JAR's bundled targets (under
`trailblaze-models/src/commonMain/resources/trails/config/targets/`) are
intentionally checked-in build outputs of the same compile step run at framework build
time via the `trailblaze.bundled-config` Gradle plugin — that's a different lifecycle
from workspace `dist/` and explicitly NOT covered by the gitignore rule. See
`trailblaze-models/build.gradle.kts` for how the plugin is wired and
`build-logic/src/main/kotlin/TrailblazeBundledConfigPlugin.kt` for the
`generateBundledTrailblazeConfig` / `verifyBundledTrailblazeConfig` task pair.

Compile-time validation:

- **Missing or cyclic `dependencies:`** → compile error names the offending trailmap so
  you can jump straight to the manifest.
- **Typo in `tool_sets:`, `tools:`, `excluded_tools:`, or `drivers:`** → compile error
  names the target id, platform, field, and unknown reference. The runtime resolver's
  graceful "skip the broken trailmap and keep going" behavior is overridden at compile
  time so authors see "compilation errors" before runtime sees a silently-missing
  capability.
- **Trailmap manifest YAML parse error** → compile error names the offending trailmap ref.

## Authoring

**Authoring rule of thumb**: prefer one trailmap per app surface. The clock trailmap ships waypoints,
the contacts example ships scripted iOS tools, the playwright sample ships web fixture
tools. Each is independently mountable, debuggable, and reviewable in one place.

When in doubt, look at the working examples:

- `examples/ios-contacts/trails/config/trailmaps/contacts/`
- `examples/playwright-native/trails/config/trailmaps/playwrightSample/`
- `trailblaze-models/src/commonMain/resources/trails/config/trailmaps/clock/`

## Migration notes

### Trailmap id `ioscontacts` → `contacts`

The iOS Contacts example workspace trailmap was renamed from `ioscontacts` to `contacts` so the
same trailmap can later host Android / web tools alongside the iOS ones (the trailmap is the
*Contacts app* trailmap, not just the iOS subset). CLI users who pinned `--target ioscontacts`
should update to `--target contacts`. Within this example workspace, the new id shadows the
bundled framework `contacts` trailmap (Google Contacts) per the precedence rule above.

### From a flat `targets/<id>.yaml` to a trailmap

A flat target like:

```yaml
# targets/myapp.yaml
id: myapp
display_name: My App
platforms:
  android: { ... }
tools:
  - script: ./tools/myapp_login.js
    name: myapp_login
    description: ...
    inputSchema: { type: object, properties: { ... } }
```

becomes:

```text
trailmaps/myapp/
├── trailmap.yaml                       # id, target.display_name, target.tools refs, platforms
└── tools/
    └── myapp_login.ts                  # typed `trailblaze.tool<I, O>(spec, handler)` export
```

with the workspace's `trailblaze.yaml` adding `trailmaps: [trailmaps/myapp/trailmap.yaml]`.
See [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the per-tool
authoring shape.

The `targets/myapp.yaml` flat form is still supported for now (legacy-only); new authoring
should use trailmaps. The flat-target tools list (`tools: [...]` with inline `InlineScriptToolConfig`
entries) is preserved verbatim for legacy use, but the per-tool `.ts` shape is the
recommended path.
