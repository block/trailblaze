package xyz.block.trailblaze.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import xyz.block.trailblaze.logs.client.TrailblazeJson

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
 * **Sealed / polymorphic hierarchies** render as TypeScript discriminated unions matching the
 * `classDiscriminator`-keyed wire format (see [TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR]):
 * the base emits `export type Base = SubA | SubB | …` and every subtype interface carries the
 * discriminator as a required string-literal field, so `switch (log.class)` narrows the union the
 * way a Kotlin `when (log)` does over the sealed class. A subtype that is ALSO referenced as a
 * concrete field type somewhere gets an *optional* discriminator instead — kotlinx omits the key
 * when the declared (static) type is the concrete class, so the field can't be required in TS.
 * OPEN (non-sealed) polymorphic types can't be enumerated statically and degrade to a permissive
 * indexed record carrying the discriminator.
 *
 * Named types are emitted alphabetically so the artifact is byte-stable for a CI diff gate.
 * Simple names that collide across packages/hierarchies (e.g. two sealed trees each declaring
 * `Failed`) are disambiguated deterministically by prefixing the enclosing class-name segments
 * (`SessionStatusEndedFailed`) — for every colliding party, so no type silently "wins" the bare name.
 */
@OptIn(ExperimentalSerializationApi::class)
object SerialDescriptorTsCodegen {

  /**
   * Generate the full TypeScript source for every named type transitively reachable from [roots].
   * [header] is prepended verbatim (use it for the AUTO-GENERATED banner + regen instructions).
   * [classDiscriminator] is the polymorphic wire key the emitting `Json` instance is configured
   * with — [TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR] everywhere in Trailblaze.
   */
  fun generate(
    roots: List<SerialDescriptor>,
    header: String,
    classDiscriminator: String = TrailblazeJson.POLYMORPHIC_CLASS_DISCRIMINATOR,
  ): String {
    val walk = Walk()
    roots.forEach { walk.collect(it, viaSealedBase = false) }

    val names = resolveTsNames(walk.named)

    val blocks = walk.named.values
      .sortedBy { names.getValue(it.serialName.removeSuffix("?")) }
      .map { renderNamed(it, names, walk, classDiscriminator) }

    return buildString {
      append(header)
      append('\n')
      append(blocks.joinToString("\n"))
    }
  }

  /** Accumulated state of one transitive descriptor walk. */
  private class Walk {
    // Keyed by serial name (the type's true identity), so the same type reached from two roots is
    // emitted once, and a self-referential type doesn't recurse forever.
    val named = linkedMapOf<String, SerialDescriptor>()

    /** Sealed-subtype serial names → the discriminator literal the wire carries for them. */
    val sealedSubtypes = mutableMapOf<String, String>()

    /** Serial names ALSO referenced statically (as a concrete field type), not only via a base. */
    val staticallyReferenced = mutableSetOf<String>()

    /** Serial names of OPEN polymorphic types (not statically enumerable). */
    val openPolymorphic = mutableSetOf<String>()

    /**
     * Transitive closure of all named (object / enum / union) types reachable from [desc].
     *
     * A nullable descriptor (e.g. `kotlin.String?`) delegates `kind` / `elementsCount` /
     * `getElementDescriptor` to its non-null original, so dispatching on [desc] directly is
     * correct; only the trailing `?` in `serialName` needs stripping.
     */
    fun collect(desc: SerialDescriptor, viaSealedBase: Boolean) {
      val key = desc.serialName.removeSuffix("?")
      // kotlinx.serialization.json.* wire shapes are raw JSON, not discriminated structures —
      // rendered inline by renderType, never as named types.
      if (key.startsWith("kotlinx.serialization.json.")) return
      // An inline (value) class serializes as its single underlying value — no named type exists
      // on the wire, so recurse straight into the payload.
      if (desc.isInline) return collect(desc.getElementDescriptor(0), viaSealedBase = false)
      when (desc.kind) {
        is PrimitiveKind, SerialKind.CONTEXTUAL -> Unit
        SerialKind.ENUM -> named.putIfAbsent(key, desc)
        StructureKind.CLASS, StructureKind.OBJECT -> {
          if (!viaSealedBase) staticallyReferenced.add(key)
          if (named.containsKey(key)) return // already visited
          named[key] = desc
          for (i in 0 until desc.elementsCount) collect(desc.getElementDescriptor(i), viaSealedBase = false)
        }
        StructureKind.LIST, StructureKind.MAP ->
          desc.elementDescriptors.forEach { collect(it, viaSealedBase = false) }
        PolymorphicKind.SEALED -> {
          if (named.containsKey(key)) return
          named[key] = desc
          desc.sealedSubtypeDescriptors().forEach { sub ->
            sealedSubtypes[sub.serialName.removeSuffix("?")] = sub.serialName.removeSuffix("?")
            collect(sub, viaSealedBase = true)
          }
        }
        PolymorphicKind.OPEN -> {
          // Registered-at-runtime hierarchy: subtypes aren't statically enumerable, so the best
          // honest TS type is a permissive discriminated record (see renderNamed).
          openPolymorphic.add(key)
          named.putIfAbsent(key, desc)
        }
        else -> error("Unsupported SerialKind ${desc.kind} for '${desc.serialName}'.")
      }
    }
  }

