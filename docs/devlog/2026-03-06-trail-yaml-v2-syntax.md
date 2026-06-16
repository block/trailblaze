---
title: "Trail YAML v2 Syntax"
type: decision
date: 2026-03-06
---

# Trail YAML v2 Syntax

Evolving our YAML syntax based on months of real-world trail authoring.

## Background

The current `.trail.yaml` syntax uses generic keywords (`prompts`, `tools`, `config`) that don't convey Trailblaze's identity and create unnecessary nesting. Key pain points:

- **`prompts` → `recording` → `tools`** is deeply nested for what's conceptually "here's an objective and the tools used to achieve it."
- **`config`** is generic and doesn't communicate what this block represents in the Trailblaze mental model.
- **`step`** is ambiguous — it doesn't communicate intent. A step could be "tapOnElement ref=e23" (mechanical) or "Tap the Sign In button" (intent). The keyword doesn't nudge authors toward the durable, self-healing form.
- **`verify`** is a separate keyword from `step`, but verification is really just a type of objective — the distinction is better expressed by the tools used (e.g., `assertVisibleBySelector`).
- **Bare `- tools:` blocks** let authors skip natural language intent entirely, producing fragile trails that can't self-heal when the UI changes.
- **`recording`** is an odd name for "the tools used to achieve this objective" — it implies a process (recording) rather than what it contains (tools).
- **No support for pre-seeded variables** — test data like emails, card numbers, and credentials must be hardcoded in step text or the `context` string rather than declared as structured, referenceable values.
- **No test setup concept** — setup steps (launch, sign in, navigate) are mixed in with test steps. There's no checkpoint to replay when iterating, and no way to distinguish "couldn't reach the starting point" from "test failed."
- **File is a list of items** — `[config, prompts, tools]` when there's exactly one of each. A document with named properties is simpler.
- **Maestro was a YAML primitive** — now replaced by `MaestroTrailblazeTool`, but the broader syntax should be updated to match.

## What we decided

### v2 Structure

The file is a YAML **mapping** (not a list) with two named sections:

| Section | Purpose | Contains |
| :--- | :--- | :--- |
| `trailhead` | Trail identity, configuration, and setup | id, title, context, memory, target, platform, metadata, setup |
| `trail` | The test itself | NL objectives with optional tool recordings |

The `trailhead` is everything about the starting point: what this trail is, how it's configured, and the objectives to get there. The `trail` is the test itself.

### Keyword Changes

| v1 Keyword | v2 Keyword | Rationale |
| :--- | :--- | :--- |
| `config` | `trailhead` | The trailhead is where the trail begins — identity, configuration, and setup all live here. |
| `prompts` | `trail` | The test objectives — the path you walk. Whether blazing (AI) or following a tool recording, it's the trail. |
| `step` | `objective` | Communicates intent ("what to achieve") rather than mechanics ("what to do next"). Nudges authors toward natural language that enables self-healing trails. |
| `recording` | `tools` (under each objective) | Honest about what it contains. Freed up by removing bare `tools` blocks — `tools` now only appears subordinate to an `objective`. |
| `- tools:` (bare block) | removed | Every entry must have an `objective`. No escape hatch that lets authors skip intent. This is the same principle as requiring `--objective` on the CLI. |
| `context` | `context` | Unchanged — still the right word. It's context for the trail, injected into the LLM system prompt at runtime. |
| `verify` | removed | Use `objective` for everything. Verification intent is expressed by the tools used. |
| `config` fields | `trailhead` fields | `id`, `title`, `context`, `memory`, `target`, `platform`, `metadata` move into `trailhead`. |
| (none) | `setup` | Setup objectives nested under `trailhead` — a checkpoint for recording iteration and deterministic replay. |

### v2 Syntax — Full Example

