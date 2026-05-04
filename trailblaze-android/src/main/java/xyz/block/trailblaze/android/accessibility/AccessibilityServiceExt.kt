package xyz.block.trailblaze.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import maestro.TreeNode
import xyz.block.trailblaze.android.AndroidSdkVersion

/** Transforms a [AccessibilityNodeInfo] into a Maestro [TreeNode]. */
fun AccessibilityNodeInfo.toTreeNode(): TreeNode {
  val nodeRect: Rect = Rect().apply { getBoundsInScreen(this) }

  // Top-left coordinate
  val topLeftX = nodeRect.left
  val topLeftY = nodeRect.top

  // Bottom-right coordinate
  val bottomRightX = nodeRect.right
  val bottomRightY = nodeRect.bottom

  // Width and height
  val width = nodeRect.width()
  val height = nodeRect.height()

  val centerX = (topLeftX + bottomRightX) / 2
  val centerY = (topLeftY + bottomRightY) / 2

  val attributes = mutableMapOf<String, String>()
  attributes["bounds"] = "[$topLeftX,$topLeftY][$bottomRightX,$bottomRightY]"
  attributes["centerPoint"] = "[$centerX,$centerY]"

  this.text?.let { attributes["text"] = it.toString() }
  this.contentDescription?.let { attributes["accessibilityText"] = it.toString() }
  this.viewIdResourceName?.let { attributes["resource-id"] = it }
  this.className?.let { attributes["class"] = it.toString() }
  if (AndroidSdkVersion.isAtLeast(33)) {
    attributes["isTextSelectable"] = this.isTextSelectable.toString()
  }

  if (AndroidSdkVersion.isAtLeast(28)) {
    this.tooltipText?.let { attributes["tooltipText"] = it.toString() }
  }

  if (this.isEditable) {
    attributes["editable"] = this.isEditable.toString()
  }

  if (this.isScrollable) {
    attributes["scrollable"] = "true"
  }

  if (this.isLongClickable) {
    attributes["long-clickable"] = "true"
  }

  // The packageName (appId) that the view belongs to
  this.packageName?.let { attributes["package"] = it.toString() }

  if (AndroidSdkVersion.isAtLeast(26)) {
    this.hintText?.let { attributes["hintText"] = it.toString() }
  }
  this.error?.let { attributes["error"] = it.toString() }

  // Compose renders EditText placeholder text as a child TextView rather than exposing it
  // via hintText. Hoist the first child's text as hintText when the EditText has neither.
  if (
    this.className?.toString() == "android.widget.EditText" &&
      !attributes.containsKey("hintText") &&
      !attributes.containsKey("text")
  ) {
    for (i in 0 until childCount) {
      val child = getChild(i) ?: continue
      try {
        if (child.text != null) {
          attributes["hintText"] = child.text.toString()
          break
        }
      } finally {
        child.recycle()
      }
    }
  }

  val childNodes = (0 until childCount).mapNotNull { index ->
    val child = getChild(index) ?: return@mapNotNull null
    try {
      child.toTreeNode()
    } finally {
      child.recycle()
    }
  }

  return TreeNode(
    attributes = attributes,
    children = childNodes,
    clickable = if (isClickable) true else null,
    enabled = isEnabled,
    focused = if (isFocused) true else null,
    checked = if (isCheckable) isChecked else null,
    selected = if (isSelected) true else null,
  )
}
