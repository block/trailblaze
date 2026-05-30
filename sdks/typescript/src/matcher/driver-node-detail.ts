// PORT of `trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/api/DriverNodeDetail.kt`.
//
// SOURCE OF TRUTH is the Kotlin file above. See `trailblaze-node.ts` header for the parity
// contract and editing rules — they apply to every file in this directory.
//
// The Kotlin file is a sealed interface with six data classes (AndroidAccessibility,
// AndroidMaestro, Web, Compose, IosMaestro, IosAxe). We mirror it as a TypeScript
// discriminated union — each variant carries a `kind` discriminator matching the
// Kotlin `@SerialName(...)` value, so JSON serialized from the host hydrates directly
// into the union without a custom deserializer. The `isInteractive` and
// `hasIdentifiableProperties` getters from Kotlin become free functions here
// (interfaces can't carry computed properties; functions over the union are the
// idiomatic TS substitute).
//
// **Matchable vs display-only** distinctions from the Kotlin doc are preserved in
// the field-level comments but not enforced at the type level — the matcher already
// has the source-of-truth list via [DriverNodeMatch*] (see `generated/selectors.ts`),
// and dual enforcement would just be drift bait.

/**
 * Discriminated union over the six driver-specific detail variants.
 *
 * **Discriminator: `class`.** The host's `TrailblazeJson` configuration uses
 * `class` as its polymorphic discriminator (see
 * `trailblaze-models/.../logs/client/TrailblazeJson.kt:27,43` —
 * `POLYMORPHIC_CLASS_DISCRIMINATOR = "class"`). Matching that on the TS side
 * means `JSON.parse(rawHostJson) as DriverNodeDetail` produces a directly
 * usable discriminated-union value with no field-rename translation step.
 *
 * The `class` property name is slightly awkward in TypeScript (the bare word
 * is a reserved keyword for class declarations) but property-access syntax —
 * `detail.class` — is unambiguous and TS's exhaustiveness narrowing works
 * across `switch (detail.class) { case "androidAccessibility": ... }`.
 * Destructuring requires renaming (`const { class: variant } = detail`);
 * call sites that need a variable binding do that locally.
 */
export type DriverNodeDetail =
  | DriverNodeDetailAndroidAccessibility
  | DriverNodeDetailAndroidMaestro
  | DriverNodeDetailWeb
  | DriverNodeDetailCompose
  | DriverNodeDetailIosMaestro
  | DriverNodeDetailIosAxe;

// ============================================================================
// Android via AccessibilityNodeInfo (Maestro-free path)
// ============================================================================

/** Collection-position metadata for an item in a list/grid. */
export interface AndroidCollectionItemInfo {
  readonly rowIndex: number;
  readonly rowSpan: number;
  readonly columnIndex: number;
  readonly columnSpan: number;
  readonly isHeading: boolean;
}

/** Collection-container metadata. */
export interface AndroidCollectionInfo {
  readonly rowCount: number;
  readonly columnCount: number;
  readonly isHierarchical: boolean;
}

/** Seekbar/progress/rating range metadata. */
export interface AndroidRangeInfo {
  readonly type: number;
  readonly min: number;
  readonly max: number;
  readonly current: number;
}

/**
 * Rich detail from Android's AccessibilityNodeInfo. ~30 properties beyond what
 * Maestro's TreeNode captures. Matches Kotlin `DriverNodeDetail.AndroidAccessibility`.
 */
export interface DriverNodeDetailAndroidAccessibility {
  readonly class: "androidAccessibility";
  // --- Matchable: Identity ---
  readonly className?: string | null;
  readonly resourceId?: string | null;
  readonly uniqueId?: string | null;
  readonly text?: string | null;
  readonly contentDescription?: string | null;
  readonly hintText?: string | null;
  readonly labeledByText?: string | null;
  readonly stateDescription?: string | null;
  readonly paneTitle?: string | null;
  readonly roleDescription?: string | null;
  readonly composeTestTag?: string | null;
  // --- Matchable: State ---
  readonly isEnabled?: boolean;
  readonly isClickable?: boolean;
  readonly isCheckable?: boolean;
  readonly isChecked?: boolean;
  readonly isSelected?: boolean;
  readonly isFocused?: boolean;
  readonly isEditable?: boolean;
  readonly isScrollable?: boolean;
  readonly isPassword?: boolean;
  readonly isHeading?: boolean;
  readonly isMultiLine?: boolean;
  readonly inputType?: number;
  // --- Matchable: Collection semantics ---
  readonly collectionItemInfo?: AndroidCollectionItemInfo | null;
  // --- Display-only ---
  readonly packageName?: string | null;
  readonly tooltipText?: string | null;
  readonly error?: string | null;
  readonly isShowingHintText?: boolean;
  readonly isContentInvalid?: boolean;
  readonly isVisibleToUser?: boolean;
  readonly isLongClickable?: boolean;
  readonly isFocusable?: boolean;
  readonly isTextSelectable?: boolean;
  readonly isImportantForAccessibility?: boolean;
  readonly drawingOrder?: number;
  readonly maxTextLength?: number;
  readonly actions?: readonly string[];
  readonly collectionInfo?: AndroidCollectionInfo | null;
  readonly rangeInfo?: AndroidRangeInfo | null;
}

