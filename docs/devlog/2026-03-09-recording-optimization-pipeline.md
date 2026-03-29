---
title: "Recording Optimization Pipeline"
type: decision
date: 2026-03-09
---

# Recording Optimization Pipeline

Post-processing recorded trails to make them more reliable and concise.

## Background

When the AI blazes a test (explores UI via natural language), it produces a raw execution trace — XY coordinates, view hierarchies, screenshots, and memory state at each action. Currently, selectors are computed at runtime during the blaze, which can be inaccurate — the AI picks whatever works fastest (often text-based selectors or even XY coordinates) without considering long-term repeatability.

This creates two problems:

1. **Runtime selectors can be wrong.** The AI guesses a selector, it matches something slightly off, but the tap still works because the coordinates are right. The recording inherits the wrong selector.
2. **Recordings are brittle.** Hardcoded values, text-based selectors, and no variable extraction mean recordings break when data changes, UI shifts, or the test runs against different backend state.

## What we decided

### Separate Capture from Optimization

**During blazing**: capture ground truth only — XY coordinates + full view hierarchy + screenshots + memory state at each action. Do not compute selectors at runtime.

**After blazing**: a post-processing pipeline transforms raw capture data into optimized, stable recordings using full context from the execution.

**Optionally before blazing**: a pre-processing step analyzes NL steps to identify memory slots, giving the AI awareness of named variables to capture.

### Pipeline Architecture

```
NL Steps (authored by human or LLM)
       │
       ▼
  Pre-Processing (optional)
  Analyze NL → identify memory slots
       │
       ▼
  Blazing (runtime)
  AI executes, raw capture only
  XY + hierarchy + screenshot + memory
       │
       ▼
  Post-Processing
  Selectors, slots, generalization (mode-aware)
       │
       ▼
  Validation Loop (policy-dependent)
  Replay → compare → refine → repeat
       │
       ▼
  Stable Trail ✓
```

### Raw Capture Format

Each action during blazing captures:

```
{
  action: "tap",
  coordinates: { x: 340, y: 720 },
  viewHierarchy: { ... },        // full snapshot at action time
  screenshot: "path/to/img",     // visual context
  nlStep: "Tap Add to Cart",     // what the AI was trying to do
  memoryState: { ... },          // current memory at this point
  timestamp: ...
}
```

This data already exists in session logs (except `memoryState`, which is easy to add). The raw capture is the **source of truth** that never changes. Post-processing is a lens applied to it — re-run with different settings without re-blazing.

### Pre-Processing: Slot Analysis

Before blazing, an LLM analyzes NL steps to identify memory slots:

**Input:**
```yaml
trail:
  - step: Note how many apples are in the cart
  - step: Add 2 more apples
  - step: Verify apple count increased by 2
```

**Output:**
- Named slots: `appleCount` (captured from screen)
- Relationships: verification uses `appleCount + 2`
- AI instructions injected into system prompt: "You have a memory variable `appleCount`. When you observe the apple count on screen, call `memory.set("appleCount", value)` to store it."

**Two kinds of slots:**
- **Input slots** — values provided before the test (email, password). Seeded in `config.memory`.
- **Captured slots** — values read from screen at runtime. The AI uses `memory.set()` to store them.

Pre-processing is optional. Without it, post-processing still extracts slots from the execution log. Pre-processing makes the AI aware of variable names upfront, producing cleaner recordings with meaningful names.

### Post-Processing

Post-processing transforms raw capture data into an optimized recording. It has four responsibilities:

#### 1. Selector Computation

Resolve XY coordinates to the best available selector using the view hierarchy:

**Process:**
1. Resolve XY → element (find element whose bounds contain coordinates)
2. Walk up the selector ranking — pick highest-durability property that uniquely identifies the element
3. Validate uniqueness against full hierarchy
4. If not unique, combine properties or add parent context

**Selector ranking (most to least durable):**