```yaml
# ── Trailhead: identity, configuration, and setup ──────────────────
trailhead:
  id: regression/suite_71172/section_838052/case_4837714
  title: Verify user cannot load more than $2,000 onto a Gift Card within 24 hours
  priority: P0

  # Optional — unlocks app-specific custom tools (e.g. launchAppSignedIn, deeplinks)
  # Without a target, trails run with generic tools only.
  target: myapp
  platform: ios

  # Injected into the LLM system prompt for this trail
  context: >
    The gift card number to use is {{giftCardNumber}}.
    Always dismiss any promotional dialogs before proceeding.

  # Pre-seeded runtime variables — available as {{varName}} in objectives and tool params
  memory:
    giftCardNumber: "7783 3224 0646 3436"
    email: testuser+giftcards@example.com
    password: "12345678"

  # Informational — never used at runtime, only for reporting/traceability
  metadata:
    caseId: "4837714"
    sectionId: "838052"
    sourceUrl: https://tracker.example.com/cases/view/12345

  # Setup objectives (checkpoint for recording iteration)
  setup:
    - objective: Launch the app with email {{email}} and password {{password}}
      tools:
        - myapp_ios_launchAppSignedIn:
            email: "{{email}}"
            password: "{{password}}"
    - objective: Navigate to Gift Cards
      tools:
        - tap: "Gift cards"

# ── Trail: the test objectives ───────────────────────────────────────
trail:
  - objective: Tap Reload card or check balance
    tools:
      - tap: "Check balance or reload card"

  - objective: Enter the gift card number
    tools:
      - tap: "0000 0000 0000 0000"
      - inputText: "{{giftCardNumber}}"

  - objective: Tap Next
    tools:
      - tap: "Next"

  - objective: Tap Add Value
    tools:
      - tap: "Add value"

  - objective: Select $50 option
    tools:
      - tap: "$50"

  - objective: Wait and tap Review sale
    tools:
      - tap: "Review sale 1 item"

  - objective: Tap Charge $50.00
    tools:
      - tap: "Charge $50.00"

  - objective: Tap on $50 amount
    tools:
      - tap: "$50"

  # Non-recordable objective — AI always handles this, tools are never overwritten
  - objective: Dismiss any payment confirmation dialogs
    recordable: false

  - objective: Verify the payment was declined
    tools:
      - assertVisible: "Amount exceeds gift card balance limit."
      - assertVisible: "Declined"
      - assertVisible: "Cancel Payment"
```

### blaze.yaml — NL Definition (Cross-Platform)

The blaze file is purely NL objectives — no tool recordings. Platform-specific tool recordings live in `.trail.yaml` files.

```yaml
trailhead:
  id: suite/71172/section/838052/case/4837714
  title: Verify gift card load limit
  memory:
    giftCardNumber: "7783 3224 0646 3436"
    email: testuser+giftcards@example.com
  setup:
    - objective: Launch the app and sign in with {{email}}
    - objective: Navigate to Gift Cards

trail:
  - objective: Tap Reload card or check balance
  - objective: Enter the gift card number
  - objective: Tap Next
  - objective: Tap Add Value
  - objective: Select $50 option
  - objective: Wait and tap Review sale
  - objective: Tap Charge $50.00
  - objective: Tap on $50 amount
  - objective: Dismiss any payment confirmation dialogs
    recordable: false
  - objective: >
      Verify the message "Amount exceeds gift card balance limit" appears.
      Verify the message "Declined" appears.
      Verify "Cancel Payment" button is visible.
```

### Key Design Principles

**1. Two sections, each with one job.** `trailhead` is where the trail begins — identity, configuration, and setup. `trail` is what you're testing — the test itself.

**2. Trailhead is the starting point.** Everything about _getting ready_ lives here: what this trail is (`id`, `title`), how it's configured (`context`, `memory`, `target`), and the objectives to reach the starting state (`setup`). The trailhead is a complete description of where the trail begins.

**3. Setup is a checkpoint.** During recording, `setup` is a save point. Mess up the test? Replay setup instantly, re-record. This is the primary motivation — it serves the recording and iteration workflow.

**4. Every entry is an `objective`.** Both `setup` and `trail` are lists of objectives. Each objective has an NL description (the durable intent) with optional `tools` (the derived implementation). There are no bare tool blocks — every tool sequence must be subordinate to an objective. This is the same principle as requiring `--objective` on the CLI: intent is not optional.

