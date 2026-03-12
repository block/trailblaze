package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.asToolType
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Parameters whose types should be excluded from Koog tool descriptor generation.
 * These are internal fields used for recording/playback, not for LLM selection.
 *
 * [TrailblazeNodeSelector] is excluded because it contains self-referencing fields
 * (childOf, below, above, etc.) that cause [StackOverflowError] in Koog's
 * [asToolType] reflection, and because it is not a parameter the LLM should set.
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
    description = this.findAnnotation<LLMDescription>()?.description?.trim()?.trimIndent() ?: "",
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
    description = kClass.findAnnotation<LLMDescription>()?.description?.trim()?.trimIndent()
      ?: error("Please add @LLMDescription to $kClass"),
    requiredParameters = requiredParams,
    optionalParameters = optionalParams,
  )
}
