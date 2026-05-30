// PORT of `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNodeSelectorResolver.kt`.
//
// SOURCE OF TRUTH is the Kotlin file above. See `trailblaze-node.ts` header for the
// parity contract and editing rules.
//
// The Kotlin object is `TrailblazeNodeSelectorResolver`. We mirror it as a module-level
// `resolve` function (TypeScript prefers free functions over class-with-static-only-members).
// The private helpers (`matchesSelector`, `matchesDriverDetail`, six per-driver matchers,
// `requirePattern`, `matchesPattern`) are all file-local — only `resolve` is exported.
//
// **`SelectorTemplating.expand` is NOT ported here.** That helper expands `{{target.appId}}`
// placeholders before resolution and is only used when the caller threads target context
// in. The TS-side `ViewHierarchy` consumes already-expanded selectors (templating happens
// during selector authoring or trail loading, before the selector reaches the matcher).
// If a TS consumer ever needs runtime templating, port `SelectorTemplating` then; today
// it would be dead code.

import type {
  DriverNodeMatchAndroidAccessibility,
  DriverNodeMatchAndroidMaestro,
  DriverNodeMatchCompose,
  DriverNodeMatchIosAxe,
  DriverNodeMatchIosMaestro,
  DriverNodeMatchWeb,
  TrailblazeNodeSelector,
} from "../generated/selectors.js";
import {
  resolveText,
  type DriverNodeDetail,
  type DriverNodeDetailAndroidAccessibility,
  type DriverNodeDetailAndroidMaestro,
  type DriverNodeDetailCompose,
  type DriverNodeDetailIosAxe,
  type DriverNodeDetailIosMaestro,
  type DriverNodeDetailWeb,
} from "./driver-node-detail.js";
import { aggregate, type Bounds, type TrailblazeNode } from "./trailblaze-node.js";

/**
 * Guard against unbounded recursion when selectors nest spatial / hierarchy
 * sub-selectors. Mirrors `MAX_RESOLVE_DEPTH` in the Kotlin source.
 */
const MAX_RESOLVE_DEPTH = 10;

/** Result of an element-resolution attempt. Mirrors Kotlin's sealed `ResolveResult`. */
export type ResolveResult =
  | { readonly kind: "singleMatch"; readonly node: TrailblazeNode }
  | { readonly kind: "noMatch"; readonly selector: TrailblazeNodeSelector }
  | {
      readonly kind: "multipleMatches";
      readonly nodes: readonly TrailblazeNode[];
      readonly selector: TrailblazeNodeSelector;
    };

/**
 * Resolves a [TrailblazeNodeSelector] against the tree rooted at [root].
 *
 * Mirrors `TrailblazeNodeSelectorResolver.resolve(root, selector)` on the Kotlin side
 * (the 2-arg overload — the 3-arg overload's templating path is not ported; see file
 * header for rationale).
 */
export function resolve(
  root: TrailblazeNode,
  selector: TrailblazeNodeSelector,
): ResolveResult {
  return resolveInternal(root, selector, 0);
}

/**
 * Convenience: resolves and returns the center point of the (first) matched node,
 * or null if there's no match or the matched node has no bounds.
 *
 * Mirrors `resolveToCenter(root, selector)` on the Kotlin side.
 */
export function resolveToCenter(
  root: TrailblazeNode,
  selector: TrailblazeNodeSelector,
): { x: number; y: number } | null {
  const result = resolve(root, selector);
  switch (result.kind) {
    case "singleMatch":
      return centerOf(result.node);
    case "multipleMatches":
      return result.nodes.length > 0 ? centerOf(result.nodes[0]!) : null;
    case "noMatch":
      return null;
  }
}

function centerOf(node: TrailblazeNode): { x: number; y: number } | null {
  if (!node.bounds) return null;
  const b = node.bounds;
  return { x: Math.trunc((b.left + b.right) / 2), y: Math.trunc((b.top + b.bottom) / 2) };
}

