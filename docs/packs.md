# Packs

A **pack** is a self-contained directory of Trailblaze configuration that wires up a target,
its toolsets, scripted tools, and waypoints together. Packs are the recommended unit of
authoring going forward — a workspace declares which packs it loads, and each pack groups
everything needed to test one app surface in one directory.

## Layout

A pack lives in a directory and is anchored by a `pack.yaml` manifest:

```text
my-workspace/trailblaze-config/
├── trailblaze.yaml                # workspace anchor — declares which packs to load
└── packs/
    └── myapp/
        ├── pack.yaml              # the manifest
        ├── tools/
        │   ├── myapp_login.yaml          # one scripted tool per file
        │   └── myapp_navigate.yaml
        ├── toolsets/                     # optional pack-local toolsets
        │   └── myapp_extras.yaml
        └── waypoints/                    # optional waypoints owned by this pack
            └── myapp-home-screen.waypoint.yaml
```

`trailblaze.yaml` references the pack:

```yaml
packs:
  - packs/myapp/pack.yaml
```

## The `pack.yaml` manifest

```yaml
id: myapp
target:
  display_name: My App
  tools:                                      # per-file scripted tool refs
    - tools/myapp_login.yaml
    - tools/myapp_navigate.yaml
  platforms:
    android:
      app_ids:
        - com.example.myapp
      tool_sets:
        - core_interaction
        - verification
        - myapp_extras                        # pack-local toolset

toolsets:                                     # pack-level file refs
  - toolsets/myapp_extras.yaml

waypoints:
  - waypoints/myapp-home-screen.waypoint.yaml
```

### Field reference

| Field | Status | Purpose |
| --- | --- | --- |
| `id` | required | Unique pack id. Used for shadowing / overrides. |
| `target` | optional | Embeds an `AppTargetYamlConfig` (id is inherited from the pack id when omitted). A pack without `target:` is treated as a library pack — it contributes via `defaults:` / `toolsets:` / `tools:` / `waypoints:` but isn't surfaced as a runnable target. `target.tools:` lists per-file scripted tool YAML paths under the pack directory. |
| `target.system_prompt_file` | optional | Pack-relative path to a markdown / text file containing the target's system-prompt template. The pack loader (and the build-time generator) reads the file and inlines its content into the generated target YAML's `system_prompt:` field. Lets authors keep multi-paragraph prompts in a standalone editable file rather than an unwieldy YAML string. Inline `system_prompt:` is not supported on `target` — a pack manifest declaring it fails the load with a migration message. The file-only shape leaves room for future per-device or per-classifier prompt selection (e.g. `app-tablet.prompt.md`) without an authoring schema change. |
| `dependencies` | optional | Pack ids this pack depends on. Transitive — depending on a pack pulls in its dependencies too. Resolves via closest-to-root-wins inheritance from each dep's `defaults:` (see below). |
| `defaults` | optional | Per-platform defaults this pack contributes to consumers via dependency resolution. Same shape as `target.platforms`. Consumers with `dependencies: [<this pack>]` inherit any field they leave null. |
| `toolsets` | optional | List of pack-relative paths to `ToolSetYamlConfig` files. |
| `tools` | optional | List of pack-relative paths to `ToolYamlConfig` files (class-backed or YAML-composed). Distinct from `target.tools:`, which holds scripted-tool refs (`PackScriptedToolFile`). |
| `waypoints` | optional | List of pack-relative paths to `WaypointDefinition` files. Wired through `loadResolvedRuntime()` and surfaced to the `trailblaze waypoint` CLI. |
| `trails` | reserved | First-class artifact loading deferred. |

> The `routes:` field was removed in 2026-04-28 — routes were dropped as a separate
> concept in favor of "shortcuts that invoke other shortcuts."

> The legacy reserved-slot fields `use:` / `extend:` / `replace:` were removed in favour
> of the unified `dependencies:` field. Workspace packs that still declare them get a
> one-shot deprecation warning at load time; migrate to `dependencies:` to restore
> composition.

