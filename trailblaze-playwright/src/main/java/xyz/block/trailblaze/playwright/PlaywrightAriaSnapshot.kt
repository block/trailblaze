package xyz.block.trailblaze.playwright

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.util.Console

/**
 * Result of capturing an ARIA snapshot from a page.
 *
 * @property yaml The ARIA snapshot in YAML format, with `[ref=eN]` annotations for element targeting.
 * @property page The Playwright page that owns this snapshot (needed for ref resolution).
 */
data class AriaSnapshotResult(
  val yaml: String,
  val page: Page,
)

/**
 * Captures the page's accessibility tree as an ARIA snapshot with ref IDs.
 *
 * Uses Playwright's built-in `ariaSnapshot()` API (standard, no refs) as the primary method.
 * The ARIA snapshot YAML is parsed into [ViewHierarchyTreeNode] for backward compatibility
 * with the existing ScreenState/LLM prompt pipeline.
 *
 * For element resolution, we use role+name matching from the ARIA snapshot YAML lines
 * to build Playwright locators (e.g., `page.getByRole("button", name="Submit")`).
 */
object PlaywrightAriaSnapshot {

  /**
   * Captures an ARIA snapshot of the page.
   * Returns the YAML string with structural information about the page's accessibility tree.
   */
  fun captureAriaSnapshot(page: Page, timeoutMs: Double = 0.0): AriaSnapshotResult {
    val yaml =
      try {
        val options = if (timeoutMs > 0) {
          Locator.AriaSnapshotOptions().setTimeout(timeoutMs)
        } else {
          null
        }
        page.locator(":root").ariaSnapshot(options)
      } catch (e: Exception) {
        Console.log("Failed to capture ARIA snapshot: ${e.message}")
        "- document: (snapshot unavailable)"
      }
    return AriaSnapshotResult(yaml = yaml, page = page)
  }

  /**
   * Resolves an element ref string to a Playwright Locator.
   *
   * Supports multiple selector strategies:
   * - **CSS selectors** prefixed with `css=` (e.g., `css=#my-id`, `css=[data-testid="card"]`)
   *   are passed directly to Playwright's `locator()` API.
   * - **ARIA descriptors** (e.g., `link "Home"`, `button "Submit"`) are resolved via
   *   Playwright's `getByRole` API.
   */
  fun resolveRef(page: Page, ref: String): Locator {
    // CSS selector prefix — pass directly to Playwright's locator engine
    if (ref.startsWith("css=")) {
      return page.locator(ref)
    }
    return resolveByRoleName(page, ref)
  }

  /**
   * Resolves an [ElementRef] to a Playwright Locator, using `.nth()` to
   * disambiguate when multiple elements share the same ARIA descriptor.
   *
   * For example, if both the nav and footer have `link "Hardware"`, the
   * nav element gets nthIndex=0 and the footer element gets nthIndex=1.
   */
  fun resolveElementRef(page: Page, elementRef: ElementRef): Locator {
    val locator = resolveByRoleName(page, elementRef.descriptor)
    return locator.nth(elementRef.nthIndex)
  }

