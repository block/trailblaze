package xyz.block.trailblaze.bundle.yaml

import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar

/**
 * Shared YAML emit + plain-scalar resolution helpers used wherever Trailblaze code rewrites
 * a YAML file from a generic Kotlin `Map<String, Any?>` tree.
 *
 * Two prior in-tree implementations of these helpers (`TrailblazeBundledConfigTasks.kt` for
 * the bundled-config plugin's pack→target generator, and `TrailblazeDesktopUtil.kt` for the
 * Goose-config writer) had byte-for-byte identical quoting and scalar-resolution rules. Both
 * call sites now route through this object — single edit point for any future YAML-emission
 * fix, and the bundler module is the natural home since it already owns the kaml-tree-to-
 * Kotlin-tree conversion path.
 *
 * **Scope.** The emit-side functions ([renderMap], [renderList], [formatScalar]) cover
 * block-style YAML with 2-space indent and list dashes flush with parent keys — the human
 * convention used in this repo's hand-authored YAML and in the Goose config format. They
 * do NOT handle anchors, aliases, multi-doc, custom tags, or comments; round-trips through
 * these emitters lose any of those features in the input. Acceptable for the current
 * callers (target generation + Goose config rewrite) — neither input format uses them.
 *
 * **Plain-scalar resolution** ([resolveYamlScalar]) implements the YAML 1.2 core schema
 * for `true` / `false` / `null` only. YAML 1.1 forms (`yes`/`no`/`on`/`off`) are
 * intentionally excluded — they collide with legitimate string values in modern manifests
 * and round-trip as quoted strings instead.
 */
object YamlEmitter {

  /**
   * YAML control words that would be misinterpreted as null/boolean/etc. when emitted
   * unquoted. `lowercase()` the input before lookup; YAML 1.1 forms (`yes`/`no`/`on`/`off`)
   * are included in the *quoting* set even though [resolveYamlScalar] doesn't *resolve*
   * them — a string `"yes"` (intended as a string by the author) must round-trip as
   * `'yes'` to avoid ambiguity with downstream parsers that DO interpret it as a boolean.
   */
  private val YAML_RESERVED_WORDS =
    setOf("yes", "no", "true", "false", "null", "on", "off", "y", "n", "~")

  /**
   * Resolve a plain YAML scalar's text content into the matching Kotlin type, per YAML 1.2
   * core schema. Returns `null` for empty / `~` / `null`-cased; `Boolean` for canonical
   * `true`/`false` (any casing); the original [content] String otherwise.
   *
   * Why this matters: kaml's tree API surfaces every plain scalar as `YamlScalar.content:
   * String`, regardless of whether the YAML wrote `true` (boolean) or `'true'` (string).
   * Without this resolution, a Kotlin tree built from kaml would have `Boolean(true)` as
   * `String("true")`, which then re-emits as `'true'` (quoted) — visible regression vs.
   * any prior SnakeYAML round-trip. Resolving here lets the emit path use natural typing.
   */
  fun resolveYamlScalar(content: String): Any? = when {
    content.isEmpty() || content == "~" || content.equals("null", ignoreCase = true) -> null
    content == "true" || content == "True" || content == "TRUE" -> true
    content == "false" || content == "False" || content == "FALSE" -> false
    else -> content
  }

  /**
   * Recursively convert an immutable kaml [YamlNode] tree into a mutable Kotlin tree
   * (`LinkedHashMap` / `ArrayList` / scalar) that callers can splice / edit before
   * re-emitting via [renderMap]. Plain scalars are resolved via [resolveYamlScalar].
   */
  fun yamlNodeToMutable(node: YamlNode): Any? = when (node) {
    is YamlNull -> null
    is YamlScalar -> resolveYamlScalar(node.content)
    is YamlList -> node.items.mapTo(ArrayList(), ::yamlNodeToMutable)
    is YamlMap -> yamlMapToMutable(node)
    else -> throw IllegalStateException("Unsupported YAML node type: ${node::class.simpleName}")
  }

