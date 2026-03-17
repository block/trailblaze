package xyz.block.trailblaze.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Strongly-typed driver-specific node properties for [TrailblazeNode].
 *
 * Each variant captures the full native richness of its platform/driver without
 * any lossy normalization. Pattern matching on the sealed interface gives type-safe
 * access to all driver-specific properties.
 *
 * ## Property matchability
 *
 * Each property is annotated as either **matchable** or **display-only**:
 *
 * - **Matchable**: Stable across runs, suitable for use in recorded selectors.
 *   These properties identify elements reliably even when the UI layout changes.
 *   The selector generator may use these to disambiguate elements.
 *
 * - **Display-only**: Transient or context-dependent. Useful for the LLM to
 *   recognize and describe elements, but NOT suitable for recorded selectors
 *   because the value may change between recording and playback.
 *
 * Each [DriverNodeDetail] variant exposes [matchablePropertyNames] listing the
 * properties that selector generators should consider.
 */
@Serializable
sealed interface DriverNodeDetail {

  /**
   * Returns the set of property names that are safe to match on in selectors.
   *
   * Selector generators should only use these properties when computing selectors
   * for recording. Display-only properties may be included in LLM context but
   * must not appear in recorded selectors.
   */
  val matchablePropertyNames: Set<String>

  // ---------------------------------------------------------------------------
  // Android via AccessibilityNodeInfo (Maestro-free path)
  // ---------------------------------------------------------------------------