function resolveInternal(
  root: TrailblazeNode,
  selector: TrailblazeNodeSelector,
  depth: number,
): ResolveResult {
  if (depth > MAX_RESOLVE_DEPTH) {
    return { kind: "noMatch", selector };
  }

  // Step 1: Determine search scope via childOf (exclude parent itself — descendants only).
  let searchScope: TrailblazeNode[];
  if (selector.childOf != null) {
    const parentResult = resolveInternal(root, selector.childOf, depth + 1);
    switch (parentResult.kind) {
      case "singleMatch":
        searchScope = aggregate(parentResult.node).slice(1);
        break;
      case "multipleMatches":
        searchScope = parentResult.nodes.flatMap((n) => aggregate(n).slice(1));
        break;
      case "noMatch":
        return { kind: "noMatch", selector };
    }
  } else {
    searchScope = aggregate(root);
  }

  // Step 2: Apply driver match + spatial + hierarchy predicates, then sort by position.
  const matched = searchScope.filter((node) =>
    matchesSelector(node, selector, root, depth),
  );
  matched.sort((a, b) => {
    // Top ascending, then left ascending. Missing bounds sort last (Int.MAX_VALUE
    // sentinel in Kotlin — mirror by using Number.MAX_SAFE_INTEGER here).
    const aTop = a.bounds?.top ?? Number.MAX_SAFE_INTEGER;
    const bTop = b.bounds?.top ?? Number.MAX_SAFE_INTEGER;
    if (aTop !== bTop) return aTop - bTop;
    const aLeft = a.bounds?.left ?? Number.MAX_SAFE_INTEGER;
    const bLeft = b.bounds?.left ?? Number.MAX_SAFE_INTEGER;
    return aLeft - bLeft;
  });

  // Step 3: Apply index if specified.
  let finalResults: TrailblazeNode[];
  if (selector.index != null) {
    const idx = selector.index;
    finalResults = idx >= 0 && idx < matched.length ? [matched[idx]!] : [];
  } else {
    finalResults = matched;
  }

  if (finalResults.length === 0) return { kind: "noMatch", selector };
  if (finalResults.length === 1) return { kind: "singleMatch", node: finalResults[0]! };
  return { kind: "multipleMatches", nodes: finalResults, selector };
}

/**
 * Returns true if [node] matches all predicates in [selector] (excluding childOf and index).
 * Mirrors Kotlin `matchesSelector(node, selector, root, depth)`.
 */
function matchesSelector(
  node: TrailblazeNode,
  selector: TrailblazeNodeSelector,
  root: TrailblazeNode,
  depth: number,
): boolean {
  if (depth > MAX_RESOLVE_DEPTH) return false;

  // Driver-specific property matching. The selector's discriminator is a non-null
  // field among the six driver keys (androidAccessibility, androidMaestro, web,
  // compose, iosMaestro, iosAxe); at most one is set in well-formed selectors.
  const driverMatchActive = activeDriverMatch(selector);
  if (driverMatchActive != null) {
    if (!matchesDriverDetail(node.driverDetail, driverMatchActive)) return false;
  }

  // Spatial relationships — each predicate compares the candidate node's bounds
  // against the FIRST matched bounds of the spatial sub-selector.
  if (selector.below != null) {
    const anchor = resolveFirstBounds(root, selector.below, depth);
    if (anchor == null) return false;
    if (node.bounds == null) return false;
    if (!(node.bounds.top >= anchor.bottom)) return false;
  }
  if (selector.above != null) {
    const anchor = resolveFirstBounds(root, selector.above, depth);
    if (anchor == null) return false;
    if (node.bounds == null) return false;
    if (!(node.bounds.bottom <= anchor.top)) return false;
  }
  if (selector.leftOf != null) {
    const anchor = resolveFirstBounds(root, selector.leftOf, depth);
    if (anchor == null) return false;
    if (node.bounds == null) return false;
    if (!(node.bounds.right <= anchor.left)) return false;
  }
  if (selector.rightOf != null) {
    const anchor = resolveFirstBounds(root, selector.rightOf, depth);
    if (anchor == null) return false;
    if (node.bounds == null) return false;
    if (!(node.bounds.left >= anchor.right)) return false;
  }

  // Hierarchy — containsChild matches DIRECT children, containsDescendants must
  // match ALL listed selectors anywhere in the subtree.
  if (selector.containsChild != null) {
    const child = selector.containsChild;
    const hasMatchingChild = (node.children ?? []).some((c) =>
      matchesSelector(c, child, root, depth + 1),
    );
    if (!hasMatchingChild) return false;
  }
  if (selector.containsDescendants != null) {
    const allDescendants = aggregate(node).slice(1); // exclude self
    const allMatch = selector.containsDescendants.every((descendantSelector) =>
      allDescendants.some((desc) =>
        matchesSelector(desc, descendantSelector, root, depth + 1),
      ),
    );
    if (!allMatch) return false;
  }

  return true;
}

