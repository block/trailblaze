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
| `config` | Identity, target, device list, context, memory, metadata | Singleton |
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
  devices:
    - android-phone
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

No `on:` wrapper, no `tools:` wrapper, no platform field at the file level —
just the `devices:` declaration in config and per-step classifier keys.

### Example — multi-platform with classifier hierarchy

```yaml
config:
  id: myapp/checkout
  target: myapp
  devices:
    - android-phone
    - android-tablet
    - ios
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
was declared in `config.devices:` but the step provides no recording reachable
via the hierarchy). The runtime never errors on missing recordings — execution
continues to the next step, and any real test breakage shows up downstream
(e.g., a subsequent assertion fails because the screen state never advanced).

### Classifier hierarchy resolution

Resolution per step, given a device classifier `X`:

1. If the step has `X:` declared (exact match), use that.
2. Otherwise, walk up the classifier hierarchy looking for a broader match
   (`android-phone` → `android`).
3. If no match is found, the step is a no-op for this device. If the device
   was declared in `config.devices:`, `trailblaze check` surfaces it as a
   coverage warning. If the device was *not* declared in `config.devices:`,
   that's also a coverage warning (a recording exists for an undeclared
   device class).

### `config:` fields

| Field | Type | Required | Notes |
| :--- | :--- | :--- | :--- |
| `id` | string | yes | Stable identifier; convention is the source-system path (e.g. `testsystem/suite_NNNN/section_NNNN/case_NNNN`). |
| `target` | string | yes | Target name from the trailmap manifest. |
| `devices` | list of classifier names | recommended | What the test claims to support. Inherited from the trailmap manifest's `platforms:` if omitted. Soft-validated by `trailblaze check`. |
| `context` | string | optional | Free-form context injected into the LLM system prompt. |
| `memory` | map of name → value | optional | Pre-seeded variables for `{{name}}` interpolation in NL and tool params. |
| `metadata` | map | optional | Informational only — never read at runtime. Used for traceability (case IDs, URLs, etc.). |

Retired from the legacy v1/v2 per-platform files:

- **`platform:`** (singular) — replaced by `devices:` (plural, granular).
- **`driver:`** — driver selection is now a runtime property derived from
  the device under test plus the trailmap's driver preferences. Storing it
  per-file forces unnecessary duplication and creates a sync problem.
- **`title:`** — was useful when each platform file had its own identity;
  with a single file per test, the `id` field covers identification and
  the human-readable title can live in `metadata.title` if needed.

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
   `driver:`, `platform:`, and `title:` (per the unified schema changes).
   Promote `id`, `target`, `context`, `memory`, `metadata` verbatim.
   Compute `devices:` from the union of present-file classifiers.
3. **Reconcile NL.** For each step index, gather the NL strings from every
   platform file. If they agree, that's the canonical NL. If they disagree
   (drift), emit a warning comment block at the top of the merged file
   listing the divergent strings — the canonical NL is the first
   platform's, but human review is required.
4. **Merge recordings into a step entry per step index.** For each
   classifier `<C>`, take its recording at that step index and place it
   under `<C>:` in the merged step. If a platform file had `# No recordings
   generated...` style absence, that classifier is omitted at this step.
5. **Auto-emit `recordable: false`** for steps where *no* platform produced
   a recording. (These are typically steps where no stable selector exists
   and the agent is expected to handle the step every run.)
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
| `platform: android` (singular) | `devices: [android-phone, android-tablet, …]` (plural, granular) |
| `driver:` field | removed (determined at runtime) |
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
devices: [android-phone, android-tablet, ios]

                                     android-phone   android-tablet   ios
Step 1: Launch myapp                  ⚠               ✓                ✓
Step 2: Verify Home tab               ✓               ✓                ✓
Step 3: LLM-handled                   —               —                —     (recordable: false)
Step 4: Skip on tablet                ✓               — (explicit [])  ✓
```

- ✓ = recording present (direct or via classifier hierarchy)
- — = explicit `[]` or `recordable: false`
- ⚠ = declared in `config.devices:` but no recording — possible gap

Soft warnings by default. Teams that want hard-fail can opt in with
`--strict`.

## What changed

**Positive:**

- One file per test instead of one per platform plus a `blaze.yaml` sibling.
  NL has exactly one physical home; drift is structurally impossible.
- Per-step classifier keys (`ios:`, `android-phone:`) read like CSS / Tailwind
  responsive overrides — base case at family level, specific overrides
  where reality diverges.
- `devices:` field in `config:` makes per-test platform coverage declarative
  and verifiable. Coverage matrix is a real artifact, not a heuristic.
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

1. **Recorder UX for the unified file.** When a recording session runs
   against a specific device, the recorder needs to write into the
   appropriate `<classifier>:` slot in the unified file rather than create
   a sibling file. Concurrent recording sessions for different device
   classes against the same file need a conflict-resolution strategy
   (file lock, append-and-merge, separate scratch files reconciled at
   commit time).
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
5. **Trailmap-level `devices:` inheritance.** A test that omits `config.devices:`
   inherits the full set from its trailmap manifest. The interaction between
   trailmap-level platform support and per-test override needs a clear spec
   and validator behavior (warn on undeclared overlap, etc.).
