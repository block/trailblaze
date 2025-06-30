package xyz.block.trailblaze.logs.client.temp

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModuleBuilder
import kotlinx.serialization.serializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Custom Serializer for [TrailblazeTool]
 *
 * This allows us to handle commands that are not on the classpath gracefully.
 */
@OptIn(InternalSerializationApi::class)
fun SerializersModuleBuilder.registerTrailblazeToolSerializer() {
  polymorphicDefaultDeserializer(TrailblazeTool::class) { className ->
    val clazz: KClass<out TrailblazeTool>? = try {
      // This type of command is on the classpath, so we will use it's serializer
      val javaClazz = Class.forName(className)
      val kotlinClazz = javaClazz.kotlin
      if (kotlinClazz.isSubclassOf(TrailblazeTool::class)) {
        kotlinClazz as KClass<out TrailblazeTool>
      } else {
        null
      }
    } catch (e: Exception) {
      null
    }
    val standardSerializer = clazz?.serializer() as? KSerializer<TrailblazeTool>

    standardSerializer ?: object : KSerializer<TrailblazeTool> {
      override val descriptor = OtherTrailblazeTool.serializer().descriptor

      override fun deserialize(decoder: Decoder): TrailblazeTool {
        val input = decoder as? JsonDecoder
          ?: error("Only JsonDecoder is supported")
        val jsonElement = input.decodeJsonElement()
        val jsonObject = jsonElement.jsonObject
        return OtherTrailblazeTool(
          raw = jsonObject,
        )
      }

      override fun serialize(encoder: Encoder, value: TrailblazeTool) {
        val output = encoder as? JsonEncoder
          ?: error("Only JsonEncoder is supported")
        if (value is OtherTrailblazeTool) {
          output.encodeJsonElement(value.raw)
        } else {
          error("Cannot serialize unknown TrailblazeTool subtype: $value")
        }
      }
    }
  }

  polymorphicDefaultSerializer(TrailblazeTool::class) { value ->
    value::class.serializer() as? KSerializer<TrailblazeTool>
  }
}

/**
 * Use this to "clean" the JSON output of the TrailblazeTool serializer.
 *
 * It will remove any of the "wrapper" commands for Commands not on the classpath.
 */
fun flattenTrailblazeJson(jsonString: String): String {
  fun flattenOtherTrailblazeTool(element: JsonElement): JsonElement {
    return when (element) {
      is JsonObject -> {
        val className = element["class"]?.jsonPrimitive?.content
        val transformed = element.mapValues { (_, v) -> flattenOtherTrailblazeTool(v) }

        if (className == OtherTrailblazeTool::class.qualifiedName) {
          // Recursively process the raw block before returning it
          val raw = (element["raw"] as? JsonObject) ?: return JsonObject(transformed)
          flattenOtherTrailblazeTool(raw)
        } else {
          JsonObject(transformed)
        }
      }

      is JsonArray -> JsonArray(element.map { flattenOtherTrailblazeTool(it) })
      else -> element
    }
  }

  val json = Json { prettyPrint = true }
  val jsonElement = json.parseToJsonElement(jsonString)
  val transformed = flattenOtherTrailblazeTool(jsonElement)
  return json.encodeToString(JsonElement.serializer(), transformed)
}