**5. `objective` communicates intent.** The word "objective" nudges authors toward writing *what to achieve* ("Navigate to the Money tab") rather than *how to do it* ("Tap the Money button"). This is the foundation of self-healing trails — when the UI changes, the objective is still clear, so the agent can re-solve it. Compare: `step: tapOnElement ref=e23` feels valid; `objective: tapOnElement ref=e23` feels obviously wrong. The name does free work.

**6. `tools` is honest.** The tool list under an objective is called `tools` because that's what it is — the tools used to achieve the objective. The old name `recording` implied a process; `tools` describes the content. Removing bare `- tools:` blocks freed up the word to be used in its natural place: subordinate to an objective.

**7. `memory` is active, `metadata` is passive.** Memory variables are interpolated at runtime via `{{varName}}`. Metadata is never touched by the framework — purely for reporting and traceability.

**8. `context` is the right word.** It's context for the trail — background information, constraints, and instructions injected into the LLM system prompt at runtime. The word communicates what the author is providing (context about this test) rather than an implementation detail (where the text is injected).

**9. `verify` is just an `objective`.** Any objective can perform verification — the intent is expressed by the tools used, not by a separate keyword.

**10. `recordable: false` remains per-objective.** This flag means "never overwrite this objective's tools during re-recording" — useful for objectives that should always be handled by the AI.

**11. File is a mapping, not a list.** Since there's exactly one of each section, named properties are simpler than an anonymous list of items.

**12. No top-level interleaving.** v1 allowed multiple `prompts` and `tools` blocks interleaved at the top level. v2 has exactly one `trailhead` and one `trail`.

**13. `objective` is source of truth, `tools` is ephemeral cache.** The semantic boundary is clear: `objective` (NL intent) is the durable, authoritative description. `tools` is a derived materialization — replaceable, rebuildable, secondary. The hierarchy itself communicates the relationship: `tools` is subordinate to `objective`.

### Setup Behavior

**Execution policy:**
1. If tools exist → replay deterministically (no AI, instant)
2. If no tools → blaze via AI (first run), then save tools
3. If tools fail → re-blaze from NL description, save new tools

**Failure semantics:**
- Setup failure = "couldn't reach the starting point" → test is **skipped/retried**, not failed
- Trail failure = "the test ran and something didn't work" → test is **failed**

**Reuse via custom tools:**
Setup is shared across tests through custom tools. A recorded setup sequence can be promoted to a custom tool (e.g., `setupMoneyTab`), then referenced by NL in other tests' setup.

### CLI Integration

The `objective` keyword aligns directly with the blaze CLI's `--objective` / `-o` flag:

```bash
# Each tool invocation declares its objective — groups into a single trail objective on save
blaze tool tapOnElement ref="Email" -o "Enter login credentials"
blaze tool typeText text="test@example.com" -o "Enter login credentials"
blaze tool tapOnElement ref="Sign In" -o "Enter login credentials"

# Goal mode — objective is the command itself
blaze "Enter login credentials"
```

When an external agent (Claude Code, Cursor, etc.) uses `blaze tool` with `-o`, consecutive calls sharing the same objective string are grouped into a single trail objective with a multi-tool recording. This gives external agents playwright-cli speed while producing repairable trails — they provide the intent they already have, and Trailblaze handles the recording infrastructure.

### Fast Mode (`--fast`)

Fast mode is a **runtime flag**, not a trail property. It's the agent saying "run as fast as you can while you complete these tool calls." The trail file is the same either way — `--fast` only controls execution overhead.

```bash
# Full mode: screenshots, logging, timeline view
blaze -o "Enter login credentials" tool tapOnElement ref="Email"

# Fast mode: text-only, skip screenshots/logging, maximum speed
blaze --fast -o "Enter login credentials" tool tapOnElement ref="Email"

# Fast mode with goal (inner agent uses text-only compact element list)
blaze --fast "Enter login credentials"

# Environment variable for CI pipelines
BLAZE_FAST=1 blaze "Enter login credentials"
```

**What `--fast` skips:** screenshots in LLM prompts (no vision tokens — text-only analysis using compact element lists), post-action screen recapture (the next command captures fresh state anyway). **What it keeps:** all logging (objective start/complete, tool calls), session recording, trail assembly — the full trail is still produced with all tool calls recorded. An agent can still explicitly request a screenshot via `ask(includeScreenshot=true)` when it needs to disambiguate visually. The trail produced is identical and repairable — it just skips the per-step screenshot overhead during execution.

