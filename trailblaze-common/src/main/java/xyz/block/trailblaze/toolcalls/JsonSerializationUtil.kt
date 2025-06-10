package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.collections.get
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

object JsonSerializationUtil {

  fun <T : Any> deserializeTrailblazeTool(clazz: KClass<T>, json: JsonObject): T {
    val constructor =
      clazz.primaryConstructor ?: throw IllegalArgumentException("No primary constructor found for ${clazz.simpleName}")

    val args = constructor.parameters.associateWith { param ->
      val jsonValue = json[param.name]
      if (jsonValue == null && !param.type.isMarkedNullable) {
        // If the field is missing and it's NOT nullable, return a default value
        return@associateWith getDefaultForType(param.type)
      }
      jsonValue?.let { convertJsonToType(it, param.type) }
    }

    return constructor.callBy(args)
  }

  fun convertJsonToType(jsonElement: JsonElement, type: KType): Any? {
    return when {
      type.classifier == String::class -> jsonElement.jsonPrimitive.contentOrNull
      type.classifier == Long::class -> jsonElement.jsonPrimitive.longOrNull ?: 0L // Default value for Long
      type.classifier == Int::class -> jsonElement.jsonPrimitive.intOrNull ?: 0 // Default value for Int
      type.classifier == Boolean::class -> jsonElement.jsonPrimitive.booleanOrNull == true // Default for Boolean
      type.classifier == List::class -> {
        val itemType = type.arguments.first().type ?: return null
        jsonElement.jsonArray.mapNotNull { convertJsonToType(it, itemType) }
      }

      type.classifier is KClass<*> -> {
        val nestedClass = type.classifier as KClass<*>
        (jsonElement as? JsonObject)?.let { deserializeTrailblazeTool(nestedClass, it) }
      }

      else -> throw IllegalArgumentException("Unsupported type: $type")
    }
  }

  // Provide default values for missing non-nullable fields
  fun getDefaultForType(type: KType): Any = when (type.classifier) {
    Int::class -> 0
    Long::class -> 0L
    Boolean::class -> false
    String::class -> ""
    List::class -> emptyList<Any>()
    else -> throw IllegalArgumentException("No default value for type: $type")
  }
}
