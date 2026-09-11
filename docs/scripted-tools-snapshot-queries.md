# Snapshot queries from scripted tools

`findSelectorMatches` is the typed-authoring primitive for asking _"is this element visible,"_ _"is the selector unambiguous,"_ and _"where is the match on screen?"_ without
mutating the device. It complements the action tools (`tapOnPoint`, `inputText`,
`swipe`, …) — together they let a scripted tool branch on the live screen state.

It takes a LIST of selectors and answers all of them from ONE view-hierarchy capture,
returning one match list per selector, index-aligned to the input.

> `findMatches` — the single-selector tool this replaces — is **deprecated** and scheduled
> for deletion once its callers move. A single-element `selectors` list behaves identically
> to it, so migrating is mechanical. Don't add new callers.

## What it returns

```ts
import { trailblaze, type EmptyInput } from "@trailblaze/scripting";

export interface SubmitIfVisibleResult {
  tapped: boolean;
}

export const submit_if_visible = trailblaze.tool<EmptyInput, SubmitIfVisibleResult>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    const [matches] = await ctx.tools.findSelectorMatches({
      selectors: [{ androidAccessibility: { textRegex: "Submit" } }],
    });

    // matches.length === 0  -> not visible
    // matches.length === 1  -> unique match, safe to act on
    // matches.length > 1    -> ambiguous, narrow the selector

    // Note the `&& matches[0].bounds` guard — `bounds` can legitimately be
    // absent on some drivers (Playwright pre-DOM-enrichment, Compose pre-
    // layout). Tapping a fabricated origin point would hit the top-left
    // corner of the screen, so always null-check before reading coordinates.
    if (matches.length === 1 && matches[0].bounds) {
      const { centerX, centerY } = boundsCenter(matches[0].bounds);
      await ctx.tools.tapOnPoint({ x: centerX, y: centerY });
      return { tapped: true };
    }
    return { tapped: false };
  },
);

function boundsCenter(b: { left: number; top: number; right: number; bottom: number }) {
  return {
    centerX: Math.floor((b.left + b.right) / 2),
    centerY: Math.floor((b.top + b.bottom) / 2),
  };
}
```

An empty match list is a MEASURED absence — the selector's "not on screen" answer, and
the caller's absent branch. Every error path means the question could not be asked at
all (nothing captured, or an answer that came out of a capture the device could not
complete and so cannot be trusted as absence). A selector that simply matches nothing
never throws.

Each `MatchDescriptor` carries enough info to act on without re-querying:

| Field             | Meaning                                                                |
| ----------------- | ---------------------------------------------------------------------- |
| `indexPath`       | `[]` for root, `[0, 2, 1, 4]` walks child indices to the match.        |
| `bounds`          | `{ left, top, right, bottom }` in device pixels.                       |
| `matchedText`     | Best-available text — Android `resolveText()`, web `ariaName`, …       |
| `accessibilityId` | Content description / accessibility label / aria descriptor.           |
| `resourceId`      | `resourceId` on Android, `accessibilityIdentifier` on iOS, `testTag`. |

### Lifetime

Treat `MatchDescriptor`s as **immediate hand-offs**, not durable references.
`indexPath` is positional: it tracks "the Nth child of the Mth child …" against
the *exact* tree the descriptor was captured from. Any change to the tree
shape between capture and use — siblings added or removed, a RecyclerView
item recycled, a parent re-mounting after a state change — silently
invalidates the path. The same logical element now lives at a different
sequence of indices.

The safe pattern is: query, act on the result in the same tool body, and
re-query after any action that could change the screen.

```ts
// Good — descriptor consumed immediately.
const [matches] = await ctx.tools.findSelectorMatches({ selectors: [submit] });
if (matches.length === 1 && matches[0].bounds) {
  const { centerX, centerY } = boundsCenter(matches[0].bounds);
  await ctx.tools.tapOnPoint({ x: centerX, y: centerY });
}

// Avoid — storing descriptors across actions.
const [earlier] = await ctx.tools.findSelectorMatches({ selectors: [submit] });
await ctx.tools.tapOnPoint({ x: 100, y: 200 });   // mutates the tree
// `earlier[0].indexPath` no longer points where you think it does.
```

