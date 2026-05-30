package xyz.block.trailblaze.bundle

import java.net.URLDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Render a JSON Schema (produced by `ts-json-schema-generator` via
 * [xyz.block.trailblaze.scripting.ScriptedToolDefinitionAnalyzer]) back into a TypeScript type
 * literal that the per-trailmap `client.d.ts` can drop in verbatim as the `args:` or `result:`
 * half of a [TrailblazeToolMap] entry.
 *
 * This is the *inverse* direction of `ts-json-schema-generator`: we lose the original source
 * identifier (`TypedDemoOutput` becomes an inline object literal in the emitted `.d.ts`) but
 * preserve the structural shape AND field-level TSDoc descriptions, which is what the
 * downstream consumer's IDE shows on hover.
 *
 * **Scope.** Handles the JSON Schema subset documented in the analyzer's kdoc:
 *  - primitives (`string`, `number`/`integer`, `boolean`, `null`)
 *  - arrays (`{ type: "array", items: <schema> }`)
 *  - objects with explicit properties + optional `required:`
 *  - optional fields (via `required:` exclusion)
 *  - enums (`{ enum: [...] }`) rendered as a union of string literals
 *  - `const` literals
 *  - literal unions / discriminated unions (`anyOf`, `oneOf`) → `A | B | ...`
 *  - intersections (`allOf`) → `A & B & ...`
 *  - `nullable` via `type: ["string", "null"]` array form
 *  - `Record<string, T>` via `{ type: "object", additionalProperties: <T-schema> }`
 *  - `$ref` into the schema's sibling `definitions:` bag (resolved inline)
 *  - `Date` (`{ type: "string", format: "date-time" }`) — emits `string` (no `Date` runtime
 *    type round-trip, since the wire JSON is a string anyway).
 *
 * Anything outside this subset degrades to `unknown` — keeps codegen unblocked on future
 * schema additions rather than failing the per-trailmap `.d.ts` emit. Authors using an
 * unsupported construct see the `unknown` in their IDE and can chase the cause.
 *
 * **Cycle prevention.** Self-referential types (`interface Node { children: Node[] }`)
 * would otherwise expand forever; the resolver tracks the set of `$ref` names currently
 * being expanded and emits `unknown` when a cycle is detected. This is lossy but
 * never crashes the bundler.
 *
 * **Sibling of [jsonSchemaToTsType].** That function handles a flat `(type, enum?)` pair
 * from the YAML-author `inputSchema:` shape, where only top-level primitives appear. This
 * function handles the recursive, nested-object case the analyzer produces. Both
 * coexist — the YAML-shape callers stay on the flat function; the analyzer-shape callers
 * use this one.
 *
 * **Build-logic exclusion is temporary.** This file is currently excluded from
 * `build-logic`'s shared srcDir composition (alongside [WorkspaceClientDtsGenerator])
 * because [kotlinx.serialization.json.JsonElement] is daemon-only today. When the
 * follow-up that wires the analyzer into `TrailblazeTrailmapBundler` (build-time) lands, the
 * exclusion will need to flip — the bundler will need this same JSON Schema → TS path.
 * If you're reading this comment because you're working on that follow-up: drop the
 * matching `exclude(...)` line for `JsonSchemaToTsRich.kt` in
 * `build-logic/build.gradle.kts` AND add `kotlinx-serialization-json` to build-logic's
 * runtime deps (currently only `kotlinx-serialization-core` is declared).
 */
internal object JsonSchemaToTsRich {

  /** Default expansion depth budget — generous enough for realistic schemas, hard cap on pathological inputs. */
  private const val MAX_DEPTH = 32

  /**
   * Render [schema] as a TS type literal. If [schema] uses `$ref` into a sibling `definitions:`
   * map, pass the top-level [definitions] so refs resolve. Indentation in the emitted output
   * starts at [baseIndent] spaces (used so the emitted block lines up with the surrounding
   * `args: {...}` block in the `.d.ts`).
   */
  fun render(
    schema: JsonElement,
    definitions: JsonObject? = null,
    baseIndent: Int = 6,
  ): String {
    val ctx = RenderContext(
      definitions = definitions ?: extractTopLevelDefinitions(schema),
    )
    return ctx.renderType(schema, baseIndent)
  }

  /**
   * Extract a `definitions:` bag from a top-level schema object when the caller didn't
   * pass one in explicitly. Defensive: the analyzer's wrapper schema always carries
   * `definitions` (or omits it entirely for simple shapes); supporting `$defs` too just
   * costs a couple of lines and keeps us forward-compatible with newer drafts.
   */
  private fun extractTopLevelDefinitions(schema: JsonElement): JsonObject? {
    val obj = schema as? JsonObject ?: return null
    return (obj["definitions"] as? JsonObject)
      ?: (obj["\$defs"] as? JsonObject)
  }