### Naming Glossary

| Term | What it is |
| :--- | :--- |
| `trailhead` | Trail identity, configuration, and setup — where the trail begins |
| `setup` | Setup objectives within the trailhead (checkpoint for recording iteration) |
| `trail` | Test objectives — the path you walk (the test) |
| `objective` | Individual intent within setup or trail — "what to achieve", not "how to do it" |
| `tools` | Tool list under an objective — the derived implementation (ephemeral, replaceable) |
| *blazing* | AI exploration when no tools exist for an objective (verb, not keyword) |
| `blaze.yaml` | NL definition file — the plan before you go |
| `*.trail.yaml` | Platform recording file — the trail left behind |
| `memory` | Pre-seeded variables for template interpolation |
| `context` | Trail context — injected into the LLM system prompt at runtime |

## Migration Strategy

1. **Build a new v2 parser** alongside the existing one in `trailblaze-models/commonMain`.
2. **Try-catch fallback**: attempt v2 parsing first, fall back to v1 on failure.
3. **Bulk migrate** all `.trail.yaml` and `blaze.yaml` files once v2 is stable.
4. **Delete v1 parser** after migration is complete.

### v1 → v2 Mapping

| v1 | v2 |
| :--- | :--- |
| `- config:` (list item) | `trailhead:` (mapping key) |
| `- prompts:` (list item, multiple allowed) | `trail:` (mapping key, exactly one) |
| `- tools:` (list item, standalone top-level) | removed — wrap in `- objective:` with NL intent |
| `step:` + `recording: tools:` | `objective:` + `tools:` |
| `verify:` | `objective:` (with assertion tools) |
| `context:` (in config) | `context:` (in trailhead) |
| multiple interleaved blocks | single `trailhead` + `trail` |

## What changed

**Positive:**
- Two clearly distinct sections — trailhead (starting point) and trail (the test)
- Setup as a checkpoint within trailhead enables recording iteration and deterministic setup replay
- `trailhead` semantically groups identity + config + setup as "everything about the starting point"
- `objective` communicates intent — nudges authors toward "what to achieve" over "how to do it", enabling self-healing trails
- `objective` aligns with CLI `--objective` / `-o` flag, giving external agents a natural way to declare intent while using direct tools
- `tools` under each objective is honest about what it contains — no more `recording` indirection
- No bare `tools` blocks — every entry has intent, same principle as requiring `--objective` on the CLI
- File is a mapping — simpler than a list when there's one of each section
- Clear semantic boundary: `objective` is source of truth, `tools` is ephemeral cache — hierarchy communicates the relationship
- `setup` and `trail` share the same authoring model (lists of objectives)
- Structured variable support via `memory`
- `context` is unchanged from v1 — already the right word, no migration needed for this field
- Removing `verify` simplifies the model — one fewer concept to learn
- Foundation for future `mode`-based execution configuration
- Setup failure vs trail failure distinction improves test reporting

**Negative:**
- All existing `.trail.yaml` and `blaze.yaml` files must be migrated (mitigated by try-catch fallback period)
- External tools/scripts that parse trail files need updating
- Two parsers coexist temporarily during migration
- Authors who previously used bare `tools` blocks must now provide an objective — a small tax that produces repairable trails

---

## Revision — 2026-05-17

After working through real authoring scenarios, this revision narrows the v2-original design and drops several speculative pieces. Three things change from v2-original; the broader principles (NL as source of truth, no bare tool blocks, ephemeral-tools-cache) all hold.

### Refinement 1 — `config:` stays, `trailhead:` is a NEW sibling key, not a rename of `config:`

The original v2 proposed renaming `config:` → `trailhead:` with everything (identity, target, platform, metadata, memory, context, setup) nested inside. That collapsed too many concepts under one name and forced a top-level rename on every existing trail.

The refined model keeps `config:` as identity (same job as v1 — `id`, `target`, `platform`, `metadata`, `memory`, `context`) and introduces `trailhead:` as a sibling key whose sole job is to describe the deterministic starting state of the trail.