For longer-lived identity, prefer `accessibilityId` or `resourceId` when the
driver populates them — those survive across captures.

## Selector shape

Selectors are platform-explicit by design — each driver gets its own match field,
the resolver dispatches on whichever variant is non-null:

```ts
// Android accessibility tree:
{ androidAccessibility: { textRegex: "Submit", isClickable: true } }

// Web via Playwright:
{ web: { ariaRole: "button", ariaNameRegex: "Submit" } }

// iOS via AXe:
{ iosAxe: { roleRegex: "AXButton", labelRegex: "Submit" } }
```

The full grammar — spatial relationships (`above` / `below` / `leftOf` /
`rightOf`), hierarchy (`childOf`, `containsChild`, `containsDescendants`), and
`index`-based disambiguation — mirrors the same shape that recorded YAML uses.
See `TrailblazeNodeSelector.kt` for the canonical definition.

The SDK also ships a `selectors` factory namespace — pure sugar over the literal
shape, but it scopes IDE autocomplete to one driver at a time:

```ts
import { selectors } from "@trailblaze/scripting";

// Equivalent to { androidAccessibility: { textRegex: "Submit" } }
const submit = selectors.androidAccessibility({ textRegex: "Submit" });
```

Both forms are interchangeable; the literal form stays copy-paste compatible with
the YAML serialization. The factory (and the selector types) are code-generated
from the Kotlin source of truth via `:trailblaze-models:generateSelectorsTs`.

## One capture, N selectors

**Batch every selector you want to ask about into ONE call.** A view-hierarchy capture
takes seconds, and the tool pays exactly one for the whole list:

```ts
// ONE capture answers both.
const [submitMatches, cancelMatches] = await ctx.tools.findSelectorMatches({
  selectors: [submit, cancel],
});
```

Splitting that into two calls pays TWO captures. The daemon's snapshot cache does not
help here: each `ctx.tools.*` callback enters its own nested cache frame, so back-to-back
queries from one scripted tool body each capture their own tree.

Batching is also a correctness win, not just a speed one. Two sequential probes can
report both conditions true, or neither, depending on which order they ran and how the
screen moved in between; one capture removes that. Ties inside a single frame are yours
to break — check your preferred selector first.

An action tool dispatched in between invalidates the cached tree, so a query after it
reads the post-action screen:

```ts
const [before] = await ctx.tools.findSelectorMatches({ selectors: [submit] });
await ctx.tools.tapOnPoint({ x: 100, y: 200 });   // invalidates
const [after] = await ctx.tools.findSelectorMatches({ selectors: [submit] });
```

Verification tools (`assertVisibleBySelector`, `assertVisibleWithText`, …) are
read-only and don't invalidate.

## Waiting for a screen

Pass `timeoutMs` and the call becomes an event-driven wait: it re-polls the live
hierarchy until a match appears or the budget elapses, so it returns the moment the
screen renders rather than after a fixed sleep.

**With several selectors it is a race primitive** — the wait ends as soon as ANY
selector matches, and every other selector is answered from that same frame. That is
how you wait for "either the wizard or the home screen" in one round trip per poll
instead of two:

```ts
const [wizard, home] = await ctx.tools.findSelectorMatches({
  selectors: [wizardAnchor, homeAnchor],
  timeoutMs: 30_000,
});
if (wizard.length > 0) { /* handle the wizard */ }
```

## When NOT to use `findSelectorMatches`

- **For mutation** — it never taps, scrolls, or types. Pair it with
  `tapOnPoint` / `swipe` / `inputText` when you need to act on a result.
- **As an LLM-callable tool** — it is hidden from the LLM agent
  (`surfaceToLlm = false`). The LLM's verification surface is
  `assertVisibleBySelector` and friends; this is for scripted authors
  who want explicit visibility branching.
