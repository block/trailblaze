// AUTO-GENERATED — do not edit by hand.
//
// Source-of-truth Kotlin files:
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNodeSelector.kt
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/MatchDescriptor.kt
//   opensource/trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/TrailblazeNode.kt (nested Bounds)
//
// Regenerate with:
//   ./gradlew :trailblaze-models:generateSelectorsTs
//
// CI's `verifySelectorsTs` task byte-diffs this file against a fresh generation and
// fails the build on drift, so edits made by hand will be reverted on the next CI run.

/**
 * Matches against [DriverNodeDetail.AndroidAccessibility] nodes.
 *
 * Only properties from [DriverNodeDetail.AndroidAccessibility.MATCHABLE_PROPERTIES]
 * should be set here. All fields are optional — only non-null fields act as predicates.
 * String (`*Regex`) fields are regex-OR-exact-literal — see [DriverNodeMatch]; values like
 * `$5.00` need no escaping.
 */
export interface DriverNodeMatchAndroidAccessibility {
  classNameRegex?: string | null;
  resourceIdRegex?: string | null;
  uniqueId?: string | null;
  composeTestTagRegex?: string | null;
  textRegex?: string | null;
  contentDescriptionRegex?: string | null;
  hintTextRegex?: string | null;
  labeledByTextRegex?: string | null;
  stateDescriptionRegex?: string | null;
  paneTitleRegex?: string | null;
  roleDescriptionRegex?: string | null;
  isEnabled?: boolean | null;
  isClickable?: boolean | null;
  isCheckable?: boolean | null;
  isChecked?: boolean | null;
  isSelected?: boolean | null;
  isFocused?: boolean | null;
  isEditable?: boolean | null;
  isScrollable?: boolean | null;
  isPassword?: boolean | null;
  isHeading?: boolean | null;
  isMultiLine?: boolean | null;
  inputType?: number | null;
  collectionItemRowIndex?: number | null;
  collectionItemColumnIndex?: number | null;
}

/**
 * Matches against [DriverNodeDetail.AndroidMaestro] nodes.
 *
 * This mirrors [TrailblazeElementSelector]'s matching capabilities but operates
 * on [TrailblazeNode] trees rather than [ViewHierarchyTreeNode].
 */
export interface DriverNodeMatchAndroidMaestro {
  textRegex?: string | null;
  resourceIdRegex?: string | null;
  accessibilityTextRegex?: string | null;
  classNameRegex?: string | null;
  hintTextRegex?: string | null;
  clickable?: boolean | null;
  enabled?: boolean | null;
  focused?: boolean | null;
  checked?: boolean | null;
  selected?: boolean | null;
}

/**
 * Matches against [DriverNodeDetail.Web] nodes.
 */
export interface DriverNodeMatchWeb {
  ariaRole?: string | null;
  ariaNameRegex?: string | null;
  ariaDescriptorRegex?: string | null;
  headingLevel?: number | null;
  cssSelector?: string | null;
  dataTestId?: string | null;
  nthIndex?: number | null;
}

/**
 * Matches against [DriverNodeDetail.Compose] nodes.
 */
export interface DriverNodeMatchCompose {
  testTag?: string | null;
  role?: string | null;
  textRegex?: string | null;
  editableTextRegex?: string | null;
  contentDescriptionRegex?: string | null;
  toggleableState?: string | null;
  isEnabled?: boolean | null;
  isFocused?: boolean | null;
  isSelected?: boolean | null;
  isPassword?: boolean | null;
}

/**
 * Matches against [DriverNodeDetail.IosMaestro] nodes.
 * Only includes properties that iOS natively provides. Excludes clickable, enabled,
 * and checked which Maestro infers/defaults rather than reading from UIKit.
 */
export interface DriverNodeMatchIosMaestro {
  textRegex?: string | null;
  resourceIdRegex?: string | null;
  accessibilityTextRegex?: string | null;
  classNameRegex?: string | null;
  hintTextRegex?: string | null;
  focused?: boolean | null;
  selected?: boolean | null;
}

/**
 * Matches against [DriverNodeDetail.IosAxe] nodes using Apple-native AX vocabulary
 * — `role` (AXButton/AXStaticText/…), `subrole`, `customActions`, etc. — rather than
 * the Maestro-inferred shape in [IosMaestro].
 *
 * String (`*Regex`) fields are regex-OR-exact-literal (see [DriverNodeMatch]): matched as a
 * regex, else by exact full-string equality — so selectors like `$0.00` still work without
 * escaping (a leading `$` never regex-matches, so it resolves via the literal fallback).
 * `uniqueId` is exact-match because app-assigned accessibility identifiers are identity,
 * not text.
 *
 * Only properties in [DriverNodeDetail.IosAxe.MATCHABLE_PROPERTIES] are exposed here.
 */
