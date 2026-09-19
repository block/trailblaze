package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Converts a [KType] to a [ToolParameterType].
 *
 * Local copy of `asToolType()` which exists in Koog source but is missing from the 0.7.2 published JAR.
 * Remove this once Koog publishes the fix:
 * https://github.com/JetBrains/koog/blob/main/agents/agents-tools/src/jvmMain/kotlin/ai/koog/agents/core/tools/reflect/util.kt
 */
fun KType.asToolType(): ToolParameterType {
  val classifier = this.classifier
  return when (classifier) {
    String::class -> ToolParameterType.String
    Int::class -> ToolParameterType.Integer
    Float::class -> ToolParameterType.Float
    Boolean::class -> ToolParameterType.Boolean
    Long::class -> ToolParameterType.Integer
    Double::class -> ToolParameterType.Float

    List::class -> {
      val listItemType = this.arguments[0].type ?: error("List item type is null")
      val listItemToolType = listItemType.asToolType()
      ToolParameterType.List(listItemToolType)
    }

    is KClass<*> -> {
      val classJava = classifier.java
      when {
        classJava.isEnum -> {
          @Suppress("UNCHECKED_CAST")
          val entries = (classJava as Class<Enum<*>>).enumConstants.map { it.name }.toTypedArray()
          ToolParameterType.Enum(entries)
        }

        classJava.isArray -> {
          val arrayItemType = this.arguments[0].type ?: error("Array item type is null")
          val arrayItemToolType = arrayItemType.asToolType()
          ToolParameterType.List(arrayItemToolType)
        }

        classifier.isData -> {
          val properties = classifier.memberProperties
            .sortedBy { it.name }
            .map { prop ->
              val rawDescription = prop.findAnnotation<LLMDescription>()?.value
              val normalizedDescription = rawDescription
                ?.trimIndent()
                ?.trim()
              val description = if (normalizedDescription.isNullOrBlank()) {
                prop.name
              } else {
                normalizedDescription
              }
              ToolParameterDescriptor(
                name = prop.name,
                description = description,
                type = prop.returnType.asToolType(),
              )
            }
          ToolParameterType.Object(properties)
        }

        else -> throw IllegalArgumentException("Unsupported type $classifier")
      }
    }

    else -> error("Unsupported type $classifier")
  }
}

/**
 * Parameters whose types should be excluded from Koog tool descriptor generation (BOTH the LLM
 * surface via [toKoogToolDescriptor] and the scripted-tool surface via [toScriptedToolDescriptor]).
 * These are internal fields used for recording/playback, not for LLM/scripted selection.
 *
 * Both [TrailblazeNodeSelector] and its legacy Maestro-shaped sibling [TrailblazeElementSelector]
 * are excluded because each contains self-referencing fields (childOf, below, above, containsChild,
 * containsDescendants, …) that recurse without bound in Koog's reflection-based [asToolType]
 * lowering and blow the stack with a [StackOverflowError]. That error is NOT an [Exception], so
 * [toScriptedToolDescriptor]'s catch can't absorb it — an un-excluded recursive selector param on a
 * surfaced tool crashes per-trailmap codegen / daemon startup outright (regression first seen when
 * `tapOnElementBySelector`, which carries a legacy `selector: TrailblazeElementSelector?`, was
 * surfaced to scripted tools). Neither selector is a parameter the LLM/scripted author sets via the
 * descriptor anyway — scripted authors get the rich selector typing from the hand-curated
 * `built-in-tools.ts`, and the legacy `selector` field is itself deprecated.
 */
private val excludedParameterTypes = setOf(
  TrailblazeNodeSelector::class.qualifiedName,
  TrailblazeElementSelector::class.qualifiedName,
)

/**
 * Which excluded selector type a parameter is built on, and whether it carries a COLLECTION of
 * them — the two facts both exclusion call sites need. Null for every non-selector parameter.
 */
private data class ExcludedSelectorType(val typeName: String, val isCollection: Boolean)

