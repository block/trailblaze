package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.annotations.LLMDescription
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
 * Parameters whose types should be excluded from Koog tool descriptor generation.
 * These are internal fields used for recording/playback, not for LLM selection.
 *
 * [TrailblazeNodeSelector] is excluded because it contains self-referencing fields
 * (childOf, below, above, etc.) that cause [StackOverflowError] in Koog's
 * reflection, and because it is not a parameter the LLM should set.
 */
private val excludedParameterTypes = setOf(
  TrailblazeNodeSelector::class.qualifiedName,
)

/**
 * Extracts [ToolDescriptor] info from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.toKoogToolDescriptor(): ToolDescriptor? {
  val kClass = this

  val trailblazeToolClassAnnotation = kClass.trailblazeToolClassAnnotation()

  if (!trailblazeToolClassAnnotation.isForLlm) {
    // This tool is not for the LLM
    return null
  }

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
