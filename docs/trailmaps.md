# Trailmaps

A **trailmap** is a self-contained directory of Trailblaze configuration that wires up a target,
its toolsets, scripted tools, and waypoints together. Trailmaps are the recommended unit of
authoring going forward — a workspace declares which trailmaps it loads, and each trailmap groups
everything needed to test one app surface in one directory.

A trailmap is also the **unit of distribution**: once a team has authored one for their app, other
teams can pick it up either by cloning the workspace, by `file:` / `github:` reference, or
(eventually) by npm package. See [Publishing a Trailmap](publishing-a-trailmap.md) for the three
tiers and what works today vs. what's planned.

## Layout

A trailmap lives in a directory and is anchored by a `trailmap.yaml` manifest:

```text
my-workspace/trails/config/
├── trailblaze.yaml                # workspace anchor — trailmaps below auto-discover
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

`trailblaze.yaml` doesn't need to list the trailmap — every
`trailmaps/<id>/trailmap.yaml` under the workspace's config directory is auto-discovered.
The optional `targets:` field (a list of trailmap **ids**, not paths) opts the workspace
into a subset when you have many on-disk trailmaps and only want some to load:

```yaml
targets:
  - myapp
```

> The old `trailmaps:` field (a list of filesystem paths to manifests) was removed in
> favour of this id-based form.

## The `trailmap.yaml` manifest

```yaml
id: myapp
target:
  display_name: My App
  # No tools list: every tools/*.ts that exports a trailblaze.tool(...) is on this target.
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