/**
 * Matches a direct selector param (`nodeSelector: TrailblazeNodeSelector`) **and a collection of
 * them** (`selectors: List<TrailblazeNodeSelector>`).
 *
 * The collection case is load-bearing, not a nicety. Keyed on the top-level classifier alone, a
 * list-typed selector param reads as `kotlin.collections.List` and is not excluded — then
 * [asToolType]'s `List` branch recurses into the element type and the self-referential selector
 * grammar blows the stack. A [StackOverflowError] is an `Error`, so [toScriptedToolDescriptor]'s
 * `catch (Exception)` does not absorb it and per-trailmap codegen dies outright rather than
 * skipping the one tool. `findSelectorMatches` is the first tool to take a list of selectors,
 * and it crashed codegen until this unwrapped one level.
 *
 * One level of unwrapping is deliberate: what is being guarded is the selector grammar's own
 * recursion, and no tool takes a list of lists of selectors.
 */
private fun KType.excludedSelectorType(): ExcludedSelectorType? {
  val classifierName = (classifier as? KClass<*>)?.qualifiedName
  if (classifierName != null && classifierName in excludedParameterTypes) {
    return ExcludedSelectorType(typeName = classifierName, isCollection = false)
  }
  if (classifier == List::class || classifier == Set::class) {
    val itemName = (arguments.firstOrNull()?.type?.classifier as? KClass<*>)?.qualifiedName
    if (itemName != null && itemName in excludedParameterTypes) {
      return ExcludedSelectorType(typeName = itemName, isCollection = true)
    }
  }
  return null
}

/**
 * A selector-typed constructor parameter that [buildToolDescriptorIgnoringSurface] STRIPS (via
 * [excludedParameterTypes]), in a surface-neutral form.
 *
 * One source of truth for *which* params were stripped, so no two consumers can disagree about
 * that. Each consumer then re-surfaces them in its own vocabulary: [selectorParamsForTs] picks a
 * TypeScript type for the trail-recording type-validation codegen, and
 * [withSelectorParamsRestored] picks a descriptor type string for human-facing tool help.
 *
 * @property name the constructor parameter name (also the recorded arg key), e.g. `nodeSelector`.
 * @property isLegacyElementSelector true for the deprecated Maestro-shaped
 *   [TrailblazeElementSelector], false for the rich [TrailblazeNodeSelector] grammar.
 * @property isCollection true when the param takes a collection of selectors rather than one.
 * @property optional whether the param has a default or is nullable in Kotlin.
 * @property description the param's `@LLMDescription`.
 */
private data class StrippedSelectorParam(
  val name: String,
  val isLegacyElementSelector: Boolean,
  val isCollection: Boolean,
  val optional: Boolean,
  val description: String?,
)

/** The selector-typed primary-constructor params [buildToolDescriptorIgnoringSurface] strips. */
private fun KClass<out TrailblazeTool>.strippedSelectorParams(): List<StrippedSelectorParam> {
  val nodeSelectorType = TrailblazeNodeSelector::class.qualifiedName
  val elementSelectorType = TrailblazeElementSelector::class.qualifiedName
  return primaryConstructor?.parameters.orEmpty().mapNotNull { param ->
    val excluded = param.type.excludedSelectorType() ?: return@mapNotNull null
    val isLegacy = when (excluded.typeName) {
      nodeSelectorType -> false
      elementSelectorType -> true
      // An excluded type neither consumer knows how to name. Dropping it keeps a future addition
      // to [excludedParameterTypes] from silently being described as a node selector.
      else -> return@mapNotNull null
    }
    // Guard the empty string too (not just null): an empty name would render `"": …;` — a TS
    // syntax error in the generated surface. Reflection normally never yields one, but cheap to pin.
    val name = param.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    StrippedSelectorParam(
      name = name,
      isLegacyElementSelector = isLegacy,
      isCollection = excluded.isCollection,
      optional = param.isOptional || param.type.isMarkedNullable,
      // trimIndent() BEFORE trim(): trimming the first line first would corrupt trimIndent()'s
      // common-indent detection on multi-line descriptions. Matches the object-property path above.
      description = param.findAnnotation<LLMDescription>()?.value?.trimIndent()?.trim(),
    )
  }
}

