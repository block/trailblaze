package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.aallam.openai.api.chat.ToolBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

object DataClassToToolUtils {

  fun registerManualToolForDataClass(
    builder: ToolBuilder,
    clazz: KClass<out TrailblazeTool>,
  ) {
    val toolDescriptor = clazz.toKoogToolDescriptor()
    with(builder) {
      function(
        name = toolDescriptor.name,
        description = toolDescriptor.description,
      ) {
        generateJsonSchema(this, clazz)
      }
    }
  }

  fun generateJsonSchema(
    builder: JsonObjectBuilder,
    clazz: KClass<*>,
  ) {
    with(builder) {
      put("type", "object")
      putJsonObject("properties") {
        clazz.primaryConstructor?.parameters?.forEach { param ->
          val paramName = param.name ?: return@forEach
          val paramType = param.type
          putJsonObject(paramName) {
            put("type", getTypeString(paramType))

            // Add description if available
            clazz.primaryConstructor
              ?.parameters
              ?.firstOrNull { it.name == paramName }
              ?.findAnnotation<LLMDescription>()
              ?.description?.let { put("description", it.trimIndent()) }

            // Handle nested objects recursively
            if (paramType.classifier is KClass<*>) {
              val nestedClass = paramType.classifier as KClass<*>
              if (nestedClass.isData) { // Avoid infinite recursion
                putJsonObject("properties") {
                  nestedClass.primaryConstructor?.parameters?.forEach { nestedParam ->
                    val nestedParamName = nestedParam.name ?: return@forEach
                    putJsonObject(nestedParamName) {
                      put("type", getTypeString(nestedParam.type))
                    }
                  }
                }
              }
            }
          }
        }
      }
      putJsonArray("required") {
        clazz.primaryConstructor?.parameters?.forEach { param ->
          if (!param.isOptional) add(param.name!!)
        }
      }
    }
  }

  fun getTypeString(type: KType): String = when (type.classifier) {
    String::class -> "string"
    Int::class -> "integer"
    Boolean::class -> "boolean"
//      List::class -> "array"
    else -> if (type.classifier is KClass<*>) "object" else error("Unsupported type: $type")
  }
}