# waypoints need no manifest entry — every *.waypoint.yaml under waypoints/ auto-discovers
```

### Field reference

> **Heads up on how tools register.** Every scripted TypeScript tool in the trailmap's
> `tools/` folder is on its target without being listed; `excluded_tools:` opts one out.
> There is no top-level `tools:` field: Kotlin-backed tool descriptors
> (`*.tool.yaml`, plus `*.trailhead.yaml`) **auto-discover** from the
> trailmap's `tools/` / `trailheads/` directories, and waypoints
> auto-discover from `waypoints/`. See
> [Tool flavors](#tool-flavors-which-kind-do-i-write) below for the rubric.

| Field | Status | Purpose |
| --- | --- | --- |
| `id` | required | Unique trailmap id. Used for shadowing / overrides. |
| `target` | optional | Embeds an `AppTargetYamlConfig` (id is inherited from the trailmap id when omitted). A trailmap without `target:` is treated as a library trailmap — it contributes via `defaults:` / `toolsets:` / `tools:` / `waypoints:` but isn't surfaced as a runnable target. Every scripted tool under `<trailmap>/tools/` is on the target; `target.tools:` may still list them by bare export name, but no longer has to. |
| `target.icon` | optional | Workspace-relative path to an icon (Android launcher icon / web favicon) shown beside this target in the TrailRunner UI. When omitted, the UI falls back to a filename convention under a shared `assets/icons/` folder relative to the workspace's `trails/config/` directory: `assets/icons/android_<app_id>.png` for an Android target, `assets/icons/favicon_<host>.png` for a web target. Drop matching files into that folder and every target lights up with no per-target authoring; set `target.icon` to override. |
| `target.system_prompt_file` | optional | Trailmap-relative path to a markdown / text file containing the target's system-prompt template. The trailmap loader (and the build-time generator) reads the file and inlines its content into the generated target YAML's `system_prompt:` field. Lets authors keep multi-paragraph prompts in a standalone editable file rather than an unwieldy YAML string. Inline `system_prompt:` is not supported on `target` — a trailmap manifest declaring it fails the load with a migration message. The file-only shape leaves room for future per-device or per-classifier prompt selection (e.g. `app-tablet.prompt.md`) without an authoring schema change. |
| `dependencies` | optional | Trailmap ids this trailmap depends on. Transitive — depending on a trailmap pulls in its dependencies too. Resolves via closest-to-root-wins inheritance from each dep's `defaults:` (see below). |
| `defaults` | optional | Per-platform defaults this trailmap contributes to consumers via dependency resolution. Same shape as `target.platforms`. Consumers with `dependencies: [<this trailmap>]` inherit any field they leave null. |
| `toolsets` | optional | List of trailmap-relative paths to `ToolSetYamlConfig` files. |
| `platforms` | optional (library trailmaps only) | Top-level per-platform config declaring the library's own runtime-registry needs — the toolsets its internal scripted tools dispatch against. Same shape as `target.platforms`. Setting it on a target trailmap is a load-time error (a target's platform config belongs under `target.platforms:`). |
| `exports` | optional | Tool **names** (not paths) this trailmap publishes to consumers via the dependency resolver. This is how a TypeScript tool is shared across trailmaps: list it in this trailmap's `target.tools:` or `target.platforms.<p>.tools:`, export it, and every trailmap with this one in its `dependencies:` gets it on the platforms the tool supports. Tools in `tools/` not listed here are internal helpers — callable inside the trailmap, invisible to consumers. |
| `waypoints` | deprecated / ignored | Waypoints auto-discover from any `*.waypoint.yaml` under `<trailmap>/waypoints/` (any depth). The field survives in the schema only so legacy manifests still parse — leave it absent. |
| `trail_validation` | optional | `trail_validation.exempt: "<reason>"` opts this target's `.trail.yaml` recordings out of the default-fatal trail-recording type-validation phase of `trailblaze check` — findings are reported but never fail the build. Blank reason = not exempt. |
| `trails` | reserved | First-class artifact loading deferred. |

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
  display_name: Contacts
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

The repo's `contacts` demo trailmap is a worked example — see
`trails/config/trailmaps/contacts/trailmap.yaml` in the OSS repo (a workspace trailmap
loaded from the repo's own `trails/config/`, not bundled into the framework JAR).

## Tool flavors: which kind do I write?

A trailmap can contribute two flavors of custom tool. They share the `<trailmap>/tools/`
directory but bind through different mechanisms:

> **Default to scripted TypeScript.** It covers everything from a one-call wrapper to
> multi-step orchestration with branching and retries. Reach for Kotlin only when the tool
> needs host-side state the scripting SDK doesn't expose.

| | **Scripted (TypeScript)** | **Kotlin-backed** |
| --- | --- | --- |
| Filename | `<id>.ts` — one file per tool | A `@TrailblazeToolClass` class in your Kotlin sources, plus `<id>.tool.yaml` (note the double suffix) with `class:` naming it |
| Manifest entry | None needed — every `.ts` in `tools/` is on the target. Opt one out with `excluded_tools:` | **Auto-discovered** — do NOT list in `trailmap.yaml` |
| Surfaced to a target via | Being in the trailmap's `tools/` folder, or a dependency's `exports:` | A toolset that names the tool, declared from `platforms.<p>.tool_sets:` |
| Param schema source | The `<I>` type parameter on the `trailblaze.tool<I, O>` call. Per-field TSDoc becomes per-field JSON Schema `description`. | The class's constructor properties and their `@LLMDescription`s |
| Host vs on-device | `requiresHost: true` on the typed spec — registration-time gate | Set by the class's `@TrailblazeToolClass(requiresHost = ...)` |

A `*.tool.yaml` or `*.trailhead.yaml` with a `tools:` list of other tool calls (the retired
YAML-composed flavor) fails to load with a pointer to TypeScript tools. Port it to a `.ts`
tool: each listed call becomes an `await ctx.tools.<name>({ ... })` line.

> **Older trailmaps still using `<id>.yaml` + `<id>.ts` pairs?** That shape continues to
> work unmodified — see the [Scripted Tools — Legacy Reference](scripted_tools.md). New
> tools should use the `.ts`-only canonical shape above; convert in place when
> convenient.

The most common mistake when authoring a trailmap from scratch is **listing a `.tool.yaml`
under `target.tools:`** in `trailmap.yaml`. `target.tools:` entries are scripted tool
export names; a `.tool.yaml` auto-discovers, so delete that entry from the manifest and leave
the file in place.

## Per-file scripted tools

The canonical shape for scripted tools is a single `.ts` file per tool with a
`trailblaze.tool<I, O>(spec, handler)` export. The export name is the dispatchable tool
name; the TSDoc above it is the LLM-facing description; the `<I>` type parameter is the
input schema. **No sibling YAML descriptor is required.**

See [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the full
authoring reference, the IDE-typings pipeline, the testing helpers, and the worked
iOS Contacts + Wikipedia examples.

Every `.ts` under `tools/` that exports a `trailblaze.tool(...)` declaration is on the
trailmap's target — no manifest entry needed. Each tool's own `supportedPlatforms` and
`supportedDrivers` decide where it runs, exactly as they do for a listed tool, so there
is nothing to list per platform or per driver. Files in `tools/` that don't export a tool
(shared helpers, type-only modules) are ignored.

To keep a tool off a platform, name it in that platform's `excluded_tools:`:

```yaml
target:
  platforms:
    android:
      excluded_tools:
        - myapp_debugOnly           # matches `export const myapp_debugOnly = trailblaze.tool(...)`
