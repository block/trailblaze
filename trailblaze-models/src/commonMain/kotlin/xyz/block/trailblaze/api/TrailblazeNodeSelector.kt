package xyz.block.trailblaze.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
@Serializable
data class TrailblazeNodeSelector(
  /**
   * Android Accessibility driver matcher. Set this when matching against
   * [DriverNodeDetail.AndroidAccessibility] nodes.
   */
  val androidAccessibility: DriverNodeMatch.AndroidAccessibility? = null,

  /** Android Maestro driver matcher. */
  val androidMaestro: DriverNodeMatch.AndroidMaestro? = null,

  /** Web (Playwright) driver matcher. */
  val web: DriverNodeMatch.Web? = null,

  /** Compose driver matcher. */
  val compose: DriverNodeMatch.Compose? = null,

  /** iOS Maestro accessibility hierarchy matcher. */
  val iosMaestro: DriverNodeMatch.IosMaestro? = null,

  // --- Spatial relationships ---

  /** Target must be below (lower Y) an element matching this selector. */
  val below: TrailblazeNodeSelector? = null,

  /** Target must be above (higher Y) an element matching this selector. */
  val above: TrailblazeNodeSelector? = null,

  /** Target must be left of an element matching this selector. */
  val leftOf: TrailblazeNodeSelector? = null,

  /** Target must be right of an element matching this selector. */
  val rightOf: TrailblazeNodeSelector? = null,

  // --- Hierarchy ---

  /** Target must be a descendant of an element matching this selector. */
  val childOf: TrailblazeNodeSelector? = null,

  /** Target must have a direct child matching this selector. */
  val containsChild: TrailblazeNodeSelector? = null,

  /** Target must have descendants matching ALL of these selectors. */
  val containsDescendants: List<TrailblazeNodeSelector>? = null,

  // --- Positioning ---

  /**
   * 0-based index among all matches, sorted top-to-bottom then left-to-right.
   * Applied after all other predicates. Last resort for disambiguation.
   */
  val index: Int? = null,
) {
  /**
   * Returns the active driver match, whichever driver-specific field is set.
   * At most one should be non-null.
   */
  @kotlinx.serialization.Transient
  val driverMatch: DriverNodeMatch?
    get() = androidAccessibility ?: androidMaestro ?: web ?: compose ?: iosMaestro

  companion object {
    /**
     * Creates a [TrailblazeNodeSelector] from a [DriverNodeMatch], dispatching to
     * the appropriate driver-specific field.
     */
    fun withMatch(
      match: DriverNodeMatch?,
      below: TrailblazeNodeSelector? = null,
      above: TrailblazeNodeSelector? = null,
      leftOf: TrailblazeNodeSelector? = null,
      rightOf: TrailblazeNodeSelector? = null,
      childOf: TrailblazeNodeSelector? = null,
      containsChild: TrailblazeNodeSelector? = null,
      containsDescendants: List<TrailblazeNodeSelector>? = null,
      index: Int? = null,
    ): TrailblazeNodeSelector = TrailblazeNodeSelector(
      androidAccessibility = match as? DriverNodeMatch.AndroidAccessibility,
      androidMaestro = match as? DriverNodeMatch.AndroidMaestro,
      web = match as? DriverNodeMatch.Web,
      compose = match as? DriverNodeMatch.Compose,
      iosMaestro = match as? DriverNodeMatch.IosMaestro,
      below = below,
      above = above,
      leftOf = leftOf,
      rightOf = rightOf,
      childOf = childOf,
      containsChild = containsChild,
      containsDescendants = containsDescendants,
      index = index,
    )
  }

  fun description(): String = buildString {
    driverMatch?.let { append(it.description()) }
    below?.let { d -> append(", below ${d.description()}") }
    above?.let { d -> append(", above ${d.description()}") }
    leftOf?.let { d -> append(", left of ${d.description()}") }
    rightOf?.let { d -> append(", right of ${d.description()}") }
    childOf?.let { d -> append(", child of ${d.description()}") }
    containsChild?.let { d -> append(", contains child ${d.description()}") }
    containsDescendants?.let { descs ->
      append(", contains descendants [${descs.joinToString(", ") { it.description() }}]")
    }
    index?.let { append(", index=$it") }
  }.trimStart(',', ' ')

  /**
   * Converts this [TrailblazeNodeSelector] to a legacy [TrailblazeElementSelector] for
   * backward compatibility. Maps driver-specific properties to the closest
   * [TrailblazeElementSelector] equivalents.
   *
   * Maestro's `textRegex` matches against `resolveText()` (text ?? hintText ?? accessibilityText),
   * so any text-like field maps to `textRegex`. Properties without a legacy equivalent
   * (className, hintText as a distinct field) are folded into the closest match.
   */
  fun toTrailblazeElementSelector(): TrailblazeElementSelector {
    var textRegex: String? = null
    var idRegex: String? = null
    var focused: Boolean? = null
    var selected: Boolean? = null
    var enabled: Boolean? = null
    var checked: Boolean? = null

    when (val match = driverMatch) {
      is DriverNodeMatch.IosMaestro -> {
        textRegex = match.textRegex ?: match.hintTextRegex ?: match.accessibilityTextRegex
        idRegex = match.resourceIdRegex
        focused = match.focused
        selected = match.selected
      }
      is DriverNodeMatch.AndroidMaestro -> {
        textRegex = match.textRegex ?: match.hintTextRegex ?: match.accessibilityTextRegex
        idRegex = match.resourceIdRegex
        focused = match.focused
        selected = match.selected
        enabled = match.enabled
        checked = match.checked
      }
      is DriverNodeMatch.AndroidAccessibility -> {
        textRegex = match.textRegex ?: match.contentDescriptionRegex ?: match.hintTextRegex
        idRegex = match.resourceIdRegex
      }
      is DriverNodeMatch.Compose -> {
        textRegex = match.textRegex
      }
      is DriverNodeMatch.Web -> {
        textRegex = match.ariaNameRegex ?: match.ariaDescriptorRegex
      }
      null -> {}
    }

    return TrailblazeElementSelector(
      textRegex = textRegex,
      idRegex = idRegex,
      focused = focused,
      selected = selected,
      enabled = enabled,
      checked = checked,
      index = this.index?.toString(),
      below = this.below?.toTrailblazeElementSelector(),
      above = this.above?.toTrailblazeElementSelector(),
      leftOf = this.leftOf?.toTrailblazeElementSelector(),
      rightOf = this.rightOf?.toTrailblazeElementSelector(),
      childOf = this.childOf?.toTrailblazeElementSelector(),
      containsChild = this.containsChild?.toTrailblazeElementSelector(),
      containsDescendants = this.containsDescendants?.map { it.toTrailblazeElementSelector() },
    )
  }
}