  /**
   * A sealed descriptor's structure is `[0]="type" (String), [1]="value"` where the value
   * element's children are the subclass descriptors.
   */
  private fun SerialDescriptor.sealedSubtypeDescriptors(): List<SerialDescriptor> =
    (0 until elementsCount)
      .map { getElementDescriptor(it) }
      .firstOrNull { it.kind == SerialKind.CONTEXTUAL }
      ?.elementDescriptors?.toList()
      ?: error("Sealed descriptor '$serialName' has no subtype container element.")

  /**
   * Assigns each collected type its TypeScript name. Default is the simple (last-segment) name;
   * simple names claimed by more than one distinct Kotlin type are ALL replaced by their
   * class-nesting-qualified form so neither silently wins the bare name.
   */
  private fun resolveTsNames(named: Map<String, SerialDescriptor>): Map<String, String> {
    val simple = named.keys.groupBy { simpleTsName(it) }
    val names = mutableMapOf<String, String>()
    simple.forEach { (name, keys) ->
      if (keys.size == 1) {
        names[keys.single()] = name
      } else {
        keys.forEach { names[it] = nestedQualifiedTsName(it) }
      }
    }
    // A qualified name could itself collide with an existing simple name (or another qualified
    // one). That means the surface needs a Kotlin-side rename — fail loud rather than emit
    // duplicate declarations.
    names.entries.groupBy { it.value }
      .entries.firstOrNull { it.value.size > 1 }
      ?.let { (name, entries) ->
        error(
          "Two Kotlin types map to the same TypeScript name '$name': " +
            entries.joinToString { it.key } +
            ". Rename one of them, or extend the codegen to namespace TS names.",
        )
      }
    return names
  }

  /** Simple TypeScript type name: last `.`/`$`-segment of the serial name, sans nullable marker. */
  private fun simpleTsName(serialName: String): String =
    sanitizeTsTypeName(serialName.removeSuffix("?").substringAfterLast('.').substringAfterLast('$'))

  /**
   * Collision-resolving name: every trailing capitalized segment of the serial name joined —
   * `…logs.model.SessionStatus.Ended.Failed` → `SessionStatusEndedFailed`. Package segments are
   * lowercase by Kotlin convention, so the capitalized suffix is exactly the class-nesting chain.
   */
  private fun nestedQualifiedTsName(serialName: String): String = sanitizeTsTypeName(
    serialName
      .removeSuffix("?")
      .split('.', '$')
      .takeLastWhile { it.firstOrNull()?.isUpperCase() == true }
      .joinToString(""),
  )

  /**
   * A serial name segment isn't always a bare identifier — kotlinx names an OPEN polymorphic
   * descriptor `kotlinx.serialization.Polymorphic<Base>`, whose angle brackets would otherwise
   * leak into the emitted declaration as a bogus generic (`export type Polymorphic<Base> = …`).
   * Dropping the non-identifier characters yields `PolymorphicBase` — valid, and still unique.
   */
  private fun sanitizeTsTypeName(name: String): String = name.replace(NON_TS_IDENTIFIER_CHARS, "")

  private val NON_TS_IDENTIFIER_CHARS = Regex("""[^A-Za-z0-9_$]""")

  private fun renderNamed(
    d: SerialDescriptor,
    names: Map<String, String>,
    walk: Walk,
    classDiscriminator: String,
  ): String = when {
    d.kind == SerialKind.ENUM -> renderEnum(d, names)
    d.kind == PolymorphicKind.SEALED -> renderSealedUnion(d, names)
    d.serialName.removeSuffix("?") in walk.openPolymorphic -> renderOpenPolymorphic(d, names, classDiscriminator)
    d.kind == StructureKind.CLASS || d.kind == StructureKind.OBJECT ->
      renderInterface(d, names, walk, classDiscriminator)
    else -> error("renderNamed called on non-named kind ${d.kind} for '${d.serialName}'.")
  }

