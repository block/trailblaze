package xyz.block.trailblaze.scripting

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Inlines every JSON-Schema `$ref` against the schema's own `definitions` / `$defs` bag and drops
 * the bag, producing a self-contained schema.
 *
 * **Why this exists.** The scripted-tool analyzer ([ScriptedToolDefinitionAnalyzer], driving
 * `ts-json-schema-generator` configured `expose: "all"`) emits any NAMED type — a string-literal
 * union enum (`type Dir = "UP" | "DOWN"`), a `Record<string, T>`, a named nested interface — as a
 * property `{ "$ref": "#/definitions/Dir" }` plus a sibling top-level `definitions` bag. The
 * subprocess wrapper's rendered zod converter
 * ([xyz.block.trailblaze.scripting.subprocess.InlineScriptToolServerSynthesizer]'s
 * `jsonSchemaPropertyToZod`) only understands a direct `enum` array or a direct `type` and THROWS
 * on a bare `$ref`, so spawning the MCP server for a tool with an enum-typed param would fail at
 * z-schema build time. The in-process QuickJS descriptor projection ([xyz.block.trailblaze.quickjs.tools]
 * `RegisteredToolSpec.toTrailblazeToolDescriptor`) doesn't throw but silently degrades a `$ref`
 * property to the default `string` type, losing its real shape.
 *
 * Flattening the analyzer's schema at the single runtime-config boundary
 * ([AnalyzerScriptedToolEnrichment], where the analyzer's raw schema becomes an
 * `InlineScriptToolConfig.inputSchema`) makes every consumer of that config — the subprocess
 * synthesizer and the in-process QuickJS projection — see a self-contained schema, so neither has to
 * special-case `$ref`. The build-time bundled-config emitter
 * ([xyz.block.trailblaze.host.BundledScriptedToolAnalyzeMain]) consumes the same enrichment output,
 * so it inherits the flattening too (it used to carry its own one-level copy of this logic). The
 * codegen path (`PerTrailmapClientDtsEmitter`) reads the analyzer's raw [ScriptedToolDefinition]
 * directly and resolves refs itself while rendering TypeScript, so it deliberately does NOT go
 * through this seam.
 *
 * **Recursive, with a cycle guard.** Resolves refs at every nesting level — `properties.*`,
 * `items`, `additionalProperties`, `prefixItems`, and `allOf` / `anyOf` / `oneOf` branches — so a
 * nested enum or an array-of-enum flattens too. A `$ref` that participates in a cycle (a recursive
 * input type, which `ts-json-schema-generator` represents precisely because it CAN'T inline it) is
 * left in place rather than expanded infinitely; a `$ref` with no matching definition is likewise
 * left untouched. Both degrade gracefully instead of throwing — flattening is best-effort, never a
 * new failure mode. (A surviving `$ref` is still rejected downstream by the subprocess synthesizer,
 * so a genuinely recursive input type — which zod can't represent without `z.lazy` anyway — stays
 * unsupported there; this is strictly better than the pre-fix state where ANY named type threw.)
 *
 * **URL-decoding.** `ts-json-schema-generator` percent-encodes special characters in the `$ref`
 * pointer (`#/definitions/Record%3Cstring%2Cnumber%3E`) while keeping the `definitions` bag keyed
 * by the decoded name (`Record<string,number>`), so the pointer's last path segment is URL-decoded
 * before lookup. (Simple names like `Dir` are unaffected — they encode to themselves.)
 */
object ScriptedToolSchemaRefFlattener {

  private val META_KEYS = setOf("definitions", "\$defs", "\$schema")

  // Local-ref pointer prefixes we know how to resolve against the root bag. Anything else (an
  // external ref like `other.json#/X`, a bare fragment, or a different JSON-pointer root) is left
  // untouched — mirrors `JsonSchemaToTsRich.extractRefName`'s prefix guard.
  private const val DEFINITIONS_PREFIX = "#/definitions/"
  private const val DEFS_PREFIX = "#/\$defs/"

  /**
   * Return [schema] with all `$ref`s resolved against its `definitions` / `$defs` bag and the bag
   * (plus any stray `$schema`) removed. Idempotent: a schema with no `$ref` / bag round-trips to an
   * equivalent object, so it's safe to call on hand-authored YAML schemas that never had refs.
   */
  fun flatten(schema: JsonObject): JsonObject {
    val definitions = (schema["definitions"] as? JsonObject) ?: (schema["\$defs"] as? JsonObject)
    if (definitions == null) {
      // Nothing to inline against — just strip a stray root `$schema` for a clean, stable shape.
      return stripRootMetaKeys(schema)
    }
    // Strip the root definition bag (and `$schema`) FIRST so the recursive walk never descends into
    // it, then resolve every `$ref` in the rest against the captured bag. Stripping happens ONLY at
    // the root: `ts-json-schema-generator` emits the bag exactly there, and a `properties` map can
    // legitimately contain a tool param literally named `definitions` / `$defs` / `$schema` that
    // must survive — so the recursive walk does NOT treat those names as meta keys.
    val rootWithoutBag = stripRootMetaKeys(schema)
    return resolveObject(rootWithoutBag, definitions, mutableSetOf()) as? JsonObject ?: rootWithoutBag
  }

  private fun stripRootMetaKeys(obj: JsonObject): JsonObject {
    if (obj.keys.none { it in META_KEYS }) return obj
    val out = LinkedHashMap<String, JsonElement>(obj.size)
    for ((k, v) in obj) if (k !in META_KEYS) out[k] = v
    return JsonObject(out)
  }

  private fun resolveNode(
    node: JsonElement,
    definitions: JsonObject,
    active: MutableSet<String>,
  ): JsonElement = when (node) {
    is JsonObject -> resolveObject(node, definitions, active)
    is JsonArray -> JsonArray(node.map { resolveNode(it, definitions, active) })
    else -> node
  }

  private fun resolveObject(
    node: JsonObject,
    definitions: JsonObject,
    active: MutableSet<String>,
  ): JsonElement {
    val refName = refTargetName(node)
    if (refName != null) {
      val target = definitions[refName] as? JsonObject
      // Unresolvable ref OR a cycle (refName already being expanded up-chain) → leave the node
      // untouched. Cyclic types can't be inlined; expanding them would loop forever.
      if (target == null || !active.add(refName)) return node
      val resolvedTarget = resolveObject(target, definitions, active)
      // The referencing node's own sibling keys (e.g. a property-level `description`) may
      // themselves be schemas in rare shapes, so resolve them too before merging.
      val resolvedSiblings = node
        .filterKeys { it != "\$ref" }
        .mapValues { (_, v) -> resolveNode(v, definitions, active) }
      active.remove(refName)
      return mergeRefSiblings(resolvedTarget, resolvedSiblings)
    }
    // No `$ref` here — recurse into every child. We do NOT drop `definitions` / `$defs` / `$schema`
    // here: below the root those names are property names (the keys of a `properties` map are
    // author-chosen and must survive), not the meta bag. The root bag was already stripped in
    // [flatten] before this walk began.
    val out = LinkedHashMap<String, JsonElement>(node.size)
    for ((k, v) in node) {
      out[k] = resolveNode(v, definitions, active)
    }
    return JsonObject(out)
  }

  /**
   * The decoded definition name a `{ "$ref": "#/definitions/Name" }` (or `#/$defs/Name`) node points
   * at, or null when the node has no string `$ref` OR the ref isn't a single-segment LOCAL pointer
   * into the root bag. Refusing to resolve other shapes — external refs (`other.json#/X`), bare
   * fragments, or deeper pointers (`#/definitions/A/properties/B`) — avoids silently mutating a
   * schema by grabbing a last segment that merely happens to collide with a bag key.
   */
  private fun refTargetName(node: JsonObject): String? {
    val ref = (node["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val encoded = when {
      ref.startsWith(DEFINITIONS_PREFIX) -> ref.removePrefix(DEFINITIONS_PREFIX)
      ref.startsWith(DEFS_PREFIX) -> ref.removePrefix(DEFS_PREFIX)
      else -> return null
    }
    // A bag key is a single path segment; a deeper pointer (`.../properties/X`) is not a bag entry.
    // ts-json-schema-generator percent-encodes any `/` inside a name as `%2F`, so a literal `/` here
    // means "deeper pointer", not "name with a slash".
    if (encoded.isEmpty() || encoded.contains('/')) return null
    return try {
      URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
      // Malformed %-escape — fall back to the literal segment rather than throwing.
      encoded
    }
  }

  /**
   * Merge a `$ref`'s resolved [target] with the referencing node's already-resolved [siblings]. The
   * target's fields come first; the referencing node's keys win on conflict (so a property-level
   * `description` overrides the definition's own). The `$ref` key is gone (replaced by its target).
   */
  private fun mergeRefSiblings(target: JsonElement, siblings: Map<String, JsonElement>): JsonElement {
    val targetObj = target as? JsonObject ?: return target
    if (siblings.isEmpty()) return targetObj
    val out = LinkedHashMap<String, JsonElement>(targetObj.size + siblings.size)
    // The target is a definition (a schema), so any `definitions` / `$defs` / `$schema` key on it is
    // a meta keyword, not a property name — drop them for consistency with the root-level stripping.
    for ((k, v) in targetObj) if (k !in META_KEYS) out[k] = v
    for ((k, v) in siblings) out[k] = v
    return JsonObject(out)
  }
}