```

Repeat it under each platform it should stay off. `excluded_tools:` is the only opt-out, so
a target that needs one must declare that platform under `platforms:`.

`target.tools:` (a list of bare export names) and `platforms.<p>.tools:` still work, but
listing a tool there is redundant, and the lists only add: `tools: []` no longer keeps a
trailmap's own tools off its target. Sharing a tool with *other* trailmaps stays explicit:
list it under `exports:`.

The legacy YAML-descriptor shape continues to work the same way — every descriptor's
`name:` is on the target too. See
[Scripted Tools — Legacy Reference](scripted_tools.md) for the full descriptor schema
and the field-level conventions in
[`TrailmapScriptedToolFile.kt`](https://github.com/block/trailblaze/blob/main/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/project/TrailmapScriptedToolFile.kt).

### Per-platform scripted tools

A tool's `supportedPlatforms` / `supportedDrivers` already limit it to its platforms
and drivers, so no listing is needed. Trailmaps that want the platform↔tool association visible in the YAML can
still declare each platform-specific tool under `platforms.<p>.tools:`, and
cross-platform tools — anything whose `supportedPlatforms` spans more than one platform
— under `target.tools:`. The build-time generator and runtime loader both hoist either
site into the delivered target's top-level `tools:` block, alongside the unlisted ones.

```yaml
# trails/config/trailmaps/myapp/trailmap.yaml
id: myapp
target:
  display_name: MyApp
  # CROSS-PLATFORM (>1 platform) — declared at target level
  tools:
    - myapp_inputTextRandom         # supportedPlatforms: ["android", "ios"]
  platforms:
    android:
      app_ids: [com.example.myapp]
      tool_sets: [core_interaction, verification]
      tools:
        - myapp_android_launchAppSignedIn   # supportedPlatforms: ["android"]
    ios:
      app_ids: [com.example.myapp.ios]
      tool_sets: [core_interaction, verification]
      tools:
        - myapp_ios_launchAppSignedIn       # supportedPlatforms: ["ios"]