/**
 * Driver-specific property matcher. Each variant corresponds to a [DriverNodeDetail] variant
 * and can match on any of that driver's matchable properties.
 *
 * Only non-null fields are used as predicates — null means "don't care about this property."
 * String fields use regex matching (with literal fallback for invalid patterns).
 */
@Serializable
sealed interface DriverNodeMatch {

  /** Human-readable description of what this matcher selects. */
  fun description(): String

  // ---------------------------------------------------------------------------
  // Android Accessibility matcher
  // ---------------------------------------------------------------------------

  /**
   * Matches against [DriverNodeDetail.AndroidAccessibility] nodes.
   *
   * Only properties from [DriverNodeDetail.AndroidAccessibility.MATCHABLE_PROPERTIES]
   * should be set here. All fields are optional — only non-null fields act as predicates.
   * String fields support regex patterns.
   */
  @Serializable
  @SerialName("androidAccessibility")
  data class AndroidAccessibility(
    // Identity
    val classNameRegex: String? = null,
    val resourceIdRegex: String? = null,
    val uniqueId: String? = null,

    // Text (regex matching)
    val textRegex: String? = null,
    val contentDescriptionRegex: String? = null,
    val hintTextRegex: String? = null,
    val labeledByTextRegex: String? = null,
    val stateDescriptionRegex: String? = null,
    val paneTitleRegex: String? = null,

    // State (exact matching)
    val isEnabled: Boolean? = null,
    val isClickable: Boolean? = null,
    val isCheckable: Boolean? = null,
    val isChecked: Boolean? = null,
    val isSelected: Boolean? = null,
    val isFocused: Boolean? = null,
    val isEditable: Boolean? = null,
    val isScrollable: Boolean? = null,
    val isPassword: Boolean? = null,
    val isHeading: Boolean? = null,
    val isMultiLine: Boolean? = null,

    // Input
    val inputType: Int? = null,

    // Collection semantics
    val collectionItemRowIndex: Int? = null,
    val collectionItemColumnIndex: Int? = null,
  ) : DriverNodeMatch {

    override fun description(): String = buildString {
      val parts = mutableListOf<String>()
      classNameRegex?.let { parts.add("class~\"$it\"") }
      resourceIdRegex?.let { parts.add("id~\"$it\"") }
      uniqueId?.let { parts.add("uid=\"$it\"") }
      textRegex?.let { parts.add("\"$it\"") }
      contentDescriptionRegex?.let { parts.add("desc~\"$it\"") }
      hintTextRegex?.let { parts.add("hint~\"$it\"") }
      labeledByTextRegex?.let { parts.add("labeledBy~\"$it\"") }
      stateDescriptionRegex?.let { parts.add("state~\"$it\"") }
      paneTitleRegex?.let { parts.add("pane~\"$it\"") }
      isEnabled?.let { parts.add(if (it) "enabled" else "disabled") }
      isClickable?.let { parts.add(if (it) "clickable" else "not clickable") }
      isCheckable?.let { parts.add(if (it) "checkable" else "not checkable") }
      isChecked?.let { parts.add(if (it) "checked" else "unchecked") }
      isSelected?.let { parts.add(if (it) "selected" else "not selected") }
      isFocused?.let { parts.add(if (it) "focused" else "not focused") }
      isEditable?.let { parts.add(if (it) "editable" else "not editable") }
      isScrollable?.let { parts.add(if (it) "scrollable" else "not scrollable") }
      isPassword?.let { parts.add("password") }
      isHeading?.let { parts.add("heading") }
      isMultiLine?.let { parts.add(if (it) "multiline" else "singleline") }
      inputType?.let { parts.add("inputType=$it") }
      collectionItemRowIndex?.let { parts.add("row=$it") }
      collectionItemColumnIndex?.let { parts.add("col=$it") }
      append(parts.joinToString(", "))
    }
  }

