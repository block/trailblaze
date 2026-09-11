package xyz.block.trailblaze.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which property a string was read from.
 *
 * Kept distinct rather than flattened to "text" because the difference is the whole point for a
 * localization audit: an icon's [CONTENT_DESCRIPTION] is translated and belongs in the report, but
 * it is not glyphs on the screen, and a reader has to be able to tell those apart.
 */
@Serializable
enum class VisibleStringSource {
  @SerialName("text")
  TEXT,

  @SerialName("editableText")
  EDITABLE_TEXT,

  @SerialName("contentDescription")
  CONTENT_DESCRIPTION,

  @SerialName("hint")
  HINT,

  @SerialName("state")
  STATE,

  @SerialName("error")
  ERROR,

  @SerialName("title")
  TITLE,

  @SerialName("value")
  VALUE,

  @SerialName("help")
  HELP,

  @SerialName("tooltip")
  TOOLTIP,

  @SerialName("labeledBy")
  LABELED_BY,

  @SerialName("roleDescription")
  ROLE_DESCRIPTION,

  @SerialName("paneTitle")
  PANE_TITLE,

  @SerialName("customAction")
  CUSTOM_ACTION,
}

/** One string a person could read on a screen, and where on that screen it came from. */
@Serializable
data class ExtractedString(
  val text: String,
  val source: VisibleStringSource,
  /** Stable element ref (e.g. `k42`) when the capture carried one, for pointing at the element. */
  val ref: String? = null,
  /** `[x, y, width, height]` in screen coordinates, or null when the capture had no bounds. */
  val bounds: List<Int>? = null,
  /** False when the element sits outside the viewport. Recorded, not dropped: an untranslated
   *  string below the fold is still a regression. */
  val visible: Boolean = true,
  /** Digits with no letters — a clock, balance, or counter that will differ on every run. */
  val volatile: Boolean = false,
)

/**
 * Reads the strings a person could see on one screen capture out of the view tree that capture
 * already persisted.
 *
 * Visibility follows the rules the compact element list builders apply — the same system-UI,
 * platform-hidden and offscreen decisions, made once.
 *
 * Deliberately wider than what the agent saw: a string outside the viewport is returned with
 * [ExtractedString.visible] set to false rather than dropped, because an untranslated string below
 * the fold is still a regression. Everything the platform reports as covered or hidden in place is
 * dropped outright.
 */
object VisibleStringExtractor {

  private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
  private const val SECURE_TEXT_FIELD = "AXSecureTextField"
  private val WHITESPACE_RUN = Regex("\\s+")
  private val MERIDIEM = setOf("am", "pm")

  /** Reading order: top to bottom, then left to right. Boundless nodes keep tree order, last. */
  private val READING_ORDER = compareBy<ExtractedString>(
    { it.bounds == null },
    { it.bounds?.get(1) ?: 0 },
    { it.bounds?.get(0) ?: 0 },
  )

  fun extract(
    root: TrailblazeNode,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
  ): List<ExtractedString> {
    val found = mutableListOf<ExtractedString>()
    collect(root, screenWidth, screenHeight, found)
    return found.dedupePreferringVisible()
  }

  /**
   * Legacy-capture overload for sessions whose logs predate [TrailblazeNode], which carry only the
   * Maestro-shaped tree. Narrower by necessity: that model has three text fields and no notion of
   * a viewport, so everything reads as onscreen.
   */
  fun extract(root: ViewHierarchyTreeNode): List<ExtractedString> = root.aggregate()
    .filterNot { it.resourceId?.startsWith(SYSTEM_UI_PACKAGE) == true }
    .flatMap { node ->
      val bounds = node.bounds?.let { listOf(it.x1, it.y1, it.width, it.height) }
      listOf(
        VisibleStringSource.TEXT to node.text.takeUnless { node.password },
        VisibleStringSource.CONTENT_DESCRIPTION to node.accessibilityText,
        VisibleStringSource.HINT to node.hintText,
      ).toExtractedStrings(ref = null, bounds = bounds, visible = true)
    }
    .dedupePreferringVisible()