```yaml
config:                                 # identity, unchanged in spirit from v1
  id: ...
  target: myapp
  platform: android

trailhead:                              # NEW — the bootstrap (singleton)
  step: Sign in as standard merchant on Square
  tools:
    - myapp_launchAppSignedInWithAccount:
        key: defaults/standard-account

trail:                                  # list of steps (the test)
  - step: Go to More Menu
    tools:
      - tapOnElementBySelector: { ... }
```

Three sibling top-level keys: `config / trailhead / trail`. Each does one thing.

### Refinement 2 — `trailhead` is a singleton with `{ step, tools }` shape, not a `setup:` list

The original v2 had `trailhead.setup:` as a list of objectives, each with optional nested tools. The refined model: `trailhead:` is one entry, structurally identical to a `trail:` entry — `{ step, tools }`. One NL step describing the starting state, plus one or more tools to reach it. The asymmetry between `trailhead:` (singleton) and `trail:` (list) reflects the real semantic difference: one start, many test steps.

| Key | Outer container | Inner shape |
|---|---|---|
| `trailhead` | singleton | `{ step, tools }` |
| `trail` | list | `[{ step, tools }, …]` |

The `tools:` list under `trailhead` can hold multiple trailhead-tagged tools (e.g. an API-call trailhead followed by an app-launch trailhead), but each must be a tool tagged via `@TrailblazeToolClass(trailheadTo = …)` or a `*.trailhead.yaml` file.

### Refinement 3 — `step:` (v1 keyword) stays; `objective:` lives only in code; `trailhead:` is OPTIONAL

- The original v2 renamed `step:` → `objective:` to nudge authors toward intent over mechanics. In practice `objective` reads as scale-ambiguous (the trail's objective, or this entry's?); `step:` is scope-locked and already understood. The intent-over-mechanics discipline lives as a lint hint and a skill principle, not a keyword. The abstract concept stays in Kotlin code.
- `trailhead:` is **optional** in the parser, same status as `config:`. Trails without a `trailhead:` block parse and run. Zero migration tax for existing trails. The discipline ("every reproducible trail has a trailhead") is established through the authoring surface, not parser rejection.

| v1 → v2 (original) | v1 → v2 (refined) |
|---|---|
| `step` → `objective` | `step` stays `step` |
| Rename `config` → `trailhead` | Keep `config:`; add `trailhead:` as sibling |
| `trailhead.setup:` as a list | `trailhead:` is one `{ step, tools }` entry |
| (implicit) trailhead-required | trailhead optional; absence is silent |

### How authors will be funneled to use trailheads — design TBD

This part of the original v2 design (CLI flows that walk authors through picking a trailhead) is still open. The current commitments:

- The data needed for an author or agent to choose a trailhead is already on `@TrailblazeToolClass` (the `trailheadTo` field) and `@LLMDescription` (the description text on class and params). No new metadata fields like `when_to_use` / `do_not_use_for` / `composes_with` should be invented; the existing `@LLMDescription` text is the right place for "when to use this." If a particular tool's description isn't decision-grade, the fix is to improve that `@LLMDescription` — not to introduce a parallel metadata channel.
- Discovery happens via the existing `./trailblaze toolbox` command, extended with section grouping and positional role filters:
  - `./trailblaze toolbox` — everything, grouped by section (TRAILHEADS / SHORTCUTS / TOOLS) with one-line role descriptions.
  - `./trailblaze toolbox trailheads` — just trailhead-tagged.
  - `./trailblaze toolbox shortcuts` — just shortcut-tagged.
  - `./trailblaze toolbox tools` — just the residual (no trailhead/shortcut metadata).
  - Optional `--target=<T> --platform=<P>` to narrow.