  // ---------------------------------------------------------------------------
  // Android Maestro matcher
  // ---------------------------------------------------------------------------

  /**
   * Matches against [DriverNodeDetail.AndroidMaestro] nodes.
   *
   * This mirrors [TrailblazeElementSelector]'s matching capabilities but operates
   * on [TrailblazeNode] trees rather than [ViewHierarchyTreeNode].
   */
  @Serializable
  @SerialName("androidMaestro")
  data class AndroidMaestro(
    val textRegex: String? = null,
    val resourceIdRegex: String? = null,
    val accessibilityTextRegex: String? = null,
    val classNameRegex: String? = null,
    val hintTextRegex: String? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val focused: Boolean? = null,
    val checked: Boolean? = null,
    val selected: Boolean? = null,
  ) : DriverNodeMatch {

    override fun description(): String = buildString {
      val parts = mutableListOf<String>()
      textRegex?.let { parts.add("\"$it\"") }
      resourceIdRegex?.let { parts.add("id~\"$it\"") }
      accessibilityTextRegex?.let { parts.add("a11y~\"$it\"") }
      classNameRegex?.let { parts.add("class~\"$it\"") }
      hintTextRegex?.let { parts.add("hint~\"$it\"") }
      clickable?.let { parts.add(if (it) "clickable" else "not clickable") }
      enabled?.let { parts.add(if (it) "enabled" else "disabled") }
      focused?.let { parts.add(if (it) "focused" else "not focused") }
      checked?.let { parts.add(if (it) "checked" else "unchecked") }
      selected?.let { parts.add(if (it) "selected" else "not selected") }
      append(parts.joinToString(", "))
    }
  }

