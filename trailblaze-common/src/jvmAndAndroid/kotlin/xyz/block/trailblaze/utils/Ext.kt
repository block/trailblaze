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

  /**
   * Gson round-trip of a [MaestroCommand] — paired with [asMaestroCommand] for log-only
   * shapes (e.g. [maestro.orchestra.MaestroValidationError.commandJsonObject] and
   * [xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroCommandLog.maestroCommandJsonObj]).
   * Do NOT use this as an execution or transport format; it emits Gson field names (e.g.
   * `backPressCommand`) that Maestro's YAML parser does not accept. For transport, render
   * commands via [xyz.block.trailblaze.maestro.MaestroYamlSerializer.toYaml] and execute
   * through [xyz.block.trailblaze.maestro.MaestroYamlParser.parseYaml].
   */
  fun MaestroCommand.asJsonObject(): JsonObject = try {
    val maestroCommandJson = TrailblazeJsonInstance.encodeToString(
      GenericGsonJsonSerializer(MaestroCommand::class),
      this,
    )
    TrailblazeJsonInstance.decodeFromString(JsonObject.serializer(), maestroCommandJson)
  } catch (e: Exception) {
    error(e)
  }

  fun Command.asJsonObject(): JsonObject = MaestroCommand(this).asJsonObject()

  /**
   * Inverse of [asJsonObject]. Only valid on JsonObjects produced by the matching
   * [asJsonObject] call (i.e. in Gson field-name shape) — used to reconstruct a [Command]
   * from a log record for rendering. Not a general-purpose Maestro-YAML decoder; for those,
   * route through [xyz.block.trailblaze.maestro.MaestroYamlParser.parseYaml].
   */
  fun JsonObject.asMaestroCommand(): Command? = try {
    TrailblazeJsonInstance.decodeFromString(
      GenericGsonJsonSerializer(MaestroCommand::class),
      this.toString(),
    ).asCommand()
  } catch (e: Exception) {
    Console.error("Failed to deserialize MaestroCommand from JSON: ${e.message}")
    null
  }

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
        x1 = bounds?.x ?: 0,
        y1 = bounds?.y ?: 0,
        x2 = bounds?.let { it.x + it.width } ?: 0,
        y2 = bounds?.let { it.y + it.height } ?: 0,
        // centerPoint is derived from integer bounds (no rounding drift).
        // Intentionally set here despite ViewHierarchyTreeNode KDoc preferring x1/y1/x2/y2:
        // CenterPointMatcher, tap tools, and selector strategies read this field directly.
        // Do not remove until those consumers are migrated to use bounds.
        centerPoint = bounds?.let { "${it.centerX},${it.centerY}" },
        checked = checked ?: false,
        children = children.mapNotNull {
          it.toViewHierarchyTreeNode()
        },
        className = getAttributeIfNotBlank("class"),
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