  /**
   * Parses a role+name descriptor into a locator.
   *
   * Supports three formats:
   * - Quoted: `button "Submit"`, `textbox "Email or phone number"`
   * - Unquoted: `button Submit`, `textbox Email or phone number`
   * - Role-only: `navigation`, `banner`
   */
  private fun resolveByRoleName(page: Page, descriptor: String): Locator {
    val sanitized = descriptor.trim().replace(TRAILING_ATTRS_PATTERN, "")

    // Quoted role+name: textbox "Email or phone number"
    ROLE_NAME_PATTERN.matchEntire(sanitized)?.let { match ->
      val (role, name) = match.destructured
      return page.getByRole(
        ariaRoleFromString(role),
        Page.GetByRoleOptions().setName(name).setExact(true),
      )
    }

    // Role-only: navigation, banner
    ROLE_ONLY_PATTERN.matchEntire(sanitized)?.let { match ->
      return page.getByRole(ariaRoleFromString(match.groupValues[1]))
    }

    // Unquoted role+name: textbox Email or phone number
    // Also handles single-quoted names from recording YAML: heading 'All orders'
    // Only returns if the locator actually matches elements — otherwise falls through
    // to try interactive roles and text matching (e.g., "Search Wikipedia" would
    // incorrectly match role=SEARCH name="Wikipedia" which resolves to 0 elements).
    ROLE_UNQUOTED_NAME_PATTERN.matchEntire(sanitized)?.let { match ->
      val (role, rawName) = match.destructured
      val ariaRole = try { AriaRole.valueOf(role.uppercase()) } catch (_: IllegalArgumentException) { null }
      if (ariaRole != null) {
        val name = rawName.removeSurrounding("'")
        val locator = page.getByRole(ariaRole, Page.GetByRoleOptions().setName(name).setExact(true))
        if (locator.count() > 0) return locator
      }
    }

    // No role prefix matched — try common interactive ARIA roles with the text as
    // the accessible name. This handles cases like "Search Wikipedia" resolving to
    // a searchbox with aria-label="Search Wikipedia", without requiring the caller
    // to know the exact ARIA role.
    val interactiveRoles = listOf(
      AriaRole.TEXTBOX, AriaRole.SEARCHBOX, AriaRole.COMBOBOX,
      AriaRole.BUTTON, AriaRole.LINK, AriaRole.CHECKBOX, AriaRole.RADIO,
      AriaRole.TAB, AriaRole.MENUITEM, AriaRole.OPTION,
    )
    for (role in interactiveRoles) {
      try {
        val roleLocator = page.getByRole(role, Page.GetByRoleOptions().setName(sanitized).setExact(true))
        if (roleLocator.count() > 0) return roleLocator
      } catch (_: Exception) { /* continue to next role */ }
    }

    // Try getByLabel (matches aria-label, <label> associations, placeholder text)
    try {
      val labelLocator = page.getByLabel(sanitized, Page.GetByLabelOptions().setExact(true))
      if (labelLocator.count() > 0) return labelLocator
    } catch (_: Exception) { /* fall through */ }

    // Last resort: visible text content
    return page.getByText(sanitized)
  }

  /**
   * A stable key for correlating the same accessibility-tree node across two independent
   * ARIA snapshot captures (one `AriaSnapshotMode.DEFAULT`, one `AriaSnapshotMode.AI`) of
   * the same page — see [buildAiRefsByRoleName].
   */
  internal fun roleNameCorrelationKey(role: String, name: String?): String =
    if (name != null) "$role $name" else role

  /**
   * Builds a `(role, name) -> ordered [ref=eN] tokens` map from an `AriaSnapshotMode.AI`
   * snapshot, in pre-order (first occurrence at index 0, second at index 1, ...).
   *
   * Why this indirection exists instead of resolving refs by absolute tree position:
   * `AriaSnapshotMode.AI`'s tree is NOT always line-for-line identical to
   * `AriaSnapshotMode.DEFAULT`'s. Playwright's generic-role normalization inserts extra
   * unnamed `generic` wrapper nodes in AI mode (observed whenever a container has more
   * than one meaningful child) that DEFAULT mode collapses away — confirmed empirically
   * against a real Chromium instance across several page shapes while investigating
   * https://github.com/block/trailblaze/issues/199. A positional (line-index or pre-order-index)
   * zip between the two captures is therefore unsound in general.
   *
   * Matching by (role, name, nth) instead is safe here for a reason the OLD
   * `BATCH_VIEWPORT_CHECK_JS` role/name matching wasn't: both snapshots use Playwright's
   * own accessibility-tree computation for role and name (identical in both modes) — so a
   * real element's role/name is never ambiguous or incomplete between the two captures
   * (no client-side `computedRole`-unavailable fallback, no hand-rolled per-tag heuristic).
   * Only a node's *absolute* tree position can shift due to synthetic wrapper insertion,
   * and (role, name, nth) is immune to that: inserting an unnamed generic wrapper never
   * reorders or duplicates a NAMED/non-generic node relative to its same-(role, name)
   * siblings.
   *
   * Anonymous generic nodes (`role == "generic" && name == null`) are deliberately
   * EXCLUDED from the map — those are exactly the shape of the synthetic wrapper AI mode
   * inserts, so (role, name, nth) can't tell a real anonymous generic wrapper from a
   * synthetic one. Callers must skip querying this map for such nodes too (see
   * `PlaywrightScreenState`) and fall back to per-node locator resolution for them, the
   * same as they already do today for any node the batch can't resolve.
   */
  fun buildAiRefsByRoleName(aiYaml: String): Map<String, List<String>> {
    if (aiYaml.isBlank()) return emptyMap()
    val result = mutableMapOf<String, MutableList<String>>()
    for (line in aiYaml.lines()) {
      if (line.isBlank()) continue
      val ref = REF_PATTERN.find(line)?.groupValues?.get(1) ?: continue
      val parsed = parseAriaLine(line)
      if (parsed.role == "generic" && parsed.name == null) continue
      val key = roleNameCorrelationKey(parsed.role, parsed.name)
      result.getOrPut(key) { mutableListOf() }.add(ref)
    }
    return result
  }

