package xyz.block.trailblaze.utils

import kotlinx.serialization.json.JsonObject
import maestro.TreeNode
import maestro.orchestra.Command
import maestro.orchestra.MaestroCommand
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.GenericGsonJsonSerializer
import xyz.block.trailblaze.util.Console
import java.util.regex.Pattern

object Ext {

  fun JsonObject.asMaestroCommand(): Command? = try {
    TrailblazeJsonInstance.decodeFromString(
      GenericGsonJsonSerializer(MaestroCommand::class),
      this.toString(),
    ).asCommand()
  } catch (e: Exception) {
    Console.error("Failed to deserialize MaestroCommand from JSON: ${e.message}")
    null
  }

  fun MaestroCommand.asJsonObject(): JsonObject = try {
    val maestroCommandJson = TrailblazeJsonInstance.encodeToString(
      GenericGsonJsonSerializer(MaestroCommand::class),
      this,
    )
    TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), maestroCommandJson)
  } catch (e: Exception) {
    error(e)
  }

  /**
   * Converts raw Maestro command JsonObjects to executable Commands via Gson deserialization.
   * Previously used MaestroYamlParser but Maestro 2.3.0 YAML parser no longer accepts
   * the Gson property names (e.g. "launchAppCommand" vs "launchApp").
   */
  fun List<JsonObject>.asMaestroCommands(): List<Command> {
    if (isEmpty()) return emptyList()
    return mapNotNull { it.asMaestroCommand() }
  }

  fun List<Command>.asJsonObjects(): List<JsonObject> = this.map { it.asJsonObject() }

  fun Command.asJsonObject(): JsonObject = MaestroCommand(this).asJsonObject()

  fun TreeNode.toViewHierarchyTreeNode(): ViewHierarchyTreeNode? {
    data class UIElementBounds(
      val x: Int,
      val y: Int,
      val width: Int,
      val height: Int,
    ) {
      val centerX = x + (width / 2)
      val centerY = y + (height / 2)
    }

    fun bounds(boundsString: String): UIElementBounds? {
      val pattern = Pattern.compile("\\[([0-9-]+),([0-9-]+)]\\[([0-9-]+),([0-9-]+)]")
      val m = pattern.matcher(boundsString)
      if (!m.matches()) {
        Console.error("Warning: Bounds text does not match expected pattern: $boundsString")
        return null
      }

      val l = m.group(1).toIntOrNull() ?: return null
      val t = m.group(2).toIntOrNull() ?: return null
      val r = m.group(3).toIntOrNull() ?: return null
      val b = m.group(4).toIntOrNull() ?: return null

      return UIElementBounds(
        x = l,
        y = t,
        width = r - l,
        height = b - t,
      )
    }

    fun getAttributeIfNotBlank(attributeName: String): String? = attributes[attributeName]?.let {
      it.ifBlank { null }
    }

    val bounds = getAttributeIfNotBlank("bounds")?.let { bounds(it) }

    return if (attributes.isEmpty() && children.isEmpty()) {
      null
    } else {
      return ViewHierarchyTreeNode(
        accessibilityText = getAttributeIfNotBlank("accessibilityText"),
        centerPoint = bounds?.let { "${it.centerX},${it.centerY}" },
        checked = checked ?: false,
        children = children.mapNotNull {
          it.toViewHierarchyTreeNode()
        },
        className = getAttributeIfNotBlank("class"),
        dimensions = bounds?.let { "${it.width}x${it.height}" },
        clickable = clickable ?: false,
        enabled = enabled ?: true,
        focusable = getAttributeIfNotBlank("focusable") == "true",
        focused = focused ?: false,
        hintText = getAttributeIfNotBlank("hintText"),
        ignoreBoundsFiltering = getAttributeIfNotBlank("ignoreBoundsFiltering") == "true",
        password = getAttributeIfNotBlank("password") == "true",
        scrollable = getAttributeIfNotBlank("scrollable") == "true",
        selected = selected ?: false,
        resourceId = getAttributeIfNotBlank("resource-id"),
        text = getAttributeIfNotBlank("text"),
      )
    }
  }
}
