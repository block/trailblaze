package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

data class ToolProperty(
  val name: String,
  val type: String,
  val isRequired: Boolean,
  val description: String,
)

data class TrailblazeToolAsLlmTool(
  val trailblazeToolClass: KClass<out Any>,
  val excludedProperties: List<String> = listOf(),
) {
  private val trailblazeClassInfo = trailblazeToolClass.findAnnotation<TrailblazeToolClass>()
    ?: error("Please add @TrailblazeToolClass to $trailblazeToolClass")
  val name: String = trailblazeClassInfo.name.trim()

  private val llmDescriptionAnnotation = trailblazeToolClass.findAnnotation<LLMDescription>()
    ?: error("Please add @LLMDescription to $trailblazeToolClass")
  val description: String = llmDescriptionAnnotation.description.trim()

  val properties = trailblazeToolClass.primaryConstructor?.parameters?.map { parameter ->
    val trailblazeToolPropertyInfo = parameter.findAnnotation<LLMDescription>()
    val type = parameter.type
    val isRequired = !parameter.isOptional
    val description = trailblazeToolPropertyInfo?.description?.trim() ?: ""
    ToolProperty(
      name = parameter.name!!,
      type = DataClassToToolUtils.getTypeString(type),
      isRequired = isRequired,
      description = description,
    )
  } ?: emptyList()
}