```

Validation rule: a scripted tool listed under `platforms.<p>.tools:` must declare
`supportedPlatforms` naming **only** that platform. A tool that supports more than one
platform belongs under `target.tools:`. The trailmap loader rejects the mismatch with
a load-time error so the authoring shape is self-consistent.

When a trailmap targets only one platform there's nothing to split — every tool
necessarily applies to that one platform, and the `platforms.<p>.tools:` block is unused.

## Tool YAML file suffixes — `.tool.yaml`, `.trailhead.yaml`

Each operational class lives under its own trailmap subdirectory and uses a matching filename
suffix. The loader enforces that the file's content matches what the suffix promises — a
`.tool.yaml` file with a stray `trailhead:` block is a load-time error.

| Suffix              | Trailmap subdir   | Class      | Available when                       | Required block       |
| ------------------- | ------------- | ---------- | ------------------------------------ | -------------------- |
| `*.tool.yaml`       | `tools/`      | tool       | a toolset names it under `tools:`    | (none)               |
| `*.trailhead.yaml`  | `trailheads/` | trailhead  | always (bootstrap from any state)    | `trailhead:`         |

Subdirectories below each top-level dir are organizational only — the loader walks them
recursively at any depth. A trailmap with multi-platform trailheads can group them as
`trailheads/{android,ios,web}/...` (or any other grouping that fits) without changing how
discovery works.

Both classes share one data class (`ToolYamlConfig`); a trailhead is a tool with the
optional `trailhead` metadata block populated.

> **Workspace `*.tool.yaml` auto-discovery.** A workspace-authored `*.tool.yaml` is
> discoverable by the resolver at session start (no manifest entry needed), but the
> framework only surfaces it to a target when **some toolset names it under `tools:`**.
> The common shape is one workspace toolset at `<workspace>/trails/config/toolsets/<id>.yaml`
> (or `<trailmap>/toolsets/<id>.yaml`) that lists the tool, and one target referencing that
> toolset under `platforms.<p>.tool_sets:`.

### Trailhead tools

A **trailhead** is a bootstrap tool that takes the agent from any state to a known starting
point. Always available, with no precondition. In TypeScript, define it with
`trailblaze.trailhead(spec, handler)` in place of `trailblaze.tool`. In YAML, give a
`*.trailhead.yaml` tool a `trailhead:` block; `trailhead: {}` is enough.

A trailhead needn't name where it lands. The optional `to` names the waypoint it usually lands
on, and only draws its edge on the waypoint graph: nothing checks it. A trailhead that must fail
on the wrong screen asserts that in its own body. Trailheads are the right shape for "launch the app and reach a logged-in screen",
"force-quit and re-sign-in", and similar reset/genesis moves that need to work regardless
of where the agent currently is.

```yaml
# trailmaps/myapp/trailheads/myapp_launchAppSignedIn.trailhead.yaml
id: myapp_launchAppSignedIn
trailhead:
  to: myapp/android/home_signed_in
