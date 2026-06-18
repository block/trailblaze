package xyz.block.trailblaze.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors

/**
 * Reusable Kotlin → TypeScript generator that walks `kotlinx.serialization` [SerialDescriptor]s, so
 * a `@Serializable` Kotlin model is the single source of truth for the matching TypeScript type. A
 * field rename or removal on the Kotlin side regenerates the `.ts` and surfaces as a compile error
 * at every TypeScript call site — the "Kotlin canonical, TypeScript derived" guarantee, generalized
 * past the selector grammar.
 *
 * **Why descriptor-walking, not source-text parsing.** The selector grammar's `SelectorTsCodegen`
 * (in `build-logic`) parses Kotlin source text, which works for nine narrowly-shaped types but
 * cannot follow type references across files/modules, has no notion of `Map`, and can't see enums
 * or `@SerialName` remapping without bespoke regexes. This generator operates on the runtime
 * serialization model instead, so it gets all of that for free and scales to a broad DTO surface.
 * It is the in-house equivalent of `kxs-ts-gen`, written against the current `kotlinx-serialization`
 * (that library's last release predates Kotlin 2.x). Because descriptors require the *compiled*
 * `@Serializable` classes, callers run it from a `JavaExec` against the owning module's classpath
 * (see the `trailblaze.dto-ts-codegen` build-logic plugin) — it cannot run in-process inside
 * `build-logic` the way the source-text `SelectorTsCodegen` does.
 *
 * **The one thing it cannot do that the source-text generator can:** preserve KDoc as TSDoc. The
 * compiler erases doc comments, so they are not reachable from a [SerialDescriptor]. Generated
 * types therefore carry no doc comments.
 *
 * **Output style** mirrors the committed `selectors.ts`:
 * - one `export interface` per object type, `export type X = "A" | "B"` per enum;
 * - `field: T` for a required, non-null property; `field?: T` for one with a default;
 *   `field?: T | null` for a nullable one;
 * - `T[]` for lists, `Record<K, V>` for maps.
 *
 * Named types are emitted alphabetically so the artifact is byte-stable for a CI diff gate.
 *
 * **Not yet supported:** sealed / polymorphic hierarchies. The first DTO surface has none; [collect]
 * fails loud (rather than emitting something subtly wrong) if one is reached, with a pointer to the
 * branch that would need adding.
 */
@OptIn(ExperimentalSerializationApi::class)
object SerialDescriptorTsCodegen {

  /**
   * Generate the full TypeScript source for every named type transitively reachable from [roots].
   * [header] is prepended verbatim (use it for the AUTO-GENERATED banner + regen instructions).
   */
  fun generate(roots: List<SerialDescriptor>, header: String): String {
    // Keyed by serial name (the type's true identity), so the same type reached from two roots is
    // emitted once, and a self-referential type doesn't recurse forever.
    val named = linkedMapOf<String, SerialDescriptor>()
    roots.forEach { collect(it, named) }

    // Fail loud if two *distinct* Kotlin types collapse to the same TypeScript name — otherwise
    // the output would silently declare a duplicate interface (or drop one). Rare, but a real
    // footgun once the surface grows beyond one package.
    named.values.groupBy { tsName(it) }
      .entries.firstOrNull { it.value.size > 1 }
      ?.let { (name, descs) ->
        error(
          "Two Kotlin types map to the same TypeScript name '$name': " +
            descs.joinToString { it.serialName } +
            ". Rename one of them, or extend the codegen to namespace TS names.",
        )
      }

    val blocks = named.values
      .sortedBy { tsName(it) }
      .map { renderNamed(it) }

    return buildString {
      append(header)
      append('\n')
      append(blocks.joinToString("\n"))
    }
  }