  /**
   * Rich detail from Android's AccessibilityNodeInfo, captured by the accessibility driver.
   *
   * This is the richest Android representation, with ~30 properties beyond what Maestro's
   * TreeNode captures. Properties like [className], [inputType], [collectionItemInfo],
   * and [labeledByText] provide powerful disambiguation dimensions for selector generation.
   *
   * ## Matchable properties (stable, use in selectors)
   * [className], [resourceId], [uniqueId], [text], [contentDescription], [hintText],
   * [labeledByText], [stateDescription], [paneTitle], [isEnabled], [isClickable],
   * [isCheckable], [isChecked], [isSelected], [isFocused], [isEditable], [isScrollable],
   * [isPassword], [isHeading], [isMultiLine], [inputType], [collectionItemInfo]
   *
   * ## Display-only properties (transient, for LLM context only)
   * [packageName], [tooltipText], [error], [isShowingHintText], [isContentInvalid],
   * [isVisibleToUser], [isLongClickable], [isFocusable], [drawingOrder],
   * [maxTextLength], [actions], [collectionInfo], [rangeInfo]
   */
  @Serializable
  @SerialName("androidAccessibility")
  data class AndroidAccessibility(
    // --- Matchable: Identity ---

    /**
     * Fully qualified class name (e.g., "android.widget.EditText", "android.widget.Button").
     *
     * **Matchable.** Highly stable across runs. One of the most powerful disambiguation
     * properties — "the EditText" vs "the TextView" immediately narrows the search space.
     */
    val className: String? = null,

    /**
     * View's resource ID (e.g., "com.example:id/btn_continue").
     *
     * **Matchable.** Developer-assigned, stable across runs for the same app version.
     */
    val resourceId: String? = null,

    /**
     * Developer-assigned unique ID (API 33+). More stable than [resourceId] across builds.
     *
     * **Matchable.** When present, this is the most reliable identifier.
     */
    val uniqueId: String? = null,

    // --- Matchable: Text content ---

    /**
     * Primary text content of the node.
     *
     * **Matchable.** The most common matching property for text-bearing elements.
     */
    val text: String? = null,

    /**
     * Content description (accessibility label).
     *
     * **Matchable.** Often the only text for icon buttons and images.
     */
    val contentDescription: String? = null,

    /**
     * Hint text for input fields (e.g., "Enter your email").
     *
     * **Matchable.** Stable placeholder text set by developers.
     */
    val hintText: String? = null,

    /**
     * Text of the label node that describes this input (resolved from `getLabeledBy()`).
     * E.g., for a password field, this might be "Password".
     *
     * **Matchable.** Captures the semantic label relationship that users see.
     * Excellent for form field disambiguation: `{labeledByText: "Email", className: ".*EditText"}`.
     */
    val labeledByText: String? = null,

    /**
     * Rich state description (API 30+). Provides custom state beyond checked/selected.
     * E.g., "On", "50%", "Expanded", "3 of 10".
     *
     * **Matchable.** When present, provides highly semantic state information.
     * Use with caution — the value is matchable but may represent mutable state.
     */
    val stateDescription: String? = null,

    /**
     * Title for pane-like containers (dialogs, bottom sheets).
     *
     * **Matchable.** Stable title set by developers for structural containers.
     */
    val paneTitle: String? = null,

    // --- Matchable: State ---

    /** **Matchable.** Whether the node is enabled for interaction. */
    val isEnabled: Boolean = true,

    /** **Matchable.** Whether the node responds to click events. */
    val isClickable: Boolean = false,

    /** **Matchable.** Whether the node can be toggled (checkbox, switch, radio). */
    val isCheckable: Boolean = false,

    /** **Matchable.** Current checked state (for checkable elements). */
    val isChecked: Boolean = false,

    /** **Matchable.** Whether the node is in a selected state (e.g., selected tab). */
    val isSelected: Boolean = false,

    /** **Matchable.** Whether the node currently has input focus. */
    val isFocused: Boolean = false,

    /** **Matchable.** Whether the node accepts text input. */
    val isEditable: Boolean = false,

    /** **Matchable.** Whether the node can be scrolled. */
    val isScrollable: Boolean = false,

    /** **Matchable.** Whether the node is a password input (content obscured). */
    val isPassword: Boolean = false,

    /** **Matchable.** Whether this node is a structural heading (API 28+). */
    val isHeading: Boolean = false,

    /** **Matchable.** Whether the text field supports multiple lines. */
    val isMultiLine: Boolean = false,

    // --- Matchable: Input ---

    /**
     * Input type for editable fields, matching Android's `InputType` constants.
     * E.g., `InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS`, `InputType.TYPE_CLASS_NUMBER`.
     *
     * **Matchable.** Distinguishes email inputs from phone inputs from plain text.
     */
    val inputType: Int = 0,

    // --- Matchable: Collection semantics ---

    /**
     * Position of this item within a list/grid (row, column, span).
     *
     * **Matchable.** Provides semantically correct list position — far more robust
     * than the brittle `index` field. "The 3rd item in the list" survives layout changes
     * that would break positional index.
     */
    val collectionItemInfo: CollectionItemInfo? = null,

    // --- Display-only: Identity ---

    /**
     * The app package that owns this view.
     *
     * **Display-only.** May vary across build flavors (debug vs release).
     */
    val packageName: String? = null,

    // --- Display-only: Text ---

    /**
     * Tooltip text shown on long-hover (API 28+).
     *
     * **Display-only.** Tooltip visibility is transient — may not be visible during playback.
     */
    val tooltipText: String? = null,

    /**
     * Error text for invalid input (e.g., "Invalid email address").
     *
     * **Display-only.** Validation state is transient — depends on user input at that moment.
     */
    val error: String? = null,

    /**
     * Whether the node is currently showing hint text rather than actual input (API 26+).
     *
     * **Display-only.** Changes as soon as the user types — not stable for matching.
     */
    val isShowingHintText: Boolean = false,

    // --- Display-only: State ---

    /**
     * Whether the content is currently invalid (form validation state).
     *
     * **Display-only.** Validation state changes with user input.
     */
    val isContentInvalid: Boolean = false,

    /**
     * Whether this node is actually visible on screen (not off-screen/hidden/covered).
     *
     * **Display-only.** Depends on scroll position and overlapping views — changes constantly.
     */
    val isVisibleToUser: Boolean = true,

    /**
     * Whether the node responds to long-press events.
     *
     * **Display-only.** Rarely useful for disambiguation.
     */
    val isLongClickable: Boolean = false,

    /**
     * Whether the node can receive focus.
     *
     * **Display-only.** Too common to be useful for disambiguation.
     */
    val isFocusable: Boolean = false,

    /**
     * Whether the text content is selectable (API 33+).
     *
     * **Display-only.** Helps distinguish copyable text displays from non-selectable labels.
     */
    val isTextSelectable: Boolean = false,

    /**
     * Whether the node is important for accessibility.
     *
     * **Display-only.** Useful for filtering decorative/structural nodes that don't carry
     * semantic content, reducing noise during element resolution.
     */
    val isImportantForAccessibility: Boolean = true,

    // --- Display-only: Layout ---

    /**
     * Visual z-order among siblings. Higher values are drawn on top.
     *
     * **Display-only.** Implementation detail of the layout system.
     */
    val drawingOrder: Int = 0,

    /**
     * Maximum text length for input fields. 0 means no limit.
     *
     * **Display-only.** Rarely distinguishes elements.
     */
    val maxTextLength: Int = 0,

    // --- Display-only: Interaction ---

    /**
     * Available accessibility actions (e.g., "ACTION_CLICK", "ACTION_SET_TEXT").
     *
     * **Display-only.** The action list can vary by transient state (focused vs unfocused).
     */
    val actions: List<String> = emptyList(),

    // --- Display-only: Collection container ---

    /**
     * For list/grid containers: row count, column count, and selection mode.
     *
     * **Display-only.** Container metadata varies as items are added/removed.
     */
    val collectionInfo: CollectionInfo? = null,

    /**
     * For seekbar/progress/rating: current value, min, max.
     *
     * **Display-only.** Current value is mutable state — min/max could be matchable
     * but rarely useful for disambiguation.
     */
    val rangeInfo: RangeInfo? = null,
  ) : DriverNodeDetail {

    override val matchablePropertyNames: Set<String>
      get() = MATCHABLE_PROPERTIES

    /** Resolves text priority: text > hintText > contentDescription (same as Maestro). */
    fun resolveText(): String? = text ?: hintText ?: contentDescription

    @Serializable
    data class CollectionInfo(
      val rowCount: Int,
      val columnCount: Int,
      val isHierarchical: Boolean,
    )

    @Serializable
    data class CollectionItemInfo(
      val rowIndex: Int,
      val rowSpan: Int,
      val columnIndex: Int,
      val columnSpan: Int,
      val isHeading: Boolean,
    )

    @Serializable
    data class RangeInfo(
      val type: Int,
      val min: Float,
      val max: Float,
      val current: Float,
    )

    companion object {
      /** Properties safe to use in recorded selectors. */
      val MATCHABLE_PROPERTIES: Set<String> = setOf(
        "className", "resourceId", "uniqueId",
        "text", "contentDescription", "hintText", "labeledByText",
        "stateDescription", "paneTitle",
        "isEnabled", "isClickable", "isCheckable", "isChecked",
        "isSelected", "isFocused", "isEditable", "isScrollable",
        "isPassword", "isHeading", "isMultiLine",
        "inputType", "collectionItemInfo",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Android via Maestro's TreeNode (existing Maestro-based path)
  // ---------------------------------------------------------------------------

  /**
   * Android view properties as captured by Maestro's TreeNode.
   *
   * Preserves exactly what Maestro captures — no more, no less. This variant
   * exists so the existing TapSelectorV2 pipeline can operate on [TrailblazeNode]
   * without any change to selector generation logic.
   *
   * Most properties are **matchable** since they correspond to what Maestro's Orchestra
   * can filter on today. A few (focusable, scrollable, password) are **display-only**
   * because they are too common or rarely useful for disambiguation.
   */
  @Serializable
  @SerialName("androidMaestro")
  data class AndroidMaestro(
    /** **Matchable.** Primary text content. */
    val text: String? = null,
    /** **Matchable.** View resource ID (e.g., "com.example:id/btn"). */
    val resourceId: String? = null,
    /** **Matchable.** Accessibility content description. */
    val accessibilityText: String? = null,
    /** **Matchable.** View class name (e.g., "android.widget.TextView"). */
    val className: String? = null,
    /** **Matchable.** Input hint text. */
    val hintText: String? = null,
    /** **Matchable.** */
    val clickable: Boolean = false,
    /** **Matchable.** */
    val enabled: Boolean = true,
    /** **Matchable.** */
    val focused: Boolean = false,
    /** **Matchable.** */
    val checked: Boolean = false,
    /** **Matchable.** */
    val selected: Boolean = false,
    /** **Display-only.** Too common to disambiguate. */
    val focusable: Boolean = false,
    /** **Display-only.** Rarely useful for disambiguation. */
    val scrollable: Boolean = false,
    /** **Display-only.** Rarely useful for disambiguation. */
    val password: Boolean = false,
  ) : DriverNodeDetail {

    override val matchablePropertyNames: Set<String>
      get() = MATCHABLE_PROPERTIES

    /** Resolves text priority: text > hintText > accessibilityText (Maestro convention). */
    fun resolveText(): String? = text ?: hintText ?: accessibilityText

    companion object {
      val MATCHABLE_PROPERTIES: Set<String> = setOf(
        "text", "resourceId", "accessibilityText", "className", "hintText",
        "clickable", "enabled", "focused", "checked", "selected",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Web via Playwright ARIA snapshot
  // ---------------------------------------------------------------------------

  /**
   * Web element properties captured via Playwright's ARIA snapshot.
   *
   * Elements are identified by ARIA descriptor + nth occurrence index, not by
   * integer node IDs. The [ariaDescriptor] is the primary matching key, with
   * [nthIndex] disambiguating duplicates (e.g., two `link "Home"` elements).
   */
  @Serializable
  @SerialName("web")
  data class Web(
    /** **Matchable.** ARIA role (e.g., "button", "textbox", "heading"). */
    val ariaRole: String? = null,
    /** **Matchable.** ARIA accessible name (e.g., "Submit", "Email"). */
    val ariaName: String? = null,
    /**
     * **Matchable.** Full ARIA descriptor for locator resolution (e.g., `button "Submit"`).
     * This is the primary element identification mechanism for Playwright.
     */
    val ariaDescriptor: String? = null,
    /** **Matchable.** Heading level (1-6) for heading elements. */
    val headingLevel: Int? = null,
    /** **Matchable.** CSS selector (e.g., "#my-id", "[data-testid='card']"). */
    val cssSelector: String? = null,
    /** **Matchable.** The data-testid attribute value. */
    val dataTestId: String? = null,
    /**
     * **Matchable.** 0-based occurrence index among elements with the same [ariaDescriptor].
     * Used with Playwright's `.nth(index)` to pick the correct duplicate.
     */
    val nthIndex: Int = 0,
    /** **Display-only.** Whether this element is interactive (button, link, input, etc.). */
    val isInteractive: Boolean = false,
    /** **Display-only.** Whether this element is a landmark section (nav, main, etc.). */
    val isLandmark: Boolean = false,
  ) : DriverNodeDetail {

    override val matchablePropertyNames: Set<String>
      get() = MATCHABLE_PROPERTIES

    companion object {
      val MATCHABLE_PROPERTIES: Set<String> = setOf(
        "ariaRole", "ariaName", "ariaDescriptor",
        "headingLevel", "cssSelector", "dataTestId", "nthIndex",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // iOS via Maestro accessibility hierarchy
  // ---------------------------------------------------------------------------

  /**
   * iOS view properties as captured by Maestro's TreeNode on iOS.
   *
   * Same shape as [AndroidMaestro] plus iOS-specific properties ([visible],
   * [ignoreBoundsFiltering]). Used for all iOS paths: both the Square custom
   * hierarchy (which produces the same fidelity as Maestro's TreeNode) and
   * the Maestro accessibility fallback.
   */
  @Serializable
  @SerialName("iosMaestro")
  data class IosMaestro(
    /** **Matchable.** Primary text content. */
    val text: String? = null,
    /** **Matchable.** Resource ID (accessibility identifier on iOS). */
    val resourceId: String? = null,
    /** **Matchable.** Accessibility content description. */
    val accessibilityText: String? = null,
    /** **Matchable.** View class name (e.g., "UIButton"). */
    val className: String? = null,
    /** **Matchable.** Input hint text. */
    val hintText: String? = null,
    /** **Display-only.** iOS has no direct "clickable" — Maestro infers this. */
    val clickable: Boolean = false,
    /** **Display-only.** Often `null` from Maestro on iOS, defaulted to `true`. */
    val enabled: Boolean = true,
    /** **Matchable.** Accessibility focus state. */
    val focused: Boolean = false,
    /** **Display-only.** iOS has no native "checked" — Maestro infers from traits/value. */
    val checked: Boolean = false,
    /** **Matchable.** Maps from `isSelected` accessibility trait. */
    val selected: Boolean = false,
    /** **Display-only.** Inferred, not a direct UIKit property. */
    val focusable: Boolean = false,
    /** **Display-only.** Rarely useful for disambiguation. */
    val scrollable: Boolean = false,
    /** **Display-only.** Rarely useful for disambiguation. */
    val password: Boolean = false,
    /** **Display-only.** iOS visibility flag for element filtering. */
    val visible: Boolean = true,
    /** **Display-only.** iOS flag to skip bounds-based filtering. */
    val ignoreBoundsFiltering: Boolean = false,
  ) : DriverNodeDetail {

    override val matchablePropertyNames: Set<String>
      get() = MATCHABLE_PROPERTIES

    /** Resolves text priority: text > hintText > accessibilityText (Maestro convention). */
    fun resolveText(): String? = text ?: hintText ?: accessibilityText

    companion object {
      /** Only properties that iOS natively provides. Excludes clickable, enabled, checked
       *  which Maestro infers/defaults rather than reading from UIKit. */
      val MATCHABLE_PROPERTIES: Set<String> = setOf(
        "text", "resourceId", "accessibilityText", "className", "hintText",
        "focused", "selected",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Compose SemanticsNode (Desktop/Android Compose)
  // ---------------------------------------------------------------------------

  /**
   * Compose element properties captured from the SemanticsNode tree.
   *
   * Compose uses a semantic property system rather than traditional view attributes.
   * [testTag] is the primary developer-assigned identifier, and [role] provides
   * the semantic element type.
   */
  @Serializable
  @SerialName("compose")
  data class Compose(
    /** **Matchable.** Developer-assigned test tag for stable identification. */
    val testTag: String? = null,
    /** **Matchable.** Compose semantic role (e.g., "Button", "Checkbox", "Switch"). */
    val role: String? = null,
    /** **Matchable.** Primary display text. */
    val text: String? = null,
    /** **Matchable.** Current editable text content. */
    val editableText: String? = null,
    /** **Matchable.** Content description (accessibility label). */
    val contentDescription: String? = null,
    /** **Matchable.** Toggleable state: "On", "Off", or "Indeterminate". */
    val toggleableState: String? = null,
    /** **Matchable.** Whether the element is enabled for interaction. */
    val isEnabled: Boolean = true,
    /** **Matchable.** Whether the element currently has focus. */
    val isFocused: Boolean = false,
    /** **Matchable.** Whether the element is in a selected state. */
    val isSelected: Boolean = false,
    /** **Matchable.** Whether this is a password input. */
    val isPassword: Boolean = false,
    /** **Display-only.** Whether the element has a click action (implementation detail). */
    val hasClickAction: Boolean = false,
    /** **Display-only.** Whether the element has a scroll action (implementation detail). */
    val hasScrollAction: Boolean = false,
  ) : DriverNodeDetail {

    override val matchablePropertyNames: Set<String>
      get() = MATCHABLE_PROPERTIES

    /** Resolves text priority: editableText > text > contentDescription. */
    fun resolveText(): String? = editableText ?: text ?: contentDescription

    companion object {
      val MATCHABLE_PROPERTIES: Set<String> = setOf(
        "testTag", "role", "text", "editableText", "contentDescription",
        "toggleableState", "isEnabled", "isFocused", "isSelected", "isPassword",
      )
    }
  }
}