/**
 * Discriminated driver-match extracted from a selector. Returns null when no
 * driver match is set. At most one is set in well-formed selectors; if multiple
 * are set, the priority order matches Kotlin's `driverMatch` getter (which uses
 * the elvis chain `androidAccessibility ?: androidMaestro ?: ...`).
 *
 * **Discrimination-style note.** `DriverNodeDetail` (in `driver-node-detail.ts`)
 * uses a `kind: "androidAccessibility" | "androidMaestro" | ...` literal-string
 * discriminator. `TrailblazeNodeSelector` (codegen'd from Kotlin in
 * `../generated/selectors.ts`) uses a property-name discriminator instead —
 * `{ androidAccessibility: {...} }` vs. `{ web: {...} }` — because the Kotlin
 * source for the selector is a data class with one nullable field per driver,
 * not a sealed `kind` field. The two styles coexist intentionally and live at
 * different layers (selector = predicate template; detail = captured node
 * data). This `ActiveDriverMatch` adapter bridges them, normalizing the
 * selector's property-name discrimination into the same `kind`-tag shape the
 * detail union uses so `matchesDriverDetail()` can pair them up with a single
 * `switch` statement.
 */
type ActiveDriverMatch =
  | { readonly kind: "androidAccessibility"; readonly match: DriverNodeMatchAndroidAccessibility }
  | { readonly kind: "androidMaestro"; readonly match: DriverNodeMatchAndroidMaestro }
  | { readonly kind: "web"; readonly match: DriverNodeMatchWeb }
  | { readonly kind: "compose"; readonly match: DriverNodeMatchCompose }
  | { readonly kind: "iosMaestro"; readonly match: DriverNodeMatchIosMaestro }
  | { readonly kind: "iosAxe"; readonly match: DriverNodeMatchIosAxe };

function activeDriverMatch(selector: TrailblazeNodeSelector): ActiveDriverMatch | null {
  if (selector.androidAccessibility != null) {
    return { kind: "androidAccessibility", match: selector.androidAccessibility };
  }
  if (selector.androidMaestro != null) {
    return { kind: "androidMaestro", match: selector.androidMaestro };
  }
  if (selector.web != null) return { kind: "web", match: selector.web };
  if (selector.compose != null) return { kind: "compose", match: selector.compose };
  if (selector.iosMaestro != null) {
    return { kind: "iosMaestro", match: selector.iosMaestro };
  }
  if (selector.iosAxe != null) return { kind: "iosAxe", match: selector.iosAxe };
  return null;
}

/** Resolves the first match's bounds. Mirrors Kotlin `resolveFirstBounds`. */
function resolveFirstBounds(
  root: TrailblazeNode,
  selector: TrailblazeNodeSelector,
  depth: number,
): Bounds | null {
  const result = resolveInternal(root, selector, depth + 1);
  switch (result.kind) {
    case "singleMatch":
      return result.node.bounds ?? null;
    case "multipleMatches":
      return result.nodes.length > 0 ? result.nodes[0]!.bounds ?? null : null;
    case "noMatch":
      return null;
  }
}

// ---------- Driver-specific matching ----------

/** Dispatches to the appropriate driver-specific matcher. Mirrors Kotlin `matchesDriverDetail`. */
function matchesDriverDetail(
  detail: DriverNodeDetail,
  match: ActiveDriverMatch,
): boolean {
  // `detail.class` is the wire-aligned discriminator (see `driver-node-detail.ts`
  // header). `match.kind` is the TS-internal `ActiveDriverMatch` tag — never
  // crosses the wire, so keeps the idiomatic `kind` name to disambiguate from
  // `detail.class` at every call site.
  switch (match.kind) {
    case "androidAccessibility":
      return (
        detail.class === "androidAccessibility" &&
        matchesAndroidAccessibility(detail, match.match)
      );
    case "androidMaestro":
      return (
        detail.class === "androidMaestro" && matchesAndroidMaestro(detail, match.match)
      );
    case "web":
      return detail.class === "web" && matchesWeb(detail, match.match);
    case "compose":
      return detail.class === "compose" && matchesCompose(detail, match.match);
    case "iosMaestro":
      return detail.class === "iosMaestro" && matchesIosMaestro(detail, match.match);
    case "iosAxe":
      return detail.class === "iosAxe" && matchesIosAxe(detail, match.match);
  }
}