/** What a [TrailblazeElementSelector] param says when the tool itself documents nothing. */
private const val LEGACY_SELECTOR_DESCRIPTION: String =
  "Deprecated legacy Maestro-shaped selector; prefer `nodeSelector`."

/**
 * This descriptor with [toolClass]'s stripped selector params put back, for a **human-facing**
 * description of the tool (`toolbox(name=…)`, `trailblaze tool <name> --help`). Unchanged for the
 * vast majority of tools, which have no selector param.
 *
 * Without this, a selector-only tool describes as taking no required arguments at all: `tapOn`
 * comes back with `relativePoint` and `longPress` and nothing to say WHAT to tap, and the rendered
 * `Run:`/YAML example cannot supply it. Do not reach for this on the LLM or scripted-tool paths —
 * they have their own answers ([toKoogToolDescriptor] omits selectors deliberately, the scripted
 * surface types them via `built-in-tools.ts`).
 *
 * The type is the flat string `OBJECT` (`ARRAY` for a collection) rather than the expanded selector
 * grammar on purpose: expanding it is the thing that overflows the stack (see
 * [excludedParameterTypes]), and a reader needs to know the argument exists and is a nested block,
 * not to read its whole schema inline.
 */
fun TrailblazeToolDescriptor.withSelectorParamsRestored(
  toolClass: KClass<out TrailblazeTool>,
): TrailblazeToolDescriptor {
  val stripped = toolClass.strippedSelectorParams()
  if (stripped.isEmpty()) return this
  fun StrippedSelectorParam.toDescriptor() = TrailblazeToolParameterDescriptor(
    name = name,
    // Matches `ToolParameterType.Object.name` / `List.name`, so a restored param's type reads the
    // same as one the normal lowering produced.
    type = if (isCollection) "ARRAY" else "OBJECT",
    description = description ?: LEGACY_SELECTOR_DESCRIPTION.takeIf { isLegacyElementSelector },
  )
  // A node selector is required even where Kotlin declares it nullable with a `= null` default.
  // That default exists so a trail recorded before the field was added still deserializes; it is
  // never a valid call. Every tool in the tree that declares one nullable rejects null at
  // execution: `assertVisibleBySelector` and `assertNotVisibleBySelector` with `require`,
  // `assertMatchCount` and the `rememberBySelector` family with a failed result,
  // `tapOnElementBySelector` with a loud error. Calling it optional in help would tell a reader
  // they may omit the one argument the tool cannot run without.
  //
  // The legacy selector is the opposite case, and stays optional to match [selectorParamsForTs]:
  // it is deprecated and superseded by `nodeSelector`, so no caller should be told to fill it in.
  val (optional, required) = stripped.partition { it.isLegacyElementSelector }
  return copy(
    // Selectors lead: they are what the tool acts ON, and the params that survived stripping
    // (`longPress`, `relativePoint`) only modify that. Declaration order among selectors is kept.
    requiredParameters = required.map { it.toDescriptor() } + requiredParameters,
    optionalParameters = optional.map { it.toDescriptor() } + optionalParameters,
  )
}

/** One element of a hand-written serializer's wire shape. */
private data class SerializedElement(val name: String, val type: String, val optional: Boolean)

/** The descriptor-side type string for a serialized element's kind. */
@OptIn(ExperimentalSerializationApi::class)
private fun SerialKind.toParameterTypeName(): String = when (this) {
  StructureKind.LIST -> "ARRAY"
  StructureKind.MAP, StructureKind.CLASS, StructureKind.OBJECT -> "OBJECT"
  PrimitiveKind.BOOLEAN -> "BOOLEAN"
  PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "INTEGER"
  PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "FLOAT"
  // Enums and anything unmodelled read as a scalar, which is what the YAML author writes.
  else -> "STRING"
}

/**
 * The wire shape of [this] tool's **hand-written** serializer, or null when it has none — which is
 * every tool but one. The compiler-generated serializer reads exactly the primary-constructor
 * params reflection already found, so there is nothing to reconcile for those.
 */