- The implementation noun stays `tool` everywhere: `TrailblazeTool` (Kotlin class), `./trailblaze toolbox` (CLI), `./trailblaze tool <name>` (invocation), `tools:` (YAML key). The TOOLS section in the listing is the same word as the implementation type — the overload is harmless because section descriptions disambiguate the role.
- Plain-text output for now; structured output (YAML / JSON) deferred until a concrete need surfaces.
- Specific funnel surfaces (interactive flows in `./trailblaze blaze`, scaffolding defaults, the on-demand pipeline's external-test-case → trail generator) are NOT settled here. They will be designed against a working discovery surface first.

The principle the funnel work will hold to: **make it easy to do things the right way.** Discovery comes first so authors can SEE what's available; funneling them through workflows is a follow-on once discovery is solid.

### Constraint on the trailhead's `tools:` list

When `trailhead:` IS present, every tool listed should resolve to a trailhead-tagged tool. The check is a soft lint warning (a teaching message pointing at `./trailblaze toolbox trailheads`), not a parser rejection. Absence of `trailhead:` is permitted; mis-tagged contents inside `trailhead:` get a warning.

### Migration

No script for the trailhead concept itself — it's additive, not a rename. Existing trails parse and run. The original v2 keyword renames (`prompts:` → `trail:`, removal of bare `- tools:` blocks, etc.) remain a separate workstream with their own migrator when that work happens.

### Examples

**Single-step bootstrap (90% case):**
```yaml
trailhead:
  step: Sign in as standard merchant on Square
  tools:
    - myapp_launchAppSignedInWithAccount:
        key: defaults/standard-account
```

**Multi-step bootstrap (API call + launch):**
```yaml
trailhead:
  step: Create a test merchant via API, then launch Square signed in
  tools:
    - myapp_apiSetup:
        template: standard-account
    - myapp_launchAppSignedInWithAccount:
        key: defaults/standard-account
```

**NL-only `blaze.yaml` (recorder materializes `tools:` on first run):**
```yaml
config:
  id: regression/suite_71172/section_946176/case_5552497
  target: myapp
  platform: android
trailhead:
  step: Sign in as standard merchant on Square
trail:
  - step: Go to More Menu
  - step: Tap Add-ons
  - step: Select "Square Appointments"
```

**Trail without a trailhead (legacy or intentional fragment):**
```yaml
config:
  id: my-debug-trail
  target: myapp
  platform: android

trail:
  - step: ...
    tools: [...]
```

Parses and runs. Soft warning at lint time; no failure.

### Open questions for follow-up devlogs

1. **Funnel design** — how the authoring surfaces (`./trailblaze blaze`, scaffolding, the test-case generator) walk authors through trailhead selection. Discovery via `./trailblaze toolbox trailheads` is the foundation; the workflow design happens after that lands.
2. **Default trailhead per target** — each registered target needs a designated default trailhead tool (e.g. `myapp` defaults to `myapp_launchAppSignedInWithAccount(key: defaults/standard-account)`). Lives in a registry of some kind; format TBD.
3. **`@LLMDescription` audit** — for the existing trailhead-tagged tool classes, ensure each description is decision-grade. Some are; others need a pass.
4. **Validation lint behavior** — `./trailblaze toolbox trailheads`-based lint warnings vs CI-gating. Default warn-only; opt-in `--strict` for teams that want PR-blocking.

### What changed (added to original "What changed" section above)

**Positive:**
- Keep `config:` as identity — zero churn on existing trail headers.
- `trailhead:` is a new sibling key with a single shape `{ step, tools }`, structurally identical to a trail entry. The asymmetry (singleton vs list) teaches the semantic difference.
- `step:` keyword preserved — no rename tax.
- Optional schema means zero migration cost for existing trails.
- Discovery surface is the existing `./trailblaze toolbox` with positional role filters (`trailheads` / `shortcuts` / `tools`) and section grouping. Small additive change; no new top-level commands, no rename of `tool` / `toolbox` / `TrailblazeTool` / `tools:` YAML key.
- No invented catalogue metadata. The agent decision-signal (`@LLMDescription` text on class + params, `trailheadTo` on the annotation) already exists; the CLI surfaces what's there.

**Negative:**
- Discipline depends on authoring-surface quality, which is not yet designed.
- Existing trails without trailheads will silently work, creating a long tail of non-bootstrap trails until they're touched.
- The funnel design is deferred. Discovery exists; getting authors to actually USE trailheads at the right moments is the harder problem and isn't settled here.
