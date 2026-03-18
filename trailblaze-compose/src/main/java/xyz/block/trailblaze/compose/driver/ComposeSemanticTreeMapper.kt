package xyz.block.trailblaze.compose.driver

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode

/**
 * Maps Compose [SemanticsNode] tree to [ViewHierarchyTreeNode] tree and compact element lists.
 *
 * This is the Compose equivalent of PlaywrightAriaSnapshot's
 * `ariaSnapshotToViewHierarchy()`. It recursively walks the Compose semantics
 * tree and produces ViewHierarchyTreeNode objects compatible with the existing
 * Trailblaze LLM prompt pipeline.
 *
 * Additionally, [buildCompactElementList] produces a token-efficient text snapshot with
 * element IDs (`[e1]`, `[e2]`) for disambiguation, mirroring the Playwright driver's
 * `CompactAriaElements` pattern.
 */
object ComposeSemanticTreeMapper {

  /** ARIA-like role constants used in compact element descriptors and matcher resolution. */
  object ComposeRole {
    const val BUTTON = "button"
    const val CHECKBOX = "checkbox"
    const val SWITCH = "switch"
    const val RADIO = "radio"
    const val TAB = "tab"
    const val IMAGE = "img"
    const val COMBOBOX = "combobox"
    const val TEXTBOX = "textbox"
    const val SCROLLABLE = "scrollable"
  }

  /**
   * Reference to a specific element in the compact element list.
   *
   * Analogous to Playwright's `ElementRef`. Stores the descriptor string and occurrence
   * index so tools can resolve `e5` back to the correct Compose node even when multiple
   * nodes share the same descriptor.
   */
  data class ComposeElementRef(
    /** ARIA-like descriptor, e.g., `button "Submit"` or `textbox "Email"`. */
    val descriptor: String,
    /** 0-based occurrence index among nodes with the same [descriptor]. */
    val nthIndex: Int,
    /** testTag if available — preferred for stable matching. */
    val testTag: String?,
  )

  /**
   * Result of [buildCompactElementList]: a token-efficient text snapshot paired with
   * a mapping from element IDs to [ComposeElementRef]s.
   */
  data class CompactComposeElements(
    /** The compact text snapshot with `[eN]` IDs for interactive elements. */
    val text: String,
    /** Maps element ID (e.g., `"e1"`) to its [ComposeElementRef]. */
    val elementIdMapping: Map<String, ComposeElementRef>,
  )

  /**
   * All semantic properties extracted from a single [SemanticsNode].
   * Single source of truth — extracted once, consumed by all mapping functions.
   */
  data class NodeSnapshot(
    val text: String?,
    val editableText: String?,
    val contentDescription: String?,
    val testTag: String?,
    val role: androidx.compose.ui.semantics.Role?,
    val toggleableState: ToggleableState?,
    val isFocused: Boolean,
    val isSelected: Boolean?,
    val isDisabled: Boolean,
    val isPassword: Boolean,
    val hasClickAction: Boolean,
    val hasScrollAction: Boolean,
    val bounds: androidx.compose.ui.geometry.Rect,
  ) {
    val isChecked: Boolean get() = toggleableState == ToggleableState.On
    val isEnabled: Boolean get() = !isDisabled
    val hasEditableText: Boolean get() = editableText != null

    val toggleableStateString: String?
      get() = when (toggleableState) {
        ToggleableState.On -> "On"
        ToggleableState.Off -> "Off"
        ToggleableState.Indeterminate -> "Indeterminate"
        null -> null
      }

    val displayText: String?
      get() = (editableText ?: text ?: contentDescription)
        ?.replace('\n', ' ')?.replace('\r', ' ')

    val inferredRole: String?
      get() = inferRole(role, hasClickAction, hasScrollAction, hasEditableText, isPassword)

    val centerX: Int get() = ((bounds.left + bounds.right) / 2).toInt()
    val centerY: Int get() = ((bounds.top + bounds.bottom) / 2).toInt()
    val width: Int get() = (bounds.right - bounds.left).toInt()
    val height: Int get() = (bounds.bottom - bounds.top).toInt()
  }