function matchesAndroidAccessibility(
  detail: DriverNodeDetailAndroidAccessibility,
  match: DriverNodeMatchAndroidAccessibility,
): boolean {
  if (!requirePattern(match.classNameRegex, detail.className ?? null)) return false;
  if (!requirePattern(match.resourceIdRegex, detail.resourceId ?? null)) return false;
  if (!requireEqual(match.uniqueId, detail.uniqueId ?? null)) return false;
  if (!requirePattern(match.composeTestTagRegex, detail.composeTestTag ?? null)) {
    return false;
  }
  // textRegex matches resolveText() (text > hintText > contentDescription on Android).
  if (!requirePattern(match.textRegex, resolveText(detail))) return false;
  if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription ?? null)) {
    return false;
  }
  if (!requirePattern(match.hintTextRegex, detail.hintText ?? null)) return false;
  if (!requirePattern(match.labeledByTextRegex, detail.labeledByText ?? null)) {
    return false;
  }
  if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription ?? null)) {
    return false;
  }
  if (!requirePattern(match.paneTitleRegex, detail.paneTitle ?? null)) return false;
  if (!requirePattern(match.roleDescriptionRegex, detail.roleDescription ?? null)) {
    return false;
  }
  // Boolean fields — Kotlin defaults are `true` for isEnabled (matches the property
  // default), `false` for everything else. We coerce undefined → the same defaults
  // so a selector that demands `isEnabled: true` matches a node that just hasn't
  // serialized the field. The Kotlin field defaults govern; mirror them here.
  if (!requireEqual(match.isEnabled, detail.isEnabled ?? true)) return false;
  if (!requireEqual(match.isClickable, detail.isClickable ?? false)) return false;
  if (!requireEqual(match.isCheckable, detail.isCheckable ?? false)) return false;
  if (!requireEqual(match.isChecked, detail.isChecked ?? false)) return false;
  if (!requireEqual(match.isSelected, detail.isSelected ?? false)) return false;
  if (!requireEqual(match.isFocused, detail.isFocused ?? false)) return false;
  if (!requireEqual(match.isEditable, detail.isEditable ?? false)) return false;
  if (!requireEqual(match.isScrollable, detail.isScrollable ?? false)) return false;
  if (!requireEqual(match.isPassword, detail.isPassword ?? false)) return false;
  if (!requireEqual(match.isHeading, detail.isHeading ?? false)) return false;
  if (!requireEqual(match.isMultiLine, detail.isMultiLine ?? false)) return false;
  if (!requireEqual(match.inputType, detail.inputType ?? 0)) return false;
  if (match.collectionItemRowIndex != null) {
    if (detail.collectionItemInfo?.rowIndex !== match.collectionItemRowIndex) {
      return false;
    }
  }
  if (match.collectionItemColumnIndex != null) {
    if (detail.collectionItemInfo?.columnIndex !== match.collectionItemColumnIndex) {
      return false;
    }
  }
  return true;
}

function matchesAndroidMaestro(
  detail: DriverNodeDetailAndroidMaestro,
  match: DriverNodeMatchAndroidMaestro,
): boolean {
  if (!requirePattern(match.textRegex, resolveText(detail))) return false;
  if (!requirePattern(match.resourceIdRegex, detail.resourceId ?? null)) return false;
  if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText ?? null)) {
    return false;
  }
  if (!requirePattern(match.classNameRegex, detail.className ?? null)) return false;
  if (!requirePattern(match.hintTextRegex, detail.hintText ?? null)) return false;
  if (!requireEqual(match.clickable, detail.clickable ?? false)) return false;
  if (!requireEqual(match.enabled, detail.enabled ?? true)) return false;
  if (!requireEqual(match.focused, detail.focused ?? false)) return false;
  if (!requireEqual(match.checked, detail.checked ?? false)) return false;
  if (!requireEqual(match.selected, detail.selected ?? false)) return false;
  return true;
}

