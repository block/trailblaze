package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.LLMDescription
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

  fun KParameter.isExcludedFromDescriptor(): Boolean {
    val typeName = this.type.classifier?.let { (it as? KClass<*>)?.qualifiedName }
    return typeName in excludedParameterTypes
  }

  fun KParameter.toKoogToolParameterDescriptors(): ToolParameterDescriptor = ToolParameterDescriptor(
    name = this.name?.trim() ?: error("Parameter name cannot be null"),
    description = this.findAnnotation<LLMDescription>()?.value?.trim()?.trimIndent() ?: "",
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
    description = kClass.findAnnotation<LLMDescription>()?.value?.trim()?.trimIndent()
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
 * Returns the scripted-tool-facing [ToolDescriptor] for a [TrailblazeTool] class, or null
 * when the class is hidden from per-trailmap `client.d.ts` codegen via
 * `@TrailblazeToolClass(surfaceToScriptedTools = false)`, OR when the class's parameter
 * shape can't be lowered to a `ToolDescriptor` by [buildToolDescriptorIgnoringSurface]
 * (e.g. uses a `Map<...>` field that `asToolType` does not yet support, or other structural
 * lowering failures). In the latter case the failure is logged and skipped so codegen for
 * the rest of the trailmap succeeds — the affected tool simply doesn't get a typed binding.
 * Scripted-tool authors can still reach it via `client.callTool(name, args)`.
 *
 * Counterpart to [toKoogToolDescriptor]. The two surfaces are independent: a tool can be
 * visible to scripted-tool authors and hidden from the LLM, or vice versa.
 */
fun KClass<out TrailblazeTool>.toScriptedToolDescriptor(): ToolDescriptor? {
  if (!trailblazeToolClassAnnotation().surfaceToScriptedTools) return null
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