| Selector type | Durability | Example |
|---|---|---|
| `id` | Best | `id: "add_to_cart_btn"` |
| `contentDescription` | Great | `contentDescription: "Add to cart"` |
| `type + parent context` | Good | `type: Button, parent: "#product-detail"` |
| `text` | Okay | `text: "Add to Cart"` |
| `class + index` | Fragile | `class: "CartButton", index: 2` |
| `xy coordinates` | Worst | `xy: [340, 720]` |

Text-based selectors are what the AI naturally picks during blazing because they're human-readable. But they break with dynamic data, localization, or minor copy changes. The post-processor upgrades to structural selectors while the NL description preserves readability.

#### 2. Slot Extraction

Identify hardcoded values that should be variables:

**Heuristics:**
- Strings in `inputText` calls → likely input slots (credentials, search terms)
- Values in both a `readText` and a later assertion → captured slots
- Values from `config.systemPrompt` that appear in tool calls → memory variables
- Repeated values across multiple steps → slot candidates

**Process:**
1. Scan all tool calls for literal values
2. Group values by semantic role (using NL context)
3. Generate meaningful variable names (LLM call using NL descriptions)
4. Replace literals with `{{variableName}}` references
5. Populate `config.memory` with input slot values

#### 3. Value Generalization

Replace exact values with patterns where the intent is format, not value:

| NL Intent | Raw | Generalized |
|---|---|---|
| "Verify a price is shown" | `equals: "$50.00"` | `matches: "\\$\\d+\\.\\d{2}"` |
| "Verify a date appears" | `equals: "March 9, 2026"` | `matches: "\\w+ \\d{1,2}, \\d{4}"` |
| "Verify item count shown" | `equals: "3 items"` | `matches: "\\d+ items"` |
| "Verify total is correct" | `equals: "$50.00"` | `equals: "{{expectedTotal}}"` |

The decision between regex and expression depends on NL intent — does the test care about a specific computed value, or just that something of the right format appeared?

#### 4. Expression Detection

Identify mathematical or logical relationships between captured values:

- AI read "5", later asserted "7", NL says "increased by 2" → `{{appleCount + 2}}`
- AI read "$25.00" twice, asserted "$50.00", NL says "total" → `{{price * quantity}}`

### Selector Modes

Different use cases want different selector strategies. Mode is set per-test or per-step:

```yaml
config:
  selectorMode: adaptive   # default for whole test

trail:
  - step: Tap the exact submit button
    selectorMode: strict    # override for this step
```

| Mode | Behavior | Use case |
|---|---|---|
| **strict** | Exact match on id or unique property. Fail if not found. | Regression — must hit this exact element |
| **flexible** | Text or content description. Tolerate minor changes. | Smoke testing — verify the flow works |
| **adaptive** | Fallback chain: id → contentDescription → text → position | General purpose (default) |

The mode controls how post-processing generates selectors from the same raw data. Re-run post-processing with a different mode to get different recordings without re-blazing.

### Validation Loop

After post-processing, validate the recording works by replaying it:

```
┌─→ Replay recording deterministically
│      │
│      ▼
│   Capture new run data (XY, hierarchies, screenshots)
│      │
│      ▼
│   Compare with blaze data:
│   - Did each selector resolve to the correct element?
│   - Same elements hit (compare bounds/properties)?
│   - Assertions produced same results?
│   - Memory slots captured expected values?
│      │
│      ▼
│   All matched? ──Yes──→ Trail is stable ✓
│      │
│      No
│      │
│      ▼
│   Refine using data from BOTH runs:
│   - Two sets of hierarchies to compare
│   - Identify what changed vs what's stable
│   - Pick selectors that work across both runs
│   - If can't stabilize after N iterations → recordable: false
│      │
└──────┘
```

**Exit criteria:**
- All steps passed on deterministic replay (not blaze)
- Every selector resolved to the correct element (validated by comparing bounds across runs)
- All memory slots populated correctly
- No XY fallbacks needed

**Convergence failure:** If a step can't stabilize after N iterations (default 3), mark it `recordable: false`. The AI handles it every time. This is an honest answer rather than a flaky test.

### Workflow Policies

The same infrastructure serves different workflows via different policies:

