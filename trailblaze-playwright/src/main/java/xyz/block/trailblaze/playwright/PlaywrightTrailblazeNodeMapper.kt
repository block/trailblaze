package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * Maps Playwright ARIA snapshot YAML to a [TrailblazeNode] tree with [DriverNodeDetail.Web].
 *
 * Uses a two-phase approach:
 * 1. **ARIA snapshot** — parsed into a tree with roles, names, and heading levels.
 *    This comes from Playwright's built-in accessibility tree, which handles implicit
 *    ARIA role computation and accessible name calculation correctly.
 * 2. **Single DOM evaluation** — one `page.evaluate()` call walks the entire DOM and
 *    captures bounding boxes for all visible elements via `getBoundingClientRect()`.
 *    Also captures `data-testid` and `id` attributes for [DriverNodeDetail.Web.cssSelector]
 *    and [DriverNodeDetail.Web.dataTestId].
 *
 * The bounds from phase 2 are matched back to ARIA nodes by comparing ARIA role+name
 * against the DOM element's computed role and accessible name attributes.
 */
object PlaywrightTrailblazeNodeMapper {

  /**
   * Maps an ARIA snapshot YAML string to a [TrailblazeNode] tree.
   *
   * This is the pure-YAML path (no live page needed). Produces a tree with correct
   * roles, names, heading levels, and nth-index disambiguation, but without bounds
   * or CSS selectors. Use [mapWithBounds] when a live [Page] is available.
   *
   * @param yaml The ARIA snapshot YAML from Playwright's `ariaSnapshot()` API.
   * @return The root [TrailblazeNode], or null if the YAML is blank/empty.
   */
  fun mapAriaSnapshotToTrailblazeNode(yaml: String): TrailblazeNode? {
    if (yaml.isBlank()) return null

    val lines = yaml.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return null

    var nextNodeId = 1L
    val descriptorOccurrences = mutableMapOf<String, Int>()
    return parseLines(lines, null, 0, { nextNodeId++ }, descriptorOccurrences, null).first
  }

  /**
   * Maps a boxed ARIA snapshot to a [TrailblazeNode] tree whose bounds are the snapshot's own
   * per-node boxes, in viewport coordinates, so every element node is located, not just those a
   * DOM walk can match back by role and name.
   *
   * Given [page], it also runs [mapWithBounds]'s DOM walk for the `cssSelector` and `dataTestId`
   * that walk stamps on the nodes it matches, so the tree is [mapWithBounds]'s — same nodes,
   * same selector fields — with more boxes, and a selector recorded against one resolves
   * against the other.
   */
  fun mapBoxedSnapshot(snapshot: PlaywrightAriaSnapshot.BoxedAriaSnapshot, page: Page? = null): TrailblazeNode? {
    if (snapshot.lines.isEmpty()) return null
    var nextNodeId = 1L
    val descriptorOccurrences = mutableMapOf<String, Int>()
    val tree = parseLines(snapshot.lines, snapshot.boxes, 0, { nextNodeId++ }, descriptorOccurrences, null).first
      ?: return null
    if (page == null) return tree
    val domBounds = TrailblazeTracer.trace("captureDomBounds", "screenState") { captureDomBounds(page) }
    // The walk's boxes are page coordinates; this tree's are the viewport's, the screenshot's.
    return if (domBounds.isEmpty()) tree else enrichTreeWithBounds(tree, domBounds, takeBounds = false)
  }

  /**
   * Maps an ARIA snapshot to a [TrailblazeNode] tree and enriches it with bounds
   * from a single DOM evaluation.
   *
   * This is the preferred entry point when a live [Page] is available. It:
   * 1. Parses the ARIA YAML into a tree (roles, names, heading levels)
   * 2. Runs one `page.evaluate()` to capture all DOM element bounds
   * 3. Matches bounds back to ARIA nodes and populates [TrailblazeNode.Bounds]
   *
   * @param yaml The ARIA snapshot YAML.
   * @param page The live Playwright page for DOM evaluation.
   * @param viewportWidth Viewport width for offscreen detection.
   * @param viewportHeight Viewport height for offscreen detection.
   * @return The root [TrailblazeNode] with bounds populated, or null if YAML is blank.
   */
  fun mapWithBounds(
    yaml: String,
    page: Page,
    viewportWidth: Int,
    viewportHeight: Int,
  ): TrailblazeNode? {
    val tree = mapAriaSnapshotToTrailblazeNode(yaml) ?: return null

    val domBounds = TrailblazeTracer.trace("captureDomBounds", "screenState") {
      captureDomBounds(page)
    }
    if (domBounds.isEmpty()) return tree

    return TrailblazeTracer.trace("enrichTreeWithBounds", "screenState") {
      enrichTreeWithBounds(tree, domBounds)
    }
  }

