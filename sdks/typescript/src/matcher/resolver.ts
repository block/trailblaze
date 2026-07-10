// PORT of `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNodeSelectorResolver.kt`.
//
// SOURCE OF TRUTH is the Kotlin file above. See `trailblaze-node.ts` header for the
// parity contract and editing rules.
//
// The Kotlin object is `TrailblazeNodeSelectorResolver`. We mirror it as a module-level
// `resolve` function (TypeScript prefers free functions over class-with-static-only-members).
// The private helpers (`matchesSelector`, `matchesDriverDetail`, six per-driver matchers,
// `requirePattern`, `matchesPattern`, and the regex-translation helpers) are all
// file-local — only `resolve` is exported.
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
  // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
  const dialect: MatchDialect = "maestro";
  if (!requirePattern(match.textRegex, resolveText(detail), dialect)) return false;
  if (!requirePattern(match.resourceIdRegex, detail.resourceId ?? null, dialect)) return false;
  if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText ?? null, dialect)) {
    return false;
  }
  if (!requirePattern(match.classNameRegex, detail.className ?? null, dialect)) return false;
  if (!requirePattern(match.hintTextRegex, detail.hintText ?? null, dialect)) return false;
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
  // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
  const dialect: MatchDialect = "maestro";
  if (!requirePattern(match.textRegex, resolveText(detail), dialect)) return false;
  if (!requirePattern(match.resourceIdRegex, detail.resourceId ?? null, dialect)) return false;
  if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText ?? null, dialect)) {
    return false;
  }
  if (!requirePattern(match.classNameRegex, detail.className ?? null, dialect)) return false;
  if (!requirePattern(match.hintTextRegex, detail.hintText ?? null, dialect)) return false;
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
 * The matching semantics a selector shape carries. A selector means what it meant under the
 * driver dialect it was authored for, everywhere it is evaluated — Maestro-shape branches
 * (`androidMaestro`, `iosMaestro`) keep the lenient semantics Maestro's Orchestra compiled
 * them with; native shapes stay strict.
 *
 * - `"native"`: no implicit regex flags; case-sensitive; `.` does not cross newlines.
 * - `"maestro"`: `ism` flags (Orchestra's `IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE`), and an
 *   invalid pattern degrades to an escaped literal with the same flags (Maestro's `toRegexSafe`)
 *   — i.e. a case-insensitive literal.
 */
type MatchDialect = "native" | "maestro";

/** Orchestra's `REGEX_OPTIONS` (IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE) as JS flags. */
const MAESTRO_FLAGS = "ism";

/**
 * The Java inline-flag letters `stripLeadingInlineFlags` translates to JS flags.
 * Coincidentally the same string as `MAESTRO_FLAGS`, but a different concept — this is
 * "which Java inline flags have a JS equivalent", not "which flags the Maestro dialect
 * implies". Update independently.
 */
const SUPPORTED_JS_FLAGS = "ism";

/**
 * Returns true if [pattern] is null (no constraint) or [text] matches it.
 * When pattern is set but text is null, the match fails (element lacks the property).
 */
function requirePattern(
  pattern: string | null | undefined,
  text: string | null | undefined,
  dialect: MatchDialect = "native",
): boolean {
  if (pattern == null) return true;
  if (text == null) return false;
  return matchesPattern(pattern, text, dialect);
}

/**
 * Matches a regex pattern against the full text, then falls back to case-sensitive
 * literal equality when the pattern doesn't match as a regex. The fallback covers both
 * an unmatchable-but-valid pattern (e.g. "$3.00", where a bare `$` is an end-of-input
 * anchor so nothing can follow it — it compiles fine but can never regex-match) and a
 * pattern that fails to compile at all. Mirrors Kotlin's `matchesPattern` / Maestro's
 * `regex.matches(v) || regex.pattern == v`.
 *
 * Uses **full-string matching** (not substring) — wraps the user pattern via
 * `fullMatchWrap` to mirror Kotlin's `Regex(p).matches(t)`. This prevents
 * `pattern = "ok"` from matching `text = "book"` via substring search, and stays
 * absolute even under the `m` flag (see `fullMatchWrap`).
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
 * back to the literal-equality path. That's safe (the selector still resolves SOMEHOW)
 * but worth catching at the parity-test layer if it happens.
 *
 * **Quote-section translation.** Java's `\Q...\E` (what Kotlin `Regex.escape()` emits,
 * pervasive in the trail corpus for currency like `\Q$3.00\E`) has no JS equivalent —
 * see `translateQuoteSections`, which rewrites quoted sections to JS-escaped literals
 * before compile.
 *
 * **Regex semantics caveats** that the inline-flag path does NOT cover:
 *   - Possessive quantifiers (`x++`, `x*+`) — Kotlin supports, JS doesn't.
 *   - Unicode property escapes (`\p{...}`) — JS requires the `u` flag, not in
 *     scope for this translator.
 *   - Some named-group syntaxes differ.
 *
 * The trail-corpus selectors are simple (alternation + escapes); divergence on
 * these edge cases is what the parity-test fixtures exist to catch.
 *
 * **Dialects.** `"maestro"` compiles with the `ism` base flags and degrades an invalid
 * pattern to an escaped literal with the same flags (`toRegexSafe`), so a Maestro-shape
 * selector matches here exactly as it did under Maestro. Case-sensitivity escape hatch
 * inside a Maestro-shape pattern: a leading `(?-i)`. `"native"` is strict; opt into
 * case-insensitivity with a leading `(?i)`.
 *
 * The behavioral contract is locked by the shared cross-language fixture
 * `matcher-parity-fixtures.json` (consumed by `matcher-parity.test.ts` here and by
 * `MatcherParityFixturesTest` on the Kotlin side). Semantics changes must update the
 * fixture and both implementations together.
 */
function matchesPattern(
  pattern: string,
  text: string,
  dialect: MatchDialect = "native",
): boolean {
  const baseFlags = dialect === "maestro" ? MAESTRO_FLAGS : "";
  const { pattern: stripped, added, removed } = stripLeadingInlineFlags(pattern);
  const flags = combineFlags(baseFlags, added, removed);
  let regex: RegExp | null;
  try {
    const translated = translateQuoteSections(stripped);
    // Probe-compile the user pattern ALONE before wrapping. An invalid pattern can fuse
    // with the wrapper into a valid-but-garbage regex (e.g. `[unclosed` + the wrapper's
    // trailing `(?![\s\S])` — the wrapper's `]` closes the dangling character class), so
    // validity must be judged on the bare pattern.
    new RegExp(translated, flags);
    regex = new RegExp(fullMatchWrap(translated), flags);
  } catch (_e) {
    regex =
      dialect === "maestro"
        ? // Maestro's StringUtils.toRegexSafe: invalid regex → escaped literal, same flags
          // (so a case-insensitive literal). Escape the ORIGINAL pattern, like Kotlin.
          new RegExp(fullMatchWrap(escapeForRegExp(pattern)), baseFlags)
        : null;
  }
  if (regex != null && regex.test(text)) {
    return true;
  }
  // Fallback: case-sensitive literal equality against the ORIGINAL pattern (not the
  // inline-flag-stripped one), so a valid-but-unmatchable regex ("$3.00") and a
  // malformed pattern that fails to compile ("(?i)foo" mid-string) both resolve
  // against the raw text. Case-sensitive to mirror Maestro / the Kotlin resolver.
  return text === pattern;
}

/** Base flags + leading inline additions − leading inline removals, deduped. */
function combineFlags(base: string, added: string, removed: string): string {
  const set = new Set([...base, ...added]);
  for (const ch of removed) set.delete(ch);
  return [...set].join("");
}

/**
 * Wraps a pattern so it must match the ENTIRE input, mirroring Kotlin's
 * `Regex(p).matches(t)`. Uses lookarounds on `[\s\S]` (any char incl. newline) instead of
 * `^...$` because the wrapper must stay ABSOLUTE under the `m` flag — with `m` (the Maestro
 * dialect's default, or a leading `(?m)`), `^`/`$` become per-line, which would let
 * `pattern "ok"` match the second line of `"book\nok"` while Kotlin's full-input `matches()`
 * rejects it. Lookarounds are flag-immune: `(?<![\s\S])` holds only at input start,
 * `(?![\s\S])` only at input end. Inner `^`/`$` written by the user still get their per-line
 * meaning from `m`.
 */
function fullMatchWrap(pattern: string): string {
  return `(?<![\\s\\S])(?:${pattern})(?![\\s\\S])`;
}

/**
 * Escapes a string so it compiles as a literal inside a JS RegExp — the JS-side counterpart
 * of Kotlin `Regex.escape` (which quotes via `\Q...\E` rather than char-escaping).
 */
function escapeForRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&");
}

/**
 * Translates Java-style `\Q...\E` quoted sections into JS-escaped literals.
 *
 * `java.util.regex` treats everything between `\Q` and `\E` as a literal — it's what
 * Kotlin's `Regex.escape()` emits, so the trail corpus is full of selectors like
 * `\Q$3.00\E`. ECMAScript has no quote construct: in JS, `\Q` is an identity escape
 * (a literal `Q`), so `\Q$3.00\E` silently compiles into a pattern that can never
 * match (`Q` + end-anchor `$` + trailing chars). Without translation, every
 * `Regex.escape()`-authored selector diverges from the Kotlin resolver.
 *
 * Mirrors Java semantics: an unterminated `\Q` quotes to the end of the pattern,
 * and a `\Q` preceded by an escaping backslash (e.g. `\\Q`) is NOT a quote start.
 */
function translateQuoteSections(pattern: string): string {
  if (!pattern.includes("\\Q")) return pattern;
  let out = "";
  let i = 0;
  while (i < pattern.length) {
    const ch = pattern[i]!;
    if (ch === "\\" && i + 1 < pattern.length) {
      const next = pattern[i + 1]!;
      if (next === "Q") {
        // Quote section: literal until `\E` or end of pattern.
        const end = pattern.indexOf("\\E", i + 2);
        const literal = end === -1 ? pattern.slice(i + 2) : pattern.slice(i + 2, end);
        out += literal.replace(/[.*+?^${}()|[\]\\/]/g, "\\$&");
        i = end === -1 ? pattern.length : end + 2;
      } else {
        // Any other escape (including `\\`): copy both chars so the escaped
        // char can't be misread as a quote start.
        out += ch + next;
        i += 2;
      }
    } else {
      out += ch;
      i += 1;
    }
  }
  return out;
}

/**
 * Strips a leading Java-style inline-flag group — `(?i)`, `(?is)`, `(?-i)`, `(?s-i)` — from
 * a regex pattern and returns which JS flags it adds and which it removes. The caller
 * combines these with the dialect's base flags (`combineFlags`), which is how a Maestro-shape
 * pattern opts back OUT of the dialect's implicit case-insensitivity with a leading `(?-i)`
 * (Java honors inline toggles natively, so the Kotlin side needs no translation). Examples:
 *
 *   `(?i)foo`      → { pattern: "foo",    added: "i",  removed: ""  }
 *   `(?is)hello`   → { pattern: "hello",  added: "is", removed: ""  }
 *   `(?-i)Pizza`   → { pattern: "Pizza",  added: "",   removed: "i" }
 *   `(?s-i).*`     → { pattern: ".*",     added: "s",  removed: "i" }
 *   `foo`          → { pattern: "foo",    added: "",   removed: ""  }
 *
 * Java flag letters supported: `i` (case-insensitive), `s` (dotall), `m` (multiline).
 * `x` (extended/comment) and other Java flags are silently dropped — JS has no
 * equivalent, and real trails don't use them. Only a LEADING group is translated;
 * mid-pattern toggles fail the wrapper compile and fall through to the literal path.
 */
function stripLeadingInlineFlags(pattern: string): {
  pattern: string;
  added: string;
  removed: string;
} {
  // `(?` + 0+ on-flags + optional `-` + off-flags + `)`, at start only. The `[a-z]` classes
  // can't match `(?:`/`(?=`/`(?!` group syntax; require at least one flag char overall.
  const match = /^\(\?([a-z]*)(?:-([a-z]+))?\)/.exec(pattern);
  if (match == null || (match[1] === "" && match[2] == null)) {
    return { pattern, added: "", removed: "" };
  }
  const keep = (chars: string) => [...new Set([...chars])].filter((c) => SUPPORTED_JS_FLAGS.includes(c)).join("");
  return {
    pattern: pattern.slice(match[0].length),
    added: keep(match[1] ?? ""),
    removed: keep(match[2] ?? ""),
  };
}