  /**
   * Converts a string role name to Playwright's [AriaRole] enum.
   *
   * Uses dynamic enum lookup so all current and future AriaRole values are supported
   * automatically without maintaining a manual mapping.
   */
  private fun ariaRoleFromString(role: String): AriaRole =
    try {
      AriaRole.valueOf(role.uppercase())
    } catch (_: IllegalArgumentException) {
      AriaRole.GENERIC
    }

  /**
   * Parses an ARIA snapshot YAML string into a [ViewHierarchyTreeNode] tree.
   *
   * This provides backward compatibility with the existing ScreenState interface
   * that expects ViewHierarchyTreeNode for LLM prompt construction.
   *
   * The YAML format from Playwright looks like:
   * ```
   * - document:
   *   - navigation:
   *     - link "Home"
   *     - link "About"
   *   - main:
   *     - heading "Welcome" [level=1]
   *     - textbox "Email"
   *     - button "Submit"
   * ```
   */
  fun ariaSnapshotToViewHierarchy(yaml: String): ViewHierarchyTreeNode {
    if (yaml.isBlank()) {
      return ViewHierarchyTreeNode(nodeId = 1, className = "document", text = "(empty page)")
    }

    val lines = yaml.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) {
      return ViewHierarchyTreeNode(nodeId = 1, className = "document")
    }