  // ---------------------------------------------------------------------------
  // ARIA YAML parsing → TrailblazeNode tree
  // ---------------------------------------------------------------------------

  /**
   * Recursively parses ARIA YAML lines into a [TrailblazeNode] tree.
   *
   * @return Pair of (parsed node, index of the next unprocessed line).
   */
  private fun parseLines(
    lines: List<String>,
    boxes: List<TrailblazeNode.Bounds?>?,
    startIndex: Int,
    nextId: () -> Long,
    descriptorOccurrences: MutableMap<String, Int>,
    parentBounds: TrailblazeNode.Bounds?,
  ): Pair<TrailblazeNode?, Int> {
    if (startIndex >= lines.size) return null to startIndex

    val rootLine = lines[startIndex]
    val rootIndent = rootLine.indexOfFirst { it != ' ' && it != '-' }
    val rootParsed = parseAriaLine(rootLine)
    // A text run has no element of its own, so it takes the box of the nearest element it sits in.
    val bounds = boxes?.getOrNull(startIndex) ?: parentBounds?.takeIf { rootParsed.role == "text" }

    val children = mutableListOf<TrailblazeNode>()
    val properties = mutableMapOf<String, String>()
    var i = startIndex + 1
    while (i < lines.size) {
      val lineIndent = lines[i].indexOfFirst { it != ' ' && it != '-' }
      if (lineIndent <= rootIndent) break

      // `- /url: …` and `- /placeholder: …` describe their parent; they are not page text.
      PROPERTY_LINE.matchEntire(lines[i])?.let { match ->
        properties[match.groupValues[1]] = yamlScalar(match.groupValues[2])
        i++
        continue
      }
      val (child, nextIndex) = parseLines(lines, boxes, i, nextId, descriptorOccurrences, bounds ?: parentBounds)
      if (child != null) children.add(child)
      i = nextIndex
    }

    val descriptor = buildAriaDescriptor(rootParsed)
    val occurrenceIndex = descriptorOccurrences.getOrDefault(descriptor, 0)
    descriptorOccurrences[descriptor] = occurrenceIndex + 1

    val node = TrailblazeNode(
      nodeId = nextId(),
      children = children,
      // Without boxes, populated later by enrichTreeWithBounds.
      bounds = bounds,
      driverDetail = DriverNodeDetail.Web(
        ariaRole = rootParsed.role,
        ariaName = rootParsed.name,
        ariaDescriptor = descriptor,
        headingLevel = rootParsed.headingLevel,
        nthIndex = occurrenceIndex,
        isInteractive = rootParsed.ariaRole in INTERACTIVE_ROLES,
        isLandmark = rootParsed.ariaRole in LANDMARK_ROLES,
        placeholder = properties["placeholder"],
        url = properties["url"],
      ),
    )
    return node to i
  }

  // ---------------------------------------------------------------------------
  // ARIA line parsing (mirrors PlaywrightAriaSnapshot.parseAriaLine + attributes)
  // ---------------------------------------------------------------------------

  private data class AriaLineParsed(
    val role: String,
    val ariaRole: AriaRole,
    val name: String?,
    val headingLevel: Int?,
    val attributes: Map<String, String>,
  )

  /**
   * Parses a single ARIA YAML line, extracting role, name, heading level, and
   * any trailing `[key=value]` or `[flag]` attributes.
   */
  private fun parseAriaLine(line: String): AriaLineParsed {
    val trimmed = line.trimStart(' ', '-').trim()

    // Extract trailing attributes like [level=1] [checked] [disabled]
    val attributes = parseAttributes(trimmed)
    val headingLevel = attributes["level"]?.toIntOrNull()

    // Pattern: role "name" [attrs]
    QUOTED_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(
        role = role,
        ariaRole = ariaRoleFromString(role),
        name = match.groupValues[2],
        headingLevel = headingLevel,
        attributes = attributes,
      )
    }

