# Snapshot queries from scripted tools

`findMatches` is the typed-authoring primitive for asking _"is this element visible,"_ _"is the selector unambiguous,"_ and _"where is the match on screen?"_ without
mutating the device. It complements the action tools (`tapOnPoint`, `inputText`,
`swipe`, …) — together they let a scripted tool branch on the live screen state.

## What it returns

```ts
import { trailblaze, type EmptyInput } from "@trailblaze/scripting";

export interface SubmitIfVisibleResult {
  tapped: boolean;
}

export const submit_if_visible = trailblaze.tool<EmptyInput, SubmitIfVisibleResult>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    const matches = await ctx.tools.findMatches({
      selector: { androidAccessibility: { textRegex: "Submit" } },
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
const matches = await ctx.tools.findMatches({ ... });
if (matches.length === 1 && matches[0].bounds) {
  const { centerX, centerY } = boundsCenter(matches[0].bounds);
  await ctx.tools.tapOnPoint({ x: centerX, y: centerY });
}

// Avoid — storing descriptors across actions.
const earlier = await ctx.tools.findMatches({ ... });
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

> A factory namespace (e.g. `selectors.androidAccessibility({...})`) is on the
> roadmap — the literal `{ androidAccessibility: {...} }` shape is the
> hand-authored escape hatch until the selector grammar codegen lands.

## Snapshot reuse

Calling `findMatches` multiple times within one tool invocation pays the
multi-second view-hierarchy fetch _at most once_:

```ts
// Both queries reuse the same captured tree.
const submitMatches = await ctx.tools.findMatches({ … });
const cancelMatches = await ctx.tools.findMatches({ … });
```

When an action tool dispatches in the same batch, the cache is invalidated
automatically so a follow-up query reads the post-action tree:

```ts
const before = await ctx.tools.findMatches({ … });   // captures
await ctx.tools.tapOnPoint({ x: 100, y: 200 });      // invalidates
const after = await ctx.tools.findMatches({ … });    // re-captures
```

Verification tools (`assertVisibleBySelector`, `assertVisibleWithText`, …) are
read-only and don't invalidate — querying around a verification reuses the same
tree.

## When NOT to use `findMatches`

- **For mutation** — `findMatches` never taps, scrolls, or types. Pair it with
  `tapOnPoint` / `swipe` / `inputText` when you need to act on a result.
- **As an LLM-callable tool** — `findMatches` is hidden from the LLM agent
  (`surfaceToLlm = false`). The LLM's verification surface is
  `assertVisibleBySelector` and friends; `findMatches` is for scripted authors
  who want explicit visibility branching.
- **For waiting on conditions** — the snapshot is captured at call time. To
  wait for an element to appear, loop with a short delay and re-query, or use
  a higher-level wait primitive when one is available.