  private class RenderContext(
    val definitions: JsonObject?,
    /** Set of `$ref` names currently being expanded — short-circuits to `unknown` on revisit. */
    val expandingRefs: MutableSet<String> = mutableSetOf(),
  ) {

    fun renderType(schemaElement: JsonElement, indent: Int, depth: Int = 0): String {
      if (depth > MAX_DEPTH) return "unknown"
      if (schemaElement is JsonNull) return "null"
      val schema = schemaElement as? JsonObject ?: return "unknown"

      // `{ "$ref": "#/definitions/X" }` — resolve inline and recurse. URL-decoding handles
      // the encoded names ts-json-schema-generator produces for generic instantiations like
      // `Record<string, number>` (encoded as `Record%3Cstring%2C%20number%3E`).
      schema["\$ref"]?.let { refElement ->
        val refValue = (refElement as? JsonPrimitive)?.contentOrNull
        if (refValue != null) {
          val refName = extractRefName(refValue) ?: return "unknown"
          if (refName in expandingRefs) return "unknown"
          val referenced = definitions?.get(refName) as? JsonObject ?: return "unknown"
          expandingRefs += refName
          try {
            return renderType(referenced, indent, depth + 1)
          } finally {
            expandingRefs -= refName
          }
        }
      }

      // `enum` — a union of literal values. Always wins over a sibling `type:` because
      // ts-json-schema-generator emits `{ "type": "string", "enum": [...] }` for string
      // enums and we want the more precise literal union.
      val enumValues = schema["enum"] as? JsonArray
      if (enumValues != null && enumValues.isNotEmpty()) {
        return enumValues.joinToString(" | ") { renderJsonLiteral(it) }
      }

      // `const` — single literal type. Same precedence rule as `enum`.
      schema["const"]?.let { return renderJsonLiteral(it) }

      // `anyOf` / `oneOf` — union. ts-json-schema-generator uses anyOf for open unions and
      // oneOf for closed ones; both render the same in TS.
      (schema["anyOf"] as? JsonArray)?.let { return renderUnion(it, indent, depth) }
      (schema["oneOf"] as? JsonArray)?.let { return renderUnion(it, indent, depth) }

      // `allOf` — intersection.
      (schema["allOf"] as? JsonArray)?.let { branches ->
        if (branches.isNotEmpty()) {
          return branches.joinToString(" & ") { renderType(it, indent, depth + 1) }
        }
      }

      // `type:` may be a string ("object") OR a `["string", "null"]` array form for
      // nullable-of-X. Handle both shapes.
      val typeElement = schema["type"]
      val types: List<String> = when (typeElement) {
        is JsonPrimitive -> listOfNotNull(typeElement.contentOrNull)
        is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
      }
      if (types.size > 1) {
        return types.joinToString(" | ") { singleType ->
          renderSingleType(singleType, schema, indent, depth)
        }
      }
      if (types.size == 1) {
        return renderSingleType(types.first(), schema, indent, depth)
      }
      return "unknown"
    }

    private fun renderUnion(branches: JsonArray, indent: Int, depth: Int): String =
      if (branches.isEmpty()) "unknown"
      else branches.joinToString(" | ") { renderType(it, indent, depth + 1) }

    private fun renderSingleType(type: String, schema: JsonObject, indent: Int, depth: Int): String = when (type) {
      "string" -> "string"
      "number", "integer" -> "number"
      "boolean" -> "boolean"
      "null" -> "null"
      "array" -> renderArray(schema, indent, depth)
      "object" -> renderObject(schema, indent, depth)
      else -> "unknown"
    }

    private fun renderArray(schema: JsonObject, indent: Int, depth: Int): String {
      val items = schema["items"] ?: return "unknown[]"
      // Tuple shape: `items: [<schema>, <schema>, ...]` — render as `[T1, T2, ...]`.
      if (items is JsonArray) {
        if (items.isEmpty()) return "[]"
        val tupleMembers = items.joinToString(", ") { renderType(it, indent, depth + 1) }
        return "[$tupleMembers]"
      }
      val itemType = renderType(items, indent, depth + 1)
      // Wrap union / intersection item types in `Array<...>` so the postfix `[]` doesn't
      // mis-bind by TS precedence. Two failure modes this catches:
      //
      //   1. `string | null` → without the wrap, `string | null[]` parses as
      //      `string | (null[])`.
      //   2. `{a:string} | {b:number}` → an earlier version of this check exempted any
      //      itemType starting with `{`, but a union of object literals also starts with
      //      `{` and still needs the wrap. Bot-flagged by codex and Copilot — pinned in
      //      the test suite via two cases (union of primitives, union of object literals).
      //
      // We detect the wrap-needed cases by looking for an OPEN-LEVEL `|` or `&` (TS
      // union / intersection operators). The inner object literals also contain `|` /
      // `&` as part of their own field types in pathological cases, but our renderer
      // emits one type per recursion so the outermost level's operators always sit
      // outside any nested braces — a simple substring check on ` | ` / ` & ` is
      // sufficient for the shapes the generator actually produces.
      val isUnionOrIntersection = itemType.contains(" | ") || itemType.contains(" & ")
      return if (isUnionOrIntersection) "Array<$itemType>" else "$itemType[]"
    }

    private fun renderObject(schema: JsonObject, indent: Int, depth: Int): String {
      val properties = schema["properties"] as? JsonObject
      val required = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet().orEmpty()
      val additionalProperties = schema["additionalProperties"]

      // `Record<string, T>` shape: no explicit `properties:`, `additionalProperties:` is
      // itself a schema (i.e. not `true`/`false`).
      val emptyProperties = properties == null || properties.isEmpty()
      if (emptyProperties && additionalProperties is JsonObject) {
        val valueType = renderType(additionalProperties, indent, depth + 1)
        return "Record<string, $valueType>"
      }
      if (emptyProperties && additionalProperties is JsonPrimitive &&
        additionalProperties.booleanOrNull == true
      ) {
        return "Record<string, unknown>"
      }
      if (emptyProperties) {
        // Closed empty object — same shape the renderer uses for "tool takes no args".
        return "Record<string, never>"
      }
      // Explicit properties — render as object type literal with field-level TSDoc.
      // When `additionalProperties` is ALSO a schema, append a TypeScript index signature
      // (`[key: string]: <T>`) after the named fields so the closed-object-with-extra-keys
      // shape `{ a: string; [k: string]: number }` round-trips faithfully. The empty-
      // properties case is already handled above as bare `Record<>`.
      val innerIndent = " ".repeat(indent + 2)
      val closeIndent = " ".repeat(indent)
      return buildString {
        append("{\n")
        properties.entries.forEach { (fieldName, fieldSchema) ->
          val fieldObj = fieldSchema as? JsonObject
          val description = fieldObj?.get("description")?.let {
            (it as? JsonPrimitive)?.contentOrNull
          }?.takeIf { it.isNotBlank() }
          if (description != null) {
            appendFieldJsDoc(description, innerIndent)
          }
          val optional = fieldName !in required
          val fieldType = renderType(fieldSchema, indent + 2, depth + 1)
          val key = if (isSafeTsIdentifier(fieldName)) fieldName else "\"${fieldName.replace("\"", "\\\"")}\""
          append(innerIndent)
          append(key)
          if (optional) append("?")
          append(": ")
          append(fieldType)
          append(";\n")
        }
        // Mixed shape: named properties AND an `additionalProperties` schema means the
        // object is a closed bag with named fields PLUS an index signature for extra
        // keys. TS expresses this as `{ a: string; [key: string]: number }`. Without
        // this branch the index signature would be silently dropped — a faithfulness
        // loss for any `interface Foo { explicit: string; [k: string]: Bar }` author.
        if (additionalProperties is JsonObject) {
          val valueType = renderType(additionalProperties, indent + 2, depth + 1)
          append(innerIndent)
          append("[key: string]: ")
          append(valueType)
          append(";\n")
        } else if (additionalProperties is JsonPrimitive && additionalProperties.booleanOrNull == true) {
          append(innerIndent)
          append("[key: string]: unknown;\n")
        }
        append(closeIndent)
        append("}")
      }
    }

    /**
     * Emit a field's TSDoc as a SINGLE JSDoc block. Single-line descriptions render
     * inline (`/** text */`); multi-line descriptions render as a multi-line block
     * (`/**\n * line1\n * line2\n */`) so IDE hover picks up every line.
     *
     * A code-review pass caught the bug where each line of a multi-line description
     * was being emitted as its own self-closing `/** line */` block — TS treats only
     * the LAST such comment as the JSDoc attached to the following field, so the
     * earlier lines silently disappeared from IDE hover.
     */
    private fun StringBuilder.appendFieldJsDoc(description: String, innerIndent: String) {
      val lines = description.lines()
      if (lines.size == 1) {
        append(innerIndent)
        append("/** ")
        append(escapeJsDocComment(lines[0]))
        append(" */\n")
        return
      }
      append(innerIndent)
      append("/**\n")
      lines.forEach { line ->
        append(innerIndent)
        append(" * ")
        append(escapeJsDocComment(line))
        append("\n")
      }
      append(innerIndent)
      append(" */\n")
    }

    /**
     * Render a JSON literal as a TS literal type. Strings get quoted (with `"` escapes);
     * numbers/booleans/null pass through verbatim. Composite literals are rare in this
     * subset but fall back to `unknown` rather than panic.
     */
    private fun renderJsonLiteral(literal: JsonElement): String = when (literal) {
      is JsonPrimitive -> when {
        literal.isString -> "\"${literal.content.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        literal.content == "null" -> "null"
        else -> literal.content
      }
      else -> "unknown"
    }

    private fun extractRefName(ref: String): String? {
      val prefix = "#/definitions/"
      val defsPrefix = "#/\$defs/"
      val encoded = when {
        ref.startsWith(prefix) -> ref.removePrefix(prefix)
        ref.startsWith(defsPrefix) -> ref.removePrefix(defsPrefix)
        else -> return null
      }
      return try {
        URLDecoder.decode(encoded, Charsets.UTF_8.name())
      } catch (_: Exception) {
        // Catch the broad surface (malformed percent-encoding via IllegalArgumentException,
        // any future decoder pathology) so this defensive branch never propagates an
        // unexpected exception out of the renderer. Matches the belt-and-suspenders
        // posture in `PerTrailmapClientDtsEmitter.collectTypedToolOverridesForTrailmap`.
        null
      }
    }
  }
}