### Composition via `dependencies:` and `defaults:`

A pack composes other packs by listing their ids:

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

The framework ships a single `trailblaze` pack that publishes the standard per-platform
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

The migrated `contacts` pack (39 lines → 18 lines) is a worked example — see
`trailblaze-models/src/commonMain/resources/trailblaze-config/packs/contacts/pack.yaml`.

## Per-file scripted tools

Each entry under `target.tools:` is a path to a YAML file with this shape:

```yaml
script: ./examples/myapp/trailblaze-config/tools/myapp_login.js
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

The pack loader translates the flat `inputSchema` into a JSON-Schema-conformant object
(`{ type: object, properties: { ... }, required: [ ... ] }`) before handing it to the runtime.
See [`PackScriptedToolFile`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/PackScriptedToolFile.kt)
for the field-level conventions:

- `required: true` is the per-property default — set `required: false` for optional params.
- `enum: [a, b, c]` constrains a string parameter to a fixed set.
- `_meta.trailblaze/supportedPlatforms` is case-insensitive (`[web]`, `[WEB]`, `[Web]` all
  collapse to the canonical form at parse time).
- `script:` paths resolve from the **JVM working directory** (typically the repo root), not
  the pack directory — the subprocess execution model anchors at CWD. This is documented in
  the kdoc on `PackScriptedToolFile.script`.

## Tool YAML file suffixes — `.tool.yaml`, `.shortcut.yaml`, `.trailhead.yaml`

Files under `tools/` use one of three suffixes that signal the tool's operational class.
The loader enforces that the file's content matches what the suffix promises — a
`.tool.yaml` file with a stray `shortcut:` block is a load-time error.

| Suffix              | Class      | Available when                       | Required block       |
| ------------------- | ---------- | ------------------------------------ | -------------------- |
| `*.tool.yaml`       | tool       | toolset rules (existing)             | (none)               |
| `*.shortcut.yaml`   | shortcut   | current waypoint matches `from`      | `shortcut:`          |
| `*.trailhead.yaml`  | trailhead  | always (bootstrap from any state)    | `trailhead:`         |

The three classes share one data class (`ToolYamlConfig`) with two optional metadata
blocks (`shortcut`, `trailhead`); they're mutually exclusive — a tool can't be both a
shortcut and a trailhead.

### Shortcut tools

A **shortcut** is a tool with a populated `shortcut: { from, to }` block — an authored
navigation edge between two waypoints with a runtime pre/post-condition contract. Same
file format, same registry, same agent-facing tool descriptor as any other tool; the
framework adds a contextual descriptor filter (only surfaces shortcut tools whose `from`
matches the current waypoint) and a pre/post-condition wrapper at execution time.

```yaml
# packs/clock/tools/clock_create_alarm.shortcut.yaml
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
# packs/myapp/tools/myapp_launchAppSignedIn.trailhead.yaml
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

Packs come from two sources at runtime:

1. **Workspace packs** declared by the workspace's `trailblaze.yaml` (`packs:` list).
2. **Classpath packs** discovered automatically from any `trailblaze-config/packs/<id>/pack.yaml`
   resource shipped on the JVM classpath (framework-bundled examples like `clock`,
   `wikipedia`, plus any dependency that ships its own packs).

When a workspace pack and a classpath pack share an id, **the workspace pack wholesale shadows
the classpath one** — none of the classpath pack's target / toolsets / tools / waypoints leak
through. This precedence rule is the operative knob: to override a framework-bundled pack,
declare a workspace pack with the same id and author whatever subset you need.

If two classpath jars both ship a pack with the same id, the loader keeps the first one
discovered and logs a warning identifying both locations — resolve by ensuring only one
bundled jar declares each pack id.

## Compile output: `trails/config/dist/`