@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
private fun KClass<out TrailblazeTool>.handWrittenSerializedElements(): List<SerializedElement>? {
  val custom = findAnnotation<Serializable>()?.with?.takeIf { it != KSerializer::class } ?: return null
  val descriptor = try {
    serializer().descriptor
  } catch (_: Throwable) {
    // A serializer that cannot be resolved reflectively is a reason to keep the constructor-derived
    // answer, not to fail a help lookup.
    Console.log("Could not read the serial descriptor of $custom for $qualifiedName")
    return null
  }
  if (descriptor.kind != StructureKind.CLASS) return null
  return (0 until descriptor.elementsCount).map { index ->
    SerializedElement(
      name = descriptor.getElementName(index),
      type = descriptor.getElementDescriptor(index).kind.toParameterTypeName(),
      optional = descriptor.isElementOptional(index),
    )
  }
}

/**
 * This descriptor's parameters reconciled against [toolClass]'s serializer — the thing that
 * actually decodes a trail step — for a **human-facing** description of the tool. A no-op for
 * every tool whose serializer the compiler generated, which is all but one of them.
 *
 * `mobile_maestro` is the exception and the reason this exists: it holds a single `yaml` string in
 * Kotlin and reads and writes `commands: [...]` on the wire. Describing it from the primary
 * constructor printed a trail step keyed on `yaml`, which its own deserializer rejects outright —
 * help that cannot be copied. It is `surfaceToLlm = false`, so this lookup is the only surface
 * that describes it at all.
 *
 * Reconciles rather than replaces: a parameter the serializer also knows keeps the type,
 * `@LLMDescription` and required/optional verdict the reflection path gave it, because a serial
 * descriptor carries none of those. Only names the serializer does not know are dropped, and only
 * names it alone knows are added.
 */
fun TrailblazeToolDescriptor.withSerializedParameterNames(
  toolClass: KClass<out TrailblazeTool>,
): TrailblazeToolDescriptor {
  val serialized = toolClass.handWrittenSerializedElements() ?: return this
  val described = requiredParameters + optionalParameters
  if (serialized.map { it.name }.toSet() == described.map { it.name }.toSet()) return this

  val requiredByName = requiredParameters.associateBy { it.name }
  val optionalByName = optionalParameters.associateBy { it.name }
  val newRequired = mutableListOf<TrailblazeToolParameterDescriptor>()
  val newOptional = mutableListOf<TrailblazeToolParameterDescriptor>()
  serialized.forEach { element ->
    val known = requiredByName[element.name] ?: optionalByName[element.name]
    when {
      known != null && optionalByName.containsKey(element.name) -> newOptional += known
      known != null -> newRequired += known
      else -> {
        val added = TrailblazeToolParameterDescriptor(name = element.name, type = element.type)
        if (element.optional) newOptional += added else newRequired += added
      }
    }
  }
  return copy(requiredParameters = newRequired, optionalParameters = newOptional)
}

/**
 * A stripped selector param re-surfaced with a hand-picked TypeScript type so the trail-recording
 * type-validation surface can model it. See [selectorParamsForTs].
 *
 * @property name the constructor parameter name (also the recorded arg key), e.g. `nodeSelector`.
 * @property tsType the TypeScript type to emit — `"TrailblazeNodeSelector"` for the rich
 *   [TrailblazeNodeSelector] grammar (the generated `selectors.ts` type, re-exported by the SDK
 *   bundle), or `"unknown"` for the deprecated Maestro-shaped [TrailblazeElementSelector] (which has
 *   no generated TS type; a loose `unknown` accepts legacy recordings without a false positive).
 * @property optional whether the param is TS-optional (`?`) — true when it has a default or is
 *   nullable in Kotlin.
 * @property description the param's `@LLMDescription`, surfaced as JSDoc on the emitted field.
 */
data class SelectorParamTs(
  val name: String,
  val tsType: String,
  val optional: Boolean,
  val description: String?,
)