export interface DriverNodeMatchIosAxe {
  /** **Matchable.** Apple AX role (e.g. `AXButton`, `AXStaticText`, `AXApplication`). */
  roleRegex?: string | null;
  /** **Matchable.** Apple AX subrole (e.g. `AXSecureTextField`). Often null. */
  subroleRegex?: string | null;
  /** **Matchable.** AXLabel — primary accessibility label. */
  labelRegex?: string | null;
  /** **Matchable.** AXValue — current value/state string. */
  valueRegex?: string | null;
  /** **Matchable.** Exact match on `accessibilityIdentifier` set by the app. */
  uniqueId?: string | null;
  /** **Matchable.** Short element type (e.g. `Button`, `StaticText`). */
  typeRegex?: string | null;
  /** **Matchable.** AXTitle — section/window title. */
  titleRegex?: string | null;
  /** **Matchable.** The node's `custom_actions` list must contain this string. */
  customAction?: string | null;
  /** **Matchable.** Whether the element is enabled (from `AXEnabled`). */
  enabled?: boolean | null;
}

/**
 * Rich element selector for [TrailblazeNode] trees.
 *
 * This is the successor to [TrailblazeElementSelector] for non-Maestro drivers.
 * Where [TrailblazeElementSelector] can only match on the limited properties that
 * Maestro's Orchestra supports (text, id, enabled, selected, checked, focused),
 * this selector can match on the full surface of each driver's native properties.
 *
 * ## Structure
 * - **[driverMatch]**: Driver-specific property matching (className, inputType, etc.)
 * - **Spatial relationships**: [above], [below], [leftOf], [rightOf]
 * - **Hierarchy**: [childOf], [containsChild], [containsDescendants]
 * - **Positioning**: [index] (last resort, applied after spatial sort)
 *
 * ## Recording flow
 * 1. User taps on screen → coordinates identify the target [TrailblazeNode]
 * 2. Selector generator examines the target and its context in the tree
 * 3. Generator produces a [TrailblazeNodeSelector] using driver-specific properties
 * 4. Selector is stored in the recording alongside fallback coordinates
 *
 * ## Playback flow
 * 1. Resolver matches the [TrailblazeNodeSelector] against the live [TrailblazeNode] tree
 * 2. If exactly one match → tap its center
 * 3. If no match → fall back to recorded coordinates
 *
 * ## Compatibility
 * [TrailblazeElementSelector] remains unchanged for Maestro-based paths.
 * This selector is used only by drivers that produce [TrailblazeNode] trees.
 *
 * @see TrailblazeElementSelector for the legacy Maestro-compatible selector
 */
export interface TrailblazeNodeSelector {
  /**
   * Android Accessibility driver matcher. Set this when matching against
   * [DriverNodeDetail.AndroidAccessibility] nodes.
   */
  androidAccessibility?: DriverNodeMatchAndroidAccessibility | null;
  /** Android Maestro driver matcher. */
  androidMaestro?: DriverNodeMatchAndroidMaestro | null;
  /** Web (Playwright) driver matcher. */
  web?: DriverNodeMatchWeb | null;
  /** Compose driver matcher. */
  compose?: DriverNodeMatchCompose | null;
  /** iOS Maestro accessibility hierarchy matcher. */
  iosMaestro?: DriverNodeMatchIosMaestro | null;
  /** iOS AXe (Apple Accessibility API) matcher — used when the tree is [DriverNodeDetail.IosAxe]. */
  iosAxe?: DriverNodeMatchIosAxe | null;
  /** Target must be below (lower Y) an element matching this selector. */
  below?: TrailblazeNodeSelector | null;
  /** Target must be above (higher Y) an element matching this selector. */
  above?: TrailblazeNodeSelector | null;
  /** Target must be left of an element matching this selector. */
  leftOf?: TrailblazeNodeSelector | null;
  /** Target must be right of an element matching this selector. */
  rightOf?: TrailblazeNodeSelector | null;
  /** Target must be a descendant of an element matching this selector. */
  childOf?: TrailblazeNodeSelector | null;
  /** Target must have a direct child matching this selector. */
  containsChild?: TrailblazeNodeSelector | null;
  /** Target must have descendants matching ALL of these selectors. */
  containsDescendants?: TrailblazeNodeSelector[] | null;
  /**
   * 0-based index among all matches, sorted top-to-bottom then left-to-right.
   * Applied after all other predicates. Last resort for disambiguation.
   */
  index?: number | null;
}

/** Screen-coordinate bounding rectangle. */
export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/**
 * Lightweight identity + position record describing one match returned by the
 * `findMatches` tool.
 *
 * Designed for scripted-tool authors who want to ask "is this element visible?",
 * "is the selector unambiguous?", and "where is the match on screen?" without
 * pulling the entire view subtree across the wire. The descriptor intentionally
 * omits any reference to the matched node's children — carrying the subtree
 * would defeat the snapshot-caching ROI, and opaque node handles would leak
 * driver implementation details into the typed authoring surface.
 *
 * ## Bounds reuse
 *
 * Reuses [TrailblazeNode.Bounds] (`left` / `top` / `right` / `bottom` with
 * computed `width` / `height` / `centerX` / `centerY`) rather than introducing
 * a separate `Rect` type — every selector resolution path already speaks this
 * shape, and the resolver hands back `TrailblazeNode` instances whose `bounds`
 * field is the same type.
 *
 * ## Cross-driver field semantics
 *
 * - [matchedText] is the matched node's best-available text — per-driver
 *   `resolveText()` for Android/iOS/Compose, `ariaName` for Web. Null when the
 *   driver detail carried no text-shaped property.
 * - [accessibilityId] is the accessibility-label / content-description / aria-
 *   descriptor on the matched node, when the driver exposes one.
 * - [resourceId] is the Android `resourceId` (or its iOS / Compose / Web
 *   analogue: `accessibilityIdentifier`, Compose `testTag`, web `data-testid`)
 *   when the driver exposes one.
 *
 * Drivers that don't expose a given property leave the corresponding field
 * null — scripted authors should not assume any field is populated.
 */