    var nextNodeId = 1L
    val root = parseAriaLines(lines, 0, nextNodeId) { nextNodeId = it }
    return root ?: ViewHierarchyTreeNode(nodeId = 1, className = "document")
  }

  private fun parseAriaLines(
    lines: List<String>,
    startIndex: Int,
    startNodeId: Long,
    onNodeIdUpdate: (Long) -> Unit,
  ): ViewHierarchyTreeNode? {
    if (startIndex >= lines.size) return null

    var nodeId = startNodeId
    val rootLine = lines[startIndex]
    val rootIndent = rootLine.indexOfFirst { it != ' ' && it != '-' }
    val rootParsed = parseAriaLine(rootLine)

    val children = mutableListOf<ViewHierarchyTreeNode>()
    var i = startIndex + 1
    while (i < lines.size) {
      val line = lines[i]
      val indent = line.indexOfFirst { it != ' ' && it != '-' }

      if (indent <= rootIndent) break

      // Direct child
      val isDirectChild =
        indent == rootIndent + 2 ||
          (indent > rootIndent && children.isEmpty())

      if (isDirectChild || indent > rootIndent) {
        nodeId++
        val childNodeId = nodeId
        onNodeIdUpdate(nodeId)

        // Find this child's sub-children
        val childEnd = findChildEnd(lines, i)
        val subChildren = mutableListOf<ViewHierarchyTreeNode>()
        var j = i + 1
        while (j < childEnd) {
          val subNode = parseAriaLines(lines, j, nodeId) { nodeId = it; onNodeIdUpdate(it) }
          if (subNode != null) {
            subChildren.add(subNode)
            j = findChildEnd(lines, j)
          } else {
            j++
          }
        }

        val childParsed = parseAriaLine(line)
        children.add(
          ViewHierarchyTreeNode(
            nodeId = childNodeId,
            className = childParsed.role,
            text = childParsed.name,
            accessibilityText = childParsed.name,
            clickable = childParsed.ariaRole in CLICKABLE_ROLES,
            focusable = childParsed.ariaRole in FOCUSABLE_ROLES,
            children = subChildren,
          ),
        )
        i = childEnd
      } else {
        i++
      }
    }

    return ViewHierarchyTreeNode(
      nodeId = startNodeId,
      className = rootParsed.role,
      text = rootParsed.name,
      accessibilityText = rootParsed.name,
      clickable = rootParsed.ariaRole in CLICKABLE_ROLES,
      focusable = rootParsed.ariaRole in FOCUSABLE_ROLES,
      children = children,
    )
  }

  private fun findChildEnd(lines: List<String>, index: Int): Int {
    if (index >= lines.size) return index
    val myIndent = lines[index].indexOfFirst { it != ' ' && it != '-' }
    var i = index + 1
    while (i < lines.size) {
      val indent = lines[i].indexOfFirst { it != ' ' && it != '-' }
      if (indent <= myIndent) return i
      i++
    }
    return i
  }

  private data class AriaLineParsed(
    val role: String,
    val ariaRole: AriaRole,
    val name: String?,
  )

  /**
   * Parses a single line from ARIA snapshot YAML.
   * Examples:
   * - `- button "Submit"` -> role="button", name="Submit"
   * - `- heading "Welcome" [level=1]` -> role="heading", name="Welcome"
   * - `- navigation:` -> role="navigation", name=null
   * - `- text: Hello world` -> role="text", name="Hello world"
   */
  private fun parseAriaLine(line: String): AriaLineParsed {
    val trimmed = line.trimStart(' ', '-').trim()

    // Pattern: role "name" [attrs]. Tried on the RAW (unstripped) line: QUOTED_ROLE_PATTERN's
    // trailing `.*$` already absorbs any bracket annotations after the closing quote
    // (AriaSnapshotMode.AI's `[ref=eN]`/`[cursor=...]`/`[active]`, or DEFAULT mode's
    // `[level=N]`), so no pre-stripping is needed here — which matters because stripping
    // bracket-shaped substrings from the WHOLE line would also corrupt a real accessible
    // name that happens to contain literal bracket text (e.g. a status badge whose name is
    // literally `Status [active]`). COLON_ROLE_PATTERN and CONTAINER_ROLE_PATTERN below
    // tolerate bracket annotations directly in their own pattern for the same reason —
    // see their KDoc.
    QUOTED_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(
        role = role,
        ariaRole = ariaRoleFromString(role),
        name = match.groupValues[2],
      )
    }

    // Pattern: role: text content
    COLON_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(
        role = role,
        ariaRole = ariaRoleFromString(role),
        name = match.groupValues[2],
      )
    }

    // Pattern: role: (container with children)
    CONTAINER_ROLE_PATTERN.matchEntire(trimmed)?.let { match ->
      val role = match.groupValues[1]
      return AriaLineParsed(role = role, ariaRole = ariaRoleFromString(role), name = null)
    }

    // Fallback: treat entire line as text
    return AriaLineParsed(role = "text", ariaRole = AriaRole.GENERIC, name = trimmed.ifBlank { null })
  }

  // -- Pre-compiled regex patterns (avoids recompilation on every call) --

  /** Strips trailing ARIA attribute annotations like `[level=1]` or `[ref=e5]`. */
  private val TRAILING_ATTRS_PATTERN = Regex("""\s+\[.*]$""")

  /** Matches `role "name"` descriptors, e.g. `button "Submit"`. */
  private val ROLE_NAME_PATTERN = Regex("""^(\w+)\s+"(.+)"$""")

  /** Matches a bare role keyword, e.g. `navigation`. */
  private val ROLE_ONLY_PATTERN = Regex("""^(\w+)$""")

  /** Matches unquoted role+name, e.g. `textbox Email or phone number`. */
  private val ROLE_UNQUOTED_NAME_PATTERN = Regex("""^(\w+)\s+(.+)$""")

  /** Matches `role "name" [attrs]` in ARIA YAML lines, e.g. `heading "Welcome" [level=1]`. */
  private val QUOTED_ROLE_PATTERN = Regex("""^(\w+)\s+"(.+?)".*$""")

  /**
   * Matches `role [attrs]...: text content` lines, e.g. `text: Hello world` or (in
   * `AriaSnapshotMode.AI`) `paragraph [ref=e8]: Some text.`. The optional
   * `(?:\s*\[[^\]]*])*` group consumes any number of bracket annotations sitting between
   * the role and the colon — needed because `[ref=eN]`/`[cursor=...]`/`[active]` (and any
   * other Playwright ARIA state annotation: `[checked]`, `[expanded]`, `[level=N]`, etc.)
   * can appear there in `AriaSnapshotMode.AI` output. Deliberately does NOT touch anything
   * after the colon, so real content that happens to contain bracket-shaped text (e.g.
   * `text: [DRAFT] Some content`) is captured into the name unchanged.
   */
  private val COLON_ROLE_PATTERN = Regex("""^(\w+)(?:\s*\[[^\]]*])*:\s*(.+)$""")

  /**
   * Matches container roles with optional bracket annotations and colon, e.g.
   * `navigation:`, `main`, or (in `AriaSnapshotMode.AI`) `generic [active] [ref=e2]:`.
   * See [COLON_ROLE_PATTERN]'s KDoc for why bracket annotations are tolerated inline
   * rather than pre-stripped.
   */
  private val CONTAINER_ROLE_PATTERN = Regex("""^(\w+)(?:\s*\[[^\]]*])*:?\s*$""")

  /**
   * Matches an `AriaSnapshotMode.AI` ref annotation and captures its id, e.g. `e5` from
   * `[ref=e5]`. Deliberately anchored to bare `e\d+` — NOT `\w+` — so it does not match
   * frame-nested refs, which Playwright prefixes with the owning frame's sequence number
   * (e.g. `f1e2` for the second ref inside the first iframe). AI-mode snapshots expand
   * `<iframe>` content inline, but the default-mode snapshot used to build the compact
   * list / viewHierarchy tree never descends into iframes (it shows a leaf `- iframe`
   * node), so a frame-nested ref must never enter [buildAiRefsByRoleName]'s correlation
   * map — if it did, an (role, name, nth) lookup could resolve into iframe content instead
   * of the intended main-frame element. Don't loosen this pattern without also filtering
   * frame-nested refs some other way.
   */
  private val REF_PATTERN = Regex("""\[ref=(e\d+)]""")

  // -- Compact ARIA element list for LLM consumption --

  /**
   * A resolved element reference that can disambiguate duplicate elements on the page.
   *
   * @property descriptor The ARIA descriptor (e.g., `link "Hardware"`) for locator resolution.
   * @property nthIndex The 0-based occurrence index when multiple elements share the same
   *   descriptor. Used with Playwright's `.nth(index)` to pick the correct one.
   * @property imageAnnotatable Whether to paint a bounding-box overlay for this element on
   *   the set-of-mark screenshot. Narrower than text-list inclusion: cells, rows, headings,
   *   and named generic elements appear in the text view (so the LLM can reference them by
   *   name) but only interactive controls (buttons, links, inputs, etc.) get drawn on the
   *   image. This stops dense landscape views (1280x800+ tables) from painting 60+
   *   overlapping boxes on every visible cell.
   */
  data class ElementRef(
    val descriptor: String,
    val nthIndex: Int,
    val imageAnnotatable: Boolean = true,
  )

  /**
   * Result of building a compact element list from an ARIA snapshot.
   *
   * @property text The compact text representation for the LLM, e.g.:
   *   ```
   *   [e1] button "Submit"
   *   [e2] link "Home"
   *   [e3] textbox "Email"
   *   ```
   * @property elementIdMapping Maps element IDs (e.g., "e1") to their [ElementRef] which
   *   includes both the ARIA descriptor and the occurrence index for disambiguation.
   */
  data class CompactAriaElements(
    val text: String,
    val elementIdMapping: Map<String, ElementRef>,
  )

  /**
   * Builds a compact, indented element list from an ARIA snapshot YAML.
   *
   * Preserves the page's structural hierarchy through indentation while filtering
   * the content to only what the LLM needs:
   * - **Meaningful elements** get IDs (e.g., `[e1] button "Submit"`) — interactive
   *   controls, headings, images, text content, alerts, etc.
   * - **Landmark sections** appear as indentation context headers (e.g., `navigation:`)
   *   but only when they contain meaningful descendants.
   * - **Purely structural containers** (generic, group, etc.) are omitted entirely.
   *
   * Example output:
   * ```
   * navigation:
   *   [e1] link "Home"
   *   [e2] link "About"
   * main:
   *   [e3] heading "Welcome"
   *   [e4] textbox "Email"
   *   [e5] button "Submit"
   * ```
   */
  fun buildCompactElementList(yaml: String): CompactAriaElements {
    if (yaml.isBlank()) {
      return CompactAriaElements(text = "(empty page)", elementIdMapping = emptyMap())
    }

    val lines = yaml.lines().filter { it.isNotBlank() }

    // Parse all lines with their indent levels
    data class ParsedLine(
      val indent: Int,
      val parsed: AriaLineParsed,
      val isMeaningful: Boolean,
      val isLandmark: Boolean,
    )

    val parsedLines = lines.map { line ->
      val indent = line.indexOfFirst { it != ' ' && it != '-' }
      val parsed = parseAriaLine(line)
      ParsedLine(
        indent = indent,
        parsed = parsed,
        isMeaningful = isLlmRelevantNode(parsed),
        isLandmark = isLandmarkNode(parsed),
      )
    }

    val minIndent = parsedLines.minOfOrNull { it.indent } ?: 0
    val elementIdMapping = mutableMapOf<String, ElementRef>()
    val compactLines = mutableListOf<String>()
    var nextId = 1

    // Track how many times each descriptor has appeared so far,
    // so we can disambiguate duplicates (e.g., two link "Hardware" elements)
    val descriptorOccurrences = mutableMapOf<String, Int>()

    // Track ancestor landmarks so we can emit section headers on demand
    val landmarkStack = mutableListOf<Int>()
    val emittedLandmarks = mutableSetOf<Int>()

    for (i in parsedLines.indices) {
      val pl = parsedLines[i]

      // Pop landmarks that are at the same or deeper indent (no longer ancestors).
      // `removeAt(size - 1)` not `removeLast()` — Kotlin 2.x compiles
      // `MutableList.removeLast()` to a direct `java.util.List.removeLast()` call,
      // which is a JDK 21+ method that doesn't exist at runtime on JDK 17 (our target),
      // surfacing as `NoSuchMethodError: 'Object java.util.List.removeLast()'`.
      // `removeAt(int)` routes through pre-JDK-21 `List.remove(int)`. KT-65235.
      while (landmarkStack.isNotEmpty() &&
        parsedLines[landmarkStack.last()].indent >= pl.indent
      ) {
        landmarkStack.removeAt(landmarkStack.size - 1)
      }

      if (pl.isLandmark) {
        landmarkStack.add(i)
      }

      if (pl.isMeaningful) {
        // Emit any ancestor landmarks that haven't been emitted yet
        for (idx in landmarkStack) {
          if (idx !in emittedLandmarks && parsedLines[idx].isLandmark) {
            emittedLandmarks.add(idx)
            val lpl = parsedLines[idx]
            val normalizedIndent = ((lpl.indent - minIndent) / 2).coerceAtLeast(0)
            val indentStr = "  ".repeat(normalizedIndent)
            val label = buildAriaDescriptor(lpl.parsed)
            compactLines.add("$indentStr$label:")
          }
        }

        // Emit the meaningful element with its ID
        val id = "e$nextId"
        val descriptor = buildAriaDescriptor(pl.parsed)

        // Track the nth occurrence for disambiguation of duplicate descriptors
        val occurrenceIndex = descriptorOccurrences.getOrDefault(descriptor, 0)
        descriptorOccurrences[descriptor] = occurrenceIndex + 1

        elementIdMapping[id] = ElementRef(
          descriptor = descriptor,
          nthIndex = occurrenceIndex,
          imageAnnotatable = isImageAnnotatable(pl.parsed),
        )
        val normalizedIndent = ((pl.indent - minIndent) / 2).coerceAtLeast(0)
        val indentStr = "  ".repeat(normalizedIndent)
        compactLines.add("$indentStr[$id] $descriptor")
        nextId++
      }
    }

    val text = if (compactLines.isEmpty()) {
      "(no interactive elements found)"
    } else {
      compactLines.joinToString("\n")
    }

    return CompactAriaElements(text = text, elementIdMapping = elementIdMapping)
  }

  /**
   * Whether a node is a landmark section that should appear as a context header.
   * These are ARIA landmark roles that provide useful page structure context.
   */
  private fun isLandmarkNode(parsed: AriaLineParsed): Boolean {
    return parsed.ariaRole in LANDMARK_ROLES
  }

  /**
   * Whether to paint a bounding-box overlay for this element on the set-of-mark screenshot.
   *
   * Deliberately narrower than [isLlmRelevantNode]: the text view broadly indexes every
   * named non-structural element (so the LLM can reference rows/cells/headings by name),
   * but the image overlay restricts to interactive controls. On dense landscape views
   * (e.g., a 1280x800 admin table with 15 rows × 6 cells = ~90 named elements) painting
   * every named element produces unreadable overlap; restricting to roles the LLM can
   * actually act on (`button`, `link`, `textbox`, `combobox`, `checkbox`, …) cuts the
   * paint count without hiding any text-level information.
   */
  private fun isImageAnnotatable(parsed: AriaLineParsed): Boolean {
    return parsed.ariaRole in INTERACTIVE_ROLES
  }

  /**
   * Determines whether an ARIA node is meaningful enough to show to the LLM.
   *
   * Includes: interactive controls, content with text (headings, images, alerts),
   * and any named element with a non-structural role.
   * Excludes: unnamed structural containers like navigation, main, section, group, etc.
   */
  private fun isLlmRelevantNode(parsed: AriaLineParsed): Boolean {
    // Always include interactive roles
    if (parsed.ariaRole in INTERACTIVE_ROLES) return true

    // Always include content roles that have text
    if (parsed.ariaRole in CONTENT_ROLES && parsed.name != null) return true

    // Include any other named element that isn't purely structural
    if (parsed.name != null && parsed.ariaRole !in STRUCTURAL_ROLES) return true

    return false
  }

  /**
   * Builds an ARIA descriptor string from a parsed line that [resolveRef] can consume.
   * Produces format like: `button "Submit"`, `heading "Welcome"`, `text: Hello world`.
   */
  private fun buildAriaDescriptor(parsed: AriaLineParsed): String {
    return when {
      parsed.name != null && parsed.role == "text" -> "text: ${parsed.name}"
      parsed.name != null -> "${parsed.role} \"${parsed.name}\""
      else -> parsed.role
    }
  }

  // -- Role classification sets (using AriaRole enum for type safety) --

  /**
   * ARIA landmark and container roles used as section headers in the compact element list.
   *
   * Includes standard ARIA landmarks (navigation, main, etc.) plus semantic container
   * roles (list, table) so the LLM can see and target these structural elements.
   * Without list/table here, their children (listitem, cell) appear to float under
   * the wrong parent in the compact view, causing tools like
   * `web_verifyListVisible` to target headings instead of lists.
   */
  private val LANDMARK_ROLES =
    setOf(
      AriaRole.NAVIGATION,
      AriaRole.MAIN,
      AriaRole.BANNER,
      AriaRole.CONTENTINFO,
      AriaRole.COMPLEMENTARY,
      AriaRole.FORM,
      AriaRole.DIALOG,
      AriaRole.REGION,
      AriaRole.LIST,
      AriaRole.TABLE,
    )

  /** Roles for elements the user can interact with — always included in compact list. */
  private val INTERACTIVE_ROLES =
    setOf(
      AriaRole.BUTTON,
      AriaRole.LINK,
      AriaRole.TEXTBOX,
      AriaRole.COMBOBOX,
      AriaRole.SEARCHBOX,
      AriaRole.CHECKBOX,
      AriaRole.RADIO,
      AriaRole.SWITCH,
      AriaRole.TAB,
      AriaRole.MENUITEM,
      AriaRole.OPTION,
      AriaRole.SLIDER,
      AriaRole.SPINBUTTON,
    )

  /** Roles for elements that convey content — included when they have text/name. */
  private val CONTENT_ROLES =
    setOf(
      AriaRole.HEADING,
      AriaRole.IMG,
      AriaRole.ALERT,
      AriaRole.DIALOG,
      AriaRole.STATUS,
      AriaRole.PROGRESSBAR,
      AriaRole.METER,
      AriaRole.TOOLTIP,
      AriaRole.CELL,
      AriaRole.COLUMNHEADER,
      AriaRole.ROWHEADER,
    )

  /**
   * Purely structural/container roles — excluded from the compact list unless
   * they happen to have a meaningful name (handled by [isLlmRelevantNode]).
   */
  private val STRUCTURAL_ROLES =
    setOf(
      AriaRole.GENERIC,
      AriaRole.NONE,
      AriaRole.PRESENTATION,
    )

  private val CLICKABLE_ROLES =
    setOf(
      AriaRole.BUTTON,
      AriaRole.LINK,
      AriaRole.MENUITEM,
      AriaRole.OPTION,
      AriaRole.TAB,
      AriaRole.CHECKBOX,
      AriaRole.RADIO,
      AriaRole.SWITCH,
    )

  private val FOCUSABLE_ROLES =
    setOf(
      AriaRole.TEXTBOX,
      AriaRole.COMBOBOX,
      AriaRole.SEARCHBOX,
      AriaRole.SPINBUTTON,
      AriaRole.SLIDER,
      AriaRole.CHECKBOX,
      AriaRole.RADIO,
      AriaRole.SWITCH,
    )
}