/**
 * The selector-typed primary-constructor params of a [TrailblazeTool] class, re-surfaced for the
 * trail-recording type-validation codegen.
 *
 * [buildToolDescriptorIgnoringSurface] deliberately drops these ([excludedParameterTypes]) because
 * the self-referential [TrailblazeNodeSelector] / [TrailblazeElementSelector] grammar overflows
 * Koog's descriptor lowering. But a *recorded* trail call to a selector-bearing tool (`tapOn`,
 * `assertVisibleBySelector`, `assertNotVisibleBySelector`, `tapOnElementBySelector`, …) carries
 * exactly those args — so the generated typed surface must model them or every such recording reads
 * as a spurious "unexpected property" finding. This returns the stripped params with a concrete TS
 * type ([TrailblazeNodeSelector] → the generated `TrailblazeNodeSelector` type; the deprecated
 * [TrailblazeElementSelector] → loose `unknown`) so the codegen can re-inject them. Non-selector
 * params keep flowing through the normal descriptor path.
 *
 * Empty for the vast majority of tools (no selector param). Ordered by declaration.
 */
fun KClass<out TrailblazeTool>.selectorParamsForTs(): List<SelectorParamTs> =
  strippedSelectorParams().map { param ->
    // A `List<Selector>` param is stripped for the same reason a bare one is, so it has to come
    // back as an ARRAY of the same TS type — emitting the scalar type would make every recorded
    // call to such a tool read as a type error in the generated surface.
    val arraySuffix = if (param.isCollection) "[]" else ""
    if (param.isLegacyElementSelector) {
      SelectorParamTs(
        name = param.name,
        // No generated TS type for the deprecated Maestro-shaped selector; `unknown` accepts a
        // legacy `selector:` block in an old recording without a false positive.
        tsType = "unknown$arraySuffix",
        optional = true,
        description = param.description ?: LEGACY_SELECTOR_DESCRIPTION,
      )
    } else {
      SelectorParamTs(
        name = param.name,
        // Must match the `TrailblazeNodeSelector` export in the generated `selectors.ts` and
        // `WorkspaceClientDtsGenerator.NODE_SELECTOR_TS_TYPE` (which emits the matching `import type`).
        // Codegen-boundary coupling with no static check — keep the three in sync if the export renames.
        tsType = "TrailblazeNodeSelector$arraySuffix",
        optional = param.optional,
        description = param.description,
      )
    }
  }

/**
 * Builds a [ToolDescriptor] from a [TrailblazeTool] class, ignoring any surface-visibility
 * gate. Throws when the class can't be descriptor-ized — [asToolType] throws
 * `IllegalArgumentException` for unsupported parameter shapes (e.g. `Map<...>`); missing
 * `@LLMDescription`, null parameter names, and other structural lowering failures throw
 * `IllegalStateException` via `error(...)`. Callers that need to tolerate either failure mode
 * (the scripted-tool codegen path does) must catch explicitly.
 *
 * Most callers should reach for [toKoogToolDescriptor] (LLM surface) or
 * [toScriptedToolDescriptor] (scripted-tool surface) instead — those layer the appropriate
 * `@TrailblazeToolClass` gate on top of this descriptor build.
 */
fun KClass<out TrailblazeTool>.buildToolDescriptorIgnoringSurface(): ToolDescriptor {
  val kClass = this
  val trailblazeToolClassAnnotation = kClass.trailblazeToolClassAnnotation()

  fun KParameter.isExcludedFromDescriptor(): Boolean = this.type.excludedSelectorType() != null

  fun KParameter.toKoogToolParameterDescriptors(): ToolParameterDescriptor = ToolParameterDescriptor(
    name = this.name?.trim() ?: error("Parameter name cannot be null"),
    // trimIndent() BEFORE trim() so a multi-line @LLMDescription keeps correct common-indent
    // detection (trimming the first line first would corrupt it). Matches [selectorParamsForTs].
    description = this.findAnnotation<LLMDescription>()?.value?.trimIndent()?.trim() ?: "",
    type = this.type.asToolType(),
  )

  val primaryConstructorParams = kClass.primaryConstructor?.parameters
    ?.filter { !it.isExcludedFromDescriptor() }
  val optionalParams = primaryConstructorParams
    ?.filter { it.isOptional }
    ?.map { it.toKoogToolParameterDescriptors() }
    ?: listOf()
  val requiredParams = primaryConstructorParams
    ?.filter { !it.isOptional }
    ?.map { it.toKoogToolParameterDescriptors() }
    ?: listOf()

  return ToolDescriptor(
    name = trailblazeToolClassAnnotation.name.trim(),
    description = kClass.findAnnotation<LLMDescription>()?.value?.trimIndent()?.trim()
      ?: error("Please add @LLMDescription to $kClass"),
    requiredParameters = requiredParams,
    optionalParameters = optionalParams,
  )
}