  private fun collect(
    node: TrailblazeNode,
    screenWidth: Int,
    screenHeight: Int,
    found: MutableList<ExtractedString>,
  ) {
    val detail = node.driverDetail
    val offscreen = detail.hasViewportCoordinates() &&
      CompactElementListUtils.isOffscreen(node, screenHeight, screenWidth)
    if (!detail.isSystemUi() && (!detail.isHiddenFromUser() || detail.isScrolledAway(offscreen))) {
      found += detail.readableFields().toExtractedStrings(
        ref = node.ref,
        // Inverted bounds mean a Compose `graphicsLayer` transform that `boundsInRoot` excludes,
        // so `width`/`height` come out negative. Recording that would put a nonsense rectangle in
        // the file; the string itself is still real.
        bounds = node.bounds
          ?.takeUnless { CompactElementListUtils.hasInvertedBounds(node) }
          ?.let { listOf(it.left, it.top, it.width, it.height) },
        visible = !offscreen,
      )
    }
    node.children.forEach { collect(it, screenWidth, screenHeight, found) }
  }

  /**
   * The same label can appear both scrolled out of the viewport and on screen — a sticky header
   * over its own list row, say. Reading order puts the offscreen copy first, so dedupe on
   * visibility before position or a string a person is looking at gets filed as one they cannot see.
   */
  private fun List<ExtractedString>.dedupePreferringVisible(): List<ExtractedString> =
    sortedWith(compareByDescending<ExtractedString> { it.visible }.then(READING_ORDER))
      .distinctBy { it.text to it.source }
      .sortedWith(READING_ORDER)

  private fun List<Pair<VisibleStringSource, String?>>.toExtractedStrings(
    ref: String?,
    bounds: List<Int>?,
    visible: Boolean,
  ): List<ExtractedString> = mapNotNull { (source, raw) ->
    val text = raw?.normalizeWhitespace()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    ExtractedString(
      text = text,
      source = source,
      ref = ref,
      bounds = bounds,
      visible = visible,
      volatile = text.looksVolatile(),
    )
  }

  /**
   * A clock, a balance, or a counter: something that differs on every run and would bury the real
   * changes in a diff. Recorded as a hint, never as a reason to drop the string.
   *
   * The test is a digit plus no words except an English `AM`/`PM`, which catches `9:41`,
   * `9:41 PM`, `$12.00`, `1,234.56` and `12/25/2026` while leaving `2 items` alone.
   *
   * A localized meridiem or a month name is deliberately not detected — `5 de enero de 2026` is
   * recorded as ordinary copy. There is no locale data in this module, and the two errors are not
   * symmetric: a string wrongly marked volatile is a regression the diff will never report, while
   * one wrongly left alone is noise someone can see and ignore.
   */
  private fun String.looksVolatile(): Boolean {
    if (none { it.isDigit() }) return false
    return split(WHITESPACE_RUN)
      .filter { word -> word.any { it.isLetter() } }
      .all { word -> word.filter { it.isLetter() }.lowercase() in MERIDIEM }
  }

