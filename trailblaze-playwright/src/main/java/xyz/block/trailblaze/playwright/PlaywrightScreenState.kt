package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DeviceInfoPrefix
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.SnapshotDetail
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.setofmark.SetOfMarkAnnotator
import xyz.block.trailblaze.tracing.TrailblazeTracer
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * ScreenState implementation for Playwright-native web testing.
 *
 * Captures the page state via Playwright's screenshot API and ARIA snapshot.
 * The ARIA snapshot YAML is parsed into [ViewHierarchyTreeNode] for backward
 * compatibility with logging, while [viewHierarchyTextRepresentation] provides
 * a compact, ID-based element list optimized for LLM consumption.
 *
 * The compact format filters out structural container nodes and assigns stable
 * element IDs (e.g., "e1", "e2") that tools can resolve back to Playwright locators
 * via [elementIdMapping].
 *
 * @param screenshotScalingConfig Controls how screenshots are scaled and compressed.
 *   Applied consistently to all screenshot output (logging, LLM requests, snapshots).
 * @param requestedDetails Detail types requested by the LLM for this snapshot.
 *   When non-empty, the [viewHierarchyTextRepresentation] will be enriched with the
 *   requested information (e.g., bounding boxes). Automatically cleared after consumption
 *   by [PlaywrightBrowserManager] so subsequent turns return to the compact default.
 */