// ============================================================================
// Android via Maestro's TreeNode
// ============================================================================

export interface DriverNodeDetailAndroidMaestro {
  readonly class: "androidMaestro";
  readonly text?: string | null;
  readonly resourceId?: string | null;
  readonly accessibilityText?: string | null;
  readonly className?: string | null;
  readonly hintText?: string | null;
  readonly clickable?: boolean;
  readonly enabled?: boolean;
  readonly focused?: boolean;
  readonly checked?: boolean;
  readonly selected?: boolean;
  readonly focusable?: boolean;
  readonly scrollable?: boolean;
  readonly password?: boolean;
}

// ============================================================================
// Web via Playwright ARIA snapshot
// ============================================================================

export interface DriverNodeDetailWeb {
  readonly class: "web";
  readonly ariaRole?: string | null;
  readonly ariaName?: string | null;
  readonly ariaDescriptor?: string | null;
  readonly headingLevel?: number | null;
  readonly cssSelector?: string | null;
  readonly dataTestId?: string | null;
  readonly nthIndex?: number;
  // The Kotlin field is named `isInteractive` and overrides the base interface's
  // property; in TS we keep the same name on the variant. The free function
  // `isInteractive(detail)` below dispatches on `kind` and reads this field
  // (or the per-driver computed equivalent) without surprises.
  readonly isInteractive?: boolean;
  readonly isLandmark?: boolean;
}

// ============================================================================
// iOS via Maestro accessibility hierarchy
// ============================================================================

export interface DriverNodeDetailIosMaestro {
  readonly class: "iosMaestro";
  readonly text?: string | null;
  readonly resourceId?: string | null;
  readonly accessibilityText?: string | null;
  readonly className?: string | null;
  readonly hintText?: string | null;
  readonly clickable?: boolean;
  readonly enabled?: boolean;
  readonly focused?: boolean;
  readonly checked?: boolean;
  readonly selected?: boolean;
  readonly focusable?: boolean;
  readonly scrollable?: boolean;
  readonly password?: boolean;
  readonly visible?: boolean;
  readonly ignoreBoundsFiltering?: boolean;
}

// ============================================================================
// iOS via AXe CLI
// ============================================================================

export interface DriverNodeDetailIosAxe {
  readonly class: "iosAxe";
  readonly role?: string | null;
  readonly subrole?: string | null;
  readonly roleDescription?: string | null;
  readonly label?: string | null;
  readonly value?: string | null;
  readonly uniqueId?: string | null;
  readonly type?: string | null;
  readonly title?: string | null;
  readonly help?: string | null;
  readonly customActions?: readonly string[];
  readonly enabled?: boolean;
  readonly contentRequired?: boolean;
  readonly pid?: number | null;
}

/**
 * AX roles that represent interactive iOS controls. Mirrors
 * `DriverNodeDetail.IosAxe.Companion.INTERACTIVE_ROLES`.
 *
 * Anything outside this set is treated as static content. AXe reports
 * `enabled = true` for nearly every node (including static text), so the
 * `enabled` flag alone isn't a reliable interactive signal — role + custom
 * actions are.
 */
export const IOS_AXE_INTERACTIVE_ROLES: ReadonlySet<string> = new Set([
  "AXButton",
  "AXLink",
  "AXTextField",
  "AXSecureTextField",
  "AXSearchField",
  "AXSwitch",
  "AXSlider",
  "AXCheckBox",
  "AXMenuItem",
  "AXPopUpButton",
  "AXRadioButton",
  "AXSegmentedControl",
  "AXStepper",
  "AXComboBox",
  "AXToolbarButton",
  "AXBackButton",
  "AXPickerWheel",
  "AXTab",
  "AXCell",
]);

// ============================================================================
// Compose SemanticsNode
// ============================================================================

export interface DriverNodeDetailCompose {
  readonly class: "compose";
  readonly testTag?: string | null;
  readonly role?: string | null;
  readonly text?: string | null;
  readonly editableText?: string | null;
  readonly contentDescription?: string | null;
  readonly toggleableState?: string | null;
  readonly isEnabled?: boolean;
  readonly isFocused?: boolean;
  readonly isSelected?: boolean;
  readonly isPassword?: boolean;
  readonly hasClickAction?: boolean;
  readonly hasScrollAction?: boolean;
}

// ============================================================================
// Helper functions over the union (replacing Kotlin's interface-level getters)
// ============================================================================

/** Returns true if the string is non-null and not blank (matches Kotlin's `!str.isNullOrBlank()`). */
function notBlank(s: string | null | undefined): boolean {
  return s != null && s.trim().length > 0;
}