  private fun renderEnum(d: SerialDescriptor, names: Map<String, String>): String {
    val members = (0 until d.elementsCount).joinToString(" | ") { "\"${d.getElementName(it)}\"" }
    return "export type ${tsNameOf(d, names)} = $members;\n"
  }

  private fun renderSealedUnion(d: SerialDescriptor, names: Map<String, String>): String {
    val members = d.sealedSubtypeDescriptors()
      .map { tsNameOf(it, names) }
      .sorted()
      .joinToString(" | ")
    return "export type ${tsNameOf(d, names)} = $members;\n"
  }

  /** OPEN polymorphism: subtypes unknown at codegen time — permissive record, discriminator kept. */
  private fun renderOpenPolymorphic(
    d: SerialDescriptor,
    names: Map<String, String>,
    classDiscriminator: String,
  ): String = "export type ${tsNameOf(d, names)} = { ${tsFieldName(classDiscriminator)}: string } " +
    "& { [key: string]: unknown };\n"

  private fun renderInterface(
    d: SerialDescriptor,
    names: Map<String, String>,
    walk: Walk,
    classDiscriminator: String,
  ): String = buildString {
    append("export interface ${tsNameOf(d, names)} {\n")
    val key = d.serialName.removeSuffix("?")
    walk.sealedSubtypes[key]?.let { discriminatorValue ->
      // Optional when the type is also used as a concrete (statically-typed) field somewhere:
      // kotlinx omits the discriminator in that encoding context.
      val optional = if (key in walk.staticallyReferenced) "?" else ""
      append("  ").append(tsFieldName(classDiscriminator)).append(optional)
        .append(": \"").append(discriminatorValue).append("\";\n")
    }
    for (i in 0 until d.elementsCount) {
      val element = d.getElementDescriptor(i)
      val optional = d.isElementOptional(i) || element.isNullable
      val nullSuffix = if (element.isNullable) " | null" else ""
      append("  ").append(tsFieldName(d.getElementName(i)))
      if (optional) append('?')
      append(": ").append(renderType(element, names)).append(nullSuffix).append(";\n")
    }
    append("}\n")
  }

  /** Render a (possibly nullable) descriptor as a TypeScript type expression. */
  private fun renderType(d: SerialDescriptor, names: Map<String, String>): String = when {
    d.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.") ->
      renderJsonElementType(d.serialName.removeSuffix("?"))
    d.isInline -> renderType(d.getElementDescriptor(0), names)
    else -> when (d.kind) {
      PrimitiveKind.BOOLEAN -> "boolean"
      PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
      PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
      PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
      -> "number"
      SerialKind.CONTEXTUAL -> "unknown"
      SerialKind.ENUM, StructureKind.CLASS, StructureKind.OBJECT -> tsNameOf(d, names)
      is PolymorphicKind -> tsNameOf(d, names)
      StructureKind.LIST -> {
        val element = d.getElementDescriptor(0)
        val inner = renderType(element, names) + if (element.isNullable) " | null" else ""
        if (element.isNullable || inner.contains(' ')) "($inner)[]" else "$inner[]"
      }
      StructureKind.MAP -> {
        val key = renderType(d.getElementDescriptor(0), names)
        val value = d.getElementDescriptor(1)
        val valueTs = renderType(value, names) + if (value.isNullable) " | null" else ""
        "Record<$key, $valueTs>"
      }
      else -> error("Unsupported SerialKind ${d.kind} for '${d.serialName}'.")
    }
  }

  /** `kotlinx.serialization.json.*` values are raw JSON on the wire — plain structural TS types. */
  private fun renderJsonElementType(serialName: String): String = when (serialName.substringAfterLast('.')) {
    "JsonObject" -> "Record<string, unknown>"
    "JsonArray" -> "unknown[]"
    "JsonPrimitive", "JsonLiteral" -> "string | number | boolean"
    "JsonNull" -> "null"
    else -> "unknown" // JsonElement itself, or a future addition
  }

  private fun tsNameOf(d: SerialDescriptor, names: Map<String, String>): String =
    names[d.serialName.removeSuffix("?")] ?: simpleTsName(d.serialName)

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