| Workflow | Pre-process | Post-process | Validate | On failure |
|---|---|---|---|---|
| **Test authoring** | Full slot analysis | Full optimization | Loop until stable | Flag unstable steps |
| **Dev loop** | Skip | One-shot, best effort | If fails, refine once with both sessions | Fall back to NL |
| **CI regression** | N/A (done) | N/A (done) | N/A (done) | Re-blaze from NL, alert |

#### Dev Loop Policy

The trail is a **cache**, not a commitment. One-shot post-processing, try the replay — if it works, saved an LLM call. If it fails, you now have two runs of data (the blaze and the failed replay), so refine selectors once using both sessions. If that still fails, fall back to NL and keep moving.

The trailhead trail is the most valuable to optimize — it's replayed dozens of times during debugging. Test steps may blaze every time since the code under test is changing.

#### Test Authoring Policy

Full pipeline — the recording will run thousands of times in CI. Pre-process for slots, full post-processing, validation loop until stable. Flag unstable steps. Measurable trail quality.

### Memory Tools

The AI uses recordable tools to read/write memory during blazing:

- **`memory.set(name, value)`** — store a captured value. Recorded as `storeAs`.
- **`memory.get(name)`** — retrieve a stored value. Recorded as `{{name}}`.

In the recording:

```yaml
- step: Note the current inventory count
  recording:
    - readText:
        selector: "#inventory-count"
        storeAs: inventoryCount

- step: Verify inventory increased by 2
  recording:
    - assertText:
        selector: "#inventory-count"
        equals: "{{inventoryCount + 2}}"
```

### Expression Support

Recordings support expressions in `{{}}` template syntax:

- Variable reference: `{{email}}`
- Arithmetic: `{{inventoryCount + 2}}`, `{{price * quantity}}`
- String interpolation: `"Hello {{firstName}}"`

Expression evaluation happens at replay time after memory slots are populated.

## Example: Before and After

### Raw recording (from blaze)

```yaml
trail:
  - step: Sign in with the test account
    recording:
      - inputText: "alice@example.com"
      - tap: "Next"
      - inputText: "password123"
      - tap: "Sign In"
  - step: Note the current inventory count
    recording:
      - readText: "5"
  - step: Add 2 items
    recording:
      - tap: "Add item"
      - tap: "Add item"
  - step: Verify inventory increased by 2
    recording:
      - assertVisible: "7"
```

### After post-processing

```yaml
config:
  memory:
    email: alice@example.com
    password: password123

trail:
  - step: Sign in with the test account
    recording:
      - inputText: "{{email}}"
      - tap:
          id: "next-button"
      - inputText: "{{password}}"
      - tap:
          id: "sign-in-button"
  - step: Note the current inventory count
    recording:
      - readText:
          selector:
            id: "inventory-count"
          storeAs: inventoryCount
  - step: Add 2 items
    recording:
      - tap:
          id: "add-item-button"
      - tap:
          id: "add-item-button"
  - step: Verify inventory increased by 2
    recording:
      - assertText:
          selector:
            id: "inventory-count"
          equals: "{{inventoryCount + 2}}"
```

Text selectors replaced with ids. Hardcoded credentials replaced with memory variables. Hardcoded inventory values replaced with captured slot + expression.

## What changed

**Positive:**
- Selectors computed from ground truth (XY + hierarchy) rather than runtime guesses
- Recordings are templatized — work with different data, accounts, environments
- Same raw capture supports different selector modes without re-blazing
- Validation loop proves repeatability instead of hoping for it
- Progressive enhancement — start with simple post-processing, add sophistication over time
- Dev loop benefits from trails as cache without requiring perfection
- Unstable steps honestly flagged as `recordable: false` rather than producing flaky tests

**Negative:**
- Post-processing adds time between blaze and usable recording
- Expression evaluation adds complexity to the replay engine
- Pre-processing requires an additional LLM call before blazing
- Selector ranking heuristics will need tuning based on real-world UI patterns
- Memory tools add to the AI's tool surface during blazing
