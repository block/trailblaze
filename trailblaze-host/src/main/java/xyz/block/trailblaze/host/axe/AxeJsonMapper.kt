package xyz.block.trailblaze.host.axe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Parses raw `axe describe-ui` JSON into a [TrailblazeNode] tree whose nodes carry
 * [DriverNodeDetail.IosAxe] detail.
 *
 * AXe's JSON is an array at the top level — typically containing a single root
 * `AXApplication` element with nested `children`. This mapper preserves the tree
 * shape and maps each element's AX fields into [DriverNodeDetail.IosAxe].
 */
object AxeJsonMapper {

  private val json = Json { ignoreUnknownKeys = true }

  /** Parses AXe JSON and returns a single root [TrailblazeNode], with true duplicates collapsed. */
  fun parse(rawJson: String): TrailblazeNode {
    val counter = NodeIdCounter()
    val root = json.parseToJsonElement(rawJson)
    val tree = when (root) {
      is JsonArray -> {
        // AXe wraps the tree in an array — unwrap to the first element when it's an
        // object, or build a synthetic root for multi-element / non-object cases.
        val single = root.singleOrNull() as? JsonObject
        single?.let { mapNode(it, counter) }
          ?: TrailblazeNode(
            nodeId = counter.next(),
            driverDetail = emptyIosAxe(),
            children = root.mapNotNull { (it as? JsonObject)?.let { obj -> mapNode(obj, counter) } },
            bounds = null,
          )
      }
      is JsonObject -> mapNode(root, counter)
      else -> error("Unexpected AXe JSON root: ${root::class.simpleName}")
    }
    return dedupeTree(tree)
  }

  /**
   * Collapses TRUE duplicate nodes — AXe sometimes reports the same physical element twice
   * (observed as a StaticText appearing twice with identical frames, typically an overlapping
   * parent/child re-projection), which makes a host-unique selector spuriously ambiguous.
   *
   * The dedupe rule is deliberately narrow so legitimately repeated UI is never touched. A
   * node is dropped only when ALL of:
   * - an earlier node (pre-order) carried the exact same [DriverNodeDetail] AND the exact
   *   same non-null bounds — two distinct real elements of the same type with identical text
   *   can't share the identical frame, while identical list rows differ in frame and survive;
   * - it carries matchable text (label/value/title/uniqueId) — content-less structural
   *   containers (nested Groups, Window-in-Application) legitimately share frames;
   * - it has no children — a subtree-bearing "duplicate" might be the only path to its
   *   children, so it is kept even at the cost of a rare residual ambiguity.
   */
  internal fun dedupeTree(root: TrailblazeNode): TrailblazeNode {
    val seen = HashSet<Pair<DriverNodeDetail, TrailblazeNode.Bounds>>()
    fun visit(node: TrailblazeNode): TrailblazeNode? {
      val key = dedupeKey(node)
      if (key != null && !seen.add(key) && node.children.isEmpty()) return null
      val children = node.children.mapNotNull(::visit)
      return if (children == node.children) node else node.copy(children = children)
    }
    return visit(root)!! // the root is always first-seen, never dropped
  }

  private fun dedupeKey(node: TrailblazeNode): Pair<DriverNodeDetail, TrailblazeNode.Bounds>? {
    val detail = node.driverDetail as? DriverNodeDetail.IosAxe ?: return null
    val bounds = node.bounds ?: return null
    if (!detail.hasIdentifiableProperties) return null
    return detail to bounds
  }

  private class NodeIdCounter {
    private var next = 0L
    fun next(): Long = next++
  }

  private fun mapNode(obj: JsonObject, counter: NodeIdCounter): TrailblazeNode {
    val bounds = parseBounds(obj["frame"])
    val children = (obj["children"] as? JsonArray).orEmpty()
      .mapNotNull { (it as? JsonObject)?.let { child -> mapNode(child, counter) } }

    return TrailblazeNode(
      nodeId = counter.next(),
      bounds = bounds,
      children = children,
      driverDetail = DriverNodeDetail.IosAxe(
        role = obj.str("role"),
        subrole = obj.str("subrole"),
        roleDescription = obj.str("role_description"),
        label = obj.str("AXLabel"),
        value = obj.str("AXValue"),
        uniqueId = obj.str("AXUniqueId"),
        type = obj.str("type"),
        title = obj.str("title"),
        help = obj.str("help"),
        customActions = (obj["custom_actions"] as? JsonArray)
          ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }
          ?: emptyList(),
        enabled = obj["enabled"]?.primitiveOrNull()?.booleanOrNull ?: true,
        contentRequired = obj["content_required"]?.primitiveOrNull()?.booleanOrNull ?: false,
        pid = obj["pid"]?.primitiveOrNull()?.intOrNull,
      ),
    )
  }

  private fun parseBounds(frame: JsonElement?): TrailblazeNode.Bounds? {
    val obj = frame as? JsonObject ?: return null
    val x = obj["x"]?.primitiveOrNull()?.doubleOrNull ?: return null
    val y = obj["y"]?.primitiveOrNull()?.doubleOrNull ?: return null
    val w = obj["width"]?.primitiveOrNull()?.doubleOrNull ?: return null
    val h = obj["height"]?.primitiveOrNull()?.doubleOrNull ?: return null
    return TrailblazeNode.Bounds(
      left = x.toInt(),
      top = y.toInt(),
      right = (x + w).toInt(),
      bottom = (y + h).toInt(),
    )
  }

  /** Returns the string content, treating AXe's " " (single space) empty marker as null. */
  private fun JsonObject.str(key: String): String? {
    val raw = this[key]?.primitiveOrNull()?.contentOrNullSafe() ?: return null
    return raw.ifBlank { null }
  }

  private fun JsonElement.primitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

  /** [JsonPrimitive.contentOrNull] isn't standard — this replicates its behavior for nullable content. */
  private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else content

  private fun emptyIosAxe(): DriverNodeDetail.IosAxe = DriverNodeDetail.IosAxe()
}