  /**
   * System chrome is the platform's copy, not the app's, and nobody translates it here.
   *
   * Only `AndroidAccessibility` carries the package outright. The other two Android details carry
   * it as the prefix of `resourceId` (`com.android.systemui:id/clock`), which is the same check
   * the legacy [ViewHierarchyTreeNode] overload makes — without it, the identical screen recorded
   * through Maestro or the view driver files the status bar clock as app copy.
   */
  private fun DriverNodeDetail.isSystemUi(): Boolean = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> packageName.isSystemUiId() || resourceId.isSystemUiId()
    is DriverNodeDetail.AndroidView -> resourceId.isSystemUiId()
    is DriverNodeDetail.AndroidMaestro -> resourceId.isSystemUiId()
    else -> false
  }

  private fun String?.isSystemUiId(): Boolean = this?.startsWith(SYSTEM_UI_PACKAGE) == true

  /**
   * Hides only the node itself, never its subtree: on Android a hidden container routinely holds
   * children the platform still reports as visible, and pruning there loses real strings.
   */
  private fun DriverNodeDetail.isHiddenFromUser(): Boolean = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> !isVisibleToUser
    is DriverNodeDetail.AndroidView -> !isShown
    is DriverNodeDetail.IosMaestro -> !visible
    else -> false
  }

  /**
   * Whether a hidden node is hidden only because it sits outside the viewport, in which case its
   * text is still the app's copy and [collect] keeps it as not visible.
   *
   * Only `isVisibleToUser` earns this reading. It is documented as covering "off-screen/hidden/
   * covered" — three situations in one flag, and geometry is what tells them apart. Every other
   * driver's flag means hidden in place: `AndroidView.isShown` is "visible and every ancestor
   * visible", which says nothing about scroll position, and `IosMaestro.visible` claims no viewport
   * semantics either. Overriding those with geometry would resurrect text that really is gone.
   */
  private fun DriverNodeDetail.isScrolledAway(offscreen: Boolean): Boolean =
    offscreen && this is DriverNodeDetail.AndroidAccessibility

  /**
   * Whether this driver's bounds can be compared against the device's dimensions at all.
   *
   * Web cannot: `PlaywrightTrailblazeNodeMapper` records `rect.y + scrollY`, which is a *page*
   * coordinate, while the dimensions passed in here are the viewport's. One viewport of scrolling
   * puts every on-screen label past `screenHeight`, so the geometry test would mark a whole page
   * `visible: false`. Reporting nothing as offscreen is the honest answer: the extractor has no
   * scroll offset to subtract, and a false `visible: false` is a string a reader stops trusting.
   */
  private fun DriverNodeDetail.hasViewportCoordinates(): Boolean = this !is DriverNodeDetail.Web

  /**
   * A password field contributes its label and never its value.
   *
   * An empty editable showing its placeholder is the other special case: Android returns the hint
   * string from `getText()` as well, which `resolveExistingEditableText` already has to work
   * around on the input path. Left alone it files one placeholder as two strings, `TEXT` and
   * `HINT`, and a diff then reports the same copy twice.
   */
  private fun DriverNodeDetail.readableFields(): List<Pair<VisibleStringSource, String?>> = when (this) {
    is DriverNodeDetail.AndroidAccessibility -> listOf(
      VisibleStringSource.TEXT to text.takeUnless { isPassword || isShowingHintText },
      VisibleStringSource.CONTENT_DESCRIPTION to contentDescription,
      VisibleStringSource.HINT to hintText,
      VisibleStringSource.STATE to stateDescription,
      VisibleStringSource.ERROR to error,
      VisibleStringSource.TOOLTIP to tooltipText,
      VisibleStringSource.LABELED_BY to labeledByText,
      VisibleStringSource.ROLE_DESCRIPTION to roleDescription,
      VisibleStringSource.PANE_TITLE to paneTitle,
    )

    is DriverNodeDetail.AndroidView -> listOf(
      VisibleStringSource.TEXT to text.takeUnless { isPassword },
      VisibleStringSource.CONTENT_DESCRIPTION to contentDescription,
      VisibleStringSource.HINT to hintText,
      VisibleStringSource.STATE to stateDescription,
      VisibleStringSource.ERROR to errorText,
    )

    is DriverNodeDetail.AndroidMaestro -> listOf(
      VisibleStringSource.TEXT to text.takeUnless { password },
      VisibleStringSource.CONTENT_DESCRIPTION to accessibilityText,
      VisibleStringSource.HINT to hintText,
    )

    is DriverNodeDetail.IosMaestro -> listOf(
      VisibleStringSource.TEXT to text.takeUnless { password },
      VisibleStringSource.CONTENT_DESCRIPTION to accessibilityText,
      VisibleStringSource.HINT to hintText,
    )

    // AXe reports a secure field's role as `AXSecureTextField` and leaves subrole null as often as
    // not, so reading only the subrole would put a password in the log.
    is DriverNodeDetail.IosAxe -> listOf(
      VisibleStringSource.TEXT to label,
      VisibleStringSource.VALUE to value
        .takeUnless { role == SECURE_TEXT_FIELD || subrole == SECURE_TEXT_FIELD },
      VisibleStringSource.TITLE to title,
      VisibleStringSource.HELP to help,
      VisibleStringSource.ROLE_DESCRIPTION to roleDescription,
    ) + customActions.map { VisibleStringSource.CUSTOM_ACTION to it }

    // `toggleableState` is left out on purpose: both collectors serialize it as a fixed English
    // `On`/`Off`/`Indeterminate` whatever the locale, so it is a state token, not copy. Read, it
    // would make every Compose toggle identical across two locales and get the screen reported as
    // untranslated. `stateDescription` is the app-authored field beside it.
    is DriverNodeDetail.Compose -> listOf(
      VisibleStringSource.TEXT to text.takeUnless { isPassword },
      VisibleStringSource.EDITABLE_TEXT to editableText.takeUnless { isPassword },
      VisibleStringSource.CONTENT_DESCRIPTION to contentDescription,
      VisibleStringSource.STATE to stateDescription,
      VisibleStringSource.PANE_TITLE to paneTitle,
      VisibleStringSource.ERROR to errorText,
    )

    // `ariaDescriptor` is Playwright's locator string (`button "Submit"`), not copy: it
    // restates `ariaName` wrapped in a role, so reading it would double every Web string.
    is DriverNodeDetail.Web -> listOf(
      VisibleStringSource.TEXT to ariaName,
    )
  }

  private fun String.normalizeWhitespace(): String =
    trim().split(WHITESPACE_RUN).joinToString(" ")
}
