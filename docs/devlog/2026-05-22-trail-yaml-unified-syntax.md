---
title: "Trail YAML — Unified Per-Test File"
type: decision
date: 2026-05-22
supersedes: 2026-03-06-trail-yaml-v2-syntax.md
---

# Trail YAML — Unified Per-Test File

Collapsing the per-platform `*.trail.yaml` files plus `blaze.yaml` into a single
file per test, with per-step recordings keyed by device classifier.

> **Historical note.** This devlog and the corresponding code in the
> `xyz.block.trailblaze.yaml.unified` package were originally drafted under
> the working name "Trail YAML v3" — chronologically the third iteration
> after v1 (per-platform list shape) and v2 (a 2026-03-06 decision record
> that never landed as a parser change). The "v3" label was renamed to
> "unified" before merge because it collided with the unrelated
> `AgentImplementation.MULTI_AGENT_V3` enum value (in
> `trailblaze-models`'s `xyz.block.trailblaze.mcp` package), which
> references the Mobile-Agent-v3 paper. References to "v1" and "v2" below
> are still the historical format labels — only the *new* format dropped
> the version-number scaffolding in favor of the descriptive "unified" name.

## Background

v2 evolved the per-file shape (`prompts` → `trail`, `recording` → `tools`,
`step` keyword preserved, no bare `tools:` blocks). It did not address the
duplication that lives **across** the per-platform files:

A typical multi-platform test folder today contains:

```
trails/<test>/
  blaze.yaml                       # NL definition, no tool recordings
  ios-iphone.trail.yaml            # NL + iPhone-specific recordings
  ios-ipad.trail.yaml              # NL + iPad-specific recordings
  android-phone.trail.yaml         # NL + Android phone recordings
  android-tablet.trail.yaml        # NL + Android tablet recordings
  web.trail.yaml                   # NL + web recordings
```

The natural-language step descriptions are copied into every per-platform file
at record time, then drift independently. Real drift observed in production
trails today includes:

- Embedded credentials in NL (`Launch the app signed in with foo@example.com`)
  diverging across platforms because each was recorded with a different test
  account.
- Per-platform step descriptions that conditionally re-explain device-specific
  behavior in different prose ("Verify the Money tab is visible" vs "Verify
  that the text 'Activity' is visible on the page" — same logical step,
  different assertion targets, drifted NL).
- New steps added to one platform's file but not the others, leaving the
  blaze.yaml out of sync with all of them.

The drift is *not* the recording content itself (recordings are intentionally
per-platform full-fidelity; see the platform-specific selector design
decision). The drift is exclusively in the duplicated NL strings — and the
recording pipeline can't fix it because each device records in isolation
without visibility into sibling recordings.

This devlog supersedes v2 and lays out a unified single-file format where the NL
exists exactly once per step and per-platform recordings nest underneath.

## What we decided

### File structure — two top-level keys

A unified trail file is a YAML mapping with exactly two top-level keys:

| Key | Purpose | Cardinality |
| :--- | :--- | :--- |
| `config` | Identity, target, optional per-classifier driver map, context, memory, metadata | Singleton |
| `trail` | The test — ordered list of steps | List |

There is no separate `trailhead:`, `setup:`, or `teardown:` section. Trailhead
behavior is a property of the tools used (`trailheadTo`-tagged tools, looked
up via `./trailblaze toolbox trailheads`), not a property of the file's
section layout. See the trailhead semantics section below.

### Step structure — NL plus per-classifier tool lists

Each entry in `trail:` is a map with this shape:

```yaml
- step: <natural-language intent — required>
  <classifier>: <tool list>
  <classifier>: <tool list>
  ...
  recordable: false   # optional, mutually exclusive with classifier keys
```

- **`step:`** — required. Natural-language description of what the step
  accomplishes. Single source of truth for the test's intent across all
  platforms.
- **`<classifier>:`** — one or more device classifier keys, each mapping
  directly to a list of tool calls (no `tools:` wrapper). A step must have
  at least one classifier key OR `recordable: false`. The classifier name
  is the same vocabulary used by `TrailblazeDeviceClassifier` and the
  closest-wins resolver.
- **`recordable: false`** — marks the step as always-LLM-handled, no
  recordings on any platform. Useful when the UI lacks stable selectors and
  the agent must blaze the step every run.
- **`<classifier>: []`** — explicit no-op for a specific device class.
  Distinguishes "author intentionally skipped this for tablet" from
  "tablet recording is missing." Both states are legal at runtime (absence
  also produces a no-op); the empty list is the way to signal explicit
  author intent for coverage reporting.

### Example — single-platform test

```yaml
config:
  id: myapp/login
  target: myapp
  memory:
    email: "test@example.com"

trail:
  - step: Launch myapp signed in
    android-phone:
      - myapp_launchAppSignedIn:
          email: "{{email}}"

  - step: Verify the Money tab is visible
    android-phone:
      - assertVisibleBySelector:
          nodeSelector:
            androidAccessibility:
              contentDescriptionRegex: Money|navigationMoney
```

No `on:` wrapper, no `tools:` wrapper, no platform field at the file level, and
— because this trail doesn't need a driver pin — no `devices:` block at all.
The supported device set is derived from the per-step classifier keys
(`android-phone` here). Add a `devices:` map (see below) only when a classifier
needs a specific driver.

### Example — multi-platform with classifier hierarchy

```yaml
config:
  id: myapp/checkout
  target: myapp
  devices:                            # Only classifiers that need a driver pin
    android: ANDROID_ONDEVICE_ACCESSIBILITY
    ios: IOS_HOST
  memory:
    email: "test@example.com"

trail:
  - step: Sign in to myapp
    android:                          # Covers android-phone AND android-tablet
      - myapp_launchAppSignedIn:
          email: "{{email}}"
    ios:                              # Covers ios-iphone AND ios-ipad
      - myapp_ios_launchAppSignedIn:
          email: "{{email}}"

  - step: Open the hamburger menu
    android:                          # Default for both Android device classes
      - tap:
          selector:
            accessibilityId: menu-btn
    ios-ipad:                         # iPad has a sidebar instead of hamburger
      - tap:
          selector:
            accessibilityId: sidebar-toggle
    ios-iphone:                       # iPhone uses the hamburger
      - tap:
          selector:
            accessibilityId: menu-btn

  - step: Dismiss any payment confirmation dialogs
    recordable: false                 # LLM always handles this, no recordings

  - step: Skip on tablet for the moment
    android-phone:
      - tap:
          selector: { text: Continue }
    android-tablet: []                # Explicit no-op
    ios:
      - tap:
          selector: { text: Continue }
```

The closest-wins resolver picks the most specific recording at runtime. A
few concrete examples against the trail above:

- **iPad running step 2 ("Open the hamburger menu")**: lookup finds `ios-ipad:`
  at the most-specific level and uses the sidebar-toggle tap. No fall-through
  needed.
- **iPad running step 4 ("Skip on tablet for the moment")**: lookup is
  `ios-ipad` → `ios` (family-level match) — uses the `ios:` Continue tap.
- **Android tablet running step 4**: lookup finds `android-tablet: []`
  (explicit no-op) at the most-specific level — runs no tools, treated as
  an intentional skip.
- **Android tablet running step 1 ("Sign in to myapp")**: lookup is
  `android-tablet` → `android` (family-level match) — uses the launch tool.

When no match is found at any level, the step is a **no-op at runtime** and
`trailblaze check` raises a **coverage warning** (because `android-tablet`
appears among the trail's recorded classifiers on other steps but this step
provides no recording reachable via the hierarchy). The supported device set is
derived from the classifiers that appear across the trail's recordings — not
from a declared list. The runtime never errors on missing recordings —
execution continues to the next step, and any real test breakage shows up
downstream (e.g., a subsequent assertion fails because the screen state never
advanced).

### Classifier hierarchy resolution

Resolution per step, given a device classifier `X`:

1. If the step has `X:` declared (exact match), use that.
2. Otherwise, walk up the classifier hierarchy looking for a broader match
   (`android-phone` → `android`).
3. If no match is found, the step is a no-op for this device. If that device
   class appears among the trail's recorded classifiers on other steps,
   `trailblaze check` surfaces this step as a coverage gap for it.

### `config:` fields

| Field | Type | Required | Notes |
| :--- | :--- | :--- | :--- |
| `id` | string | yes | Stable identifier; convention is the source-system path (e.g. `testsystem/suite_NNNN/section_NNNN/case_NNNN`). |
| `target` | string | yes | Target name from the trailmap manifest. |
| `description` | string | optional | Human-readable summary of the test. Round-trips losslessly to/from the v1 `TrailConfig.description` and is surfaced at runtime (e.g. as a display label). |
| `devices` | map of classifier → driver | optional | Per-classifier driver pins (e.g. `{android: ANDROID_ONDEVICE_ACCESSIBILITY, ios: IOS_HOST}`) for trails whose recordings are driver-specific. Resolved closest-wins for the device under test (like recordings), then lowered to the single v1 `TrailConfig.driver` for that run. **Omit the whole block** when no classifier needs a driver pin — the supported device set is derived from the recorded classifier keys, and the driver resolves at run time (`--driver` flag > app setting > device). The `--driver` flag still overrides a pin. |
| `context` | string | optional | Free-form context injected into the LLM system prompt. |
| `memory` | map of name → value | optional | Pre-seeded variables for `{{name}}` interpolation in NL and tool params. |
| `metadata` | map | optional | Informational only — never read at runtime. Used for traceability (case IDs, URLs, etc.). |

Retired from the legacy v1/v2 per-platform files:

- **`platform:`** (singular) — gone. The set of device classes a trail supports
  is derived from the per-step classifier keys, not from a declared list.
- **`title:`** — was useful when each platform file had its own identity;
  with a single file per test, the `id` field covers identification and
  the human-readable title can live in `metadata.title` if needed.
- **A standalone `devices:` *list*** — an earlier draft of this format carried
  a `devices: [android-phone, ios, …]` coverage-declaration list. It was
  dropped: the supported device set is fully derivable from the classifiers
  that appear in the recordings, so a hand-maintained list is a second source
  of truth that only drifts. The `devices:` **key still exists**, but it is now
  a *map* (classifier → driver, below), not a list — and it's optional.

> **`driver:` — folded into an optional per-classifier `devices:` map (revised).**
> Driver was originally retired on the theory that it's fully derivable at run
> time from the device plus the trailmap's driver preferences. In practice
> there is **no trailmap-level per-platform driver preference for Android**, and
> trails legitimately mix drivers within one trailmap+device (e.g. some Clock
> trails record against `ANDROID_ONDEVICE_ACCESSIBILITY`, one against
> `ANDROID_ONDEVICE_INSTRUMENTATION`). A trail whose recordings are
> driver-specific (an `androidAccessibility` selector only replays on the
> accessibility driver) would silently lose that pin. It is **per-classifier**,
> not a single scalar — a multi-platform trail needs a different driver for
> `android:` than for `ios:`, which one scalar can't express.
>
> Rather than add a *second* classifier-keyed map (`drivers:`) alongside a
> `devices:` coverage list, we collapsed both into a single `devices:` **map**
> keyed by classifier whose value is that classifier's driver
> (`devices: { android: ANDROID_ONDEVICE_ACCESSIBILITY, ios: IOS_HOST }`). The
> keys call out the device classes that need a specific driver (`android` vs
> `android-tablet` vs `ios-iphone`, …); the value is the driver. It's resolved
> closest-wins for the device under test with the same
> [`TrailblazeClassifierLineage`](#classifier-hierarchy-resolution) the
> recordings use, then lowered to the single v1 `TrailConfig.driver` for that
> run (the `--driver` flag still overrides). Omit the block entirely and the
> driver resolves at run time as above.

### Per-platform config in a trail — classifier-keyed maps, not a platform root

The driver pin raised a general question: when a trail config field is
platform-specific, should we nest it under a platform root
(`platforms: { android: { driver: … } }`) the way the *trailmap* manifest's
`PlatformConfig` does? The decision: **no — express each platform-specific
trail-config field as its own classifier-keyed map** (`devices: { android: … }`),
resolved closest-wins by the same lineage the recordings use.

Rationale:

- **The trail config is deliberately the platform-agnostic layer.** Identity,
  intent, `memory`, `metadata`, `context` are one value shared across every
  platform the trail runs on. The rich per-platform config — `app_ids`,
  `tool_sets`, the available driver list, `base_url`, `min_build_version` —
  already lives platform-rooted in the trailmap's `PlatformConfig`, which is
  where it belongs. The per-classifier driver pin is the lone per-trail,
  per-platform field today.
- **Recordings are classifier-keyed, not platform-rooted.** Each step is
  `{ android: [...], ios: [...] }` — the classifier is a map key, not a grouping
  node with config underneath. The classifier-keyed `devices:` map mirrors that
  exactly; a `platforms:` root block would *diverge* from it.
- **A platform root is over-structuring for one field.** Nesting a single scalar
  under a nested object earns nothing.

**When to revisit:** if a *second* per-platform trail-config field lands, the
map value stops being a bare driver string and becomes an object
(`devices: { android: { driver: …, <field>: … } }`) — at which point it has
effectively become a per-classifier `PlatformConfig`, and grouping under a
classifier root is the right call (that's exactly what made the trailmap's
`PlatformConfig` platform-rooted — many fields). The likeliest second field is
per-platform `memory` (a different test account per platform — a drift pain
called out in the Background above). Until then, one field, one scalar value.

### Trailhead semantics — tool tag, not section

The v2 devlog (refined 2026-05-17) introduced `trailhead:` as a sibling
top-level key. the unified format removes that — trailhead behavior is sourced entirely
from the tool's `@TrailblazeToolClass(trailheadTo = …)` tag, not from
schema position.

**Operational semantics still hold without a sibling section:**

- **Setup-vs-trail failure distinction** comes from the failing tool's tag.
  A step whose tools are all `trailheadTo`-tagged is a setup step; failure
  there → skip/retry. Otherwise → test failure. The framework determines
  this from the tool registry, not from the file's section layout.
- **Multi-step setup** is natural: the first N steps each use trailhead-tagged
  tools, and the framework treats "the last consecutive trailhead-tagged
  step" as the setup boundary. The v2 design's singleton `trailhead:` was
  wrong-headed in this respect — login → navigate → enable flag → open
  screen is genuinely four setup steps, not one.
- **Checkpoint replay during recording** is a runtime concern (a CLI flag
  like `--from-step=N`, or a convention like "skip steps whose tools are
  all `trailheadTo`-tagged"). It doesn't need schema support.

**Discovery and lint:**

- `./trailblaze toolbox trailheads` lists trailhead-tagged tools (already
  designed in v2).
- `trailblaze check` warns when step 1's tools aren't `trailheadTo`-tagged
  ("step 1 doesn't appear to start from a deterministic state — intentional?").
  Soft warning, not error.
- Every test's resolved-target report (see `ResolvedTargetReportEmitter`)
  surfaces "trailhead boundary" as derived metadata.

### Teardown — explicitly not modeled

We do not add a `teardown:` section, for two reasons:

1. **Real teardown is fragile.** When a test fails, the cleanup step runs
   against bad state, and either succeeds while hiding the original failure
   or fails on top of it. Both outcomes obscure the real signal.
2. **The trailhead pattern is the structural alternative.** A test that
   starts from a deterministic, freshly-provisioned state via trailhead-tagged
   tools doesn't *need* cleanup — each run begins from a known good state
   regardless of what the previous run did to the world.

If a specific test genuinely needs to undo a side effect (e.g., toggle off
a feature flag the test toggled on), that's just a final step in `trail:`
using a teardown-purpose tool. No section required.

### Optimize for reading; accept duplication; never resolve through indirection

The file format follows one principle that drove many of the smaller
collapses:

> **The file is read more than hand-written, and the writer is almost
> always an agent or recording pipeline.** Optimize for clarity at read
> time, even at the cost of redundancy.

Concretely, this principle rules out:

- **YAML anchors and references** (`&name` / `*ref`). When two classifiers
  share a recording, the file duplicates it. Three extra lines is nothing
  compared to the cost of a reader having to chase `*ref` to its `&name`
  declaration.
- **Wrapper keys that disambiguate nothing.** v1's `recording:` wrapped
  `tools:`; v2 dropped `recording:`. The unified format drops `tools:` under
  each classifier — the classifier's value is a tool list directly, because
  there's nothing else it could be. Same logic killed the proposed `on:` wrapper.
- **Step-level "any platform" fallback** (`tools:` at the step level
  meaning "use this if no classifier matches"). All Trailblaze selectors
  are intentionally platform-specific; a step needs at least one classifier
  declared. The closest-wins resolver still gives you per-family sharing
  via `ios:` covering iPhone+iPad.

What we do keep:

- **Classifier hierarchy** (`ios` is broader than `ios-iphone`) — earns its
  place because it expresses a real semantic relationship (iPhone IS an
  iOS device) rather than a syntactic convenience.
- **`recordable: false`** — earns its place because it expresses author
  intent ("always LLM-handled, never overwrite during re-recording") that
  has no other reasonable home.
- **Explicit `<classifier>: []`** — earns its place because it distinguishes
  intentional skip from accidental absence in the coverage report.

### `reason:` is ephemeral

Recordings often contain a `reason:` string per tool call ("The objective
is to tap the Submit button; this is the only enabled button on the
screen"). These are recording-time annotations meant to help humans
debug; they are not load-bearing at replay time and they are intentionally
*not* part of equality when determining whether two recordings can be
collapsed under a broader classifier.

When the recorder emits a recording, `reason:` is helpful. When the
migration tool determines whether (say) `ios-iphone` and `ios-ipad`
recordings are equivalent so they can be folded into a single `ios:`
entry, `reason:` is stripped before comparison. This is what allows
the family-collapse pass to fold sub-classifiers into a broader classifier
even when the per-device recordings have different reason prose.

## Migration plan

### Strategy

1. **Build the unified parser alongside v1/v2** in `trailblaze-models/commonMain`.
2. **Try-catch fallback** during a transition window: attempt unified parsing
   first, fall back to v1/v2 on failure. The fallback period is short
   because the migration is mechanical and there are no external consumers
   of the trail format yet.
3. **Bulk migrate** every `*.trail.yaml` and `blaze.yaml` in every workspace
   via a one-shot script.
4. **Hard-cut** to unified after the bulk migration lands. The v1/v2 parsers
   are deleted, not deprecated — per the hard-cut-pre-external-users
   policy, no shim debt is carried.

### Mechanical migration

A prototype of the migration logic was built during this design's
exploration phase. The shape is:

For each test folder containing one or more `*.trail.yaml` files:

1. **Load every platform file.** The filename minus `.trail.yaml` is the
   device classifier (e.g. `android-phone.trail.yaml` → `android-phone`).
2. **Canonicalize config.** Take the first file's `config:` block. Drop
   `platform:` and `title:` (per the unified schema changes). Promote `id`,
   `target`, `description`, `context`, `memory`, `metadata` verbatim. Build the
   `devices:` map by keying each per-platform file's `driver:` under that file's
   classifier (so a multi-platform trail keeps each platform's driver). If no
   file pinned a driver, omit `devices:` entirely — the supported device set is
   derived from the recorded classifier keys.
3. **Reconcile NL.** For each step index, gather the NL strings from every
   platform file. If they agree, that's the canonical NL. If they disagree
   (drift), emit a warning comment block at the top of the merged file
   listing the divergent strings — the canonical NL is the first
   platform's, but human review is required.
4. **Merge recordings into a step entry per step index.** For each
   classifier `<C>`, take its recording at that step index and place it
   under `<C>:` in the merged step. If a platform file had `# No recordings
   generated...` style absence, that classifier is omitted at this step.
5. **Leave `recordable` at its default (`true`)** for steps where no platform
   produced a recording. A step with no recording just runs via the agent and
   can be recorded later — that's "not recorded yet," not "never record." The
   migrator never auto-emits `recordable: false`; authors set it by hand for a
   deliberately LLM-only step.
6. **Collapse equivalent sub-classifiers.** For each family
   (`android` = `android-phone` + `android-tablet`, `ios` = `ios-iphone`
   + `ios-ipad`, etc. — driven by the classifier hierarchy registered in
   `TrailblazeDeviceClassifier`), if every sub-classifier present at a
   given step has equivalent recordings (with `reason:` fields stripped),
   collapse to the broader classifier and keep one canonical
   recording. Otherwise, leave the sub-classifiers separate so divergence
   stays visible.
7. **Emit one file per test.** The merged file replaces every per-platform
   `*.trail.yaml` and the `blaze.yaml` in the folder.

The migration is idempotent: re-running on already-merged input is a no-op.
Drift cases are surfaced as comments, not silently flattened.

### v1 → unified mapping

In practice almost every existing trail in the repository is on **v1**
syntax — top-level list of `- config:` / `- prompts:` items with `recording:
tools:` wrappers under each step. The v2 design from the 2026-03-06 devlog
was a decision record that never landed as a parser change; no checked-in
files use v2-refined shape. The table below covers the v1 → unified transition
that the migration tool will execute against every workspace.

| v1 | unified |
| :--- | :--- |
| `- config:` (list item, top-level) | `config:` (top-level mapping key) |
| `- prompts:` (list item, top-level) | `trail:` (top-level mapping key) |
| `step:` + `recording: tools: […]` | `step:` + `<classifier>: [tool, tool, …]` |
| `verify:` keyword | `step:` (verify intent is expressed in NL + assertion tools) |
| `recording:` wrapper | removed |
| `platform: android` (singular) | removed — supported device set is derived from the per-step classifier keys |
| `driver:` field | folded into an optional per-classifier `config.devices:` map (keyed by the file's classifier; omit the whole block to resolve at runtime; pins survive for driver-specific recordings) |
| `title:` field | removed (or moved into `metadata.title` if needed) |
| One file per platform | One file per test |
| `blaze.yaml` (NL-only sibling) | absorbed into the unified file |

If any v2-refined files do exist (e.g. on an in-flight branch), they require
two additional transformations on top of the above:

| v2-refined | unified |
| :--- | :--- |
| `tools:` directly under each `step:` (no `recording:` wrapper) | replaced with `<classifier>: [tool, tool, …]` keys; the `tools:` keyword is removed in favor of per-classifier lists |
| `trailhead:` (sibling top-level singleton) | removed — trailhead behavior is now sourced from `@TrailblazeToolClass(trailheadTo = …)` metadata on the tools used in the first `trail:` step |

### Drift detection during migration

When the same step has divergent NL across platforms, the migration tool
emits a leading comment block in the merged file:

```yaml
# WARNING: 3 step(s) had divergent NL across platforms during migration.
# Used the first platform's NL as canonical. Review the diff:
#   step 1:
#     android-phone: 'Launch the app signed in with tb+test1@example.com'
#     ios-iphone:    'Launch the app signed in with tb+test1@example.com'
#     web:           'Log in to STAGING using email "tb+test1@example.com"...'
#   step 5:
#     ...
```

This is the migration's primary signal for "the team needs to make a
canonicalization decision before this trail is stable in the unified format." The trail
parses and runs in the meantime; the warning surfaces in
`trailblaze check`.

### Coverage warnings post-migration

After migration, `trailblaze check` produces a per-test coverage matrix:

```
trail: myapp/checkout
device classes (from recordings): android-phone, android-tablet, ios

                                     android-phone   android-tablet   ios
Step 1: Launch myapp                  ⚠               ✓                ✓
Step 2: Verify Home tab               ✓               ✓                ✓
Step 3: LLM-handled                   —               —                —     (recordable: false)
Step 4: Skip on tablet                ✓               — (explicit [])  ✓
```

- ✓ = recording present (direct or via classifier hierarchy)
- — = explicit `[]` or `recordable: false`
- ⚠ = this device class appears in the trail's recordings elsewhere but not on
  this step — possible gap

Soft warnings by default. Teams that want hard-fail can opt in with
`--strict`.

## What changed

**Positive:**

- One file per test instead of one per platform plus a `blaze.yaml` sibling.
  NL has exactly one physical home; drift is structurally impossible.
- Per-step classifier keys (`ios:`, `android-phone:`) read like CSS / Tailwind
  responsive overrides — base case at family level, specific overrides
  where reality diverges.
- Per-test platform coverage is derived from the recorded classifier keys —
  no hand-maintained list to drift. The coverage matrix is a real artifact
  computed from the recordings, not a heuristic.
- Classifier hierarchy does real work — sub-classifiers fold into broader
  classifiers when recordings agree, surface separately when they don't.
- `reason:` field stays for human readability without being load-bearing
  for equality / collapse decisions.
- `recordable: false` and explicit `<classifier>: []` give authors clean
  ways to express "LLM always handles this" and "intentionally skipped
  here" without polluting NL with conditional prose.
- Trailhead-as-tool eliminates the singleton-vs-list schema tension. Multi-step
  setup is just multiple consecutive trailhead-tagged steps; runtime
  semantics derived from the tool registry, not the file's section layout.
- No teardown section — pushes test design toward idempotency via
  deterministic setup, which is structurally more reliable than
  cleanup-on-exit.
- Driver field retires; one less per-file thing to keep in sync.
- Optimized for reading: no YAML anchors, no indirection, duplication is
  acceptable because the writer is almost always an agent.

**Negative:**

- All existing `*.trail.yaml` and `blaze.yaml` files must be migrated. The
  migration is mechanical and a script handles it, but the diff is
  significant.
- Tools and scripts that parse trail files (the resolved-target report
  emitter, the recorder, the CLI runner, any external scripts) need
  updating to the new shape.
- File sizes per test grow vs. a single-platform v1 file — the unified
  file covers every platform's recordings. Net repository size
  decreases significantly (the NL is no longer duplicated 3–6 times per
  test), but any individual file is larger than its v1 single-platform
  predecessor.
- Coverage warnings will be noisy immediately post-migration as gaps that
  were previously invisible (missing per-platform files) become visible
  as ⚠ entries in the coverage matrix. This is the intended outcome but
  the noise is real until teams triage.
- The migration emits warning comments for drift cases; those need human
  reconciliation before the trail is fully clean. Tests that drifted
  recently will surface; tests that drifted long ago will surface
  retrospectively.

## Open questions for follow-up

1. **Recorder UX for the unified file.** *(Largely resolved — `trailblaze run`
   save-back can write the unified format, behind an opt-in rollout gate.)* On a
   successful run the recorder merges the device's `<classifier>:` slot into the
   directory's `trail.yaml` (`UnifiedTrailAdapter.mergeRecordedClassifier`),
   preserving every other device already recorded there, instead of dropping a
   legacy sibling. A greenfield trail (authored from an NL definition, no
   `*.trail.yaml` yet) writes unified; a directory that still holds legacy
   `<classifier>.trail.yaml` siblings stays legacy so re-recording never forks a
   half-migrated directory (migration is a separate, deliberate step).
   **Rollout gate:** unified save-back is opt-in while the surrounding tooling
   (recording type-validation, CI trail discovery, `verify:` steps, the
   MCP/desktop writers) catches up — enable per run with `--unified-recordings`,
   per environment with `TRAILBLAZE_UNIFIED_RECORDINGS=1`, or persistently with
   `trailblaze config unified-recordings true` (flag > env > config, mirroring
   self-heal). While off, save-back is byte-identical to the pre-unified
   recorder, including its refusal to write a legacy sibling next to a unified
   `trail.yaml`. The default flips to on once that tooling wave lands.
   The fan-out run loop is sequential, so `--device android,ios` merges each
   device in turn with no in-process race. **Remaining:** two *separate*
   processes recording the same trail concurrently could still race the
   read-modify-write — a cross-process file lock (or append-and-merge / scratch
   reconcile) is the outstanding piece. The MCP authoring and desktop recording
   surfaces still write v1 siblings and are a follow-up.
2. **`trailblaze show <test> --device=<X>`** — a CLI rendering of the
   resolved-view-for-a-given-device, computing the closest-wins lookups
   and presenting the test as if it were a single-device run. Useful for
   debugging a specific device's execution path through a multi-platform
   trail.
3. **Sub-classifier audit pass.** Many of the migrated trails will have
   sub-classifiers (`ios-iphone` vs. `ios-ipad`) that diverge in trivial
   ways — typically the iPad recording is strictly weaker than the iPhone
   one (same `textRegex`, fewer additional matchers). A post-migration
   suggestion tool could flag these as "potentially collapsible into the
   broader classifier with one selector edit."
4. **Cross-classifier coverage policy.** What is the right default behavior
   when a step has classifiers declared but the device under test matches
   *none* of them? Today the answer is "no-op + check warning." Worth
   revisiting once the format has been in use long enough to see whether
   that produces actionable signal or just noise.
5. **Trailmap-level default drivers.** A trail's `config.devices:` map pins a
   driver per classifier only when it needs to override the default. Whether
   the trailmap manifest should carry a per-classifier default driver that a
   trail inherits (and a per-test `devices:` entry overrides) needs a clear
   spec and validator behavior. Today there is no trailmap-level per-platform
   Android driver preference — which is exactly why the per-trail pin exists.

## Revision — 2026-07-01: recordings grouped under `recording:`, trailhead as one tool, `config` optional

Working the contract against real multi-platform authoring surfaced a structural problem: a step put
`step:` (a fixed schema key) beside dynamic device-classifier keys (`android-phone:`) in one map —
known and dynamic keys sharing a level, which reads wrong and is awkward to model. This revision fixes
that and pins the trailhead contract. It **supersedes** the "File structure", "Step structure", and
"Trailhead semantics — tool tag, not section" sections above.

### File structure — three top-level keys (`config` and `trailhead` optional)

| Key | Purpose | Required |
| :-- | :-- | :-- |
| `config` | Identity, target, devices, memory, metadata | **Optional** — every field defaults; absent = empty config (restores v1) |
| `trailhead` | The deterministic step 0 — one bootstrap tool per platform | Optional |
| `trail` | The test — ordered list of steps | **Required, non-empty** |

`config` is no longer required — all its fields already default (no `target` → generic tools; no
`devices` → inherit from the trailmap manifest; no `id` → still runs), so the minimal valid file is
just `trail:`. `config` and `trailhead` stay **separate siblings** (not `config` folded into
`trailhead`): `config` is passive metadata, `trailhead` is an executable action. `trail:` is required
because it is the one key that makes the file a *test* — a trailhead-only file would run its bootstrap
and assert nothing (a vacuous always-pass).

### Step structure — device classifiers nest under `recording:`

A step's only top-level keys are **reserved schema** (`step`, `recording`, `recordable`). Every device
classifier lives **under `recording:`** — the two vocabularies never share a level.

```yaml
trail:
  # NL + per-device recordings (broad `android` and specific `android-phone`)
  - step: Add a latte to the cart and open checkout
    recording:
      android:                       # broad family
        - tapOn: { text: Latte }
        - tapOn: { text: Add to cart }
      android-phone:                 # most-specific wins at run time
        - tapOn: { id: menu_latte }
        - swipeUp: {}
        - tapOn: { id: cart_fab }
      ios:
        - tapOn: { label: Latte }
        - tapOn: { label: Cart }

  - step: Confirm the order summary shows exactly one item   # NL-only (agent solves live)

  - step: Verify the order total is shown                    # NL + assertion recording
    recording:
      android:
        - assertVisible: { text: Order total }
      ios:
        - assertVisible: { text: Order total }
```

- **`recording:` is singular** — the common case is a single device type, and it matches the
  pre-existing `recording:` key. Each classifier maps to an ordered **list** of tool calls.
- **`step:` (NL) is required; `recording:` is optional.** Two shapes: NL-only (blazed live) or
  NL + per-device recording. **There is no recording-only step** — natural language is forced so
  every step carries its intent (the trail stays legible and self-healing). This is the v2
  "no bare tool blocks — every entry has intent" principle, kept.
- **No default / inline recording.** Every recording is explicitly device-keyed — a cross-platform
  test spans many device types, and an implicit default would let an author record one platform and
  never realize the others are unhandled.
- **Resolution unchanged** — most-specific-first closest-wins (`android-phone` → `android`); a miss
  degrades to solving the NL live. The runtime/LLM only sees the NL plus the recording for the device
  under test.
- **One entry type: `step`.** There is no `verify:` entry and no per-step assertion-only flag. "Does
  not modify state" ≠ "verify" (a read-only tool like `listInstalledApps` is not a verification), so
  read-only-ness is a property of the *tool*, not the step. Verification is a step whose NL says
  "verify X" and whose recording holds assertion tools; the LLM picks non-modifying tools from intent.

### Trailhead — a first-class section: one tool per platform

This **supersedes** "Trailhead semantics — tool tag, not section". The trailhead is an explicit,
optional top-level element — the single "get to the start" call (step 0). Same outer shape as a step,
but its per-device recording is **exactly one tool** (a single function call), not a list:

```yaml
config:
  target: myapp
  memory:
    account_email: qe-sender@example.com

trailhead:
  step: Sign in as the QE sender          # NL (required) — intent + what the LLM matches to pick the tool
  recording:
    android-phone: { myapp_signInViaUI: { email: "{{memory.account_email}}" } }
    ios-iphone:    { myapp_ios_signInViaUI: { email: "{{memory.account_email}}" } }
    web:           { myapp_web_signIn: { email: "{{memory.account_email}}" } }
```

- **Optional**, at most one, positioned after `config:` and before the first `trail:` step. Runs first
  and replays deterministically.
- **One tool per platform** (a map, not a list). The bare-string shorthand
  (`trailhead: myapp_freshInstall`) is **removed** — a trailhead is always a tool call with its params.
- **NL required** — it is the signal the LLM uses to pick the trailhead tool, and NL is forced
  everywhere (no NL-less trailhead, no recording-only step).
- **A trailhead is a tool wearing a role.** The same trailhead-tagged tool is a normal tool that can
  also be called later in a regular `trail:` step (e.g. relaunch the app mid-flow). Multi-primitive
  bootstraps compose *inside* the trailhead tool's own definition (`*.trailhead.yaml` / `.trailhead.ts`
  / `@TrailblazeToolClass(trailheadTo=…)`), so the trail always sees a single call. The role governs
  where it can bootstrap from (any state, step 0); it never restricts the tool elsewhere.

### Migration

This changes the step shape that shipped with the unified format (classifier keys beside `step:` →
grouped under `recording:`; `step` now optional; `config` optional; trailhead reworked to a single tool
per platform). Only a handful of files use the unified format and there are no external users, so this
is a **hard cut** — migrate the few files, delete the old shape, no shim.

## Revision — 2026-07-02: `config.skip` (per-classifier) and `config.tags` restored

Migrating the first real batch of legacy trails into the unified format surfaced two `TrailConfig`
fields the initial `UnifiedTrailConfig` had dropped: `skip:` (gate a trail off a known blocker —
parsed and validated but not executed) and `tags:` (free-form grouping/filter labels). Neither had a
home in the unified config, so a straight migration would have silently un-skipped every gated trail.
Both are added back:

### `config.skip` — per-classifier, closest-wins

`skip:` is a **map of device-classifier → reason**, resolved with the same
[`TrailblazeClassifierLineage`](#classifier-hierarchy-resolution) the recordings and the `devices:`
driver pins use — so a trail can be skipped on one device family while still running on another.

```yaml
config:
  target: myapp
  skip:
    android: "blocked on #123 — flaky offline banner"
    # ios: not listed → still runs on iOS
```

- Resolved closest-wins for the device under test, then lowered to the single v1 `TrailConfig.skip`
  so every existing skip consumer (`firstSkipReason`, the CLI's `planTrailExecution` pre-flight,
  the runtime rules) is unchanged.
- **Device-agnostic fallback:** a caller with no classifiers at all counts the trail as skipped if
  *any* classifier declares a non-blank reason, so the skip gate still fires. The CLI planner
  resolves classifiers from `--device`, or — for a `--driver`-only run with no `--device` — from the
  driver's platform, so a device- or driver-pinned run gets the precise per-classifier verdict
  (e.g. `--driver=IOS_HOST` won't be halted by an `android`-only skip). The any-skip fallback only
  applies when neither `--device` nor `--driver` is set.
- Blank reasons (`skip: {android: ""}`) are treated as not-skipped, matching the v1 scalar semantics.

Per-classifier (not a scalar) because everything else per-device in this format already is; a
single scalar couldn't express "skip Android but keep running iOS."

### `config.tags` — flat, trail-level

`tags:` is a flat `List<String>`, identical to v1. Tags name the *whole* test (`smoke`, `flaky`),
not a device, so they lower verbatim with no per-classifier resolution and feed the CLI's `--tags`
filter for unified trails exactly as they did for v1.

Both are additive optional fields on `UnifiedTrailConfig`; absent from a file they default to null
and nothing changes.

## Revision — 2026-07-08: `verify:` steps

The unified format initially modeled only `- step:`, but ~80% of checked-in v1 trails use
`- verify:` steps, and verify semantics are load-bearing at run time: a verify step advertises only
assertion tools (the agent can't tap/scroll mid-assertion), auto-terminates with an assertion
ledger, and is never self-healed. Lowering `verify:` to `step:` silently discards all of that, so
the unified format now mirrors v1 exactly:

```yaml
trail:
  - step: Open the cart
  - verify: The cart shows 2 items
    recording:
      android-phone:
        - assertVisibleWithText:
            text: 2 items
```

- A step is authored as exactly one of `- step:` / `- verify:` (both or neither is a parse error),
  with the same optional sibling keys (`recording:`, `recordable:`, `maxRetries:`).
- A `verify:` step lowers to the v1 `VerificationStep` (a `step:` still lowers to `DirectionStep`),
  so the runtime's verify handling is unchanged.
- The **trailhead stays step-only** — it is a deterministic bootstrap, not an assertion; `verify:`
  there is a parse error.
- The recorder merge preserves kind: an appended step takes `verify:` when the recorded step was a
  verification step; on a re-record into an existing step the existing kind wins, with kind
  disagreement logged as drift (same policy as NL drift).
- The migrator carries each step's kind through (canonical preference mirrors NL: blaze.yaml when
  present, otherwise the first platform) and surfaces cross-platform kind disagreement as a
  leading-comment drift warning — never silently flattened.

Rollout note: a unified file containing `verify:` is a hard parse error (unexpected step-level
key) on Trailblaze binaries that predate this revision — on a mixed-version fleet, upgrade the
readers before checking in verify-step unified trails.