export interface MatchDescriptor {
  /**
   * Child-index path from the hierarchy root to this match.
   *
   * `[]` is the root, `[0, 2, 1, 4]` means "child 0 of root → child 2 → child 1
   * → child 4." Lets a caller re-identify a specific match against **the same
   * captured tree** without re-running the selector.
   *
   * ## Lifetime — frame-scoped, not durable
   *
   * The path is positional, so it is only stable for the lifetime of one
   * captured view-hierarchy snapshot. Any change to the tree shape between
   * capture and use — siblings added or removed, a RecyclerView item recycled,
   * a parent node re-mounting after a state change — invalidates the path:
   * the same physical pixels are now reached by a different index sequence.
   *
   * Treat descriptors as "immediate hand-offs to act on in this tool body"
   * rather than long-lived references. Re-querying via [findMatches] is the
   * right pattern after any device-mutating action, even if the matched
   * element is logically the same. For longer-lived identity, prefer
   * [accessibilityId] / [resourceId] when the driver populates them.
   */
  indexPath: number[];
  /**
   * Bounding rectangle of the matched node, in device pixels. `null` when the
   * driver couldn't compute bounds for the node (some Playwright nodes from
   * the parsed ARIA tree lack DOM-bounds enrichment; some Compose nodes
   * before first layout, etc.). Callers must treat `null` as "no coordinates
   * available" rather than tapping the origin — defaulting an unknown bounds
   * to `(0, 0, 0, 0)` would let a scripted tool accidentally tap the
   * top-left corner of the screen.
   */
  bounds?: Bounds | null;
  /**
   * Best-available text on the matched node, when the driver exposes one.
   * Per-driver: `text ?: hintText ?: contentDescription` on Android,
   * `text ?: hintText ?: accessibilityText` on iOS Maestro, `ariaName` on Web,
   * etc. Null when the matched node carried no text-shaped property.
   */
  matchedText?: string | null;
  /**
   * Accessibility label / content description on the matched node, when the
   * driver exposes one. Maps to `contentDescription` on Android accessibility,
   * `accessibilityText` on Android/iOS Maestro, `uniqueId` on iOS AXe,
   * `contentDescription` on Compose, `ariaDescriptor` on Web. Null otherwise.
   */
  accessibilityId?: string | null;
  /**
   * Stable identifier on the matched node, when the driver exposes one. Maps
   * to `resourceId` on Android, `accessibilityIdentifier` on iOS, `testTag` on
   * Compose, `data-testid` on Web. Null otherwise.
   */
  resourceId?: string | null;
}

/**
 * Ergonomic constructors for [TrailblazeNodeSelector] with scoped IDE autocomplete on
 * each driver's match-field surface. Both forms below produce identical values:
 *
 * ```ts
 * // Factory form — IDE narrows to the chosen driver's fields
 * const a: TrailblazeNodeSelector = selectors.androidAccessibility({ textRegex: "Submit" });
 *
 * // Literal form — copy-paste compatible with the YAML serialization
 * const b: TrailblazeNodeSelector = { androidAccessibility: { textRegex: "Submit" } };
 * ```
 *
 * The factory is pure sugar — its implementation is `(args) => ({ <driverKey>: args })`
 * — but it lets authors write a selector without remembering the exact wire-discriminator
 * key, and scopes autocomplete to one driver at a time. Adding a new driver is a single
 * Kotlin sealed-class branch + codegen regen; no parallel TypeScript edits to remember.
 */
export const selectors = {
  androidAccessibility: (args: DriverNodeMatchAndroidAccessibility): TrailblazeNodeSelector => ({ androidAccessibility: args }),
  androidMaestro: (args: DriverNodeMatchAndroidMaestro): TrailblazeNodeSelector => ({ androidMaestro: args }),
  web: (args: DriverNodeMatchWeb): TrailblazeNodeSelector => ({ web: args }),
  compose: (args: DriverNodeMatchCompose): TrailblazeNodeSelector => ({ compose: args }),
  iosMaestro: (args: DriverNodeMatchIosMaestro): TrailblazeNodeSelector => ({ iosMaestro: args }),
  iosAxe: (args: DriverNodeMatchIosAxe): TrailblazeNodeSelector => ({ iosAxe: args })
};
