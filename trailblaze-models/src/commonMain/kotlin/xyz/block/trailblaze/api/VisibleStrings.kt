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
  /**
   * `[left, top, right, bottom]` in the device's screen coordinates — the corners
   * [TrailblazeNode.Bounds] stores — or null when the capture had no bounds.
   */
  val bounds: List<Int>? = null,
  /** False when the element sits outside the viewport. Recorded, not dropped: an untranslated
   *  string below the fold is still a regression. Recorded at capture because it depends on the
   *  viewport at that moment, which nothing can reconstruct later. */
  val visible: Boolean = true,
)

/**
 * Reads the strings on one screen capture out of its view tree.
 *
 * Runs as each capture log is emitted, on the device or the host (see `withVisibleStrings`), so
 * the strings travel on the log; a log recorded before that can still be read the same way later.
 * The strings are the elements the agent's element list showed — the same list the LLM and the
 * CLI `snapshot` were given — built over the same tree and device size, so the two cannot
 * disagree about what was on screen. Each element contributes its full text from its own fields,
 * never the list's rendering, which truncates long labels.
 *
 * Elements the list shows only when asked for offscreen content are kept with
 * [ExtractedString.visible] set to false, because an untranslated string below the fold is still
 * a regression. An element the list hides for any other reason — covered, hidden in place, or
 * judged structural — contributes nothing. The Android list judges offscreen by height alone, so a
 * node scrolled sideways out of view is reported visible, as the agent was shown it.
 *
 * This applies to iOS AXe, iOS Maestro and the three Android drivers, whose lists live in this
 * module. Compose and Web trees have no list here and keep a direct walk of the tree with the same
 * hidden, system-UI and offscreen rules the lists apply.
 */
object VisibleStringExtractor {

  private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
  private const val SECURE_TEXT_FIELD = "AXSecureTextField"
  private val WHITESPACE_RUN = Regex("\\s+")

  /** Reading order: top to bottom, then left to right. Boundless nodes keep tree order, last. */
  private val READING_ORDER = compareBy<ExtractedString>(
    { it.bounds == null },
    { it.bounds?.get(1) ?: 0 },
    { it.bounds?.get(0) ?: 0 },
  )

  fun extract(root: TrailblazeNode, screenWidth: Int = 0, screenHeight: Int = 0): List<ExtractedString> =
    extract(root, screenWidth, screenHeight, frames = emptyList())

  /**
   * [extract] for a web page with iframes, each frame a tree of its own in the page's coordinates.
   * The frames are read with the page and deduped with it, so a label on both reads once, as a
   * repeat within one tree does. An overload rather than a defaulted parameter, so the
   * three-argument method compiled clients call stays in the ABI.
   */
  fun extract(
    root: TrailblazeNode,
    screenWidth: Int,
    screenHeight: Int,
    frames: List<TrailblazeNode>,
  ): List<ExtractedString> {
    val found = mutableListOf<ExtractedString>()
    for (tree in listOf(root) + frames) {
      val shown = ElementListSelection.of(tree, screenWidth, screenHeight)
      if (shown != null) {
        collectShown(tree, shown, screenWidth, screenHeight, found)
      } else {
        collect(tree, screenWidth, screenHeight, found)
      }
    }
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
      val bounds = node.bounds?.let { listOf(it.x1, it.y1, it.x2, it.y2) }
      listOf(
        VisibleStringSource.TEXT to node.text.takeUnless { node.password },
        VisibleStringSource.CONTENT_DESCRIPTION to node.accessibilityText,
        VisibleStringSource.HINT to node.hintText,
      ).toExtractedStrings(ref = null, bounds = bounds, visible = true)
    }
    .dedupePreferringVisible()

  /**
   * The node ids the element list printed for a tree: [onScreen] in its default view, and
   * [offscreenOnly] only once `SnapshotDetail.OFFSCREEN` is asked for. Null for a driver with no
   * list in this module.
   *
   * The capture-time screen states build the list with no details, so the default view is exactly
   * what the agent was shown. The driver is read off the root, as the builders do: a tree comes
   * from one driver.
   */
  private class ElementListSelection(val onScreen: Set<Long>, val offscreenOnly: Set<Long>) {
    companion object {
      fun of(root: TrailblazeNode, screenWidth: Int, screenHeight: Int): ElementListSelection? {
        val shownBy: (Set<SnapshotDetail>) -> List<Long> = when (root.driverDetail) {
          is DriverNodeDetail.IosAxe -> { details ->
            IosAxeCompactElementList.build(root, details, screenHeight, screenWidth).elementNodeIds
          }
          is DriverNodeDetail.IosMaestro -> { details ->
            IosCompactElementList.build(root, details, screenHeight, screenWidth)
              .let { it.elementNodeIds + it.textNodeIds }
          }
          is DriverNodeDetail.AndroidAccessibility,
          is DriverNodeDetail.AndroidView,
          is DriverNodeDetail.AndroidMaestro,
          -> { details ->
            AndroidCompactElementList.build(root, details, screenHeight, screenWidth)
              .let { it.elementNodeIds + it.textNodeIds }
          }
          else -> return null
        }
        val onScreen = shownBy(emptySet()).toSet()
        return ElementListSelection(
          onScreen = onScreen,
          offscreenOnly = shownBy(setOf(SnapshotDetail.OFFSCREEN)).toSet() - onScreen,
        )
      }
    }
  }

