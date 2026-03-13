package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
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
      PlaywrightAriaSnapshot.captureAriaSnapshot(page, captureTimeoutMs).yaml
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
    TrailblazeTracer.trace("viewHierarchyTextRepresentation", "screenState") {
      val baseText = if (requestedDetails.isEmpty()) {
        compactAriaElements.text
      } else {
        enrichCompactElementList(compactAriaElements)
      }
      val includeOffscreen = ViewHierarchyDetail.OFFSCREEN_ELEMENTS in requestedDetails
      val elementsText = TrailblazeTracer.trace("handleOffscreenElements", "screenState") {
        handleOffscreenElements(baseText, compactAriaElements, includeOffscreen)
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
    appendLine("Browser: ${browserEngine.displayName} ($viewportSizeClass)")

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
    appendLine("Viewport: ${viewportWidth}x${viewportHeight}")

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
   * Handles offscreen elements in the compact list based on the [includeOffscreen] flag.
   *
   * - **Default** ([includeOffscreen] = false): Removes offscreen element lines from the
   *   text and appends a summary line showing how many were hidden. Also removes landmark
   *   header lines (e.g., `navigation:`) that become empty after all their children are
   *   filtered out.
   * - **OFFSCREEN_ELEMENTS requested** ([includeOffscreen] = true): Keeps all elements
   *   and annotates offscreen ones with `(offscreen)` (legacy behavior).
   *
   * Uses Playwright's native locator resolution via [PlaywrightAriaSnapshot.resolveElementRef]
   * to check each element's bounding box. This guarantees correct ARIA role matching
   * (including implicit roles like `<h2>` → heading) without reimplementing accessibility
   * tree logic in JavaScript.
   *
   * For typical pages with 10-30 interactive elements, this adds ~50-100ms — acceptable
   * since it saves the LLM from wasting actions on invisible elements.
   *
   * Elements whose position can't be determined (e.g., detached from DOM) are left
   * unchanged — the LLM can infer from the screenshot whether they're visible.
   */
  private fun handleOffscreenElements(
    text: String,
    compact: PlaywrightAriaSnapshot.CompactAriaElements,
    includeOffscreen: Boolean,
  ): String {
    if (compact.elementIdMapping.isEmpty()) return text

    // Check each element's bounding box against the viewport.
    // Uses a fast locator.count() pre-check to avoid the expensive boundingBox()
    // timeout when a locator can't resolve (e.g., stale ARIA descriptor after DOM change).
    val offscreenIds = mutableSetOf<String>()
    val boundingBoxOptions = Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs)
    for ((id, elementRef) in compact.elementIdMapping) {
      try {
        val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
        // count() returns 0 immediately if no matching elements — avoids a full timeout
        if (locator.count() == 0) continue
        val box = locator.boundingBox(boundingBoxOptions)
        if (box != null) {
          // Element is offscreen if it doesn't overlap with the viewport at all
          val inViewport = box.y + box.height > 0 && box.y < viewportHeight &&
            box.x + box.width > 0 && box.x < viewportWidth &&
            box.width > 0 && box.height > 0
          if (!inViewport) {
            offscreenIds.add(id)
          }
        }
        // null bounding box = can't determine position, leave unannotated
      } catch (_: Exception) {
        // Resolution failed — leave unannotated
      }
    }

    if (offscreenIds.isEmpty()) return text

    if (includeOffscreen) {
      // Annotate offscreen elements with "(offscreen)" but keep them in the list
      return text.lines().joinToString("\n") { line ->
        val match = ELEMENT_ID_PATTERN.find(line) ?: return@joinToString line
        val elementId = "e${match.groupValues[1]}"
        if (elementId in offscreenIds) {
          "$line (offscreen)"
        } else {
          line
        }
      }
    }

    // Default: remove offscreen element lines and empty landmark headers
    val filteredLines = mutableListOf<String>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val match = ELEMENT_ID_PATTERN.find(line)
      if (match != null) {
        val elementId = "e${match.groupValues[1]}"
        if (elementId in offscreenIds) {
          i++
          continue // skip offscreen element lines
        }
      }
      filteredLines.add(line)
      i++
    }

    // Remove landmark header lines that have no remaining children.
    // A landmark header is a line like "navigation:" or "  main:" followed by indented
    // element lines. If all children were removed, the header is now empty.
    // Loop until stable to handle nested landmarks (e.g., navigation: > banner: where
    // both become empty after offscreen elements are removed).
    var result = filteredLines.toMutableList()
    var changed = true
    while (changed) {
      changed = false
      val pass = mutableListOf<String>()
      for (j in result.indices) {
        val line = result[j]
        if (LANDMARK_HEADER_PATTERN.matches(line)) {
          // Check if the next non-blank line is indented more (i.e., a child)
          val nextContentLine = result.subList(j + 1, result.size)
            .firstOrNull { it.isNotBlank() }
          if (nextContentLine == null || !nextContentLine.startsWith(line.takeWhile { it == ' ' } + "  ")) {
            changed = true
            continue // skip empty landmark header
          }
        }
        pass.add(line)
      }
      result = pass
    }

    val hiddenCount = offscreenIds.size
    result.add("($hiddenCount offscreen elements hidden — request OFFSCREEN_ELEMENTS for full list)")
    return result.joinToString("\n")
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
  ): String {
    val enrichBounds = ViewHierarchyDetail.BOUNDS in requestedDetails
    val enrichCss = ViewHierarchyDetail.CSS_SELECTORS in requestedDetails

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
   * Scaled screenshot for LLM requests. Currently identical to [screenshotBytes] since
   * Playwright does not use set-of-mark annotations.
   */
  override val annotatedScreenshotBytes: ByteArray? by lazy {
    screenshotBytes
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
  override val viewHierarchyOriginal: ViewHierarchyTreeNode by lazy {
    val tree = PlaywrightAriaSnapshot.ariaSnapshotToViewHierarchy(ariaSnapshotYaml)
    enrichViewHierarchyWithBounds(tree)
  }

  override val viewHierarchy: ViewHierarchyTreeNode by lazy {
    viewHierarchyOriginal
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
    var centerPoint: String? = null
    var dimensions: String? = null
    try {
      val elementRef = PlaywrightAriaSnapshot.ElementRef(descriptor, nthIndex)
      val locator = PlaywrightAriaSnapshot.resolveElementRef(page, elementRef)
      if (locator.count() > 0) {
        val box = locator.boundingBox(
          Locator.BoundingBoxOptions().setTimeout(captureTimeoutMs),
        )
        if (box != null && box.width > 0 && box.height > 0) {
          val cx = (box.x + box.width / 2).toInt()
          val cy = (box.y + box.height / 2).toInt()
          centerPoint = "$cx,$cy"
          dimensions = "${box.width.toInt()}x${box.height.toInt()}"
        }
      }
    } catch (_: Exception) {
      // Resolution failed — leave without bounds
    }

    // Enrich children recursively
    val enrichedChildren = node.children.map { child ->
      enrichNodeWithLocatorBounds(child, descriptorOccurrences)
    }

    return if (centerPoint != null) {
      node.copy(
        children = enrichedChildren,
        centerPoint = centerPoint,
        dimensions = dimensions,
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
     * Timeout for Playwright API calls during screen state capture (ms).
     * Kept short since these calls should be near-instant on a responsive page.
     * If a call exceeds this, it's caught and degraded gracefully.
     */
    private const val CAPTURE_TIMEOUT_MS = 500.0

    /** Maximum number of hidden CSS-targetable elements to surface to the LLM. */
    private const val MAX_HIDDEN_CSS_ELEMENTS = 50

    /** Pattern matching element ID references like [e42] in compact element list text. */
    private val ELEMENT_ID_PATTERN = Regex("""\[e(\d+)]""")

    /** Pattern matching ARIA landmark/container header lines like "navigation:", "  main:", or "navigation "Top nav":" */
    private val LANDMARK_HEADER_PATTERN = Regex(
      """^\s*(?:navigation|main|banner|contentinfo|complementary|form|dialog|region|list|table)(?:\s+".*?")?:\s*$""",
    )

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
  }
}