function matchesWeb(detail: DriverNodeDetailWeb, match: DriverNodeMatchWeb): boolean {
  if (!requireEqual(match.ariaRole, detail.ariaRole ?? null)) return false;
  if (!requirePattern(match.ariaNameRegex, detail.ariaName ?? null)) return false;
  if (!requirePattern(match.ariaDescriptorRegex, detail.ariaDescriptor ?? null)) {
    return false;
  }
  if (!requireEqual(match.headingLevel, detail.headingLevel ?? null)) return false;
  if (!requireEqual(match.cssSelector, detail.cssSelector ?? null)) return false;
  if (!requireEqual(match.dataTestId, detail.dataTestId ?? null)) return false;
  if (!requireEqual(match.nthIndex, detail.nthIndex ?? 0)) return false;
  return true;
}

function matchesCompose(
  detail: DriverNodeDetailCompose,
  match: DriverNodeMatchCompose,
): boolean {
  if (!requireEqual(match.testTag, detail.testTag ?? null)) return false;
  if (!requireEqual(match.role, detail.role ?? null)) return false;
  if (!requirePattern(match.textRegex, resolveText(detail))) return false;
  if (!requirePattern(match.editableTextRegex, detail.editableText ?? null)) return false;
  if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription ?? null)) {
    return false;
  }
  if (!requireEqual(match.toggleableState, detail.toggleableState ?? null)) return false;
  if (!requireEqual(match.isEnabled, detail.isEnabled ?? true)) return false;
  if (!requireEqual(match.isFocused, detail.isFocused ?? false)) return false;
  if (!requireEqual(match.isSelected, detail.isSelected ?? false)) return false;
  if (!requireEqual(match.isPassword, detail.isPassword ?? false)) return false;
  return true;
}

function matchesIosMaestro(
  detail: DriverNodeDetailIosMaestro,
  match: DriverNodeMatchIosMaestro,
): boolean {
  if (!requirePattern(match.textRegex, resolveText(detail))) return false;
  if (!requirePattern(match.resourceIdRegex, detail.resourceId ?? null)) return false;
  if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText ?? null)) {
    return false;
  }
  if (!requirePattern(match.classNameRegex, detail.className ?? null)) return false;
  if (!requirePattern(match.hintTextRegex, detail.hintText ?? null)) return false;
  if (!requireEqual(match.focused, detail.focused ?? false)) return false;
  if (!requireEqual(match.selected, detail.selected ?? false)) return false;
  return true;
}

function matchesIosAxe(
  detail: DriverNodeDetailIosAxe,
  match: DriverNodeMatchIosAxe,
): boolean {
  if (!requirePattern(match.roleRegex, detail.role ?? null)) return false;
  if (!requirePattern(match.subroleRegex, detail.subrole ?? null)) return false;
  if (!requirePattern(match.labelRegex, detail.label ?? null)) return false;
  if (!requirePattern(match.valueRegex, detail.value ?? null)) return false;
  if (!requireEqual(match.uniqueId, detail.uniqueId ?? null)) return false;
  if (!requirePattern(match.typeRegex, detail.type ?? null)) return false;
  if (!requirePattern(match.titleRegex, detail.title ?? null)) return false;
  if (match.customAction != null) {
    if (!(detail.customActions ?? []).includes(match.customAction)) return false;
  }
  if (!requireEqual(match.enabled, detail.enabled ?? true)) return false;
  return true;
}

// ---------- Match helpers ----------

/** Returns true if [expected] is null/undefined (no constraint) or equals [actual]. */
function requireEqual<T>(
  expected: T | null | undefined,
  actual: T | null | undefined,
): boolean {
  if (expected == null) return true;
  return expected === actual;
}

/**
 * Returns true if [pattern] is null (no constraint) or [text] matches it.
 * When pattern is set but text is null, the match fails (element lacks the property).
 */
function requirePattern(
  pattern: string | null | undefined,
  text: string | null | undefined,
): boolean {
  if (pattern == null) return true;
  if (text == null) return false;
  return matchesPattern(pattern, text);
}