  /**
   * Emits every node the element list showed.
   *
   * The offscreen view also admits nodes the platform hides in place (an Android node covered by
   * a dialog is `isVisibleToUser = false` on screen), so an offscreen-only node is kept only when
   * its bounds really are outside the viewport and its driver's hidden flag can mean scrolled away
   * — the same reading [isScrolledAway] gives the direct walk. System UI stays filtered because
   * the Android list recognises it only by package, and an accessibility node can name it through
   * its resource id alone.
   */
  private fun collectShown(
    node: TrailblazeNode,
    shown: ElementListSelection,
    screenWidth: Int,
    screenHeight: Int,
    found: MutableList<ExtractedString>,
  ) {
    val detail = node.driverDetail
    val visible = when (node.nodeId) {
      in shown.onScreen -> true
      in shown.offscreenOnly -> {
        val offscreen = CompactElementListUtils.isOffscreen(node, screenHeight, screenWidth)
        if (offscreen && (!detail.isHiddenFromUser() || detail.isScrolledAway(offscreen))) false else null
      }
      else -> null
    }
    if (visible != null && !detail.isSystemUi()) found += node.readableStrings(visible)
    node.children.forEach { collectShown(it, shown, screenWidth, screenHeight, found) }
  }

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
      found += node.readableStrings(visible = !offscreen)
    }
    node.children.forEach { collect(it, screenWidth, screenHeight, found) }
  }

  private fun TrailblazeNode.readableStrings(visible: Boolean): List<ExtractedString> =
    driverDetail.readableFields().toExtractedStrings(
      ref = ref,
      // Inverted bounds mean a Compose `graphicsLayer` transform that `boundsInRoot` excludes,
      // so `width`/`height` come out negative. Recording that would put a nonsense rectangle in
      // the file; the string itself is still real.
      bounds = bounds
        ?.takeUnless { CompactElementListUtils.hasInvertedBounds(this) }
        ?.let { listOf(it.left, it.top, it.right, it.bottom) },
      visible = visible,
    )

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
    )
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
   * text is still the app's copy and is kept as not visible.
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
    //
    // `roleDescription` is left out: on AXe it is the system's name for the role ("button",
    // "group"), not app copy, and the element list shows the element type in its place. Android's
    // `roleDescription` is app-authored and stays.
    is DriverNodeDetail.IosAxe -> listOf(
      VisibleStringSource.TEXT to label,
      VisibleStringSource.VALUE to value
        .takeUnless { role == SECURE_TEXT_FIELD || subrole == SECURE_TEXT_FIELD },
      VisibleStringSource.TITLE to title,
      VisibleStringSource.HELP to help,
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
    // restates `ariaName` wrapped in a role, so reading it would double every Web string. `url`
    // is an address, not copy. A field with no label takes its placeholder as its name, so the
    // placeholder is read as a hint only when it says something the name does not.
    is DriverNodeDetail.Web -> listOf(
      VisibleStringSource.TEXT to ariaName,
      VisibleStringSource.HINT to placeholder.takeUnless { it == ariaName },
      VisibleStringSource.TITLE to title,
    )
  }

  private fun String.normalizeWhitespace(): String =
    trim().split(WHITESPACE_RUN).joinToString(" ")
}

/**
 * A clock, a balance, or a counter: text that differs on every run and would bury the real changes
 * in a diff. Judged when the strings are read, never stored with them, so a better rule applies to
 * every session already recorded.
 *
 * The test is a digit plus no words except an English `AM`/`PM`, which catches `9:41`, `9:41 PM`,
 * `$12.00`, `1,234.56` and `12/25/2026` while leaving `2 items` alone.
 *
 * A localized meridiem or a month name is deliberately not detected — `5 de enero de 2026` reads as
 * ordinary copy. There is no locale data in this module, and the two errors are not symmetric: a
 * string wrongly judged volatile is a regression a diff will never report, while one wrongly left
 * alone is noise someone can see and ignore.
 *
 * The report's Strings tab applies the same rule in TypeScript (`looksVolatile` in
 * `run-report-visible-strings.ts`); keep the two in step.
 */
object VolatileText {

  private val WORD_SPLIT = Regex("\\s+")
  private val MERIDIEM = setOf("am", "pm")

  fun looksVolatile(text: String): Boolean {
    if (text.none { it.isDigit() }) return false
    return text.split(WORD_SPLIT)
      .filter { word -> word.any { it.isLetter() } }
      .all { word -> word.filter { it.isLetter() }.lowercase() in MERIDIEM }
  }
}