class: com.example.myapp.LaunchAppSignedInTool
```

A trailhead is one reusable atom. Trail-level setup (the trail's own `trailhead.setup:`
section in the v2 trail YAML) composes one or more trailheads alongside other tools —
e.g. setting a feature flag before invoking a launch trailhead. The trailhead is the
atom; the trail's setup is the orchestration.

### Trailhead teardown

A TypeScript trailhead can define `teardown`. Trailblaze calls it once after the trail,
whatever happened: passed, failed, cancelled, or the trailhead itself threw. It cleans up what
the run left behind and says whether the next trail can reuse this setup.

```ts
export const myapp_launchAppSignedIn = trailblaze.trailhead<{ email: string }>(
  {
    async teardown(args, ctx, trailRun) {
      // The history is a tree: a composite tool's own calls sit under its `calls`.
      const visit = async (calls: ToolCall[]) => {
        for (const c of calls) {
          if (c.name === "myapp_addItem") await deleteItem(ctx, String(c.args.id));
          await visit(c.calls);
        }
      };
      await visit(await ctx.history.toolCalls("trail"));
      if (trailRun.outcome !== "passed") return { reusable: false, reason: "trail did not pass" };
      return { reusable: true, reason: "home screen", state: { cartItems: await cartCount(ctx) } };
    },
  },
  async (args, ctx) => {
    if (ctx.previousRun && (await onHomeScreen(ctx))) {
      return ctx.previousRun.reuse("already signed in on home", async () => {
        if (ctx.previousRun?.state?.cartItems) await clearCart(ctx);
      });
    }
    // ...full launch and sign-in
  },
);
```

- **`trailRun`** has `outcome` and `events.list()` / `events.query(stream, { match, window, limit })`, which return only the
  matching records of this session's event streams. Matching runs in Kotlin: equality, nested
  subset objects, and `RegExp` (flags `i` and `m`; `g` is ignored and any other flag is an error).
  `events` works only inside `teardown`. In a multi-device trail a query reads the device the trail
  ended on and skips the other devices' `trailhead` records.
- **`ctx.history`** lists every tool call of the run, with its args and outcome, split into
  `trailhead`, `trail` and `teardown` sections. `ctx.history.toolCalls({ section })` with the
  `trail` section's id returns what the trail did and nothing from setup. See
  [Reading what ran via `ctx.history`](scripted-tools-typed-authoring.md#reading-what-ran-via-ctxhistory).
- **The return value** is `{ reusable, reason, state? }`. `reason` is required and shows in the
  report. `state` is your own small JSON (16 KB cap, no secrets). It is printed and logged with the
  session's trailhead events, as is the next run's `previousRun`, so it goes wherever the
  session goes, such as a failed session uploaded by `loop --on-failure`.
- **`ctx.previousRun`** is `{ sessionId, reason, state?, trailheadEndWaypoint?, reuse(reason, catchUp?) }`: what the
  last run's `teardown` reported, handed to the next run of the same trailhead on the same device,
  and only when `teardown` said `reusable: true`. It is a claim to check, not a guarantee. It
  is `null` otherwise: not reusable, a different tool, code, args, device or driver, reuse off for
  the lane (`TRAILBLAZE_TRAILHEAD_REUSE`, see [Configuration](configuration.md)) or for the run that
  set it up, and in every
  tool that isn't the trailhead. The session log says why it was `null`. A hand-off lasts one
  trail: the next run of that trailhead consumes it, match or not, and a trail with no
  `teardown` trailhead drops every pending hand-off in the runner, because the runner can't tell
  which device that trail changed. Reuse is off on Compose and Revyl, whose device ids don't tell
  one app or cloud device from the next.
- **A reusable hand-off skips the runner's clean start.** Runners normally force-stop the app
  before each trail. With reuse on, a trail whose trailhead has a reusable hand-off waiting on the
  device leaves the app running and the trailhead decides; every other trail keeps its clean start. So a full setup must establish its own starting state
  (relaunch the app, for example) rather than count on the runner having stopped it. A trail that
  changes the device's language (its `locale`, or the `en-US` default after a localized trail)
  still gets a fresh process, and the change drops the device's pending hand-offs.
- **Where the setup landed is part of the hand-off.** On a host run with reuse on, the runner
  matches the screen against the target's own waypoints (its trailmap's, ids under `<trailmap id>/`) as the
  trailhead returns, giving it up to 5 seconds to settle onto one. It logs every match
  (`trailheadEnd` in `events/trailhead.ndjson`). The landing has to match exactly one waypoint,
  which is handed on as `previousRun.trailheadEndWaypoint`. A run whose trailhead matched none, or
  more than one, hands nothing on: make overlapping waypoints exclude each other with `forbidden`.
  A browser run, which selects no app target, and a target with no waypoints are not checked, and
  their setup is handed on as on a device. A reused setup must land on that waypoint again when
  the trailhead returns (its own `required` and `forbidden` selectors, waiting up to 5 seconds as
  `assertWaypoint` does), or the trailhead fails. To reuse only from one screen, check
  `previousRun.trailheadEndWaypoint` before calling `reuse`. On-device runs record no landing, so
  `trailheadEndWaypoint` is absent and nothing is checked.
- **`reuse(reason, catchUp?)`** skips the full setup. It logs the attempt, runs `catchUp` (what the
  last run left to finish) and resolves with its result. If `catchUp` or the rest of the handler
  throws, the trailhead fails and the report says it failed while reusing that session's setup.
  Without a call the log says "full setup".
- **Two hooks, two kinds of work.** `teardown` runs even when no trail follows, so it does the
  cleanup that can't wait: records on a server, leases, uploads. Work that a full setup also undoes
  (back to the home screen, clear a cart) can wait for the next trailhead: record it in `state` and
  do it there when `ctx.previousRun` arrives. Defer nothing else, because when it is `null` the
  next trailhead does a full setup and never sees what was left.
- **`reusable` is forced to `false`** when the trail did not pass, when `teardown` throws or
  runs past its 45-second budget, when the session holds a resource the host releases at session
  end, or when `state` is over 16 KB (it is dropped). The budget
  is best effort while `teardown` waits on a nested tool call, which runs to its own timeout.
- **In a multi-device trail** a trailhead's `teardown` runs only if the trail ended on the device
  that trailhead set up. Otherwise it is skipped and reported as failed, since its cleanup would
  land on the wrong device; switch back before the trail ends to keep it.
- **It never changes the verdict.** A failing `teardown` shows as a failed "Teardown"
  section in the report, after the trail; the trail keeps its own result. Its tool calls are never
  written into a recording.

`teardown` is supported for in-process (QuickJS) TypeScript tools run by `trailblaze run`
and the JUnit runners. It is not called for subprocess tools or for trails run step by step over
MCP.

### Reading session events mid-run

Any TypeScript tool can check what the session's event streams (`events/<stream>.ndjson`) hold
so far, at any point in a run, and wait for a record to land. This lets a tool check app state from
the app's own signals rather than guess it from the screen:

```typescript
await ctx.events.mark("launched");
// ... sign in ...
const login = await ctx.events.query("analytics", {
  match: { event: "sign_in", properties: { result: "success" } },
  since: "launched", // "session" (default), "trailhead", "trail", or a mark name
  waitMs: 15_000,    // poll until a match lands, up to this long
});
if (!login.captured) throw new Error("no analytics capture in this session");
```

- **`query(stream, options)`** returns `{ captured, events, cursor }`. `captured: false` means the
  stream has no file in this session, so nothing captured it; tell that apart from "captured, and
  nothing matched". Pass `cursor` back as `after` to read only what arrives next. `newest: true`
  returns the last matches to arrive first, read from the end of the stream. A result stops at
  about 4 MB of record text and says `truncated: true`; an oldest-first read continues from its
  `cursor` passed as `after`.
- **`count(stream, options)`** returns how many records match, with no payloads.
- Both say `complete: false`, with an `incompleteReason`, when a capture writing the session's
  streams recorded losing data, or when the read skipped a record over 4 MB. An absence check ("no 5xx since the mark") should fail then, since
  the record it looks for may have been dropped.
- **`mark(name)`** names this point in every stream. A stream that appears after the mark starts
  after it.
- **`match`** uses the same rules as `trailRun.events.query`: strings, numbers and booleans
  match exactly, a `RegExp` tests, a nested object is a subset match.
- **It is cheap enough for a device with no host.** Matching runs in Kotlin over the stream's bytes.
  A query reads only the bytes after its starting point, lines missing a matched value are skipped
  before they are decoded, and a wait checks the file size between reads.

Which streams exist depends on what the trailmap captures. Records are returned to the tool
as captured, so don't log or return payloads that may hold secrets.

A trailmap's [surveys](surveys.md#running-a-survey-mid-trail) run over the same streams, as typed
functions: `await ctx.surveys.checkoutDone({ waitMs: 15_000 })` returns `{ passed, message, findings }`.

### Authoring forms

Write a trailhead as a TypeScript tool (declare `trailhead: { to: ... }` in its
`trailblaze.tool(...)` spec) or as a `*.trailhead.yaml` with `class:` pointing at Kotlin. A
shortcut is a `*.shortcut.yaml` with `class:`. See [Tool flavors](#tool-flavors-which-kind-do-i-write).

## Discovery and precedence

Trailmaps come from two sources at runtime:

1. **Workspace trailmaps** auto-discovered from `<workspace>/trails/config/trailmaps/<id>/trailmap.yaml`
   on disk. The workspace's `trailblaze.yaml` may narrow the set with `targets:` (a list of
   trailmap ids); empty/omitted loads every target trailmap found.
2. **Classpath trailmaps** discovered automatically from any `trails/config/trailmaps/<id>/trailmap.yaml`
   resource shipped on the JVM classpath (the framework bundles `trailblaze`, `mobile`, and
   `android`; any dependency jar can ship its own).

When a workspace trailmap and a classpath trailmap share an id, **the workspace trailmap wholesale shadows
the classpath one** — none of the classpath trailmap's target / toolsets / tools / waypoints leak
through. This precedence rule is the operative knob: to override a framework-bundled trailmap,
declare a workspace trailmap with the same id and author whatever subset you need.

If two classpath jars both ship a trailmap with the same id, the loader keeps the first one
discovered and logs a warning identifying both locations — resolve by ensuring only one
bundled jar declares each trailmap id.

### Targets that live in another repository

A trailmap can be at home in a repo other than the one your binary was built from, and its trails
almost always are. Declare that in any YAML file under the `trails/config/known-target-workspaces`
resource directory. A file may inline several repos:

```yaml
workspaces:
  - repo: git@github.com:your-org/your-app-trails.git
    url: https://github.com/your-org/your-app-trails
    description: Trails and trailmap for Your App
    targets:
      - yourapp
  - repo: git@github.com:your-org/other-trails.git
    targets:
      - otherapp
