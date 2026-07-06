package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.DeviceInfoPrefix
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
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
import xyz.block.trailblaze.util.Console
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
 *   Defaults to [EffectiveScreenshotScalingConfig.effectiveForWeb] so web captures aren't squeezed
 *   by the mobile-portrait short-side cap; an explicit user-configured scaling still wins.
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
  private val screenshotScalingConfig: ScreenshotScalingConfig? =
    EffectiveScreenshotScalingConfig.effectiveForWeb,
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
   * - [offscreen]: element's bbox is outside the viewport (or zero-size).
   * - [occluded]: element is in-viewport but Playwright's `expectHitTarget`
   *   composed-tree walk reaches a different element at the candidate's center
   *   — i.e., the candidate is visually covered. See [BATCH_VIEWPORT_CHECK_JS]
   *   KDoc for the upstream-pinned algorithm.
   * - [bounds]: viewport-relative bbox per id, so [annotationElements] can drop its
   *   per-element CDP `locator.boundingBox()` calls.
   */
  private data class ElementVisibility(
    val offscreen: Set<String>,
    val occluded: Set<String>,
    val bounds: Map<String, TrailblazeNode.Bounds>,
  ) {
    companion object {
      val EMPTY = ElementVisibility(emptySet(), emptySet(), emptyMap())
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
  ): ElementVisibility = TrailblazeTracer.trace("computeElementVisibility", "screenState") {
    if (compact.elementIdMapping.isEmpty()) return@trace ElementVisibility.EMPTY
    try {
      val elements = compact.elementIdMapping.map { (id, ref) ->
        val (role, name) = parseAriaDescriptor(ref.descriptor)
        mapOf("id" to id, "role" to role, "name" to name, "nth" to ref.nthIndex)
      }
      val result = page.evaluate(
        BATCH_VIEWPORT_CHECK_JS,
        mapOf("elements" to elements, "vw" to viewportWidth, "vh" to viewportHeight),
      ) as? Map<String, Any?> ?: return@trace ElementVisibility.EMPTY

      val rawBounds = result["bounds"] as? Map<String, Map<String, Any?>> ?: emptyMap()
      val bounds = rawBounds.mapNotNull { (id, b) ->
        val boundsObj = b.toBoundsOrNull() ?: return@mapNotNull null
        id to boundsObj
      }.toMap()
      val offscreen = (result["offscreen"] as? List<String>)?.toSet() ?: emptySet()
      val occluded = (result["occluded"] as? List<String>)?.toSet() ?: emptySet()
      val skipped = (result["skipped"] as? List<String>) ?: emptyList()
      if (skipped.isNotEmpty()) {
        Console.log("[PlaywrightScreenState] computeElementVisibility skipped ${skipped.size} candidate(s): $skipped")
      }

      ElementVisibility(offscreen = offscreen, occluded = occluded, bounds = bounds)
    } catch (e: Exception) {
      // Don't swallow silently — observability for "filtering quietly off" failure modes.
      // Emit a discrete trace span so the failure is visible in trace.json artifacts
      // (CI silences Console.log; the trace recorder survives).
      TrailblazeTracer.trace(
        "computeElementVisibilityFailed",
        "screenState",
        args = mapOf(
          "exception" to (e::class.simpleName ?: "Exception"),
          "message" to (e.message ?: ""),
        ),
      ) { }
      Console.log("[PlaywrightScreenState] computeElementVisibility failed (${e::class.simpleName}: ${e.message}); falling back to no filtering")
      ElementVisibility.EMPTY
    }
  }

  /**
   * Parses a JS bounds map into [TrailblazeNode.Bounds], dropping zero/negative-size
   * rectangles. Used when downstream consumers (annotation overlay) only care about
   * paintable regions.
   */
  private fun Map<String, Any?>.toBoundsOrNull(): TrailblazeNode.Bounds? {
    val x = (this["x"] as? Number)?.toInt() ?: return null
    val y = (this["y"] as? Number)?.toInt() ?: return null
    val w = (this["w"] as? Number)?.toInt() ?: return null
    val h = (this["h"] as? Number)?.toInt() ?: return null
    if (w <= 0 || h <= 0) return null
    return TrailblazeNode.Bounds(left = x, top = y, right = x + w, bottom = y + h)
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
   * Tracks whether we've already logged the budget-exceeded warning for THIS screen-state
   * instance. Per-instance (not companion-level) so each state capture gets one warning;
   * a session that traverses several Wikipedia-class pages logs once per page rather than
   * once globally.
   */
  private var enrichmentBudgetExceededLogged = false

  /**
   * Enriches a [ViewHierarchyTreeNode] tree with bounds from the live page.
   *
   * Resolves bounds for the WHOLE tree in a single batched `page.evaluate`
   * ([computeViewHierarchyBoundsBatched]) instead of one Playwright locator round-trip
   * per node. The per-node path made ~2-3 CDP round-trips per node and, on DOM-heavy
   * pages (thousands of nodes), saturated [ENRICHMENT_BUDGET_MS] and shipped partial
   * bounds; the batched pass is a single round-trip regardless of tree size
   * (https://github.com/block/trailblaze/issues/199).
   *
   * The locator-based path survives as a bounded FALLBACK ([resolveNodeBoundsViaLocator])
   * for nodes the batch couldn't resolve — unmatched role/name, or a zero-size DOM rect,
   * which is the Compose-Web-Wasm case where accessibility overlay elements have no DOM
   * rect but valid positions in Playwright's accessibility tree. On normal pages that
   * fallback set is small, so [ENRICHMENT_BUDGET_MS] rarely bites.
   *
   * Populates [ViewHierarchyTreeNode.x1], [ViewHierarchyTreeNode.y1], [ViewHierarchyTreeNode.x2],
   * and [ViewHierarchyTreeNode.y2] so the UI Inspector's hover overlay can highlight elements on
   * the screenshot.
   */
  private fun enrichViewHierarchyWithBounds(
    tree: ViewHierarchyTreeNode,
  ): ViewHierarchyTreeNode {
    val batchedBounds = computeViewHierarchyBoundsBatched(tree)
    // Build ARIA descriptor occurrence counts to disambiguate duplicate elements for the
    // fallback locator path, and share one wall-clock budget across all fallback RPCs.
    val descriptorOccurrences = mutableMapOf<String, Int>()
    val deadlineMs = System.currentTimeMillis() + ENRICHMENT_BUDGET_MS
    val nextIndex = intArrayOf(0)
    return enrichNodeWithBounds(tree, batchedBounds, descriptorOccurrences, deadlineMs, nextIndex)
  }

  /**
   * Builds the ARIA descriptor string that [PlaywrightAriaSnapshot.resolveRef] and
   * [BATCH_VIEWPORT_CHECK_JS] both consume, from a node's role + name. Kept in one place
   * so the pre-order (role, name, nth) numbering in [computeViewHierarchyBoundsBatched]
   * and [enrichNodeWithBounds] can't drift apart.
   *
   * The inverse of [parseAriaDescriptor] (which recovers role/name from this same string
   * format for the compact-element-list enrichment path) — both must agree on the
   * `text: $name` / `$role "$name"` / `$role` shapes.
   */
  private fun boundsDescriptorFor(role: String, name: String?): String = when {
    name != null && role == "text" -> "text: $name"
    name != null -> "$role \"$name\""
    else -> role
  }

  /**
   * Returns this descriptor's occurrence count so far and records this occurrence,
   * i.e. the 0-based "nth" index used to disambiguate duplicate (role, name) pairs.
   * Shared by [computeViewHierarchyBoundsBatched] and [enrichNodeWithBounds] so the two
   * independent pre-order passes count occurrences identically — each still keeps its
   * own [descriptorOccurrences] map instance (one feeds the batch request, the other
   * feeds the locator fallback), but the counting logic itself isn't duplicated.
   */
  private fun MutableMap<String, Int>.nextOccurrence(descriptor: String): Int {
    val nth = getOrDefault(descriptor, 0)
    this[descriptor] = nth + 1
    return nth
  }

  /**
   * Resolves bounds for every node in [tree] in ONE `page.evaluate`, reusing
   * [BATCH_VIEWPORT_CHECK_JS]'s role/name/nth → element resolution (the same batched
   * resolver that powers [computeElementVisibility]). Returns a map from pre-order node
   * index to bounds; nodes the batch couldn't resolve — or whose DOM rect was zero-size
   * (dropped by [toBoundsOrNull]) — are simply absent, and the caller falls back to a
   * per-node locator lookup for those.
   *
   * The pre-order (role, name, nth) numbering here mirrors [enrichNodeWithBounds] exactly
   * (both are pure pre-order DFS over the same immutable tree), so the integer keys line
   * up between the two passes.
   */
  @Suppress("UNCHECKED_CAST")
  private fun computeViewHierarchyBoundsBatched(
    tree: ViewHierarchyTreeNode,
  ): Map<Int, TrailblazeNode.Bounds> = TrailblazeTracer.trace(
    "computeViewHierarchyBoundsBatched",
    "screenState",
  ) {
    val descriptorOccurrences = mutableMapOf<String, Int>()
    val elements = mutableListOf<Map<String, Any?>>()
    var index = 0
    fun collect(node: ViewHierarchyTreeNode) {
      // `className` is null only for malformed/non-Playwright-sourced nodes — a real ARIA
      // snapshot never emits a roleless entry, so this "generic" fallback is expected to
      // never actually match BATCH_VIEWPORT_CHECK_JS's roleNameMap (which excludes
      // computedRole == 'generic'). Such a node always resolves via the per-node locator
      // fallback below instead, same as before this batching pass existed.
      val role = node.className ?: "generic"
      val name = node.text
      val descriptor = boundsDescriptorFor(role, name)
      val nth = descriptorOccurrences.nextOccurrence(descriptor)
      elements.add(mapOf("id" to index.toString(), "role" to role, "name" to name, "nth" to nth))
      index++
      node.children.forEach { collect(it) }
    }
    collect(tree)
    if (elements.isEmpty()) return@trace emptyMap()

    try {
      val result = page.evaluate(
        BATCH_VIEWPORT_CHECK_JS,
        mapOf("elements" to elements, "vw" to viewportWidth, "vh" to viewportHeight),
      ) as? Map<String, Any?> ?: return@trace emptyMap()
      val rawBounds = result["bounds"] as? Map<String, Map<String, Any?>> ?: return@trace emptyMap()
      val resolved = rawBounds.mapNotNull { (id, b) ->
        val idx = id.toIntOrNull() ?: return@mapNotNull null
        val bounds = b.toBoundsOrNull() ?: return@mapNotNull null
        idx to bounds
      }.toMap()
      if (resolved.size < elements.size) {
        Console.log(
          "[PlaywrightScreenState] batch resolved ${resolved.size}/${elements.size} " +
            "viewHierarchy node(s); falling back to per-node locator bounds for the remainder",
        )
      }
      resolved
    } catch (e: Exception) {
      Console.log(
        "[PlaywrightScreenState] batched viewHierarchy bounds failed for ${elements.size} node(s) " +
          "(${e::class.simpleName}: ${e.message}); falling back to per-node locator bounds",
      )
      emptyMap()
    }
  }

  private fun enrichNodeWithBounds(
    node: ViewHierarchyTreeNode,
    batchedBounds: Map<Int, TrailblazeNode.Bounds>,
    descriptorOccurrences: MutableMap<String, Int>,
    deadlineMs: Long,
    nextIndex: IntArray,
  ): ViewHierarchyTreeNode {
    val index = nextIndex[0]
    nextIndex[0] = index + 1

    val role = node.className ?: "generic"
    val name = node.text
    val descriptor = boundsDescriptorFor(role, name)
    // Track nth occurrence for disambiguation of duplicate descriptors (needed by the
    // fallback locator path). Counted for EVERY node, batched or not, so a later
    // duplicate's nth stays correct even when earlier duplicates were served by the batch.
    val nthIndex = descriptorOccurrences.nextOccurrence(descriptor)

    // Prefer the batched DOM rect (one round-trip for the whole tree). Only when the batch
    // had no usable bounds for this node do we pay for a per-node locator round-trip — the
    // accessibility-aware path that still works on zero-DOM-rect Compose-Web-Wasm overlay
    // elements. The wall-clock budget guards only that fallback fan-out.
    val bounds = batchedBounds[index]
      ?: resolveNodeBoundsViaLocator(descriptor, nthIndex, deadlineMs)

    // Recurse into children even past the fallback budget so batched bounds already
    // resolved for the subtree are still applied — only the per-node locator RPCs stop.
    val enrichedChildren = node.children.map { child ->
      enrichNodeWithBounds(child, batchedBounds, descriptorOccurrences, deadlineMs, nextIndex)
    }

    return if (bounds != null) {
      node.copy(
        children = enrichedChildren,
        x1 = bounds.left,
        y1 = bounds.top,
        x2 = bounds.right,
        y2 = bounds.bottom,
      )
    } else {
      node.copy(children = enrichedChildren)
    }
  }

  /**
   * Fallback per-node bounds resolution via Playwright's accessibility-tree-aware locator
   * API, used only when [computeViewHierarchyBoundsBatched] produced no usable bounds for
   * the node. Bounded by a shared wall-clock [deadlineMs]: once the budget is blown this
   * returns null (a tolerated "no bounds" state downstream) rather than locking the next
   * tool's pre-action log path for minutes on content-heavy pages. Logs once per affected
   * screen-state so future "missing bounds" investigations have a greppable anchor.
   */
  private fun resolveNodeBoundsViaLocator(
    descriptor: String,
    nthIndex: Int,
    deadlineMs: Long,
  ): TrailblazeNode.Bounds? {
    if (System.currentTimeMillis() > deadlineMs) {
      if (!enrichmentBudgetExceededLogged) {
        Console.log(
          "[PlaywrightScreenState] viewHierarchy enrichment budget (${ENRICHMENT_BUDGET_MS}ms) " +
            "exceeded — returning partial bounds for remaining nodes.",
        )
        enrichmentBudgetExceededLogged = true
      }
      return null
    }
    return try {
      val elementRef = PlaywrightAriaSnapshot.ElementRef(descriptor, nthIndex)
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() > 0) {
        val box = locator.boundingBox(
          Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs),
        )
        if (box != null && box.width > 0 && box.height > 0) {
          TrailblazeNode.Bounds(
            left = box.x.toInt(),
            top = box.y.toInt(),
            right = (box.x + box.width).toInt(),
            bottom = (box.y + box.height).toInt(),
          )
        } else {
          null
        }
      } else {
        null
      }
    } catch (_: Exception) {
      // Resolution failed — leave without bounds
      null
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

    /**
     * Wall-clock cap on the per-node locator FALLBACK during `viewHierarchy` enrichment.
     *
     * `enrichViewHierarchyWithBounds` now resolves the whole tree's bounds in a single
     * batched `page.evaluate` (`computeViewHierarchyBoundsBatched`) — one browser
     * round-trip regardless of tree size. Only nodes the batch can't resolve (unmatched
     * role/name, or zero-size DOM rects such as Compose-Web-Wasm overlay elements) fall
     * back to a per-node `Locator.count()` + `boundingBox()` RPC. This budget bounds that
     * residual fallback fan-out.
     *
     * Before batching, the per-node path ran for EVERY node: on a typical app page (a few
     * hundred nodes at ~100 ms/RPC) it finished in a few seconds, but on a content-heavy
     * page (Wikipedia article, ~thousands of nodes) the serial RPC traffic stretched into
     * many minutes and wedged the next tool's pre-action diagnostic log path — a Wikipedia
     * trail hung for 10+ minutes on every CI run, with the thread dump pinned inside
     * `LocatorImpl.count()` (https://github.com/block/trailblaze/issues/199). Batching removes
     * that as the common case; the budget survives as a backstop for pages with an unusually
     * large unmatched-node set.
     *
     * 5 s comfortably covers the normal case; a page whose fallback set blows the budget
     * ships partial bounds rather than locking the agent loop. Downstream consumers already
     * tolerate `bounds == null`, so the contract is preserved.
     *
     * Left at 5 s rather than tightened after batching landed: the fallback set is *usually*
     * tiny now, but its size still depends on how much of a given page's accessibility tree
     * the batch's role/name matching misses, which varies by app (e.g. more on pages heavy
     * with Compose-Web-Wasm overlays). 5 s is cheap insurance against a worse case we haven't
     * measured yet; revisit with production fallback-set-size data (see the size-mismatch log
     * in `computeViewHierarchyBoundsBatched`) before tightening it.
     */
    private const val ENRICHMENT_BUDGET_MS = 5_000L

    /** Pattern matching element ID references like [e42] in compact element list text. */
    private val ELEMENT_ID_PATTERN = Regex("""\[e(\d+)]""")

    /** Pattern matching ARIA landmark/container header lines like "navigation:", "  main:", or "navigation "Top nav":" */
    private val LANDMARK_HEADER_PATTERN = Regex(
      """^\s*(?:navigation|main|banner|contentinfo|complementary|form|dialog|region|list|table)(?:\s+".*?")?:\s*$""",
    )

    /**
     * JavaScript function that classifies each ARIA-tree candidate as offscreen,
     * occluded, or visible — in one page evaluation.
     *
     * Receives `{elements: [{id, role, name, nth}], vw, vh}` (where `elements`
     * enumerates the candidates the LLM cares about) and returns:
     * ```
     * {
     *   bounds:    { id: {x,y,w,h}, ... },   // every matched candidate's bbox
     *   offscreen: [id, ...],                // bbox outside viewport / zero-size
     *   occluded:  [id, ...],                // in viewport but covered per Playwright's hit-test
     *   skipped:   [id, ...]                 // threw during classification — fail-open (treated as visible)
     * }
     * ```
     *
     * The occlusion algorithm is a 1:1 port of Playwright's `expectHitTarget`
     * (the actionability check that produces `<el> intercepts pointer events`
     * errors during `locator.click()`). Specifically:
     *   1. Walk UP from the candidate through shadow roots, collecting every
     *      enclosing root (Document and any open ShadowRoots).
     *   2. From outermost root inward, call `root.elementsFromPoint(cx, cy)`.
     *      The topmost element in the innermost root is `hitElement`.
     *   3. Walk UP from `hitElement` via `assignedSlot ?? parentElementOrShadowHost`.
     *      If the walk reaches the candidate, it's visible. Otherwise occluded.
     *
     * **Upstream source — pinned to the Playwright version we depend on
     * (`libs.versions.toml#playwright = "1.59.0"`, commit `01b2b153`):**
     *   - `expectHitTarget`:
     *     <https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/injectedScript.ts#L955>
     *   - `parentElementOrShadowHost`:
     *     <https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/domUtils.ts#L43>
     *   - `enclosingShadowRootOrDocument`:
     *     <https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/domUtils.ts#L52>
     *
     * Compare against `main` (line numbers will drift but function names are
     * stable) when bumping the Playwright dependency:
     *   - <https://github.com/microsoft/playwright/blob/main/packages/injected/src/injectedScript.ts>
     *   - <https://github.com/microsoft/playwright/blob/main/packages/injected/src/domUtils.ts>
     *
     * We intentionally match it byte-for-byte (modulo the click-failure
     * description, which we don't need) so our visibility classification
     * tracks Playwright's actionability decision. If upstream changes the
     * algorithm, the right move is to re-port — not to drift locally.
     *
     * **One deliberate divergence:** the hit-test point is the center of the
     * intersection of the element rect and the viewport, not the element's
     * geometric center. Playwright's `expectHitTarget` is called from
     * `checkHitTarget`, which receives a viewport-clamped click point from
     * its caller; we have to do that clamping ourselves so partially-visible
     * elements (tall sections, large tables, edge-clipped content) hit-test
     * at a point inside the viewport. Don't "fix" this back to the geometric
     * center without understanding why — see the clamp at the call site.
     *
     * **Aria-hidden filter** (role/name map only): elements with
     * `aria-hidden="true"` and elements with `display: none` / `visibility:
     * hidden` are excluded when building the role/name map (so candidate
     * nth-indices align with Playwright's `ariaSnapshot()`), but NOT when
     * doing the hit-test. Visual occlusion is about pixels, and aria-hidden
     * elements still paint.
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

      // Recursive descent that pierces open shadow roots. Real production
      // dashboards (Square Dashboard, GitHub, modern enterprise UIs) wrap most
      // interactive content in Web Components with shadow DOM — `<market-button>`,
      // `<market-tile>`, `<sl-button>`, etc. `document.querySelectorAll('*')`
      // returns the shadow HOST but stops at the shadow boundary, so the
      // role/name map ends up missing ~87% of the candidates that Playwright's
      // accessibility-tree-aware `ariaSnapshot()` does see (those traverse the
      // shadow tree). Walking shadow roots here brings the map back in sync.
      //
      // Order matters for nth-index alignment with Playwright's `ariaSnapshot()`:
      // host element first, then its shadow children in document order, then
      // its light-DOM children. Closed shadow roots are inaccessible by design;
      // only `mode: 'open'` shadow roots are traversed.
      function* walkAllInclShadow(root) {
        if (!root || root.nodeType !== 1) return;
        yield root;
        const sr = root.shadowRoot;
        if (sr) {
          for (const c of sr.children) {
            yield* walkAllInclShadow(c);
          }
        }
        for (const c of root.children) {
          yield* walkAllInclShadow(c);
        }
      }

      // Walk DOM in document order (matching the accessibility tree traversal),
      // build lookup: "role\nname" -> [el, el, ...]
      // Skips elements hidden from the accessibility tree to keep nth-indices aligned
      // with Playwright's ariaSnapshot() output.
      const roleNameMap = {};
      for (const el of walkAllInclShadow(document.documentElement)) {
        if (isHiddenFromAccessibilityTree(el)) continue;
        const role = getRole(el);
        if (!role) continue;
        const name = getName(el, role);
        const key = name != null ? role + '\n' + name : role;
        if (!roleNameMap[key]) roleNameMap[key] = [];
        roleNameMap[key].push(el);
      }

      // ---- Direct port of Playwright's expectHitTarget composed-tree algorithm ----
      // Pinned to Playwright v1.59.0 (commit 01b2b153) — the version in libs.versions.toml.
      //   expectHitTarget                https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/injectedScript.ts#L955
      //   parentElementOrShadowHost      https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/domUtils.ts#L43
      //   enclosingShadowRootOrDocument  https://github.com/microsoft/playwright/blob/v1.59.0/packages/injected/src/domUtils.ts#L52
      // Kept byte-identical to upstream so our occlusion verdict tracks Playwright's
      // actionability decision. Re-port — don't patch locally — when the Playwright
      // dependency moves.

      function parentElementOrShadowHost(element) {
        if (element.parentElement) return element.parentElement;
        if (!element.parentNode) return null;
        if (element.parentNode.nodeType === 11 /* DOCUMENT_FRAGMENT_NODE */
            && element.parentNode.host) {
          return element.parentNode.host;
        }
        return null;
      }

      function enclosingShadowRootOrDocument(element) {
        let node = element;
        while (node.parentNode) node = node.parentNode;
        if (node.nodeType === 11 /* DOCUMENT_FRAGMENT_NODE */
            || node.nodeType === 9 /* DOCUMENT_NODE */) {
          return node;
        }
        return null;
      }

      function isHitTargetReached(hitPoint, targetElement) {
        // Collect every enclosing root from the target up through shadow hosts.
        const roots = [];
        let parentElement = targetElement;
        while (parentElement) {
          const root = enclosingShadowRootOrDocument(parentElement);
          if (!root) break;
          roots.push(root);
          if (root.nodeType === 9 /* DOCUMENT_NODE */) break;
          parentElement = root.host;
        }
        // Walk outermost-root-first; each root's topmost-at-point must be the
        // host of the next inner root, ending at an element in the target's
        // own scope.
        let hitElement;
        for (let index = roots.length - 1; index >= 0; index--) {
          const root = roots[index];
          const els = root.elementsFromPoint(hitPoint.x, hitPoint.y);
          const single = root.elementFromPoint(hitPoint.x, hitPoint.y);
          if (single && els[0] && parentElementOrShadowHost(single) === els[0]) {
            const style = window.getComputedStyle(single);
            if (style && style.display === 'contents') {
              els.unshift(single);
            }
          }
          if (els[0] && els[0].shadowRoot === root && els[1] === single) {
            els.shift();
          }
          const innerElement = els[0];
          if (!innerElement) break;
          hitElement = innerElement;
          if (index && innerElement !== roots[index - 1].host) break;
        }
        // Walk up the composed tree from hitElement; reach the target → visible.
        while (hitElement && hitElement !== targetElement) {
          hitElement = hitElement.assignedSlot != null
            ? hitElement.assignedSlot
            : parentElementOrShadowHost(hitElement);
        }
        return hitElement === targetElement;
      }

      // ---- End Playwright port ----

      const bounds = {};
      const offscreen = [];
      const occluded = [];
      const skipped = [];

      for (const elem of elements) {
        try {
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

          // Zero-size or fully-outside-viewport → offscreen. (Includes elements
          // far below the fold on long docs pages — those are scroll-reachable
          // but not present in the screenshot, so the LLM shouldn't see them
          // in the current-view text either.)
          const inViewport = rect.bottom > 0 && rect.top < vh
            && rect.right > 0 && rect.left < vw
            && rect.width > 0 && rect.height > 0;
          if (!inViewport) {
            offscreen.push(elem.id);
            continue;
          }

          // Hit-test at the center of the element's IN-VIEWPORT region —
          // not its geometric center. A wide table row or tall section that
          // crosses the viewport edge has its geometric center outside the
          // viewport, where elementsFromPoint returns nothing and the element
          // would be falsely marked occluded. Clamping to the intersection
          // keeps the hit point on visible pixels.
          const ix0 = Math.max(rect.left, 0);
          const iy0 = Math.max(rect.top, 0);
          const ix1 = Math.min(rect.right, vw);
          const iy1 = Math.min(rect.bottom, vh);
          const cx = (ix0 + ix1) / 2;
          const cy = (iy0 + iy1) / 2;
          if (!isHitTargetReached({ x: cx, y: cy }, el)) {
            occluded.push(elem.id);
          }
        } catch (_) {
          // A single bad candidate (detached during snapshot, getBoundingClientRect
          // throws on a non-Element-shaped match, etc.) must not zero out
          // visibility for the rest of the batch. Skip it and continue.
          skipped.push(elem.id);
        }
      }

      return { bounds, offscreen, occluded, skipped };
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
