package xyz.block.trailblaze.migration

import com.charleskorn.kaml.Location
import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Finds v1 trail-file content that the migrator's (lenient) decode silently drops. Two distinct drop
 * mechanisms are covered:
 *
 * 1. **Schema-unknown keys.** The runtime [TrailblazeYaml] decodes with kaml `strictMode = false` +
 *    `ignoreUnknownKeys = true`, so any key the schema doesn't recognize is discarded during decode.
 *    This detector re-decodes the SAME text against a **strict** [TrailblazeYaml] (kaml
 *    `strictMode = true`), which throws [UnknownPropertyException] on the first such key. kaml aborts
 *    on the first one, so to enumerate ALL of them this decodes from a parsed [YamlNode] tree and, on
 *    each throw, records the drop, prunes that one key, and re-decodes until strict decode succeeds.
 *    Strict re-parse beats a re-encode diff here: the custom serializers omit default-valued fields
 *    (e.g. `recordable: true`) on encode, which a structural diff can't tell apart from a real drop.
 *
 * 2. **Extra keys in a tool-list item.** `TrailblazeToolYamlWrapperSerializer` decodes only the FIRST
 *    entry of each `tools:` list-item map (the tool name), so any sibling key at the item level — e.g.
 *    a positional anchor accidentally dedented out of the tool's args — is silently dropped. Strict
 *    decode can't catch this: the serializer never hands the extra keys to any decoder, so they never
 *    read as unknown properties. A structural pre-scan of `tools:` items flags them instead.
 *
 * Both malformed shapes have really bitten hand-authored trails (positional anchors written where the
 * schema can't carry them); with no signal, the only defense was a human eyeballing the diff.
 *
 * Strictly a diagnostic: it never mutates what migrates and never throws — any unexpected failure
 * degrades to "report what we found so far," so a detector bug can't break a migration. [detect]
 * enforces the never-throws contract itself rather than leaning on callers.
 */
internal object TrailRoundTripDropDetector {

  /** Backstop against a non-converging prune loop; far above any real file's unknown-key count. */
  private const val MAX_ITERATIONS = 500

  /**
   * Detect content in [yamlText] (a single v1 `*.trail.yaml`, named [fileName] for reporting) that a
   * lenient decode drops. Returns one [DroppedContentEntry] per dropped key, de-duplicated and
   * ordered by source line. Empty when nothing is dropped, when [yamlText] isn't a decodable v1 trail
   * list, or on any unexpected failure (best-effort by contract — never throws).
   */
  fun detect(
    strictYaml: TrailblazeYaml,
    fileName: String,
    yamlText: String,
  ): List<DroppedContentEntry> {
    val instance = strictYaml.getInstance()

    // Parse once; both detectors read this tree (the strict loop re-decodes it after each prune).
    // A parse failure here means the file isn't a decodable v1 trail (e.g. it's the unified format),
    // which the lenient migrator path would also reject — nothing for us to say.
    val root: YamlNode = try {
      instance.parseToYamlNode(yamlText)
    } catch (_: Throwable) {
      return emptyList()
    }

    // Enforce the "never throws" contract at the seam rather than relying on the caller: an
    // unexpected failure in either detector degrades to what that detector could produce (empty on a
    // hard failure), so a detector bug can never propagate out of a migration.
    val strictDrops = runCatching { strictDecodeUnknownKeys(instance, root, fileName) }.getOrDefault(emptyList())
    val extraToolKeyDrops = runCatching { extraToolEntryKeys(root, fileName) }.getOrDefault(emptyList())

    return (strictDrops + extraToolKeyDrops)
      .distinctBy { Triple(it.path, it.line, it.key) }
      .sortedWith(compareBy({ it.line }, { it.path }, { it.key }))
  }

  /**
   * Mechanism 1 (see class kdoc): enumerate every schema-unknown key by strict-decoding [root], and
   * on each [UnknownPropertyException] recording the key, pruning it, and re-decoding until decode
   * succeeds. Returns empty when [root] isn't a decodable v1 trail list, or on any non-unknown-key
   * failure (best-effort).
   */
  @OptIn(ExperimentalSerializationApi::class)
  private fun strictDecodeUnknownKeys(
    instance: Yaml,
    root: YamlNode,
    fileName: String,
  ): List<DroppedContentEntry> {
    val itemSerializer = instance.serializersModule.getContextual(TrailYamlItem::class) ?: return emptyList()
    val listSerializer: KSerializer<List<TrailYamlItem>> = ListSerializer(itemSerializer)

    var current = root
    val dropped = mutableListOf<DroppedContentEntry>()
    val seen = mutableSetOf<Pair<Int, Int>>()
    repeat(MAX_ITERATIONS) {
      try {
        instance.decodeFromYamlNode(listSerializer, current)
        return dropped // strict decode succeeded — nothing (else) was dropped
      } catch (e: Throwable) {
        // kaml wraps a nested unknown-key error in `InvalidPropertyValueException` at every level it
        // bubbles up through (e.g. `recording` → `tools` → the tool's args), so the real signal is in
        // the cause chain, not the top-level throw. No `UnknownPropertyException` anywhere in the
        // chain means strict mode failed for some OTHER reason the lenient decode didn't hit —
        // unexpected, so degrade to what we've found rather than break the migration.
        val unknown = findUnknownProperty(e) ?: return dropped
        val loc = unknown.location
        if (!seen.add(loc.line to loc.column)) {
          // The same unknown key resurfaced, so the prune didn't converge — stop rather than loop.
          return dropped
        }
        dropped += DroppedContentEntry(
          file = fileName,
          path = unknown.path.toHumanReadableString(),
          key = unknown.propertyName,
          line = unknown.line,
        )
        current = pruneKeyByLocation(current, unknown.propertyName, loc)
      }
    }
    return dropped
  }

  /**
   * Mechanism 2 (see class kdoc): flag every sibling key in a multi-entry `tools:` list item. The
   * wrapper serializer keeps only the first entry (document order), so entries 2..N are dropped
   * without any decode error — a structural scan is the only way to see them.
   */
  private fun extraToolEntryKeys(
    root: YamlNode,
    fileName: String,
  ): List<DroppedContentEntry> {
    val out = mutableListOf<DroppedContentEntry>()
    fun visit(node: YamlNode) {
      when (node) {
        is YamlMap -> {
          for ((key, value) in node.entries) {
            // A `tools:` value (or its items) may be wrapped in a YAML tag; unwrap before inspecting.
            val toolsList = value.untag()
            if (key.content == TrailYamlItem.KEYWORD_TOOLS && toolsList is YamlList) {
              for (rawItem in toolsList.items) {
                val item = rawItem.untag()
                if (item is YamlMap && item.entries.size > 1) {
                  // First entry is the tool the serializer keeps; the rest are silently dropped.
                  item.entries.entries.drop(1).forEach { (extraKey, _) ->
                    out += DroppedContentEntry(
                      file = fileName,
                      path = extraKey.path.toHumanReadableString(),
                      key = extraKey.content,
                      line = extraKey.location.line,
                    )
                  }
                }
              }
            }
            visit(value)
          }
        }
        is YamlList -> node.items.forEach(::visit)
        is YamlTaggedNode -> visit(node.innerNode)
        else -> {}
      }
    }
    visit(root)
    return out
  }

  /** Strip a YAML tag wrapper if present, so structural checks see the underlying node. */
  private fun YamlNode.untag(): YamlNode = if (this is YamlTaggedNode) innerNode else this

  /** Walk [t]'s cause chain for the underlying [UnknownPropertyException] kaml wraps on the way up. */
  private fun findUnknownProperty(t: Throwable): UnknownPropertyException? {
    var current: Throwable? = t
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
      if (current is UnknownPropertyException) return current
      current = current.cause
    }
    return null
  }

  /**
   * Rebuild [node] with the single map entry whose key is [key] at source [location] removed.
   * Matching on the exact (name + line + column) location targets one key precisely, so a same-named
   * key elsewhere in the tree is untouched. Remaining nodes keep their original paths, so a later
   * [UnknownPropertyException]'s reported line stays accurate. Pure — unit-tested in isolation from
   * the strict-decode loop.
   */
  internal fun pruneKeyByLocation(node: YamlNode, key: String, location: Location): YamlNode =
    when (node) {
      is YamlMap -> {
        val kept = LinkedHashMap<YamlScalar, YamlNode>()
        for ((k, v) in node.entries) {
          if (k.content == key && k.location == location) continue // drop this entry (and its subtree)
          kept[k] = pruneKeyByLocation(v, key, location)
        }
        YamlMap(kept, node.path)
      }
      is YamlList -> YamlList(node.items.map { pruneKeyByLocation(it, key, location) }, node.path)
      is YamlTaggedNode -> node.copy(innerNode = pruneKeyByLocation(node.innerNode, key, location))
      else -> node
    }
}