/**
 * Returns the LLM-facing [ToolDescriptor] for a [TrailblazeTool] class, or null when the
 * class is hidden from the agent toolbox via `@TrailblazeToolClass(surfaceToLlm = false)`.
 *
 * This is the canonical entry point for **LLM agent toolbox composition** and any other
 * "what tools should the LLM see?" call sites. For the scripted-tool typed surface
 * (per-trailmap `client.d.ts` codegen), use [toScriptedToolDescriptor] instead — the two flags
 * are deliberately independent so that, for example, brittle text-based selectors can stay
 * hidden from the LLM (where they bite) but remain typed and callable in scripted tools
 * (where authors choose them explicitly).
 */
fun KClass<out TrailblazeTool>.toKoogToolDescriptor(): ToolDescriptor? {
  if (!trailblazeToolClassAnnotation().surfaceToLlm) return null
  return buildToolDescriptorIgnoringSurface()
}

/**
 * Returns the scripted-tool-facing [ToolDescriptor] for a [TrailblazeTool] class, or null when
 * [buildToolDescriptorIgnoringSurface] can't build a descriptor for it. That covers any
 * descriptor-build failure the catch below absorbs — a parameter shape `asToolType` can't lower
 * (e.g. a `Map<...>` field), a null `List`/`Array` item type, a missing `@LLMDescription`, or a
 * null parameter name. In every such case the failure is logged and skipped so codegen for the
 * rest of the trailmap succeeds — the affected tool simply doesn't get a typed binding, and
 * scripted-tool authors can still reach it via `client.callTool(name, args)`.
 *
 * Every class-backed tool a trailmap resolves is surfaced to scripted-tool authors — there is
 * no scripted-surface visibility gate. A tool hidden from the LLM (`surfaceToLlm = false`) is
 * still typed and callable here: hiding a tool from the model's autonomous picking does not
 * hide it from an expert TS author who reaches for it explicitly. Counterpart to
 * [toKoogToolDescriptor], which DOES gate on `surfaceToLlm`.
 */
fun KClass<out TrailblazeTool>.toScriptedToolDescriptor(): ToolDescriptor? {
  return try {
    buildToolDescriptorIgnoringSurface()
  } catch (e: Exception) {
    // [buildToolDescriptorIgnoringSurface] throws `IllegalArgumentException` from `asToolType`
    // for parameter shapes it can't lower (e.g. `Map<String, ...>`) and `IllegalStateException`
    // from `error(...)` for null List/Array item types, missing `@LLMDescription`, and null
    // parameter names. The LLM path hides these failures by bailing on `surfaceToLlm = false`
    // before reaching the lowering — the scripted-tool path can't rely on that gate, so we
    // skip the tool from the typed surface and log so a regression doesn't disappear silently.
    // `client.callTool(...)` remains callable. Catch broadly (Exception, not just the two
    // specific types) because the lowering walks reflection and downstream Koog code in
    // `asToolType` can grow new throw sites that we'd otherwise need to chase one at a time.
    Console.log(
      "[toScriptedToolDescriptor] Skipping ${qualifiedName ?: simpleName} from per-trailmap " +
        "client.d.ts codegen: descriptor build failed (${e::class.simpleName}: ${e.message}). " +
        "Tool remains callable via client.callTool(...). " +
        "If this is a Map<String, V> parameter, consider modeling it as List<KV> where KV " +
        "carries the key as a named field — closed-shape arrays of structured objects are " +
        "LLM-friendly AND surface to the typed client.tools.<name>() autocomplete. See " +
        "xyz.block.trailblaze.mobile.tools.BroadcastExtra for the pattern.",
    )
    null
  }
}