  // ---------------------------------------------------------------------------
  // Web (Playwright) matcher
  // ---------------------------------------------------------------------------

  /**
   * Matches against [DriverNodeDetail.Web] nodes.
   */
  @Serializable
  @SerialName("web")
  data class Web(
    val ariaRole: String? = null,
    val ariaNameRegex: String? = null,
    val ariaDescriptorRegex: String? = null,
    val headingLevel: Int? = null,
    val cssSelector: String? = null,
    val dataTestId: String? = null,
    val nthIndex: Int? = null,
  ) : DriverNodeMatch {

    override fun description(): String = buildString {
      val parts = mutableListOf<String>()
      ariaRole?.let { parts.add("role=$it") }
      ariaNameRegex?.let { parts.add("\"$it\"") }
      ariaDescriptorRegex?.let { parts.add("aria~\"$it\"") }
      headingLevel?.let { parts.add("h$it") }
      cssSelector?.let { parts.add("css=$it") }
      dataTestId?.let { parts.add("testid=\"$it\"") }
      nthIndex?.let { parts.add("nth=$it") }
      append(parts.joinToString(", "))
    }
  }

  // ---------------------------------------------------------------------------
  // iOS Maestro matcher
  // ---------------------------------------------------------------------------

  /**
   * Matches against [DriverNodeDetail.IosMaestro] nodes.
   * Only includes properties that iOS natively provides. Excludes clickable, enabled,
   * and checked which Maestro infers/defaults rather than reading from UIKit.
   */
  @Serializable
  @SerialName("iosMaestro")
  data class IosMaestro(
    val textRegex: String? = null,
    val resourceIdRegex: String? = null,
    val accessibilityTextRegex: String? = null,
    val classNameRegex: String? = null,
    val hintTextRegex: String? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null,
  ) : DriverNodeMatch {

    override fun description(): String = buildString {
      val parts = mutableListOf<String>()
      textRegex?.let { parts.add("\"$it\"") }
      resourceIdRegex?.let { parts.add("id~\"$it\"") }
      accessibilityTextRegex?.let { parts.add("a11y~\"$it\"") }
      classNameRegex?.let { parts.add("class~\"$it\"") }
      hintTextRegex?.let { parts.add("hint~\"$it\"") }
      focused?.let { parts.add(if (it) "focused" else "not focused") }
      selected?.let { parts.add(if (it) "selected" else "not selected") }
      append(parts.joinToString(", "))
    }
  }

  // ---------------------------------------------------------------------------
  // Compose matcher
  // ---------------------------------------------------------------------------

  /**
   * Matches against [DriverNodeDetail.Compose] nodes.
   */
  @Serializable
  @SerialName("compose")
  data class Compose(
    val testTag: String? = null,
    val role: String? = null,
    val textRegex: String? = null,
    val editableTextRegex: String? = null,
    val contentDescriptionRegex: String? = null,
    val toggleableState: String? = null,
    val isEnabled: Boolean? = null,
    val isFocused: Boolean? = null,
    val isSelected: Boolean? = null,
    val isPassword: Boolean? = null,
  ) : DriverNodeMatch {

    override fun description(): String = buildString {
      val parts = mutableListOf<String>()
      testTag?.let { parts.add("tag=\"$it\"") }
      role?.let { parts.add("role=$it") }
      textRegex?.let { parts.add("\"$it\"") }
      editableTextRegex?.let { parts.add("editable~\"$it\"") }
      contentDescriptionRegex?.let { parts.add("desc~\"$it\"") }
      toggleableState?.let { parts.add("toggle=$it") }
      isEnabled?.let { parts.add(if (it) "enabled" else "disabled") }
      isFocused?.let { parts.add(if (it) "focused" else "not focused") }
      isSelected?.let { parts.add(if (it) "selected" else "not selected") }
      isPassword?.let { parts.add("password") }
      append(parts.joinToString(", "))
    }
  }
}