  /** Extracts all semantic properties from a [SemanticsNode] into a [NodeSnapshot]. */
  internal fun extractSnapshot(node: SemanticsNode): NodeSnapshot {
    val config = node.config
    return NodeSnapshot(
      text = config.getOrNull(SemanticsProperties.Text)?.joinToString(", ") { it.text },
      editableText = config.getOrNull(SemanticsProperties.EditableText)?.text,
      contentDescription =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", "),
      testTag = config.getOrNull(SemanticsProperties.TestTag),
      role = config.getOrNull(SemanticsProperties.Role),
      toggleableState = config.getOrNull(SemanticsProperties.ToggleableState),
      isFocused = config.getOrNull(SemanticsProperties.Focused) ?: false,
      isSelected = config.getOrNull(SemanticsProperties.Selected),
      isDisabled = config.contains(SemanticsProperties.Disabled),
      isPassword = config.getOrNull(SemanticsProperties.Password) != null,
      hasClickAction =
        config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null,
      hasScrollAction =
        config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) != null,
      bounds = node.boundsInWindow,
    )
  }

  fun map(rootNode: SemanticsNode): ViewHierarchyTreeNode {
    var nextNodeId = 1L
    return mapNode(rootNode) { nextNodeId++ }
  }

  fun mapToTrailblazeNode(rootNode: SemanticsNode): TrailblazeNode {
    var nextNodeId = 1L
    return mapNodeToTrailblazeNode(rootNode) { nextNodeId++ }
  }

  private fun mapNodeToTrailblazeNode(
    node: SemanticsNode,
    nextId: () -> Long,
  ): TrailblazeNode {
    val nodeId = nextId()
    val s = extractSnapshot(node)
    val children = node.children.map { child -> mapNodeToTrailblazeNode(child, nextId) }

    return TrailblazeNode(
      nodeId = nodeId,
      children = children,
      bounds = TrailblazeNode.Bounds(
        left = s.bounds.left.toInt(),
        top = s.bounds.top.toInt(),
        right = s.bounds.right.toInt(),
        bottom = s.bounds.bottom.toInt(),
      ),
      driverDetail = DriverNodeDetail.Compose(
        testTag = s.testTag,
        role = s.role?.toString(),
        text = s.text,
        editableText = s.editableText,
        contentDescription = s.contentDescription,
        toggleableState = s.toggleableStateString,
        isEnabled = s.isEnabled,
        isFocused = s.isFocused,
        isSelected = s.isSelected ?: false,
        isPassword = s.isPassword,
        hasClickAction = s.hasClickAction,
        hasScrollAction = s.hasScrollAction,
      ),
    )
  }

  private fun mapNode(
    node: SemanticsNode,
    nextId: () -> Long,
  ): ViewHierarchyTreeNode {
    val nodeId = nextId()
    val s = extractSnapshot(node)
    val children = node.children.map { child -> mapNode(child, nextId) }

    return ViewHierarchyTreeNode(
      nodeId = nodeId,
      text = s.editableText ?: s.text,
      accessibilityText = s.contentDescription,
      resourceId = s.testTag,
      className = s.role?.toString() ?: DEFAULT_CLASS_NAME,
      clickable = s.hasClickAction,
      focusable = s.isFocused || s.hasClickAction || s.editableText != null,
      focused = s.isFocused,
      enabled = s.isEnabled,
      scrollable = s.hasScrollAction,
      checked = s.isChecked,
      selected = s.isSelected ?: false,
      password = s.isPassword,
      dimensions = "${s.width}x${s.height}",
      centerPoint = "${s.centerX},${s.centerY}",
      children = children,
    )
  }

  private const val DEFAULT_CLASS_NAME = "View"

  // -- Compact element list with element IDs --

  /**
   * Builds a compact, token-efficient element list from the semantics tree.
   *
   * Interactive elements get sequential `[eN]` IDs. Static text and labels appear
   * without IDs for context. Structural wrappers with no meaningful content are omitted.
   */
  fun buildCompactElementList(
    rootNode: SemanticsNode,
    includeBounds: Boolean = false,
  ): CompactComposeElements = buildCompactElementList(listOf(rootNode), includeBounds)

  /**
   * Builds a compact element list from multiple root nodes.
   *
   * Each root is processed in order with shared element ID numbering, so popups/dialogs
   * contribute elements alongside the main window content.
   */
  fun buildCompactElementList(
    rootNodes: List<SemanticsNode>,
    includeBounds: Boolean = false,
  ): CompactComposeElements {
    var nextElementId = 1
    val elementIdMapping = mutableMapOf<String, ComposeElementRef>()
    val descriptorOccurrences = mutableMapOf<String, Int>()

    val text = buildString {
      for (rootNode in rootNodes) {
        appendCompactNode(
          node = rootNode,
          indent = 0,
          nextElementId = { nextElementId++ },
          elementIdMapping = elementIdMapping,
          descriptorOccurrences = descriptorOccurrences,
          includeBounds = includeBounds,
        )
      }
    }

    return CompactComposeElements(
      text = text.trimEnd().ifEmpty { "(empty page)" },
      elementIdMapping = elementIdMapping,
    )
  }

  private fun StringBuilder.appendCompactNode(
    node: SemanticsNode,
    indent: Int,
    nextElementId: () -> Int,
    elementIdMapping: MutableMap<String, ComposeElementRef>,
    descriptorOccurrences: MutableMap<String, Int>,
    includeBounds: Boolean,
  ) {
    val s = extractSnapshot(node)
    val inferredRole = s.inferredRole
    val displayText = s.displayText

    val isInteractive = inferredRole != null && inferredRole != ComposeRole.SCROLLABLE
    val isScrollable = inferredRole == ComposeRole.SCROLLABLE
    val hasMeaningfulContent = displayText != null || s.testTag != null

    // Skip structural noise: no role, no text, no testTag
    if (!isInteractive && !isScrollable && !hasMeaningfulContent) {
      for (child in node.children) {
        appendCompactNode(
          child, indent, nextElementId, elementIdMapping, descriptorOccurrences, includeBounds,
        )
      }
      return
    }

    val prefix = "  ".repeat(indent)

    // Scrollable containers appear as structural headers
    if (isScrollable) {
      append(prefix)
      append("scrollable")
      if (s.testTag != null) append(" [testTag=${s.testTag}]")
      appendLine(":")
      for (child in node.children) {
        appendCompactNode(
          child,
          indent + 1,
          nextElementId,
          elementIdMapping,
          descriptorOccurrences,
          includeBounds,
        )
      }
      return
    }

    if (isInteractive) {
      val descriptor = buildDescriptor(inferredRole!!, displayText, s.testTag)
      val occurrenceIndex = descriptorOccurrences.getOrDefault(descriptor, 0)
      descriptorOccurrences[descriptor] = occurrenceIndex + 1

      val elementId = "e${nextElementId()}"
      elementIdMapping[elementId] =
        ComposeElementRef(
          descriptor = descriptor,
          nthIndex = occurrenceIndex,
          testTag = s.testTag,
        )

      append(prefix)
      append("[$elementId] ")
      append(inferredRole)
      if (displayText != null) append(" \"${escapeQuotes(displayText)}\"")
      if (s.testTag != null) append(" [testTag=${s.testTag}]")
      s.appendStateAnnotations(this)
      if (includeBounds) appendBounds(s)
      appendLine()
    } else {
      // Static content (text/label with no interactive role)
      append(prefix)
      if (displayText != null) append("text \"${escapeQuotes(displayText)}\"")
      if (s.testTag != null) {
        if (displayText != null) append(" ")
        append("[testTag=${s.testTag}]")
      }
      s.appendStateAnnotations(this)
      if (includeBounds) appendBounds(s)
      appendLine()
    }

    for (child in node.children) {
      appendCompactNode(
        child, indent + 1, nextElementId, elementIdMapping, descriptorOccurrences, includeBounds,
      )
    }
  }

  private fun NodeSnapshot.appendStateAnnotations(sb: StringBuilder) {
    if (isChecked) sb.append(" [checked]")
    if (isSelected == true) sb.append(" [selected]")
    if (isDisabled) sb.append(" [disabled]")
    if (isPassword) sb.append(" [password]")
    if (isFocused) sb.append(" [focused]")
  }

  private fun StringBuilder.appendBounds(s: NodeSnapshot) {
    append(" {x:${s.bounds.left.toInt()},y:${s.bounds.top.toInt()},w:${s.width},h:${s.height}}")
  }

  /**
   * Infers a richer role string from semantic properties.
   *
   * Returns `null` for purely structural nodes that should be skipped.
   */
  internal fun inferRole(
    role: androidx.compose.ui.semantics.Role?,
    hasClickAction: Boolean,
    hasScrollAction: Boolean,
    hasEditableText: Boolean,
    isPassword: Boolean,
  ): String? {
    when (role) {
      androidx.compose.ui.semantics.Role.Button -> return ComposeRole.BUTTON
      androidx.compose.ui.semantics.Role.Checkbox -> return ComposeRole.CHECKBOX
      androidx.compose.ui.semantics.Role.Switch -> return ComposeRole.SWITCH
      androidx.compose.ui.semantics.Role.RadioButton -> return ComposeRole.RADIO
      androidx.compose.ui.semantics.Role.Tab -> return ComposeRole.TAB
      androidx.compose.ui.semantics.Role.Image -> return ComposeRole.IMAGE
      androidx.compose.ui.semantics.Role.DropdownList -> return ComposeRole.COMBOBOX
      else -> {}
    }
    if (hasEditableText) return ComposeRole.TEXTBOX
    if (hasScrollAction) return ComposeRole.SCROLLABLE
    if (hasClickAction) return ComposeRole.BUTTON
    return null
  }

  /**
   * Builds an ARIA-like descriptor for element ID mapping.
   *
   * Examples: `button "Submit"`, `textbox "Email" [testTag=submit_btn]`
   */
  internal fun buildDescriptor(role: String, text: String?, testTag: String?): String {
    return buildString {
      append(role)
      if (text != null) append(" \"${escapeQuotes(text)}\"")
      if (testTag != null) append(" [testTag=$testTag]")
    }
  }

  private fun escapeQuotes(text: String): String =
    text.replace("\\", "\\\\").replace("\"", "\\\"")

  /**
   * Generates a text representation of the semantics tree for LLM consumption.
   *
   * Delegates to [buildCompactElementList] to produce the element-ID-annotated format.
   */
  fun toTextSnapshot(
    rootNode: SemanticsNode,
    includeBounds: Boolean = false,
  ): String {
    return buildCompactElementList(rootNode, includeBounds).text
  }
}