  /**
   * Transitive closure of all named (object / enum) types reachable from [desc].
   *
   * A nullable descriptor (e.g. `kotlin.String?`) delegates `kind` / `elementsCount` /
   * `getElementDescriptor` to its non-null original, so dispatching on [desc] directly is correct;
   * only the trailing `?` in `serialName` needs stripping, which [tsName] handles.
   */
  private fun collect(desc: SerialDescriptor, into: MutableMap<String, SerialDescriptor>) {
    val key = desc.serialName.removeSuffix("?")
    when (desc.kind) {
      is PrimitiveKind, SerialKind.CONTEXTUAL -> Unit
      SerialKind.ENUM -> into.putIfAbsent(key, desc)
      StructureKind.CLASS, StructureKind.OBJECT -> {
        if (into.containsKey(key)) return // already visited
        into[key] = desc
        for (i in 0 until desc.elementsCount) collect(desc.getElementDescriptor(i), into)
      }
      StructureKind.LIST, StructureKind.MAP -> desc.elementDescriptors.forEach { collect(it, into) }
      is PolymorphicKind -> error(
        "Sealed/polymorphic type '${desc.serialName}' is not yet supported by the descriptor TS " +
          "codegen. Add a discriminated-union branch to SerialDescriptorTsCodegen before " +
          "exporting it.",
      )
      else -> error("Unsupported SerialKind ${desc.kind} for '${desc.serialName}'.")
    }
  }

  private fun renderNamed(d: SerialDescriptor): String = when (d.kind) {
    SerialKind.ENUM -> renderEnum(d)
    StructureKind.CLASS, StructureKind.OBJECT -> renderInterface(d)
    else -> error("renderNamed called on non-named kind ${d.kind} for '${d.serialName}'.")
  }

  private fun renderEnum(d: SerialDescriptor): String {
    val members = (0 until d.elementsCount).joinToString(" | ") { "\"${d.getElementName(it)}\"" }
    return "export type ${tsName(d)} = $members;\n"
  }

  private fun renderInterface(d: SerialDescriptor): String = buildString {
    append("export interface ${tsName(d)} {\n")
    for (i in 0 until d.elementsCount) {
      val element = d.getElementDescriptor(i)
      val optional = d.isElementOptional(i) || element.isNullable
      val nullSuffix = if (element.isNullable) " | null" else ""
      append("  ").append(tsFieldName(d.getElementName(i)))
      if (optional) append('?')
      append(": ").append(renderType(element)).append(nullSuffix).append(";\n")
    }
    append("}\n")
  }

  /** Render a (possibly nullable) descriptor as a TypeScript type expression. */
  private fun renderType(d: SerialDescriptor): String = when (d.kind) {
    PrimitiveKind.BOOLEAN -> "boolean"
    PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
    PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
    PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
    -> "number"
    SerialKind.CONTEXTUAL -> "unknown"
    SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT -> tsName(d)
    StructureKind.LIST -> {
      val element = d.getElementDescriptor(0)
      val inner = renderType(element) + if (element.isNullable) " | null" else ""
      if (element.isNullable) "($inner)[]" else "$inner[]"
    }
    StructureKind.MAP -> {
      val key = renderType(d.getElementDescriptor(0))
      val value = d.getElementDescriptor(1)
      val valueTs = renderType(value) + if (value.isNullable) " | null" else ""
      "Record<$key, $valueTs>"
    }
    is PolymorphicKind -> error("Polymorphic type '${d.serialName}' not supported yet.")
    else -> error("Unsupported SerialKind ${d.kind} for '${d.serialName}'.")
  }

  /** Simple TypeScript type name: last `.`/`$`-segment of the serial name, sans nullable marker. */
  private fun tsName(d: SerialDescriptor): String =
    d.serialName.removeSuffix("?").substringAfterLast('.').substringAfterLast('$')

  /**
   * A field key the way it must appear in a TS interface. A `@SerialName` wire key that isn't a
   * valid TS identifier (e.g. `"kebab-case"`) is emitted as a quoted property name — valid TS that
   * a consumer accesses via `obj["kebab-case"]` — rather than a bare key, which would be a syntax
   * error. Reserved words like `class` ARE valid bare property names in TS, so they stay unquoted.
   */
  private fun tsFieldName(name: String): String =
    if (TS_IDENTIFIER.matches(name)) name else "\"$name\""

  private val TS_IDENTIFIER = Regex("""^[A-Za-z_${'$'}][A-Za-z0-9_${'$'}]*$""")
}
