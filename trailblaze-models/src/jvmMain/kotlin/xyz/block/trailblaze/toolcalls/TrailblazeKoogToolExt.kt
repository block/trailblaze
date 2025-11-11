package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.asToolType
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Extracts tool name from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.trailblazeToolClassAnnotation(): TrailblazeToolClass = this.findAnnotation<TrailblazeToolClass>()
  ?: error("Please add @TrailblazeToolClass to $this")

/**
 * Extracts tool name from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.toolName(): ToolName = ToolName(this.trailblazeToolClassAnnotation().name)

/**
 * Extracts [ai.koog.agents.core.tools.ToolDescriptor] info from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.toKoogToolDescriptor(): ToolDescriptor? {
  val kClass = this

  val trailblazeToolClassAnnotation = kClass.trailblazeToolClassAnnotation()

  if (!trailblazeToolClassAnnotation.isForLlm) {
    // This tool is not for the LLM
    return null
  }

  fun KParameter.toKoogToolParameterDescriptors(): ToolParameterDescriptor = ToolParameterDescriptor(
    name = this.name?.trim() ?: error("Parameter name cannot be null"),
    description = this.findAnnotation<LLMDescription>()?.description?.trim()?.trimIndent() ?: "",
    type = this.type.asToolType(),
  )

  val primaryConstructorParams = kClass.primaryConstructor?.parameters
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