class PlaywrightScreenState(
  private val page: Page,
  private val viewportWidth: Int,
  private val viewportHeight: Int,
  private val browserEngine: BrowserEngine = BrowserEngine.CHROMIUM,
  private val tabContext: TabContext? = null,
  private val screenshotScalingConfig: ScreenshotScalingConfig? = ScreenshotScalingConfig.DEFAULT,
  private val requestedDetails: Set<ViewHierarchyDetail> = emptySet(),
  private val captureTimeoutMs: Double = CAPTURE_TIMEOUT_MS,
) : ScreenState {

  /** The raw ARIA snapshot YAML - preferred format for web LLM prompts. */
  val ariaSnapshotYaml: String by lazy {
    TrailblazeTracer.trace("ariaSnapshot", "screenState") {
      PlaywrightAriaSnapshot.captureAriaSnapshot(page, ARIA_SNAPSHOT_TIMEOUT_MS).yaml
    }
  }

  /** Compact ARIA elements with ID mapping, built lazily from the ARIA snapshot. */
  private val compactAriaElements: PlaywrightAriaSnapshot.CompactAriaElements by lazy {
    PlaywrightAriaSnapshot.buildCompactElementList(ariaSnapshotYaml)
  }

  /**
   * Maps element IDs (e.g., "e1") to their [PlaywrightAriaSnapshot.ElementRef] which
   * includes the ARIA descriptor and occurrence index for disambiguation.
   *
   * Used by Playwright tools to resolve LLM-provided element IDs back to
   * precise Playwright locators via [PlaywrightAriaSnapshot.resolveElementRef].
   */
  val elementIdMapping: Map<String, PlaywrightAriaSnapshot.ElementRef> by lazy {
    compactAriaElements.elementIdMapping
  }

  /**
   * Compact, plain-text element list sent to the LLM instead of JSON-serialized
   * [ViewHierarchyTreeNode]. Includes a dynamic page context header followed by
   * meaningful/interactive elements with IDs.
   *
   * The page context header is prepended automatically every turn:
   * ```
   * Browser: Chromium (desktop)
   * URL: https://example.com/dashboard
   * Title: Dashboard - My App
   * Tabs: viewing tab 2 of 3
   *   Tab 1: Home - My App
   *   Tab 3: Settings
   * Viewport: 1280x800
   * Scroll: 450px of 2000px total height (28% scrolled)
   * Focused: textbox "Search"
   * Dialog open: "Confirm deletion"
   * ```
   *
   * When [requestedDetails] is empty (default), produces the compact format:
   * ```
   * Browser: Chromium (desktop)
   * URL: https://example.com/
   * Title: My App
   * Viewport: 1280x800
   * Scroll: page fits in viewport (no scrolling needed)
   *
   * navigation:
   *   [e1] link "Home"
   *   [e2] link "About"
   * main:
   *   [e3] heading "Welcome"
   *   [e4] textbox "Email"
   *   [e5] button "Submit"
   * ```
   *
   * When [ViewHierarchyDetail.BOUNDS] is requested, enriches each element with
   * bounding box coordinates from Playwright's layout engine:
   * ```
   * navigation:
   *   [e1] link "Home" {x:50,y:10,w:80,h:24}
   *   [e2] link "About" {x:140,y:10,w:90,h:24}
   * main:
   *   [e3] heading "Welcome" {x:100,y:80,w:400,h:48}
   *   [e4] textbox "Email" {x:100,y:200,w:300,h:40}
   *   [e5] button "Submit" {x:120,y:260,w:200,h:40}
   * ```
   */
  override val viewHierarchyTextRepresentation: String by lazy {
    renderTextRepresentation(requestedDetails)
  }

  /**
   * Re-renders the text representation with ad-hoc [details] supplied at call time.
   *
   * The constructor's [requestedDetails] still controls the lazily-cached
   * [viewHierarchyTextRepresentation] for the agent path, but the snapshot CLI
   * (`trailblaze snapshot --bounds --offscreen --all`) supplies its flags after
   * the screen state has already been captured. This override lets that path
   * land enrichment without re-capturing the page — the underlying ARIA
   * snapshot and the live [page] are both reusable.
   *
   * [SnapshotDetail.BOUNDS] maps directly to [ViewHierarchyDetail.BOUNDS].
   * [SnapshotDetail.OFFSCREEN] maps to [ViewHierarchyDetail.OFFSCREEN_ELEMENTS].
   * [SnapshotDetail.OCCLUDED] maps to [ViewHierarchyDetail.OCCLUDED_ELEMENTS].
   * [SnapshotDetail.ALL_ELEMENTS] maps to [ViewHierarchyDetail.CSS_SELECTORS] —
   * Playwright's compact list already shows interactive + content-bearing nodes,
   * so "show me everything" is most usefully interpreted as "surface DOM elements
   * that aren't in the ARIA tree" (which is what CSS_SELECTORS does).
   */
  override fun viewHierarchyTextRepresentation(details: Set<SnapshotDetail>): String {
    if (details.isEmpty()) return viewHierarchyTextRepresentation
    val translated = buildSet {
      if (SnapshotDetail.BOUNDS in details) add(ViewHierarchyDetail.BOUNDS)
      if (SnapshotDetail.OFFSCREEN in details) add(ViewHierarchyDetail.OFFSCREEN_ELEMENTS)
      if (SnapshotDetail.OCCLUDED in details) add(ViewHierarchyDetail.OCCLUDED_ELEMENTS)
      if (SnapshotDetail.ALL_ELEMENTS in details) add(ViewHierarchyDetail.CSS_SELECTORS)
    }
    if (translated.isEmpty()) return viewHierarchyTextRepresentation
    return renderTextRepresentation(translated)
  }

  private fun renderTextRepresentation(details: Set<ViewHierarchyDetail>): String {
    return TrailblazeTracer.trace("viewHierarchyTextRepresentation", "screenState") {
      val baseText = if (details.isEmpty()) {
        compactAriaElements.text
      } else {
        enrichCompactElementList(compactAriaElements, details)
      }
      val includeOffscreen = ViewHierarchyDetail.OFFSCREEN_ELEMENTS in details
      val includeOccluded = ViewHierarchyDetail.OCCLUDED_ELEMENTS in details
      val elementsText = TrailblazeTracer.trace("handleHiddenElements", "screenState") {
        handleHiddenElements(baseText, compactAriaElements, includeOffscreen, includeOccluded)
      }
      val pageContext = TrailblazeTracer.trace("buildPageContextHeader", "screenState") {
        buildPageContextHeader()
      }
      if (pageContext.isNotEmpty()) "$pageContext\n\n$elementsText" else elementsText
    }
  }

  /**
   * Builds a context header with dynamic page metadata for the LLM.
   *
   * Provides a complete snapshot of the browser environment on every turn:
   * - **Browser**: Engine name and viewport size class (e.g., "Chromium (desktop)")
   * - **URL**: The current page URL (helps the LLM understand which page it's on)
   * - **Title**: The page's `<title>` element (quick summary of page state)
   * - **Tabs**: Which tab is active and what's in the other tabs (only shown when
   *   multiple tabs are open — helps the LLM understand popup/new-tab scenarios)
   * - **Viewport**: Exact pixel dimensions of the browser viewport
   * - **Scroll**: Current scroll position and total page height (helps the LLM
   *   understand how much content is above/below the viewport without guessing)
   * - **Page state**: Loading indicator when the page hasn't fully loaded yet (only
   *   shown when `document.readyState` is not "complete" — helps the LLM decide
   *   whether to wait before interacting)
   * - **Focused**: Which element currently has keyboard focus (helps the LLM know
   *   if a textbox is ready for input without needing to click first)
   * - **Dialog open**: Names of any visible `<dialog>` elements or ARIA `role="dialog"`
   *   overlays (helps the LLM prioritize dismissing or interacting with the dialog
   *   before trying to reach elements behind it)
   *
   * Page state, focused element, and dialog detection are captured in a single
   * JavaScript evaluation to minimize browser round-trips.
   *
   * Each field is captured safely — if any fails (e.g., page is navigating),
   * that field is simply omitted. Returns empty string if nothing could be captured.
   */
  private fun buildPageContextHeader(): String = buildString {
    // Browser engine and viewport size class
    appendLine(DeviceInfoPrefix.BROWSER.line("${browserEngine.displayName} ($viewportSizeClass)"))

    // Current URL
    try {
      val url = page.url()
      if (!url.isNullOrBlank() && url != "about:blank") {
        appendLine("URL: $url")
      }
    } catch (_: Exception) {}

    // Page title
    try {
      val title = page.title()
      if (!title.isNullOrBlank()) {
        appendLine("Title: $title")
      }
    } catch (_: Exception) {}

    // Tab context — show which tab is active and list other open tabs
    if (tabContext != null && tabContext.tabs.size > 1) {
      val tabNum = tabContext.activeTabIndex + 1
      appendLine("Tabs: viewing tab $tabNum of ${tabContext.tabs.size}")
      for ((index, tab) in tabContext.tabs.withIndex()) {
        if (index == tabContext.activeTabIndex) continue // skip current tab (already shown via URL/Title)
        val tabLabel = tab.title.ifBlank { tab.url.ifBlank { "blank" } }
        appendLine("  Tab ${index + 1}: $tabLabel")
      }
    }

    // Viewport size — alongside scroll info for easy LLM reference
    appendLine(DeviceInfoPrefix.VIEWPORT.line("${viewportWidth}x${viewportHeight}"))

    // Scroll position and total page height
    try {
      @Suppress("UNCHECKED_CAST")
      val scrollInfo = page.evaluate(
        """() => ({
          scrollY: Math.round(window.scrollY),
          totalHeight: Math.round(document.documentElement.scrollHeight),
          viewportHeight: window.innerHeight
        })""",
      ) as? Map<String, Any?>
      if (scrollInfo != null) {
        val scrollY = (scrollInfo["scrollY"] as? Number)?.toInt() ?: 0
        val totalHeight = (scrollInfo["totalHeight"] as? Number)?.toInt() ?: 0
        val vpHeight = (scrollInfo["viewportHeight"] as? Number)?.toInt() ?: viewportHeight
        if (totalHeight > vpHeight) {
          // Page is scrollable — show position context
          val percentScrolled = if (totalHeight - vpHeight > 0) {
            ((scrollY.toFloat() / (totalHeight - vpHeight)) * 100).toInt().coerceIn(0, 100)
          } else {
            0
          }
          appendLine("Scroll: ${scrollY}px of ${totalHeight}px total height ($percentScrolled% scrolled)")
        } else {
          appendLine("Scroll: page fits in viewport (no scrolling needed)")
        }
      }
    } catch (_: Exception) {}

    // Page state, focused element, and dialog detection — captured in a single JS evaluate
    // to minimize round-trips to the browser.
    try {
      @Suppress("UNCHECKED_CAST")
      val pageState = page.evaluate(
        """() => {
          // Loading / ready state
          const readyState = document.readyState;

          // Focused element
          let focus = null;
          const el = document.activeElement;
          if (el && el !== document.body && el !== document.documentElement) {
            const tag = el.tagName.toLowerCase();
            const role = el.getAttribute('role') || '';
            const type = el.getAttribute('type') || '';
            const name = el.getAttribute('aria-label')
              || el.getAttribute('name')
              || el.getAttribute('placeholder')
              || '';
            focus = { tag, role, type, name };
          }

          // Open dialogs / modals
          const dialogs = [];
          // Native <dialog open> elements
          document.querySelectorAll('dialog[open]').forEach(d => {
            const label = d.getAttribute('aria-label') || d.querySelector('h1,h2,h3,h4,h5,h6')?.textContent?.trim() || '';
            dialogs.push(label || 'unnamed dialog');
          });
          // ARIA role="dialog" or role="alertdialog" elements that are visible
          document.querySelectorAll('[role="dialog"], [role="alertdialog"]').forEach(d => {
            if (d.tagName.toLowerCase() === 'dialog') return; // already captured
            const style = window.getComputedStyle(d);
            if (style.display === 'none' || style.visibility === 'hidden') return;
            const rect = d.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) return;
            const label = d.getAttribute('aria-label')
              || d.querySelector('h1,h2,h3,h4,h5,h6')?.textContent?.trim()
              || '';
            dialogs.push(label || 'unnamed dialog');
          });

          return { readyState, focus, dialogs };
        }""",
      ) as? Map<String, Any?>
      if (pageState != null) {
        // Loading state
        val readyState = pageState["readyState"] as? String
        if (readyState != null && readyState != "complete") {
          appendLine("Page state: $readyState (still loading)")
        }

        // Focused element
        @Suppress("UNCHECKED_CAST")
        val focusInfo = pageState["focus"] as? Map<String, Any?>
        if (focusInfo != null) {
          val tag = focusInfo["tag"] as? String ?: ""
          val role = focusInfo["role"] as? String ?: ""
          val type = focusInfo["type"] as? String ?: ""
          val name = focusInfo["name"] as? String ?: ""
          val descriptor = buildString {
            append(role.ifBlank { tag })
            if (type.isNotBlank()) append(" type=$type")
            if (name.isNotBlank()) append(" \"$name\"")
          }
          appendLine("Focused: $descriptor")
        }

        // Open dialogs
        @Suppress("UNCHECKED_CAST")
        val dialogs = pageState["dialogs"] as? List<String>
        if (!dialogs.isNullOrEmpty()) {
          val dialogList = dialogs.joinToString(", ") { "\"$it\"" }
          appendLine("Dialog open: $dialogList")
        }
      }
    } catch (_: Exception) {}
  }.trimEnd()

  /**
   * Resolves an element ID (e.g., "e5") to its [PlaywrightAriaSnapshot.ElementRef].
   * Returns null if the ID is not found in the current snapshot's mapping.
   */
  fun resolveElementId(elementId: String): PlaywrightAriaSnapshot.ElementRef? {
    // Handle both "e5" and "[e5]" formats
    val normalizedId = elementId.trim().removePrefix("[").removeSuffix("]")
    return elementIdMapping[normalizedId]
  }

  /**
   * Filters or annotates elements in the compact list based on visibility signals
   * from [elementVisibility] — currently offscreen (outside the viewport) and
   * occluded (in-viewport but visually covered by another element).
   *
   * - **Default** (neither detail flag set): both offscreen and occluded element
   *   lines are *removed*, empty landmark headers cleaned up, and a summary line
   *   is appended saying how many of each kind were hidden plus the detail flags
   *   to request them back. This matches the offscreen-only behavior we shipped
   *   originally, extended to also drop occluded refs — the LLM can't click
   *   either kind, and listing them is misleading.
   * - **[ViewHierarchyDetail.OFFSCREEN_ELEMENTS] requested**: offscreen lines are
   *   kept with `(offscreen)` annotation, occluded lines are still filtered.
   * - **[ViewHierarchyDetail.OCCLUDED_ELEMENTS] requested**: occluded lines are
   *   kept with `(occluded)` annotation, offscreen lines are still filtered.
   * - **Both requested**: every element line is kept with the appropriate
   *   annotation.
   *
   * Elements whose visibility can't be determined (role/name mismatch between
   * Playwright's ARIA snapshot and the simplified JS DOM walk) are left unchanged
   * — the safe default, since the LLM can still see them in the screenshot.
   */
  private fun handleHiddenElements(
    text: String,
    compact: PlaywrightAriaSnapshot.CompactAriaElements,
    includeOffscreen: Boolean,
    includeOccluded: Boolean,
  ): String {
    if (compact.elementIdMapping.isEmpty()) return text

    val visibility = elementVisibility
    val offscreenIds = visibility.offscreen
    val occludedIds = visibility.occluded

    if (offscreenIds.isEmpty() && occludedIds.isEmpty()) return text

    // Annotation pass: tag every kept line with its visibility status.
    val annotatedLines = text.lines().map { line ->
      val match = ELEMENT_ID_PATTERN.find(line) ?: return@map line
      val elementId = "e${match.groupValues[1]}"
      when (elementId) {
        in offscreenIds -> if (includeOffscreen) "$line (offscreen)" else null
        in occludedIds -> if (includeOccluded) "$line (occluded)" else null
        else -> line
      }
    }

    // Filter pass: drop null-marked lines (the ones we're hiding).
    val filteredLines = annotatedLines.filterNotNull().toMutableList()

    // Remove landmark header lines that no longer have children. Loop until stable
    // to handle nested landmarks where the parent only becomes empty after the
    // child's removal.
    var result = filteredLines
    var changed = true
    while (changed) {
      changed = false
      val pass = mutableListOf<String>()
      for (j in result.indices) {
        val line = result[j]
        if (LANDMARK_HEADER_PATTERN.matches(line)) {
          val nextContentLine = result.subList(j + 1, result.size)
            .firstOrNull { it.isNotBlank() }
          if (nextContentLine == null || !nextContentLine.startsWith(line.takeWhile { it == ' ' } + "  ")) {
            changed = true
            continue
          }
        }
        pass.add(line)
      }
      result = pass
    }

    val hiddenOffscreen = if (!includeOffscreen) offscreenIds.size else 0
    val hiddenOccluded = if (!includeOccluded) occludedIds.size else 0
    if (hiddenOffscreen > 0) {
      result.add("($hiddenOffscreen offscreen elements hidden — request OFFSCREEN_ELEMENTS for full list)")
    }
    if (hiddenOccluded > 0) {
      result.add("($hiddenOccluded occluded elements hidden — request OCCLUDED_ELEMENTS for full list)")
    }
    return result.joinToString("\n")
  }

  /**
   * Per-element visibility data computed once per snapshot via [BATCH_VIEWPORT_CHECK_JS].
   *
   * Three signals collapsed into a single CDP round-trip:
   * - [offscreen]: element's bbox is outside the viewport.
   * - [occluded]: element is in-viewport but visually covered by something on top
   *   ([document.elementFromPoint](https://developer.mozilla.org/en-US/docs/Web/API/Document/elementFromPoint) — the same hit-test
   *   Playwright's `click()` actionability check runs internally).
   * - [bounds]: viewport-relative bbox per id, so [annotationElements] can drop its
   *   per-element CDP `locator.boundingBox()` calls.
   *
   * [occlusionReliable] is false on canvas-rendered pages (Compose Web Wasm and friends)
   * where `elementFromPoint` always returns the canvas / body / html — in that case
   * we keep bounds + offscreen and skip occlusion filtering entirely.
   */
  private data class ElementVisibility(
    val offscreen: Set<String>,
    val occluded: Set<String>,
    val bounds: Map<String, TrailblazeNode.Bounds>,
    val occlusionReliable: Boolean,
  ) {
    companion object {
      val EMPTY = ElementVisibility(emptySet(), emptySet(), emptyMap(), occlusionReliable = false)
    }
  }

  /**
   * Lazily-computed visibility data, shared between [annotationElements] (image overlay)
   * and [handleHiddenElements] (text view filtering). The lazy property ensures the
   * batched JS call only runs once per [PlaywrightScreenState], even when both the
   * screenshot and the text representation are accessed.
   */
  private val elementVisibility: ElementVisibility by lazy {
    computeElementVisibility(compactAriaElements)
  }

  @Suppress("UNCHECKED_CAST")
  private fun computeElementVisibility(
    compact: PlaywrightAriaSnapshot.CompactAriaElements,
  ): ElementVisibility {
    if (compact.elementIdMapping.isEmpty()) return ElementVisibility.EMPTY
    return try {
      val elements = compact.elementIdMapping.map { (id, ref) ->
        val (role, name) = parseAriaDescriptor(ref.descriptor)
        mapOf("id" to id, "role" to role, "name" to name, "nth" to ref.nthIndex)
      }
      val result = page.evaluate(
        BATCH_VIEWPORT_CHECK_JS,
        mapOf("elements" to elements, "vw" to viewportWidth, "vh" to viewportHeight),
      ) as? Map<String, Any?> ?: return ElementVisibility.EMPTY

      val offscreen = (result["offscreen"] as? List<String>)?.toSet() ?: emptySet()
      val occluded = (result["occluded"] as? List<String>)?.toSet() ?: emptySet()
      val occlusionReliable = (result["occlusionReliable"] as? Boolean) ?: false
      val rawBounds = result["bounds"] as? Map<String, Map<String, Any?>> ?: emptyMap()
      val bounds = rawBounds.mapNotNull { (id, b) ->
        val x = (b["x"] as? Number)?.toInt()
        val y = (b["y"] as? Number)?.toInt()
        val w = (b["w"] as? Number)?.toInt()
        val h = (b["h"] as? Number)?.toInt()
        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) null
        else id to TrailblazeNode.Bounds(left = x, top = y, right = x + w, bottom = y + h)
      }.toMap()

      ElementVisibility(offscreen, occluded, bounds, occlusionReliable)
    } catch (_: Exception) {
      ElementVisibility.EMPTY
    }
  }

  /**
   * Parses a compact element descriptor into its role and name components.
   *
   * Handles three formats produced by [PlaywrightAriaSnapshot.buildAriaDescriptor]:
   * - `button "Submit"` → ("button", "Submit")
   * - `text: Hello world` → ("text", "Hello world")
   * - `navigation` → ("navigation", null)
   */
  private fun parseAriaDescriptor(descriptor: String): Pair<String, String?> {
    // text: Hello world
    if (descriptor.startsWith("text: ")) {
      return "text" to descriptor.removePrefix("text: ")
    }
    // button "Submit"
    val quoteIdx = descriptor.indexOf('"')
    if (quoteIdx > 0) {
      val role = descriptor.substring(0, quoteIdx).trim()
      val name = descriptor.substring(quoteIdx + 1).removeSuffix("\"")
      return role to name
    }
    // navigation
    return descriptor to null
  }

  /**
   * Enriches the compact element list with details requested by the LLM.
   *
   * Supports multiple enrichment types that can be combined:
   * - [ViewHierarchyDetail.BOUNDS]: Appends `{x,y,w,h}` bounding box coordinates.
   * - [ViewHierarchyDetail.CSS_SELECTORS]: Appends `[css=...]` selectors and surfaces
   *   elements that are normally hidden in the compact list (e.g., unnamed `<div>`s
   *   with `id` or `data-testid` attributes).
   */
  private fun enrichCompactElementList(
    compact: PlaywrightAriaSnapshot.CompactAriaElements,
    details: Set<ViewHierarchyDetail> = requestedDetails,
  ): String {
    val enrichBounds = ViewHierarchyDetail.BOUNDS in details
    val enrichCss = ViewHierarchyDetail.CSS_SELECTORS in details

    if (!enrichBounds && !enrichCss) {
      return compact.text
    }

    // Build per-element annotations in a single pass
    val annotationsByElementId: Map<String, String> =
      compact.elementIdMapping.mapValues { (_, elementRef) ->
        try {
          val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
          val parts = mutableListOf<String>()

          if (enrichBounds) {
            val box = locator.boundingBox(Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs))
            if (box != null) {
              parts.add("{x:${box.x.toInt()},y:${box.y.toInt()},w:${box.width.toInt()},h:${box.height.toInt()}}")
            }
          }

          if (enrichCss) {
            val cssSelector = buildCssSelectorForLocator(locator)
            if (cssSelector != null) {
              parts.add("[css=$cssSelector]")
            }
          }

          parts.joinToString(" ")
        } catch (_: Exception) {
          ""
        }
      }

    // Rebuild existing lines with annotations
    val enrichedLines = compact.text.lines().map { line ->
      val match = ELEMENT_ID_PATTERN.find(line) ?: return@map line
      val elementId = "e${match.groupValues[1]}"
      val annotation = annotationsByElementId[elementId]
      if (!annotation.isNullOrEmpty()) "$line $annotation" else line
    }.toMutableList()

    // When CSS_SELECTORS is requested, also surface hidden elements
    // (generic/structural nodes that were filtered out of the compact list)
    if (enrichCss) {
      val hiddenElements = discoverHiddenCssTargetableElements(compact)
      if (hiddenElements.isNotEmpty()) {
        enrichedLines.add("")
        enrichedLines.add(
          "-- Additional elements (no ARIA semantics, targetable via css= prefix) " +
            "[viewport: ${viewportWidth}x${viewportHeight}] --",
        )
        enrichedLines.addAll(hiddenElements)
      }
    }

    return enrichedLines.joinToString("\n")
  }

  /**
   * Builds the best CSS selector for a Playwright locator's underlying DOM element.
   *
   * Prefers `#id` selectors, then `[data-testid="..."]`, then returns null if
   * no stable CSS selector is available. This avoids generating fragile class-based
   * or structural selectors that would break across sessions.
   */
  private fun buildCssSelectorForLocator(locator: com.microsoft.playwright.Locator): String? {
    return try {
      val result = locator.first().evaluate(
        """el => {
          if (el.id) return '#' + CSS.escape(el.id);
          const testId = el.getAttribute('data-testid');
          if (testId) return '[data-testid="' + CSS.escape(testId) + '"]';
          const testId2 = el.getAttribute('data-test-id');
          if (testId2) return '[data-test-id="' + CSS.escape(testId2) + '"]';
          return null;
        }""",
      )
      result as? String
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Discovers DOM elements that were filtered out of the compact ARIA element list
   * but have stable CSS selectors (id or data-testid).
   *
   * These are typically `<div>`, `<span>`, or other generic elements that:
   * - Have no ARIA role or accessible name (so Playwright's ARIA snapshot ignores them)
   * - But have an HTML `id`, `data-testid`, or `data-test-id` attribute
   * - And are visible on the page (rendered with non-zero dimensions)
   *
   * **Scope**: Queries the entire DOM, not just the viewport. Elements outside the
   * viewport are included and annotated with `(offscreen)` so the LLM knows to scroll.
   *
   * **Filtering**: Applies multiple heuristics to keep the list manageable:
   * - Skips elements with interactive ARIA roles or labels (already in the ARIA snapshot)
   * - Skips common semantic HTML tags (already in the ARIA snapshot)
   * - Skips large container/wrapper elements (width or height >= 80% of viewport)
   * - Skips common framework-generated IDs (React root, Next.js __next, etc.)
   * - Deduplicates nested elements: if a parent and child both match, keeps the child
   * - Caps results at [MAX_HIDDEN_CSS_ELEMENTS] to avoid overwhelming the LLM
   *
   * Returns formatted lines ready to append to the compact element list, e.g.:
   * ```
   * [h1] css=#interactive-panel (div) "Click to expand" {x:120,y:450,w:200,h:40}
   * [h2] css=[data-testid="card-widget"] (div) (offscreen) {x:120,y:1800,w:300,h:200}
   * ```
   *
   * Uses "h" prefix (for "hidden") to distinguish from ARIA element IDs ("e" prefix).
   */
  @Suppress("UNCHECKED_CAST")
  private fun discoverHiddenCssTargetableElements(
    compact: PlaywrightAriaSnapshot.CompactAriaElements,
  ): List<String> {
    return try {
      // Collect all CSS selectors already known from the ARIA elements
      // so we don't duplicate them in the hidden elements section
      val knownCssSelectors = mutableSetOf<String>()
      for ((_, elementRef) in compact.elementIdMapping) {
        try {
          val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
          val css = buildCssSelectorForLocator(locator)
          if (css != null) knownCssSelectors.add(css)
        } catch (_: Exception) {}
      }

      // Query the DOM for visible elements with id or data-testid that have
      // no accessible name and no interactive ARIA role.
      // Returns ALL matching elements (not just in-viewport), with viewport info
      // so we can annotate offscreen elements.
      val elements =
        page.evaluate(
          """() => {
          const results = [];
          const vw = window.innerWidth;
          const vh = window.innerHeight;
          const interactiveRoles = new Set([
            'button', 'link', 'textbox', 'combobox', 'searchbox',
            'checkbox', 'radio', 'switch', 'tab', 'menuitem',
            'option', 'slider', 'spinbutton'
          ]);
          // Common framework/boilerplate IDs that are just wrappers
          const skipIds = new Set([
            'root', 'app', '__next', '__nuxt', 'gatsby-focus-wrapper',
            'svelte', 'ember-application', 'wrapper', 'page', 'layout',
            'main-content', 'content', 'container'
          ]);

          const candidates = document.querySelectorAll('[id], [data-testid], [data-test-id]');
          // Track which elements we've collected, for parent dedup
          const collectedElements = new Set();

          for (const el of candidates) {
            // Skip invisible elements (display:none, visibility:hidden, or zero-size)
            const rect = el.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) continue;
            const style = window.getComputedStyle(el);
            if (style.display === 'none' || style.visibility === 'hidden') continue;
            if (parseFloat(style.opacity) === 0) continue;

            // Skip elements that already have good ARIA semantics
            const role = el.getAttribute('role') || el.tagName.toLowerCase();
            const ariaLabel = el.getAttribute('aria-label') || el.getAttribute('aria-labelledby');
            if (interactiveRoles.has(role)) continue;
            if (ariaLabel) continue;

            // Skip common semantic HTML that the ARIA snapshot already handles
            const tag = el.tagName.toLowerCase();
            if (['a', 'button', 'input', 'select', 'textarea',
                 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'img',
                 'html', 'body', 'head', 'script', 'style', 'meta',
                 'link', 'noscript', 'template'].includes(tag)) continue;

            // Skip common framework wrapper IDs
            if (el.id && skipIds.has(el.id.toLowerCase())) continue;

            // Skip large container/wrapper elements (>= 80% of viewport in both dims)
            if (rect.width >= vw * 0.8 && rect.height >= vh * 0.8) continue;

            // Build the best CSS selector
            let selector;
            if (el.id) {
              selector = '#' + CSS.escape(el.id);
            } else {
              const testId = el.getAttribute('data-testid');
              const testId2 = el.getAttribute('data-test-id');
              if (testId) selector = '[data-testid="' + CSS.escape(testId) + '"]';
              else if (testId2) selector = '[data-test-id="' + CSS.escape(testId2) + '"]';
            }
            if (!selector) continue;

            // Get direct text content only (not from deep children) for a meaningful preview
            let text = '';
            for (const child of el.childNodes) {
              if (child.nodeType === 3) text += child.textContent;
            }
            text = text.trim().substring(0, 60);
            // If no direct text, try innerText but keep it short
            if (!text) {
              text = (el.innerText || '').trim().substring(0, 60);
            }

            // Determine if the element is in the viewport
            const inViewport = rect.bottom > 0 && rect.top < vh
              && rect.right > 0 && rect.left < vw;

            collectedElements.add(el);

            results.push({
              selector: selector,
              tag: tag,
              text: text || null,
              inViewport: inViewport,
              element: el,
              bounds: {
                x: Math.round(rect.x + window.scrollX),
                y: Math.round(rect.y + window.scrollY),
                w: Math.round(rect.width),
                h: Math.round(rect.height)
              }
            });
          }

          // Deduplicate nested elements: if a parent is in the list and so is its
          // child, remove the parent (the child is more specific/useful to target)
          const toRemove = new Set();
          for (const entry of results) {
            let parent = entry.element.parentElement;
            while (parent) {
              if (collectedElements.has(parent)) {
                // Find the parent's index and mark it for removal
                for (let i = 0; i < results.length; i++) {
                  if (results[i].element === parent) toRemove.add(i);
                }
              }
              parent = parent.parentElement;
            }
          }

          // Clean up element references (can't serialize DOM nodes) and filter
          return results
            .filter((_, i) => !toRemove.has(i))
            .map(({ element, ...rest }) => rest);
        }""",
        ) as? List<Map<String, Any?>> ?: emptyList()

      var nextHiddenId = 1
      elements
        .filter { elem ->
          val selector = elem["selector"] as? String ?: return@filter false
          selector !in knownCssSelectors
        }
        .take(MAX_HIDDEN_CSS_ELEMENTS)
        .map { elem ->
          val selector = elem["selector"] as String
          val tag = elem["tag"] as? String ?: "element"
          val text = elem["text"] as? String
          val inViewport = elem["inViewport"] as? Boolean ?: true
          val bounds = elem["bounds"] as? Map<String, Any?>
          val id = "h${nextHiddenId++}"

          buildString {
            append("[$id] css=$selector ($tag)")
            if (!text.isNullOrBlank()) {
              val preview = if (text.length > 40) text.take(40) + "..." else text
              append(" \"$preview\"")
            }
            if (!inViewport) {
              append(" (offscreen)")
            }
            if (bounds != null) {
              val x = (bounds["x"] as? Number)?.toInt() ?: 0
              val y = (bounds["y"] as? Number)?.toInt() ?: 0
              val w = (bounds["w"] as? Number)?.toInt() ?: 0
              val h = (bounds["h"] as? Number)?.toInt() ?: 0
              append(" {x:$x,y:$y,w:$w,h:$h}")
            }
          }
        }
    } catch (_: Exception) {
      emptyList()
    }
  }

  /** Raw PNG screenshot from Playwright, before any scaling. */
  private val rawScreenshotBytes: ByteArray? by lazy {
    TrailblazeTracer.trace("screenshot", "screenState") {
      try {
        page.screenshot(
          Page.ScreenshotOptions()
            .setFullPage(false)
            .setAnimations(ScreenshotAnimations.DISABLED)
            .setTimeout(captureTimeoutMs),
        )
      } catch (_: Exception) {
        null
      }
    }
  }

  /**
   * Clean screenshot scaled and compressed via [ScreenshotScalingConfig].
   * Used for logging, snapshots, and as the base for [annotatedScreenshotBytes].
   */
  override val screenshotBytes: ByteArray? by lazy {
    scaleAndEncode(rawScreenshotBytes)
  }

  /**
   * Set-of-mark annotation elements keyed to the same `[eN]` IDs the LLM sees in
   * [viewHierarchyTextRepresentation]. Without this override the SoM annotator falls
   * back to numbering [viewHierarchy] nodes by their internal nodeId, producing
   * labels like "4, 8, 10, 13" on the screenshot while the LLM is reading "[e4],
   * [e5], [e6], [e7]" in text — the two never line up, so the model can't reliably
   * map a label to an element.
   *
   * Bounds are read out of [elementVisibility], the same batched JS call that powers
   * offscreen + occlusion detection — so this previously made N CDP round-trips
   * (one per imageAnnotatable element), and now makes 0. For elements the batched
   * call couldn't match (role/name mismatch on Compose Web Wasm and friends), we
   * fall back to per-element `locator.boundingBox()` so the overlay still renders
   * via Playwright's accessibility-aware locator path.
   *
   * Filtered to [PlaywrightAriaSnapshot.ElementRef.imageAnnotatable] elements only,
   * **minus** anything in [ElementVisibility.offscreen] or [ElementVisibility.occluded]
   * — boxes for offscreen elements would draw outside the screenshot, and boxes for
   * occluded elements would paint over the popup/modal hiding them, both misleading
   * to the LLM. Cells, rows, headings, and named generic elements still appear in
   * the text view (so the LLM can address them by name) but don't get image boxes.
   */
  override val annotationElements: List<AnnotationElement>? by lazy {
    val visibility = elementVisibility
    val out = mutableListOf<AnnotationElement>()
    var nodeId = 1L
    for ((id, ref) in elementIdMapping) {
      if (!ref.imageAnnotatable) continue
      if (id in visibility.offscreen) continue
      if (id in visibility.occluded) continue

      val bounds = visibility.bounds[id] ?: run {
        // Batched check didn't match this element (role/name mismatch, common on
        // Compose Web Wasm where overlay elements may not expose computedRole).
        // Fall back to the locator-based path — accessibility-aware bbox.
        try {
          val locator = PlaywrightAriaSnapshot.resolveElementRef(page, ref)
          val box = locator.boundingBox(
            Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs),
          ) ?: return@run null
          if (box.width <= 0 || box.height <= 0) return@run null
          TrailblazeNode.Bounds(
            left = box.x.toInt(),
            top = box.y.toInt(),
            right = (box.x + box.width).toInt(),
            bottom = (box.y + box.height).toInt(),
          )
        } catch (_: Exception) {
          null
        }
      } ?: continue

      out.add(
        AnnotationElement(
          nodeId = nodeId++,
          bounds = bounds,
          refLabel = id,
        ),
      )
    }
    out.takeIf { it.isNotEmpty() }
  }

  /**
   * Scaled screenshot with set-of-mark annotations for LLM requests.
   * Annotates from raw PNG (before scaling) so element bounds align with the image,
   * then applies the same scaling as [screenshotBytes] for consistent output.
   */
  override val annotatedScreenshotBytes: ByteArray? by lazy {
    val annotated = SetOfMarkAnnotator.annotate(
      screenshotBytes = rawScreenshotBytes,
      screenWidth = deviceWidth,
      screenHeight = deviceHeight,
      platform = trailblazeDevicePlatform,
      annotationElements = annotationElements,
    )
    scaleAndEncode(annotated)
  }

  /**
   * Scales and encodes raw screenshot bytes using [screenshotScalingConfig].
   */
  private fun scaleAndEncode(rawBytes: ByteArray?): ByteArray? {
    rawBytes ?: return null
    val config = screenshotScalingConfig ?: return rawBytes

    return try {
      val original = ByteArrayInputStream(rawBytes).use { ImageIO.read(it) }
        ?: return rawBytes

      val scaled = original.scaleToFit(
        maxDim1 = config.maxDimension1,
        maxDim2 = config.maxDimension2,
      )

      scaled.toCompressedByteArray(
        format = config.imageFormat,
        quality = config.compressionQuality,
      )
    } catch (_: Exception) {
      rawBytes
    }
  }

  override val deviceWidth: Int
    get() = viewportWidth

  override val deviceHeight: Int
    get() = viewportHeight

  /** Full tree for logging/backward compatibility — not sent to the LLM. */
  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    val tree = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(ariaSnapshotYaml)
    enrichViewHierarchyWithBounds(tree)
  }

  /**
   * Native [TrailblazeNode] tree with [DriverNodeDetail.Web][xyz.block.trailblaze.api.DriverNodeDetail.Web]
   * detail, built from the ARIA snapshot and enriched with bounds.
   *
   * First tries the fast DOM-walk approach via [PlaywrightTrailblazeNodeMapper.mapWithBounds].
   * If that produces no bounds (e.g., Compose Web Wasm where accessibility overlay elements
   * have zero-size DOM rects), falls back to locator-based [boundingBox] resolution which
   * goes through Playwright's accessibility tree.
   */
  override val trailblazeNodeTree: TrailblazeNode? by lazy {
    TrailblazeTracer.trace("trailblazeNodeTree", "screenState") {
      val tree = PlaywrightTrailblazeNodeMapper.mapWithBounds(
        yaml = ariaSnapshotYaml,
        page = page,
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight,
      )
      // If DOM walk produced no bounds, fall back to locator-based resolution
      if (tree != null && !tree.hasAnyBounds()) {
        enrichTrailblazeNodeWithLocatorBounds(tree)
      } else {
        tree
      }
    }
  }

  override val trailblazeDevicePlatform: TrailblazeDevicePlatform
    get() = TrailblazeDevicePlatform.WEB

  override val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = listOf(
      TrailblazeDeviceClassifier(browserEngine.displayName),
      TrailblazeDeviceClassifier(viewportSizeClass),
    )

  /**
   * Classifies the viewport width into a human-readable size class.
   * Follows common responsive design breakpoints:
   * - mobile: < 768px (phone-sized viewports)
   * - tablet: 768–1023px (tablet-sized viewports)
   * - desktop: ≥ 1024px (desktop/laptop viewports)
   */
  private val viewportSizeClass: String
    get() = when {
      viewportWidth < 768 -> "mobile"
      viewportWidth < 1024 -> "tablet"
      else -> "desktop"
    }

  /**
   * Enriches a [ViewHierarchyTreeNode] tree with bounds from the live page.
   *
   * Uses Playwright's locator-based [boundingBox] API (via ARIA descriptors) rather than
   * raw DOM [getBoundingClientRect]. This is critical for Compose Web Wasm pages where
   * accessibility overlay elements have zero-size DOM rects but valid positions in
   * Playwright's internal accessibility tree.
   *
   * Populates [ViewHierarchyTreeNode.centerPoint] and [ViewHierarchyTreeNode.dimensions] so
   * the UI Inspector's hover overlay can highlight elements on the screenshot.
   */
  private fun enrichViewHierarchyWithBounds(
    tree: ViewHierarchyTreeNode,
  ): ViewHierarchyTreeNode {
    // Build ARIA descriptor occurrence counts to disambiguate duplicate elements
    val descriptorOccurrences = mutableMapOf<String, Int>()
    return enrichNodeWithLocatorBounds(tree, descriptorOccurrences)
  }

  private fun enrichNodeWithLocatorBounds(
    node: ViewHierarchyTreeNode,
    descriptorOccurrences: MutableMap<String, Int>,
  ): ViewHierarchyTreeNode {
    val role = node.className ?: "generic"
    val name = node.text

    // Build ARIA descriptor matching the format PlaywrightAriaSnapshot.resolveRef expects
    val descriptor = when {
      name != null && role == "text" -> "text: $name"
      name != null -> "$role \"$name\""
      else -> role
    }

    // Track nth occurrence for disambiguation of duplicate descriptors
    val nthIndex = descriptorOccurrences.getOrDefault(descriptor, 0)
    descriptorOccurrences[descriptor] = nthIndex + 1

    // Resolve bounds via Playwright's accessibility-tree-aware locator API
    var bLeft: Int? = null
    var bTop: Int? = null
    var bRight: Int? = null
    var bBottom: Int? = null
    try {
      val elementRef = PlaywrightAriaSnapshot.ElementRef(descriptor, nthIndex)
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() > 0) {
        val box = locator.boundingBox(
          Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs),
        )
        if (box != null && box.width > 0 && box.height > 0) {
          bLeft = box.x.toInt()
          bTop = box.y.toInt()
          bRight = (box.x + box.width).toInt()
          bBottom = (box.y + box.height).toInt()
        }
      }
    } catch (_: Exception) {
      // Resolution failed — leave without bounds
    }

    // Enrich children recursively
    val enrichedChildren = node.children.map { child ->
      enrichNodeWithLocatorBounds(child, descriptorOccurrences)
    }

    return if (bLeft != null && bTop != null && bRight != null && bBottom != null) {
      node.copy(
        children = enrichedChildren,
        x1 = bLeft,
        y1 = bTop,
        x2 = bRight,
        y2 = bBottom,
      )
    } else {
      node.copy(children = enrichedChildren)
    }
  }

  /** Returns true if any node in the tree has non-null bounds. */
  private fun TrailblazeNode.hasAnyBounds(): Boolean =
    bounds != null || children.any { it.hasAnyBounds() }

  /**
   * Enriches a [TrailblazeNode] tree with bounds using Playwright's locator-based
   * [boundingBox] API. Fallback for when the DOM-walk approach in
   * [PlaywrightTrailblazeNodeMapper.mapWithBounds] returns no bounds (e.g., Compose Wasm).
   */
  private fun enrichTrailblazeNodeWithLocatorBounds(root: TrailblazeNode): TrailblazeNode {
    return enrichTrailblazeNode(root)
  }

  private fun enrichTrailblazeNode(node: TrailblazeNode): TrailblazeNode {
    val detail = node.driverDetail as? DriverNodeDetail.Web
    val descriptor = detail?.ariaDescriptor
    var resolvedBounds: TrailblazeNode.Bounds? = null

    if (descriptor != null) {
      try {
        val elementRef = PlaywrightAriaSnapshot.ElementRef(descriptor, detail.nthIndex)
        val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
        if (locator.count() > 0) {
          val box = locator.boundingBox(
            Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs),
          )
          if (box != null && box.width > 0 && box.height > 0) {
            resolvedBounds = TrailblazeNode.Bounds(
              left = box.x.toInt(),
              top = box.y.toInt(),
              right = (box.x + box.width).toInt(),
              bottom = (box.y + box.height).toInt(),
            )
          }
        }
      } catch (_: Exception) {
        // Resolution failed — leave without bounds
      }
    }

    val enrichedChildren = node.children.map { enrichTrailblazeNode(it) }

    return node.copy(
      children = enrichedChildren,
      bounds = resolvedBounds ?: node.bounds,
    )
  }

  companion object {

    /**
     * Timeout for per-element Playwright API calls (bounding box, CSS selector) (ms).
     * Kept short since these calls should be near-instant on a responsive page.
     * If a call exceeds this, it's caught and degraded gracefully.
     */
    private const val CAPTURE_TIMEOUT_MS = 500.0

    /**
     * Timeout for the ARIA snapshot capture itself (ms).
     * Content-rich pages (e.g., Wikipedia articles) can produce 500KB+ of ARIA YAML
     * with thousands of nodes. The default Playwright timeout (30s) is appropriate here
     * since the call scales with page complexity, not page responsiveness.
     * Set to 0 to use Playwright's default timeout.
     */
    private const val ARIA_SNAPSHOT_TIMEOUT_MS = 0.0

    /** Maximum number of hidden CSS-targetable elements to surface to the LLM. */
    private const val MAX_HIDDEN_CSS_ELEMENTS = 50

    /** Pattern matching element ID references like [e42] in compact element list text. */
    private val ELEMENT_ID_PATTERN = Regex("""\[e(\d+)]""")

    /** Pattern matching ARIA landmark/container header lines like "navigation:", "  main:", or "navigation "Top nav":" */
    private val LANDMARK_HEADER_PATTERN = Regex(
      """^\s*(?:navigation|main|banner|contentinfo|complementary|form|dialog|region|list|table)(?:\s+".*?")?:\s*$""",
    )

    /**
     * JavaScript function that batch-computes per-element visibility data in a single evaluation.
     *
     * Receives `{elements: [{id, role, name, nth}], vw, vh}` and returns
     * `{offscreen: [...ids], occluded: [...ids], bounds: {id: {x,y,w,h}}, occlusionReliable: bool}`.
     *
     * Three signals per element, all derived from one DOM walk to keep the cost at a
     * single CDP round-trip regardless of element count:
     *
     * 1. **Bounds** — `getBoundingClientRect()` viewport-relative coordinates. Replaces
     *    the per-element `locator.boundingBox()` CDP calls in `annotationElements`, so
     *    the SoM overlay can be drawn from this payload alone.
     *
     * 2. **Offscreen** — center of bbox lies outside the viewport. Same semantics as
     *    before; element gets dropped (or annotated `(offscreen)` when OFFSCREEN_ELEMENTS
     *    is requested).
     *
     * 3. **Occluded** — `document.elementFromPoint(centerX, centerY)` after
     *    injecting a page-wide stylesheet rule `* { pointer-events: auto
     *    !important }` that neutralizes every `pointer-events: none` declaration
     *    on the page. The browser then runs its own full CSS stacking-context math
     *    (z-index buckets, position-creates-context, opacity/transform-creates-
     *    context, etc.) and returns the visually-topmost element including
     *    overlays that would otherwise be skipped because they're set to be
     *    click-transparent. The stylesheet is removed in a `finally` block within
     *    the same synchronous JS evaluation, so MutationObservers and other page
     *    code never observe the temporary state.
     *
     *    Why a stylesheet rule, not a per-element flip: real-world popups (e.g.,
     *    Square Dashboard's Managerbot toast) sit inside a NON-POSITIONED
     *    full-viewport wrapper that itself carries `pointer-events: none` (so
     *    clicks pass through to the page underneath). A previous version of this
     *    code walked only `position: fixed/absolute/sticky` elements and missed
     *    the wrapper case — confirmed broken against the live Square Dashboard.
     *    The global rule catches every wrapper regardless of positioning or DOM
     *    depth in O(1), since the browser applies the rule to the entire stylesheet
     *    cascade in one operation. `pointer-events` doesn't affect layout, only
     *    hit-testing, so there's no reflow penalty either.
     *
     * **Compose Web Wasm fail-open**: on canvas-rendered pages the relevant
     * accessibility-overlay elements may not have position:absolute/fixed and the
     * canvas takes up the whole viewport. `elementFromPoint` returns the canvas
     * for any point. Per-element rule: if the topmost is CANVAS / HTML / BODY,
     * treat the verdict as unknown rather than occluded.
     *
     * Uses Chromium's `Element.computedRole` and `Element.computedName` properties to
     * match elements — these are the same values the browser's accessibility tree exposes,
     * which Playwright's `ariaSnapshot()` reads from. This provides near-exact matching
     * without reimplementing the ARIA role/name computation in JavaScript.
     *
     * Falls back to a simplified manual computation if the browser APIs are unavailable.
     */
    private val BATCH_VIEWPORT_CHECK_JS = """(args) => {
      const { elements, vw, vh } = args;

      // Feature-detect Chromium's computed accessibility properties.
      // These match what Playwright's ariaSnapshot() reads from the accessibility tree.
      const testEl = document.createElement('div');
      const hasComputedAccess = 'computedRole' in testEl;

      // Fallback: manual implicit role mapping (only used if computedRole unavailable)
      function getFallbackRole(el) {
        const explicit = el.getAttribute('role');
        if (explicit) return explicit.toLowerCase();
        const tag = el.tagName;
        if (tag === 'A') return el.hasAttribute('href') ? 'link' : null;
        if (tag === 'AREA') return el.hasAttribute('href') ? 'link' : null;
        if (tag === 'BUTTON' || tag === 'SUMMARY') return 'button';
        if (tag === 'INPUT') {
          const type = (el.type || 'text').toLowerCase();
          if (type === 'checkbox') return 'checkbox';
          if (type === 'radio') return 'radio';
          if (type === 'range') return 'slider';
          if (type === 'number') return 'spinbutton';
          if (type === 'search') return 'searchbox';
          if (type === 'hidden') return null;
          if (['submit', 'reset', 'button', 'image'].includes(type)) return 'button';
          return 'textbox';
        }
        if (tag === 'SELECT') return el.multiple ? 'listbox' : 'combobox';
        if (tag === 'TEXTAREA') return 'textbox';
        if (/^H[1-6]${'$'}/.test(tag)) return 'heading';
        if (tag === 'IMG') return 'img';
        if (tag === 'NAV') return 'navigation';
        if (tag === 'MAIN') return 'main';
        if (tag === 'ASIDE') return 'complementary';
        if (tag === 'FORM') return 'form';
        if (tag === 'DIALOG') return 'dialog';
        if (tag === 'TABLE') return 'table';
        if (tag === 'UL' || tag === 'OL') return 'list';
        if (tag === 'OPTION') return 'option';
        if (tag === 'PROGRESS') return 'progressbar';
        if (tag === 'OUTPUT') return 'status';
        if (tag === 'METER') return 'meter';
        if (tag === 'DETAILS') return 'group';
        if (tag === 'HEADER') return el.closest('article, aside, main, nav, section') ? null : 'banner';
        if (tag === 'FOOTER') return el.closest('article, aside, main, nav, section') ? null : 'contentinfo';
        return null;
      }

      // Fallback: simplified accessible name (only used if computedName unavailable)
      function getFallbackName(el, role) {
        const ariaLabel = el.getAttribute('aria-label');
        if (ariaLabel) return ariaLabel;
        const labelledBy = el.getAttribute('aria-labelledby');
        if (labelledBy) {
          const parts = labelledBy.split(/\s+/)
            .map(id => document.getElementById(id))
            .filter(Boolean)
            .map(ref => (ref.innerText || ref.textContent || '').trim());
          if (parts.length) return parts.join(' ');
        }
        if (el.tagName === 'IMG') return el.alt || null;
        if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
          if (el.labels && el.labels.length) {
            return (el.labels[0].innerText || el.labels[0].textContent || '').trim() || null;
          }
          if (el.placeholder) return el.placeholder;
          if (el.title) return el.title;
          return null;
        }
        if (el.tagName === 'TABLE') {
          const caption = el.querySelector('caption');
          if (caption) return caption.textContent.trim() || null;
        }
        const textRoles = ['link', 'button', 'heading', 'tab', 'menuitem', 'option',
          'cell', 'columnheader', 'rowheader', 'treeitem', 'switch'];
        if (textRoles.includes(role)) {
          const text = (el.innerText || el.textContent || '').trim();
          return text || null;
        }
        if (el.title) return el.title;
        return null;
      }

      function getRole(el) {
        if (hasComputedAccess) {
          const r = el.computedRole;
          return (r && r !== 'generic' && r !== 'none' && r !== 'presentation') ? r : null;
        }
        return getFallbackRole(el);
      }

      function getName(el, role) {
        if (hasComputedAccess) {
          const n = el.computedName;
          return n || null;
        }
        return getFallbackName(el, role);
      }

      // Check if an element is hidden from the accessibility tree.
      // The ARIA snapshot (and compact element list) only includes elements visible
      // to the accessibility tree, so we must skip hidden elements to keep nth-indices
      // aligned. Without this, an aria-hidden duplicate would shift all subsequent
      // nth-indices and cause mismatches.
      function isHiddenFromAccessibilityTree(el) {
        // aria-hidden="true" hides the entire subtree
        if (el.closest('[aria-hidden="true"]')) return true;
        // display:none / visibility:hidden are not in the accessibility tree
        const style = window.getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden') return true;
        return false;
      }

      // Walk DOM in document order (matching the accessibility tree traversal),
      // build lookup: "role\nname" -> [el, el, ...]
      // Skips elements hidden from the accessibility tree to keep nth-indices aligned
      // with Playwright's ariaSnapshot() output.
      const roleNameMap = {};
      const allEls = document.querySelectorAll('*');
      for (const el of allEls) {
        if (isHiddenFromAccessibilityTree(el)) continue;
        const role = getRole(el);
        if (!role) continue;
        const name = getName(el, role);
        const key = name != null ? role + '\n' + name : role;
        if (!roleNameMap[key]) roleNameMap[key] = [];
        roleNameMap[key].push(el);
      }

      // Visual-occlusion strategy:
      //
      // 1. Inject a stylesheet rule `* { pointer-events: auto !important }` so
      //    no element gets skipped by hit-testing for being click-transparent.
      //    Removed in a finally block, bounded to this synchronous JS eval.
      //
      // 2. For each element, walk `document.elementsFromPoint(centerX, centerY)`
      //    front-to-back looking for the first VISUALLY OPAQUE element. An
      //    element counts as opaque iff it has a non-transparent
      //    background-color OR a non-none background-image OR a non-trivial
      //    border. Transparent click-through wrappers (like the full-viewport
      //    container Square's Managerbot popup uses) get skipped, so they
      //    don't get treated as occluders just because they exist in the
      //    paint stack.
      //
      // 3. If the first opaque element is `el` itself or a descendant, the
      //    element is visible. Otherwise it's occluded.
      //
      // Why this combination: just neutralizing pointer-events:none with the
      // stylesheet rule makes hit-tests return the topmost element regardless
      // of click-through tricks (which fixes the case where popups sit inside
      // a non-positioned pointer-events:none wrapper). But the rule alone
      // would also make transparent wrappers register as occluders, which is
      // wrong — the LLM looking at the screenshot can see right through them.
      // Walking the stack for the first opaque element fixes that without
      // having to manually reimplement CSS stacking-context rules.
      const peOverrideStyle = document.createElement('style');
      peOverrideStyle.textContent = '* { pointer-events: auto !important; }';
      document.head.appendChild(peOverrideStyle);

      function isVisuallyOpaqueAt(node) {
        // Has non-transparent background color? `rgba(0, 0, 0, 0)` and
        // `transparent` are the standard transparent-color renderings.
        const cs = window.getComputedStyle(node);
        const bg = cs.backgroundColor;
        if (bg && bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent') return true;
        // Has a background image?
        if (cs.backgroundImage && cs.backgroundImage !== 'none') return true;
        // Has a non-trivial border on at least one side?
        const bw = parseFloat(cs.borderTopWidth || '0')
          + parseFloat(cs.borderRightWidth || '0')
          + parseFloat(cs.borderBottomWidth || '0')
          + parseFloat(cs.borderLeftWidth || '0');
        if (bw > 0) return true;
        // Has a non-empty image or replaced content?
        if (node.tagName === 'IMG' || node.tagName === 'SVG'
          || node.tagName === 'CANVAS' || node.tagName === 'VIDEO') return true;
        return false;
      }

      const offscreen = [];
      const occluded = [];
      const bounds = {};
      let occlusionReliable = false;
      try {
        for (const elem of elements) {
          const key = elem.name != null ? elem.role + '\n' + elem.name : elem.role;
          const matches = roleNameMap[key];
          if (!matches || elem.nth >= matches.length) continue;
          const el = matches[elem.nth];
          const rect = el.getBoundingClientRect();

          bounds[elem.id] = {
            x: Math.round(rect.x),
            y: Math.round(rect.y),
            w: Math.round(rect.width),
            h: Math.round(rect.height),
          };

          const inViewport = rect.bottom > 0 && rect.top < vh
            && rect.right > 0 && rect.left < vw
            && rect.width > 0 && rect.height > 0;
          if (!inViewport) {
            offscreen.push(elem.id);
            continue; // occlusion check is meaningless for offscreen elements
          }

          const cx = rect.x + rect.width / 2;
          const cy = rect.y + rect.height / 2;
          const stack = document.elementsFromPoint(cx, cy);
          if (!stack || stack.length === 0) continue;

          // Find the first visually opaque element in the paint stack.
          // Transparent wrappers (click-through containers) get skipped.
          let visualTop = null;
          for (const node of stack) {
            const tag = node.tagName;
            if (tag === 'HTML' || tag === 'BODY') break; // hit the page background — stop
            if (isVisuallyOpaqueAt(node)) {
              visualTop = node;
              break;
            }
          }
          if (visualTop == null) continue; // nothing opaque between top and body — unknown
          if (visualTop.tagName === 'CANVAS') continue; // canvas-rendered, unreliable
          // Visible iff visualTop is `el` or related by ancestry in either direction:
          //  - visualTop IS el or descendant of el: el's own opaque content is on top.
          //  - visualTop IS ancestor of el: el sits inside visualTop's opaque region —
          //    visualTop is the background the LLM sees el painted onto, which means
          //    el is still visible (a transparent-text-inside-opaque-card case, e.g.,
          //    the "Square's AI terms" link inside the Managerbot popup card).
          if (el.contains(visualTop) || visualTop.contains(el)) {
            occlusionReliable = true;
            continue;
          }
          occluded.push(elem.id);
          occlusionReliable = true;
        }
      } finally {
        // Always remove the injected override stylesheet, even if the loop above
        // threw. Bounded to this synchronous JS evaluation: no MutationObserver
        // microtask runs between append and remove.
        peOverrideStyle.remove();
      }

      return { offscreen, occluded, bounds, occlusionReliable };
    }"""

    /**
     * Scales a BufferedImage to fit within the specified dimensions while maintaining
     * aspect ratio. Only scales down, never up. Mirrors [BufferedImageUtils.scale].
     */
    private fun BufferedImage.scaleToFit(maxDim1: Int, maxDim2: Int): BufferedImage {
      val targetLong = maxOf(maxDim1, maxDim2)
      val targetShort = minOf(maxDim1, maxDim2)
      val imageLong = maxOf(width, height)
      val imageShort = minOf(width, height)

      if (imageLong <= targetLong && imageShort <= targetShort) {
        return this
      }

      val scale = minOf(
        targetLong.toFloat() / imageLong,
        targetShort.toFloat() / imageShort,
      )
      val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
      val scaledHeight = (height * scale).toInt().coerceAtLeast(1)

      // TYPE_CUSTOM (0) is not a valid constructor argument — default to TYPE_INT_ARGB
      val imageType =
        if (type == BufferedImage.TYPE_CUSTOM) BufferedImage.TYPE_INT_ARGB else type
      val scaledImage = BufferedImage(scaledWidth, scaledHeight, imageType)
      val graphics = scaledImage.createGraphics()
      try {
        graphics.drawImage(
          getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH),
          0, 0, null,
        )
      } finally {
        graphics.dispose()
      }
      return scaledImage
    }

    /**
     * Converts a BufferedImage to a byte array with the specified format and quality.
     * Mirrors [BufferedImageUtils.toByteArray] — handles JPEG alpha-channel conversion.
     */
    private fun BufferedImage.toCompressedByteArray(
      format: TrailblazeImageFormat,
      quality: Float,
    ): ByteArray {
      ByteArrayOutputStream().use { outputStream ->
        when (format) {
          TrailblazeImageFormat.PNG -> {
            ImageIO.write(this, format.formatName, outputStream)
          }

          TrailblazeImageFormat.WEBP -> {
            return this.encodeWebPWithSkia(quality)
          }

          TrailblazeImageFormat.JPEG -> {
            // JPEG doesn't support transparency — convert ARGB to RGB if needed
            val imageToWrite = if (type == BufferedImage.TYPE_INT_ARGB ||
              type == BufferedImage.TYPE_4BYTE_ABGR ||
              transparency != BufferedImage.OPAQUE
            ) {
              val rgbImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
              val g = rgbImage.createGraphics()
              try {
                g.drawImage(this, 0, 0, java.awt.Color.WHITE, null)
              } finally {
                g.dispose()
              }
              rgbImage
            } else {
              this
            }

            val writer = ImageIO.getImageWritersByFormatName(format.formatName).next()
            try {
              val writeParam = writer.defaultWriteParam
              if (writeParam.canWriteCompressed()) {
                writeParam.compressionMode = ImageWriteParam.MODE_EXPLICIT
                writeParam.compressionQuality = quality.coerceIn(0f, 1f)
              }
              ImageIO.createImageOutputStream(outputStream).use { imageOutputStream ->
                writer.output = imageOutputStream
                writer.write(null, IIOImage(imageToWrite, null, null), writeParam)
              }
            } finally {
              writer.dispose()
            }
          }
        }
        return outputStream.toByteArray()
      }
    }

    /** Encodes a BufferedImage to WebP using Skia (via Skiko). */
    private fun BufferedImage.encodeWebPWithSkia(quality: Float): ByteArray {
      val rgbImage = if (this.type == BufferedImage.TYPE_INT_ARGB) {
        this
      } else {
        val converted = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = converted.createGraphics()
        g.drawImage(this, 0, 0, null)
        g.dispose()
        converted
      }

      val argbPixels = rgbImage.getRGB(0, 0, width, height, null, 0, width)
      val bytes = ByteArray(width * height * 4)
      for (i in argbPixels.indices) {
        val pixel = argbPixels[i]
        val offset = i * 4
        bytes[offset] = (pixel and 0xFF).toByte()             // B
        bytes[offset + 1] = (pixel shr 8 and 0xFF).toByte()   // G
        bytes[offset + 2] = (pixel shr 16 and 0xFF).toByte()  // R
        bytes[offset + 3] = (pixel shr 24 and 0xFF).toByte()  // A
      }

      val imageInfo = org.jetbrains.skia.ImageInfo.makeN32(
        width, height, org.jetbrains.skia.ColorAlphaType.UNPREMUL,
      )
      // Note: duplicates BufferedImageUtils.encodeWithSkia() — kept separate because
      // trailblaze-playwright does not depend on trailblaze-host.
      val skiaImage = org.jetbrains.skia.Image.makeRaster(imageInfo, bytes, width * 4)
      try {
        val encoded = skiaImage.encodeToData(
          org.jetbrains.skia.EncodedImageFormat.WEBP, (quality * 100).toInt(),
        ) ?: error("Skia failed to encode image as WEBP")
        try {
          return encoded.bytes
        } finally {
          encoded.close()
        }
      } finally {
        skiaImage.close()
      }
    }
  }
}