```

A file holding a single bare record (the fields above with no `workspaces:` wrapper) is also accepted.
Every file in the directory is merged, so put yours under its own filename — discovery keys on
resource path and layered sources fold last-wins, so a file whose path matches one already on the
classpath replaces those records instead of adding to them.

These records are pointers, not a third discovery source — nothing is fetched, cloned, or resolved
from them. They exist so every surface that names a target can answer "where do I get this?" instead
of listing whatever happens to be installed:

- a `--target yourapp` that resolves nowhere names the repo and prints a clone command, rather than
  an available-list that reads as "pick a different target"
- `trailblaze config target` lists declared-but-absent targets under **Not installed**
- an installed target whose trails live elsewhere is annotated with that repo, so an empty Trails
  tab has an explanation
- a trail whose `config.target` names an absent target still runs against the workspace default
  (that fallback is deliberate), but now says so on every run instead of retargeting silently

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
- `trails/config/trailmaps/clock/` (the OSS repo's own workspace)

## Migration notes

### Trailmap id `ioscontacts` → `contacts`

The iOS Contacts example workspace trailmap was renamed from `ioscontacts` to `contacts` so the
same trailmap can later host Android / web tools alongside the iOS ones (the trailmap is the
*Contacts app* trailmap, not just the iOS subset). CLI users who pinned `--target ioscontacts`
should update to `--target contacts`. The id happens to match the main repo workspace's own
`contacts` demo trailmap (Google Contacts) — no conflict arises because each workspace only
loads its own trailmaps; the precedence rule above applies only when a workspace trailmap
shares an id with a classpath-bundled one.

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
├── trailmap.yaml                       # id, target.display_name, platforms
└── tools/
    └── myapp_login.ts                  # typed `trailblaze.tool<I, O>(spec, handler)` export
```

The new trailmap auto-discovers — no `trailblaze.yaml` entry needed (optionally pin it with
`targets: [myapp]`). See [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md)
for the per-tool authoring shape.

The `targets/myapp.yaml` flat form is still supported for now (legacy-only); new authoring
should use trailmaps. The flat-target tools list (`tools: [...]` with inline `InlineScriptToolConfig`
entries) is preserved verbatim for legacy use, but the per-tool `.ts` shape is the
recommended path.
