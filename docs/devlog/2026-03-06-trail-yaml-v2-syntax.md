---
title: "Trail YAML v2 Syntax"
type: decision
date: 2026-03-06
---

# Trail YAML v2 Syntax

Evolving our YAML syntax based on months of real-world trail authoring.

## Background

The current `.trail.yaml` syntax uses generic keywords (`prompts`, `tools`, `config`) that don't convey Trailblaze's identity and create unnecessary nesting. Key pain points:

- **`prompts` → `recording` → `tools`** is deeply nested for what's conceptually "here's a step and what was recorded."
- **`config`** is generic and doesn't communicate what this block represents in the Trailblaze mental model.
- **`verify`** is a separate keyword from `step`, but verification is really just a type of step — the distinction is better expressed by the tools used (e.g., `assertVisibleBySelector`).
- **`context`** doesn't communicate that the value is injected into the LLM system prompt.
- **No support for pre-seeded variables** — test data like emails, card numbers, and credentials must be hardcoded in step text or the `context` string rather than declared as structured, referenceable values.
- **No test setup concept** — setup steps (launch, sign in, navigate) are mixed in with test steps. There's no checkpoint to replay when iterating, and no way to distinguish "couldn't reach the starting point" from "test failed."
- **File is a list of items** — `[config, prompts, tools]` when there's exactly one of each. A document with named properties is simpler.
- **Maestro was a YAML primitive** — now replaced by `MaestroTrailblazeTool` (see PR #1944), but the broader syntax should be updated to match.

## What we decided

### v2 Structure

The file is a YAML **mapping** (not a list) with two named sections:

| Section | Purpose | Contains |
| :--- | :--- | :--- |
| `trailhead` | Trail identity, configuration, and setup | id, title, systemPrompt, memory, target, platform, metadata, setup |
| `trail` | The test itself | NL steps with optional recordings |

The `trailhead` is everything about the starting point: what this trail is, how it's configured, and the steps to get there. The `trail` is the test itself.

### Keyword Changes

| v1 Keyword | v2 Keyword | Rationale |
| :--- | :--- | :--- |
| `config` | `trailhead` | The trailhead is where the trail begins — identity, configuration, and setup all live here. |
| `prompts` | `trail` | The test steps — the path you walk. Whether blazing (AI) or following a recording, it's the trail. |
| `recording.tools` | `recording` (under each step) | Flattened from 2 levels to 1. The `tools` wrapper is removed. |
| `tools` (top-level) | `tools` (in step lists) | No longer a standalone top-level block. Now a directly authored deterministic primitive alongside `step` entries in `setup` and `trail`. |
| `context` | `systemPrompt` | No ambiguity — this text is injected into the LLM system prompt. |
| `verify` | removed | Use `step` for everything. Verification intent is expressed by the tools used. |
| `config` fields | `trailhead` fields | `id`, `title`, `systemPrompt`, `memory`, `target`, `platform`, `metadata` move into `trailhead`. |
| (none) | `setup` | Setup steps nested under `trailhead` — a checkpoint for recording iteration and deterministic replay. |

### v2 Syntax — Full Example

```yaml
# ── Trailhead: identity, configuration, and setup ──────────────────
trailhead:
  id: testrail/suite_71172/section_838052/case_4837714
  title: Verify user cannot load more than $2,000 onto a Gift Card within 24 hours
  priority: P0

  # Optional — unlocks app-specific custom tools (e.g. launchAppSignedIn, deeplinks)
  # Without a target, trails run with generic tools only.
  target: myapp
  platform: ios

  # Injected into the LLM system prompt for this trail
  systemPrompt: >
    The gift card number to use is {{giftCardNumber}}.
    Always dismiss any promotional dialogs before proceeding.

  # Pre-seeded runtime variables — available as {{varName}} in steps and tool params
  memory:
    giftCardNumber: "7783 3224 0646 3436"
    email: testuser+giftcards@example.com
    password: "12345678"

  # Informational — never used at runtime, only for reporting/traceability
  metadata:
    caseId: "4837714"
    sectionId: "838052"
    testRailUrl: https://testrail.example.com/index.php?/cases/view/12345

  # Setup steps (checkpoint for recording iteration)
  setup:
    - step: Launch the app with email {{email}} and password {{password}}
      recording:
        - myapp_ios_launchAppSignedIn:
            email: "{{email}}"
            password: "{{password}}"
    - tools:
        - tap: "Gift cards"

# ── Trail: the test steps ────────────────────────────────────────────
trail:
  - step: Tap Reload card or check balance
    recording:
      - tap: "Check balance or reload card"

  - step: Enter the gift card number
    recording:
      - tap: "0000 0000 0000 0000"
      - inputText: "{{giftCardNumber}}"

  - step: Tap Next
    recording:
      - tap: "Next"

  - step: Tap Add Value
    recording:
      - tap: "Add value"

  - step: Select $50 option
    recording:
      - tap: "$50"

  - step: Wait and tap Review sale
    recording:
      - tap: "Review sale 1 item"

  - step: Tap Charge $50.00
    recording:
      - tap: "Charge $50.00"

  - step: Tap on $50 amount
    recording:
      - tap: "$50"

  # Non-recordable step — AI always handles this, recording is never overwritten
  - step: Dismiss any payment confirmation dialogs
    recordable: false

  # Direct tools block — hand-authored deterministic sequence, no NL step needed
  - tools:
      - assertVisible: "Amount exceeds gift card balance limit."
      - assertVisible: "Declined"
      - assertVisible: "Cancel Payment"
```

### blaze.yaml — NL Definition (Cross-Platform)

The blaze file is primarily NL — no recordings. `tools` blocks are allowed for platform-agnostic deterministic sequences, but platform-specific recordings live in `.trail.yaml` files.

```yaml
trailhead:
  id: suite/71172/section/838052/case/4837714
  title: Verify gift card load limit
  memory:
    giftCardNumber: "7783 3224 0646 3436"
    email: testuser+giftcards@example.com
  setup:
    - step: Launch the app and sign in with {{email}}
    - step: Navigate to Gift Cards

trail:
  - step: Tap Reload card or check balance
  - step: Enter the gift card number
  - step: Tap Next
  - step: Tap Add Value
  - step: Select $50 option
  - step: Wait and tap Review sale
  - step: Tap Charge $50.00
  - step: Tap on $50 amount
  - step: Dismiss any payment confirmation dialogs
    recordable: false
  - step: >
      Verify the message "Amount exceeds gift card balance limit" appears.
      Verify the message "Declined" appears.
      Verify "Cancel Payment" button is visible.
```

### Key Design Principles

**1. Two sections, each with one job.** `trailhead` is where the trail begins — identity, configuration, and setup. `trail` is what you're testing — the test itself.

**2. Trailhead is the starting point.** Everything about _getting ready_ lives here: what this trail is (`id`, `title`), how it's configured (`systemPrompt`, `memory`, `target`), and the steps to reach the starting state (`setup`). The trailhead is a complete description of where the trail begins.

**3. Setup is a checkpoint.** During recording, `setup` is a save point. Mess up the test? Replay setup instantly, re-record. This is the primary motivation — it serves the recording and iteration workflow.

**4. Two kinds of entries: `step` and `tools`.** Both `setup` and `trail` are lists that can contain either kind. A `step` has an NL description (the durable intent) with an optional `recording` (ephemeral derived cache). A `tools` block is a directly authored deterministic sequence — no NL, no recording, hand-written by the author. "Blazing" (AI exploration) is a process/verb, not a keyword.

**5. `recording` is flat.** `recording.tools` becomes just `recording` — one level of nesting removed.

**6. `memory` is active, `metadata` is passive.** Memory variables are interpolated at runtime via `{{varName}}`. Metadata is never touched by the framework — purely for reporting and traceability.

**7. `systemPrompt` is honest.** Calling it what it is removes all ambiguity about where this text ends up.

**8. `verify` is just a `step` (or `tools`).** Any step can perform verification — the intent is expressed by the tools used, not by a separate keyword. Pure assertion sequences can also be written as direct `tools` blocks.

**9. `recordable: false` remains per-step.** This flag means "never overwrite this step's recording during re-recording" — useful for steps that should always be handled by the AI.

**10. File is a mapping, not a list.** Since there's exactly one of each section, named properties are simpler than an anonymous list of items.

**11. No top-level interleaving.** v1 allowed multiple `prompts` and `tools` blocks interleaved at the top level. v2 has exactly one `trailhead` and one `trail`. Within each list, `step` and `tools` entries can be freely mixed — but the top-level structure is fixed.

**12. `step` is source of truth, `recording` is ephemeral cache.** The semantic boundary is clear: `step` (NL intent) is the durable, authoritative description. `recording` is a derived materialization — replaceable, rebuildable, secondary. `tools` blocks are different: they are hand-authored and authoritative in their own right.

### Setup Behavior

**Execution policy:**
1. If recording exists → replay deterministically (no AI, instant)
2. If no recording → blaze via AI (first run), then save recording
3. If recording fails → re-blaze from NL description, save new recording

**Failure semantics:**
- Setup failure = "couldn't reach the starting point" → test is **skipped/retried**, not failed
- Trail failure = "the test ran and something didn't work" → test is **failed**

**Reuse via custom tools:**
Setup is shared across tests through custom tools. A recorded setup sequence can be promoted to a custom tool (e.g., `setupMoneyTab`), then referenced by NL in other tests' setup.

### Naming Glossary

| Term | What it is |
| :--- | :--- |
| `trailhead` | Trail identity, configuration, and setup — where the trail begins |
| `setup` | Setup steps within the trailhead (checkpoint for recording iteration) |
| `trail` | Test steps — the path you walk (the test) |
| `step` | Individual action within setup or trail |
| `recording` | Ephemeral derived cache for a step (deterministic replay, replaceable) |
| `tools` | Directly authored deterministic block — hand-written, not derived from a step |
| *blazing* | AI exploration when no recording exists (verb, not keyword) |
| `blaze.yaml` | NL definition file — the plan before you go |
| `*.trail.yaml` | Platform recording file — the trail left behind |
| `memory` | Pre-seeded variables for template interpolation |
| `systemPrompt` | Text injected into LLM system prompt |

### Future: Execution Mode

A future enhancement will add a `mode` property to `trailhead` that controls the speed/accuracy tradeoff:

```yaml
trailhead:
  mode: fast      # fast | accurate | custom
```

| Mode | View Hierarchy | Tool Sets | Target |
| :--- | :--- | :--- | :--- |
| `fast` | Filtered, minimal nodes | Core tools only | Local LLMs, small context windows |
| `accurate` | Full hierarchy, all nodes/bounds | All available tools | Frontier models, max reliability |
| `custom` | Explicit per-trail config | Explicit per-trail config | Fine-tuned control |

This is intentionally deferred — the right API will emerge from real usage with local vs. frontier models.

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
| `- tools:` (list item, standalone top-level) | `- tools:` (entry in `setup`/`trail` lists) |
| `step:` + `recording: tools:` | `step:` + `recording:` |
| `verify:` | `step:` (with assertion tools) |
| `context:` (in config) | `systemPrompt:` (in trailhead) |
| multiple interleaved blocks | single `trailhead` + `trail` |

## What changed

**Positive:**
- Two clearly distinct sections — trailhead (starting point) and trail (the test)
- Setup as a checkpoint within trailhead enables recording iteration and deterministic setup replay
- `trailhead` semantically groups identity + config + setup as "everything about the starting point"
- Flat `recording` syntax (one fewer nesting level)
- File is a mapping — simpler than a list when there's one of each section
- Clear semantic boundary: `step` is source of truth, `recording` is ephemeral cache
- `tools` blocks preserved as a hand-authored deterministic primitive alongside `step` entries
- `setup` and `trail` share the same authoring model (mixed `step` and `tools`)
- Structured variable support via `memory`
- `systemPrompt` removes confusion about where context text is used
- Removing `verify` simplifies the model — one fewer concept to learn
- `tools` as a first-class primitive gives authors an escape hatch for deterministic sequences without forcing NL wrapping
- Foundation for future `mode`-based execution configuration
- Setup failure vs trail failure distinction improves test reporting

**Negative:**
- All existing `.trail.yaml` and `blaze.yaml` files must be migrated (mitigated by try-catch fallback period)
- External tools/scripts that parse trail files need updating
- Two parsers coexist temporarily during migration