/**
 * Resolves the best-available text for a node. Per-driver priority chain matches
 * the Kotlin `resolveText()` methods on each `DriverNodeDetail` variant.
 */
export function resolveText(detail: DriverNodeDetail): string | null {
  switch (detail.class) {
    case "androidAccessibility":
      return detail.text ?? detail.hintText ?? detail.contentDescription ?? null;
    case "androidMaestro":
      return detail.text ?? detail.hintText ?? detail.accessibilityText ?? null;
    case "iosMaestro":
      return detail.text ?? detail.hintText ?? detail.accessibilityText ?? null;
    case "iosAxe":
      // Per-variant Kotlin: label > value > title, with non-blank checks.
      if (notBlank(detail.label)) return detail.label!;
      if (notBlank(detail.value)) return detail.value!;
      if (notBlank(detail.title)) return detail.title!;
      return null;
    case "compose":
      return detail.editableText ?? detail.text ?? detail.contentDescription ?? null;
    case "web":
      // Web has no `resolveText()` on Kotlin (it uses `ariaName` directly in
      // call sites like `describe()`); mirror that — return ariaName.
      return detail.ariaName ?? null;
  }
}

/**
 * Returns true if the node has at least one identifiable property — a property
 * the selector generator can resolve a node by ALONE. Mirrors Kotlin
 * `hasIdentifiableProperties` per-variant computations.
 */
export function hasIdentifiableProperties(detail: DriverNodeDetail): boolean {
  switch (detail.class) {
    case "androidAccessibility":
      return (
        notBlank(detail.text) ||
        notBlank(detail.resourceId) ||
        notBlank(detail.uniqueId) ||
        notBlank(detail.composeTestTag) ||
        notBlank(detail.contentDescription) ||
        notBlank(detail.hintText) ||
        notBlank(detail.className)
      );
    case "androidMaestro":
      return (
        notBlank(detail.text) ||
        notBlank(detail.resourceId) ||
        notBlank(detail.accessibilityText) ||
        notBlank(detail.hintText)
      );
    case "iosMaestro":
      return (
        notBlank(detail.text) ||
        notBlank(detail.resourceId) ||
        notBlank(detail.accessibilityText) ||
        notBlank(detail.hintText)
      );
    case "iosAxe":
      return (
        notBlank(detail.label) ||
        notBlank(detail.value) ||
        notBlank(detail.uniqueId) ||
        notBlank(detail.title)
      );
    case "compose":
      return (
        notBlank(detail.testTag) ||
        notBlank(detail.role) ||
        notBlank(detail.text) ||
        notBlank(detail.editableText) ||
        notBlank(detail.contentDescription)
      );
    case "web":
      return (
        notBlank(detail.ariaRole) ||
        notBlank(detail.ariaName) ||
        notBlank(detail.ariaDescriptor) ||
        notBlank(detail.cssSelector) ||
        notBlank(detail.dataTestId)
      );
  }
}

/**
 * Returns true if this node represents an interactive element. Mirrors Kotlin
 * `isInteractive` per-variant computations.
 *
 * Note: Web carries `isInteractive` as a stored field (Kotlin overrides the
 * interface property with a constructor parameter); the rest compute it.
 */
export function isInteractive(detail: DriverNodeDetail): boolean {
  switch (detail.class) {
    case "androidAccessibility":
      return (
        (detail.isClickable ?? false) ||
        (detail.isEditable ?? false) ||
        (detail.isCheckable ?? false) ||
        (detail.isFocusable ?? false) ||
        (detail.isScrollable ?? false)
      );
    case "androidMaestro":
      return (
        (detail.clickable ?? false) ||
        (detail.focusable ?? false) ||
        (detail.scrollable ?? false)
      );
    case "iosMaestro":
      return (
        (detail.clickable ?? false) ||
        (detail.focusable ?? false) ||
        (detail.scrollable ?? false)
      );
    case "iosAxe":
      return (
        (detail.customActions != null && detail.customActions.length > 0) ||
        (detail.role != null && IOS_AXE_INTERACTIVE_ROLES.has(detail.role))
      );
    case "compose":
      return (detail.hasClickAction ?? false) || (detail.hasScrollAction ?? false);
    case "web":
      return detail.isInteractive ?? false;
  }
}

// Note: `DriverNodeDetail` variants are pure data — they don't carry methods. The
// Kotlin source has `isInteractive` / `hasIdentifiableProperties` / `resolveText()`
// as interface members; the TS port surfaces those as free functions in this file
// (`isInteractive(detail)`, etc.). Callers in `trailblaze-node.ts` and `resolver.ts`
// import the functions explicitly. The exception is `DriverNodeDetailWeb.isInteractive`,
// which carries a stored field because the Kotlin Web variant overrides the interface
// property with a constructor parameter; the free `isInteractive(detail)` function
// reads that field when the variant is `web`.