`trailblaze compile` is the pack→target compile step (think `javac` for packs). It reads
your workspace's pack manifests under `trails/config/packs/<id>/pack.yaml`, walks the
dependency graph with closest-wins inheritance, validates every `tool_sets:`,
`drivers:`, `tools:`, and `excluded_tools:` reference against the discovered pool, and
emits one materialized `<id>.yaml` per app pack into `trails/config/dist/targets/`.

```
trails/config/
├── trailblaze.yaml                    # workspace anchor
├── packs/                             # SOURCE — author here, commit here
│   ├── myapp/pack.yaml
│   └── shared-toolset/pack.yaml
└── dist/                              # OUTPUT — generated, NEVER commit
    └── targets/
        └── myapp.yaml
```

The `dist/` directory is in `.gitignore` by default — its contents are a build artifact,
not source. The compile step is idempotent: stale `<id>.yaml` files left in `dist/targets/`
from a previous compile that no longer correspond to a current pack are deleted
automatically (orphan cleanup). The compiler only manages files that bear its
`# GENERATED BY trailblaze compile. DO NOT EDIT.` banner — hand-authored YAMLs in
`dist/targets/` are left alone.

The framework JAR's bundled targets (under
`trailblaze-models/src/commonMain/resources/trailblaze-config/targets/`) are
intentionally checked-in build outputs of the same compile step run at framework build
time via the `trailblaze.bundled-config` Gradle plugin — that's a different lifecycle
from workspace `dist/` and explicitly NOT covered by the gitignore rule. See
`trailblaze-models/build.gradle.kts` for how the plugin is wired and
`build-logic/src/main/kotlin/TrailblazeBundledConfigPlugin.kt` for the
`generateBundledTrailblazeConfig` / `verifyBundledTrailblazeConfig` task pair.

Compile-time validation:

- **Missing or cyclic `dependencies:`** → compile error names the offending pack so
  you can jump straight to the manifest.
- **Typo in `tool_sets:`, `tools:`, `excluded_tools:`, or `drivers:`** → compile error
  names the target id, platform, field, and unknown reference. The runtime resolver's
  graceful "skip the broken pack and keep going" behavior is overridden at compile
  time so authors see "compilation errors" before runtime sees a silently-missing
  capability.
- **Pack manifest YAML parse error** → compile error names the offending pack ref.

## Authoring

**Authoring rule of thumb**: prefer one pack per app surface. The clock pack ships waypoints,
the contacts example ships scripted iOS tools, the playwright sample ships web fixture
tools. Each is independently mountable, debuggable, and reviewable in one place.

When in doubt, look at the working examples:

- `examples/ios-contacts/trailblaze-config/packs/contacts/`
- `examples/playwright-native/trailblaze-config/packs/playwrightsample/`
- `trailblaze-models/src/commonMain/resources/trailblaze-config/packs/clock/`

## Migration notes

### Pack id `ioscontacts` → `contacts`

The iOS Contacts example workspace pack was renamed from `ioscontacts` to `contacts` so the
same pack can later host Android / web tools alongside the iOS ones (the pack is the
*Contacts app* pack, not just the iOS subset). CLI users who pinned `--target ioscontacts`
should update to `--target contacts`. Within this example workspace, the new id shadows the
bundled framework `contacts` pack (Google Contacts) per the precedence rule above.

### From a flat `targets/<id>.yaml` to a pack

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
packs/myapp/
├── pack.yaml                       # id, target.display_name, target.tools refs, platforms
└── tools/
    └── myapp_login.yaml            # script, name, description, flat inputSchema
```

with the workspace's `trailblaze.yaml` adding `packs: [packs/myapp/pack.yaml]`.

The `targets/myapp.yaml` flat form is still supported for now (legacy-only); new authoring
should use packs. The flat-target tools list (`tools: [...]` with inline `InlineScriptToolConfig`
entries) is preserved verbatim for legacy use, but the per-file `PackScriptedToolFile` shape
with flat `inputSchema` is the recommended path.