  fun yamlMapToMutable(map: YamlMap): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    map.entries.forEach { (k, v) -> out[k.content] = yamlNodeToMutable(v) }
    return out
  }

  /**
   * Render a Kotlin tree as block-style YAML text. 2-space indent, list dashes flush with
   * the parent key. Scalars are quoted only when they would otherwise be misread; see
   * [needsYamlQuoting] for the rules. Output never starts with the `---` document marker
   * and never ends with a trailing blank line — callers add framing if they need it.
   */
  fun renderMap(map: Map<String, Any?>, indent: String = ""): String =
    buildString { appendMap(this, map, indent) }

  /**
   * Append a Kotlin map to [out] at the given [indent]. Public for the rare caller that's
   * already accumulating into a `StringBuilder` (e.g., the bundled-config target file
   * builder, which prepends a banner before the rendered map).
   */
  fun appendMap(out: StringBuilder, map: Map<String, Any?>, indent: String) {
    map.forEach { (key, value) ->
      when (value) {
        is Map<*, *> -> {
          out.append(indent).append(key).append(":\n")
          @Suppress("UNCHECKED_CAST")
          appendMap(out, value as Map<String, Any?>, indent + "  ")
        }
        is List<*> -> {
          out.append(indent).append(key).append(":\n")
          appendList(out, value, indent + "  ")
        }
        else -> out.append(indent).append(key).append(": ").append(formatScalar(value)).append('\n')
      }
    }
  }

  /**
   * Append a Kotlin list to [out] at the given [indent], emitting each item on its own
   * line prefixed with `- `. Maps inside the list use the "first key on the dash line,
   * remaining keys indented one level deeper" convention — block-style equivalent of
   * SnakeYAML's `defaultFlowStyle = BLOCK` + `indicatorIndent = 0`. Nested lists/maps
   * inside list items recurse into [appendMap] / [appendList] with consistent indent.
   */
  fun appendList(out: StringBuilder, values: List<*>, indent: String) {
    values.forEach { value ->
      when (value) {
        is Map<*, *> -> {
          @Suppress("UNCHECKED_CAST")
          val entries = (value as Map<String, Any?>).entries.toList()
          if (entries.isEmpty()) {
            out.append(indent).append("- {}\n")
            return@forEach
          }
          // First entry shares the dash line; subsequent entries indent two spaces deeper
          // than the dash so they align under the *value* column, not under the dash.
          val (firstKey, firstValue) = entries.first()
          when (firstValue) {
            is Map<*, *> -> {
              out.append(indent).append("- ").append(firstKey).append(":\n")
              @Suppress("UNCHECKED_CAST")
              appendMap(out, firstValue as Map<String, Any?>, indent + "    ")
            }
            is List<*> -> {
              out.append(indent).append("- ").append(firstKey).append(":\n")
              appendList(out, firstValue, indent + "    ")
            }
            else -> out.append(indent).append("- ").append(firstKey).append(": ").append(formatScalar(firstValue)).append('\n')
          }
          entries.drop(1).forEach { (k, v) ->
            when (v) {
              is Map<*, *> -> {
                out.append(indent).append("  ").append(k).append(":\n")
                @Suppress("UNCHECKED_CAST")
                appendMap(out, v as Map<String, Any?>, indent + "    ")
              }
              is List<*> -> {
                out.append(indent).append("  ").append(k).append(":\n")
                appendList(out, v, indent + "    ")
              }
              else -> out.append(indent).append("  ").append(k).append(": ").append(formatScalar(v)).append('\n')
            }
          }
        }
        is List<*> -> {
          out.append(indent).append("-\n")
          appendList(out, value, indent + "  ")
        }
        else -> out.append(indent).append("- ").append(formatScalar(value)).append('\n')
      }
    }
  }

  /** Format a single Kotlin value as a YAML scalar (quoted only when ambiguous). */
  fun formatScalar(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    is String -> if (needsYamlQuoting(value)) "'${value.replace("'", "''")}'" else value
    else -> value.toString()
  }

  /**
   * Conservative YAML 1.2 plain-scalar safety check. Returns true when [s] would be
   * misinterpreted by a YAML parser if emitted unquoted. Single-quoting (rather than
   * double-quoting) avoids escape-sequence concerns; the caller doubles `'` chars.
   */
  fun needsYamlQuoting(s: String): Boolean {
    if (s.isEmpty()) return true
    if (s.lowercase() in YAML_RESERVED_WORDS) return true
    if (s.toDoubleOrNull() != null) return true
    val first = s[0]
    if (first in "*&!?[]{}|>'\"%@`#,") return true
    if (s.first().isWhitespace() || s.last().isWhitespace()) return true
    if (": " in s || s.endsWith(":")) return true
    if ('\n' in s || '\r' in s) return true
    return false
  }
}
