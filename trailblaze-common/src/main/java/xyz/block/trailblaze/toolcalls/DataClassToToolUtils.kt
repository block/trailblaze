package xyz.block.trailblaze.toolcalls

import com.aallam.openai.api.chat.ToolBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object DataClassToToolUtils {

  fun registerManualToolForDataClass(
    builder: ToolBuilder,
    clazz: KClass<*>,
    /** Whether or not to include a property */
    propertyFilter: (String) -> Boolean,
  ) {
    val trailblazeClassInfo =
      clazz.findAnnotation<TrailblazeToolClass>() ?: error("Please add @TrailblazeToolClass to $clazz")
    with(builder) {
      function(
        name = trailblazeClassInfo.name.trim(),
        description = trailblazeClassInfo.description.trim(),
      ) {
        generateJsonSchema(this, clazz, propertyFilter)
      }
    }
  }

  fun generateJsonSchema(
    builder: JsonObjectBuilder,
    clazz: KClass<*>,
    propertyFilter: (String) -> Boolean,
  ) {
    with(builder) {
      put("type", "object")
      putJsonObject("properties") {
        clazz.primaryConstructor?.parameters?.forEach { param ->
          val paramName = param.name ?: return@forEach
          if (!propertyFilter(paramName)) {
            // Don't include this property
            return@forEach
          }
          val paramType = param.type
          putJsonObject(paramName) {
            put("type", getTypeString(paramType))

            val fieldInfo = clazz.memberProperties.first { it.name == paramName }.findAnnotation<TrailblazeToolProperty>()

            fieldInfo?.description?.let {
              put("description", it.trim())
            }

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