/**
 * Matches a regex pattern against the full text. Falls back to exact case-insensitive
 * comparison if the pattern isn't valid regex (e.g. "$3.00" where $ has anchor meaning
 * but the trailing `.00` makes the whole pattern syntactically valid as regex, except
 * `$` in middle position has the literal-then-zero-width-end interpretation that
 * mismatches the user's intent).
 *
 * Uses **full-string matching** (not substring) — wraps the user pattern in `^(?:...)$`
 * to mirror Kotlin's `Regex(p).matches(t)`. This prevents `pattern = "ok"` from matching
 * `text = "book"` via substring search.
 *
 * **Inline-flag translation.** Kotlin uses `java.util.regex`, which supports inline
 * flags like `(?i)foo` (case-insensitive) and `(?s)` (dotall). ECMAScript treats
 * `(?i)` as a syntax error. Selectors in the trail corpus DO use `(?i)` for
 * case-insensitive matching — the matcher strips a leading inline-flag group and
 * forwards the equivalent JS `RegExp` flags before compile. Today: `(?i)` → `i`
 * (case-insensitive), `(?s)` → `s` (dotall). Combined forms like `(?is)` and `(?si)`
 * work too. Inline flags that aren't at the start of the pattern are NOT translated
 * — Java allows them anywhere, but the trail-corpus selectors only use them as a
 * leading prefix. If a non-leading inline flag shows up in production, this code
 * leaves it un-translated; the wrapper compile will throw, and `matchesPattern` falls
 * back to the case-insensitive literal-equality path. That's safe (the selector
 * still resolves SOMEHOW) but worth catching at the parity-test layer if it
 * happens.
 *
 * **Regex semantics caveats** that the inline-flag path does NOT cover:
 *   - Possessive quantifiers (`x++`, `x*+`) — Kotlin supports, JS doesn't.
 *   - Unicode property escapes (`\p{...}`) — JS requires the `u` flag, not in
 *     scope for this translator.
 *   - Some named-group syntaxes differ.
 *
 * The trail-corpus selectors are simple (alternation + escapes); divergence on
 * these edge cases is what the parity-test fixtures exist to catch.
 */
function matchesPattern(pattern: string, text: string): boolean {
  const { pattern: stripped, flags } = stripLeadingInlineFlags(pattern);
  let regex: RegExp | null;
  try {
    regex = new RegExp(`^(?:${stripped})$`, flags);
  } catch (_e) {
    regex = null;
  }
  if (regex != null) {
    return regex.test(text);
  }
  // Fallback: case-insensitive literal equality. Use the ORIGINAL pattern here
  // (not the inline-flag-stripped one) so a malformed `(?i)foo` that fails to
  // compile still falls back to literal comparison against the raw text.
  return text.toLowerCase() === pattern.toLowerCase();
}

/**
 * Strips a leading Java-style inline-flag group like `(?i)`, `(?s)`, or
 * combined `(?is)` from a regex pattern and returns the equivalent JS
 * `RegExp` flag string. Examples:
 *
 *   `(?i)foo`      → { pattern: "foo",   flags: "i" }
 *   `(?s).*bar`    → { pattern: ".*bar", flags: "s" }
 *   `(?is)hello`   → { pattern: "hello", flags: "is" }
 *   `(?m)^line$`   → { pattern: "^line$", flags: "m" }  // m: multiline
 *   `(?x)x y z`    → { pattern: "x y z", flags: "" }   // x: extended; no JS equivalent, drop silently
 *   `foo`          → { pattern: "foo",   flags: "" }
 *
 * Java flag letters supported: `i` (case-insensitive), `s` (dotall), `m`
 * (multiline). `x` (extended/comment) is silently dropped because JS has no
 * equivalent — a pattern relying on whitespace-insensitivity will likely
 * fail to compile after the leading flag is stripped, falling through to
 * the literal-equality path (which is safe but not ideal). Real trails
 * don't use `(?x)`.
 */
function stripLeadingInlineFlags(pattern: string): { pattern: string; flags: string } {
  // Match a literal `(?` then 0+ flag letters, then `)`. Only at start.
  const match = /^\(\?([a-z]+)\)/.exec(pattern);
  if (match == null) return { pattern, flags: "" };
  const javaFlags = match[1]!;
  const jsFlagChars: string[] = [];
  for (const ch of javaFlags) {
    if (ch === "i" || ch === "s" || ch === "m") {
      if (!jsFlagChars.includes(ch)) jsFlagChars.push(ch);
    }
    // `x`, `u`, `d`, `U` and any other Java flags: silently dropped — see kdoc.
  }
  return { pattern: pattern.slice(match[0].length), flags: jsFlagChars.join("") };
}
