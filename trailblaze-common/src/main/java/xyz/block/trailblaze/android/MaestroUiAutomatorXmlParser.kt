package xyz.block.trailblaze.android

import maestro.TreeNode
import org.w3c.dom.Element
import org.w3c.dom.Node
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import javax.xml.parsers.DocumentBuilderFactory

/**
 * See https://github.com/mobile-dev-inc/maestro/blob/dcd2f206fbbaf61e5f089f844d572f8888d13913/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L311-L326
 */
object MaestroUiAutomatorXmlParser {

  /**
   * XML document builder factory.
   */
  private val documentBuilderFactory = DocumentBuilderFactory.newInstance()

  fun getUiAutomatorViewHierarchyAsSerializableTreeNodes(
    xmlHierarchy: String,
    excludeKeyboardElements: Boolean,
  ): ViewHierarchyTreeNode = getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
    xmlHierarchy,
    excludeKeyboardElements,
  ).toViewHierarchyTreeNode()!!

  fun getUiAutomatorViewHierarchyFromViewHierarchyAsMaestroTreeNodes(
    viewHiearchyXml: String,
    excludeKeyboardElements: Boolean,
  ): TreeNode {
    val document = documentBuilderFactory
      .newDocumentBuilder()
      .parse(viewHiearchyXml.byteInputStream())

    val treeNode = mapXmlHierarchyToMaestroTreeNode(document)
    return if (excludeKeyboardElements) {
      treeNode.excludeKeyboardElements() ?: treeNode
    } else {
      treeNode
    }
  }

  private fun mapXmlHierarchyToMaestroTreeNode(node: Node): TreeNode {
    val attributes = if (node is Element) {
      val attributesBuilder = mutableMapOf<String, String>()

      if (node.hasAttribute("text")) {
        val text = node.getAttribute("text")
        attributesBuilder["text"] = text
      }

      if (node.hasAttribute("content-desc")) {
        attributesBuilder["accessibilityText"] = node.getAttribute("content-desc")
      }

      if (node.hasAttribute("hintText")) {
        attributesBuilder["hintText"] = node.getAttribute("hintText")
      }

      if (node.hasAttribute("class") && node.getAttribute("class") == "android.widget.Toast") {
        attributesBuilder["ignoreBoundsFiltering"] = true.toString()
      } else {
        attributesBuilder["ignoreBoundsFiltering"] = false.toString()
      }

      if (node.hasAttribute("resource-id")) {
        attributesBuilder["resource-id"] = node.getAttribute("resource-id")
      }

      if (node.hasAttribute("clickable")) {
        attributesBuilder["clickable"] = node.getAttribute("clickable")
      }

      if (node.hasAttribute("bounds")) {
        attributesBuilder["bounds"] = node.getAttribute("bounds")
      }

      if (node.hasAttribute("enabled")) {
        attributesBuilder["enabled"] = node.getAttribute("enabled")
      }

      if (node.hasAttribute("focused")) {
        attributesBuilder["focused"] = node.getAttribute("focused")
      }

      if (node.hasAttribute("checked")) {
        attributesBuilder["checked"] = node.getAttribute("checked")
      }

      if (node.hasAttribute("scrollable")) {
        attributesBuilder["scrollable"] = node.getAttribute("scrollable")
      }

      if (node.hasAttribute("selected")) {
        attributesBuilder["selected"] = node.getAttribute("selected")
      }

      if (node.hasAttribute("class")) {
        attributesBuilder["class"] = node.getAttribute("class")
      }

      attributesBuilder
    } else {
      emptyMap()
    }

    val children = mutableListOf<TreeNode>()
    val childNodes = node.childNodes
    (0 until childNodes.length).forEach { i ->
      children.plusAssign(mapXmlHierarchyToMaestroTreeNode(childNodes.item(i)))
    }

    return TreeNode(
      attributes = attributes.toMutableMap(),
      children = children,
      clickable = node.getBoolean("clickable"),
      enabled = node.getBoolean("enabled"),
      focused = node.getBoolean("focused"),
      checked = node.getBoolean("checked"),
      selected = node.getBoolean("selected"),
    )
  }

  private fun Node.getBoolean(name: String): Boolean? = (this as? Element)
    ?.getAttribute(name)
    ?.let { it == "true" }

  private fun TreeNode.excludeKeyboardElements(): TreeNode? {
    val filtered = children.mapNotNull {
      it.excludeKeyboardElements()
    }.toList()

    val resourceId = attributes["resource-id"]
    if (resourceId != null && resourceId.startsWith("com.google.android.inputmethod.latin:id/")) {
      return null
    }
    return TreeNode(
      attributes = attributes,
      children = filtered,
      clickable = clickable,
      enabled = enabled,
      focused = focused,
      checked = checked,
      selected = selected,
    )
  }
}