    // Pattern: role: text content
    COLON_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(
        role = role,
        ariaRole = ariaRoleFromString(role),
        name = match.groupValues[2],
        headingLevel = headingLevel,
        attributes = attributes,
      )
    }

    // Pattern: role: (container with children)
    CONTAINER_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(
        role = role,
        ariaRole = ariaRoleFromString(role),
        name = null,
        headingLevel = headingLevel,
        attributes = attributes,
      )
    }

    // Fallback: treat entire line as text
    return AriaLineParsed(
      role = "text",
      ariaRole = AriaRole.GENERIC,
      name = trimmed.ifBlank { null },
      headingLevel = null,
      attributes = emptyMap(),
    )
  }

  /** Extracts `[key=value]` and `[flag]` attributes from trailing brackets. */
  private fun parseAttributes(line: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    ATTR_PATTERN.findAll(line).forEach { match ->
      val key = match.groupValues[1]
      val value = match.groupValues[2].ifEmpty { "true" }
      result[key] = value
    }
    return result
  }

  /** Builds an ARIA descriptor string that [PlaywrightAriaSnapshot.resolveRef] can consume. */
  private fun buildAriaDescriptor(parsed: AriaLineParsed): String {
    return when {
      parsed.name != null && parsed.role == "text" -> "text: ${parsed.name}"
      parsed.name != null -> "${parsed.role} \"${parsed.name}\""
      else -> parsed.role
    }
  }

  private fun ariaRoleFromString(role: String): AriaRole =
    try {
      AriaRole.valueOf(role.uppercase())
    } catch (_: IllegalArgumentException) {
      AriaRole.GENERIC
    }

  // -- Regex patterns --

  private val PROPERTY_LINE = Regex("""^\s*-\s+/(\w+):\s*(.*)$""")

  /** A YAML scalar as Playwright writes one: bare, or quoted when it holds YAML syntax. */
  internal fun yamlScalar(raw: String): String {
    val v = raw.trim()
    return when {
      v.length >= 2 && v.startsWith('"') && v.endsWith('"') ->
        v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
      v.length >= 2 && v.startsWith('\'') && v.endsWith('\'') -> v.substring(1, v.length - 1).replace("''", "'")
      else -> v
    }
  }

  private val QUOTED_ROLE_PATTERN = Regex("""^(\w+)\s+"(.+?)".*$""")
  private val COLON_ROLE_PATTERN = Regex("""^(\w+):\s*(.+)$""")
  private val CONTAINER_ROLE_PATTERN = Regex("""^(\w+):?\s*$""")
  private val ATTR_PATTERN = Regex("""\[(\w+)(?:=([^\]]*))?\]""")

  // -- Role classification sets --

  private val INTERACTIVE_ROLES = setOf(
    AriaRole.BUTTON, AriaRole.LINK, AriaRole.TEXTBOX, AriaRole.COMBOBOX,
    AriaRole.SEARCHBOX, AriaRole.CHECKBOX, AriaRole.RADIO, AriaRole.SWITCH,
    AriaRole.TAB, AriaRole.MENUITEM, AriaRole.OPTION, AriaRole.SLIDER,
    AriaRole.SPINBUTTON,
  )

  private val LANDMARK_ROLES = setOf(
    AriaRole.NAVIGATION, AriaRole.MAIN, AriaRole.BANNER, AriaRole.CONTENTINFO,
    AriaRole.COMPLEMENTARY, AriaRole.FORM, AriaRole.DIALOG, AriaRole.REGION,
    AriaRole.LIST, AriaRole.TABLE,
  )

  // ---------------------------------------------------------------------------
  // Single-call DOM bounds capture
  // ---------------------------------------------------------------------------

  /**
   * Element bounds and metadata captured from the DOM in a single `page.evaluate()`.
   *
   * Each entry represents a visible DOM element with its bounding rectangle and
   * optional identifiers (ARIA role, label, id, data-testid) for matching back
   * to ARIA snapshot nodes.
   */
  data class DomElementBounds(
    val tag: String,
    val ariaRole: String?,
    val ariaLabel: String?,
    val textContent: String?,
    val id: String?,
    val dataTestId: String?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
  )

  /**
   * Captures bounding boxes and identifiers for all visible DOM elements in a single
   * `page.evaluate()` call.
   *
   * Walks the entire DOM tree in JavaScript, calling `getBoundingClientRect()` on each
   * visible element. Returns a flat list of [DomElementBounds] with page-coordinate
   * bounds (scroll offset applied) and element identifiers for matching.
   */
  internal fun captureDomBounds(page: Page): List<DomElementBounds> {
    return try {
      // One JSON string, not an array of objects: Playwright ships an array one object at a
      // time, which on a 4,000-element page costs 20ms more than the walk itself.
      val json = page.evaluate(DOM_BOUNDS_SCRIPT) as? String ?: return emptyList()
      Json.parseToJsonElement(json).jsonArray.map { element ->
        val entry = element.jsonObject
        fun string(key: String) = entry[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String) = entry[key]?.jsonPrimitive?.intOrNull ?: 0
        DomElementBounds(
          tag = string("tag") ?: "",
          ariaRole = string("ariaRole"),
          ariaLabel = string("ariaLabel"),
          textContent = string("textContent"),
          id = string("id"),
          dataTestId = string("dataTestId"),
          x = int("x"),
          y = int("y"),
          width = int("w"),
          height = int("h"),
        )
      }
    } catch (e: Exception) {
      Console.log("Failed to capture DOM bounds: ${e.message}")
      emptyList()
    }
  }

  /**
   * JavaScript that walks the DOM tree and returns element bounds + identifiers.
   *
   * Captures for each visible element:
   * - Bounding rectangle (page coordinates, with scroll offset)
   * - HTML tag name
   * - Explicit ARIA role and aria-label
   * - Direct text content (first 100 chars, for matching to ARIA name)
   * - id and data-testid attributes
   *
   * Skips invisible elements (display:none, visibility:hidden, zero-size) and
   * non-element nodes. Elements are returned in DOM order (pre-order DFS).
   */
  private val DOM_BOUNDS_SCRIPT = """
    () => {
      const results = [];
      const scrollX = window.scrollX;
      const scrollY = window.scrollY;

      function walkDOM(node) {
        if (node.nodeType !== 1) return;

        const el = node;
        const rect = el.getBoundingClientRect();

        if (rect.width > 0 && rect.height > 0) {
          const style = window.getComputedStyle(el);
          if (style.display !== 'none' && style.visibility !== 'hidden') {
            const tag = el.tagName.toLowerCase();
            const explicitRole = el.getAttribute('role') || null;
            const ariaLabel = el.getAttribute('aria-label') || null;

            let textContent = null;
            for (const child of el.childNodes) {
              if (child.nodeType === 3) {
                const t = child.textContent.trim();
                if (t) { textContent = (textContent || '') + t; }
              }
            }
            if (textContent && textContent.length > 100) {
              textContent = textContent.substring(0, 100);
            }

            const id = el.id || null;
            const dataTestId = el.getAttribute('data-testid')
              || el.getAttribute('data-test-id') || null;

            results.push({
              tag: tag,
              ariaRole: explicitRole,
              ariaLabel: ariaLabel,
              textContent: textContent,
              id: id,
              dataTestId: dataTestId,
              x: Math.round(rect.x + scrollX),
              y: Math.round(rect.y + scrollY),
              w: Math.round(rect.width),
              h: Math.round(rect.height),
            });
          }
        }

        for (const child of el.children) {
          walkDOM(child);
        }
      }

      walkDOM(document.documentElement);
      return JSON.stringify(results);
    }
  """.trimIndent()

  // ---------------------------------------------------------------------------
  // Bounds matching: correlate DOM elements back to ARIA nodes
  // ---------------------------------------------------------------------------

  /**
   * Enriches a [TrailblazeNode] tree with bounds from DOM evaluation results.
   *
   * For each node in the tree, attempts to find a matching [DomElementBounds] entry
   * by comparing the ARIA role+name against the DOM element's properties. When a match
   * is found, the node's bounds are populated.
   *
   * Also populates [DriverNodeDetail.Web.cssSelector] and [DriverNodeDetail.Web.dataTestId]
   * from the matched DOM element when available.
   *
   * Uses a consumed-set to avoid assigning the same DOM element to multiple ARIA nodes.
   */
  internal fun enrichTreeWithBounds(
    root: TrailblazeNode,
    domBounds: List<DomElementBounds>,
    takeBounds: Boolean = true,
  ): TrailblazeNode {
    // Index DOM elements by role+name for efficient lookup.
    // Multiple DOM elements can share the same key, so we track occurrence indices.
    val domByKey = mutableMapOf<String, MutableList<IndexedValue<DomElementBounds>>>()
    for ((index, entry) in domBounds.withIndex()) {
      val key = buildDomMatchKey(entry)
      domByKey.getOrPut(key) { mutableListOf() }.add(IndexedValue(index, entry))
    }

    // Track which DOM entries have been consumed
    val consumed = mutableSetOf<Int>()
    // Track how many times each ARIA descriptor has been seen during tree walk
    val ariaDescriptorCount = mutableMapOf<String, Int>()

    return enrichNode(root, domByKey, consumed, ariaDescriptorCount, takeBounds)
  }

  private fun enrichNode(
    node: TrailblazeNode,
    domByKey: Map<String, List<IndexedValue<DomElementBounds>>>,
    consumed: MutableSet<Int>,
    ariaDescriptorCount: MutableMap<String, Int>,
    takeBounds: Boolean,
  ): TrailblazeNode {
    val detail = node.driverDetail as? DriverNodeDetail.Web ?: return node
    val descriptor = detail.ariaDescriptor ?: return node

    // Track nth occurrence of this descriptor in tree-walk order
    val currentOccurrence = ariaDescriptorCount.getOrDefault(descriptor, 0)
    ariaDescriptorCount[descriptor] = currentOccurrence + 1

    // Try to find a matching DOM element
    val matchKey = buildAriaMatchKey(detail)
    val candidates = domByKey[matchKey] ?: emptyList()
    val matched = findBestMatch(candidates, consumed, currentOccurrence)

    // Enrich children first (pre-order, but children get enriched after parent key is tracked)
    val enrichedChildren = node.children.map { child ->
      enrichNode(child, domByKey, consumed, ariaDescriptorCount, takeBounds)
    }

    if (matched != null) {
      consumed.add(matched.index)
      val entry = matched.value
      return node.copy(
        children = enrichedChildren,
        bounds = if (takeBounds) {
          TrailblazeNode.Bounds(
            left = entry.x,
            top = entry.y,
            right = entry.x + entry.width,
            bottom = entry.y + entry.height,
          )
        } else {
          node.bounds
        },
        driverDetail = detail.copy(
          cssSelector = detail.cssSelector ?: buildCssSelector(entry),
          dataTestId = detail.dataTestId ?: entry.dataTestId,
        ),
      )
    }

    return node.copy(children = enrichedChildren)
  }

  /**
   * Builds a match key from a DOM element for correlation with ARIA nodes.
   *
   * Uses the element's implicit or explicit ARIA role + accessible label.
   * The key format matches what [buildAriaMatchKey] produces for ARIA nodes.
   */
  private fun buildDomMatchKey(entry: DomElementBounds): String {
    val role = entry.ariaRole ?: implicitAriaRole(entry.tag)
    val name = entry.ariaLabel ?: entry.textContent
    return if (name != null) "$role:$name" else role
  }

  /**
   * Builds a match key from an ARIA node's [DriverNodeDetail.Web] for correlation
   * with DOM elements.
   */
  private fun buildAriaMatchKey(detail: DriverNodeDetail.Web): String {
    val name = detail.ariaName
    val role = detail.ariaRole ?: "generic"
    return if (name != null) "$role:$name" else role
  }

  /**
   * Finds the best matching DOM element from candidates, preferring the one at
   * [targetOccurrence] index among unconsumed candidates.
   */
  private fun findBestMatch(
    candidates: List<IndexedValue<DomElementBounds>>,
    consumed: Set<Int>,
    targetOccurrence: Int,
  ): IndexedValue<DomElementBounds>? {
    val unconsumed = candidates.filter { it.index !in consumed }
    if (unconsumed.isEmpty()) return null
    // Return the nth unconsumed candidate, or the last one if we've exceeded
    return unconsumed.getOrElse(targetOccurrence) { unconsumed.last() }
  }

  /** Builds a CSS selector from DOM element identifiers, preferring id over data-testid. */
  private fun buildCssSelector(entry: DomElementBounds): String? {
    if (entry.id != null) return "#${entry.id}"
    if (entry.dataTestId != null) return "[data-testid=\"${entry.dataTestId}\"]"
    return null
  }

  /**
   * Maps common HTML tags to their implicit ARIA role.
   *
   * This is a subset of the full WAI-ARIA implicit role mapping, covering the
   * most common elements. Elements not in this map default to "generic".
   */
  private fun implicitAriaRole(tag: String): String = when (tag) {
    "a" -> "link"
    "button" -> "button"
    "input" -> "textbox" // simplified; depends on type attribute
    "textarea" -> "textbox"
    "select" -> "combobox"
    "h1", "h2", "h3", "h4", "h5", "h6" -> "heading"
    "img" -> "img"
    "nav" -> "navigation"
    "main" -> "main"
    "header" -> "banner"
    "footer" -> "contentinfo"
    "aside" -> "complementary"
    "form" -> "form"
    "dialog" -> "dialog"
    "section" -> "region"
    "ul", "ol" -> "list"
    "li" -> "listitem"
    "table" -> "table"
    "tr" -> "row"
    "td" -> "cell"
    "th" -> "columnheader"
    else -> "generic"
  }
}
