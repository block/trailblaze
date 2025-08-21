package xyz.block.trailblaze.logs.client.temp

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

@OptIn(InternalSerializationApi::class)
class OtherTrailblazeToolSerializer(private val allToolClasses: Map<ToolDescriptor, KClass<out TrailblazeTool>>) : KSerializer<TrailblazeTool> {
  override val descriptor = OtherTrailblazeTool.serializer().descriptor

  private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
  }

  override fun deserialize(decoder: Decoder): TrailblazeTool {
    val input = decoder as? JsonDecoder
      ?: error("Only JsonDecoder is supported")
    val jsonElement = input.decodeJsonElement()
    val jsonObject = jsonElement.jsonObject

    val className = jsonObject["class"]?.jsonPrimitive?.contentOrNull
    val toolName = jsonObject["toolName"]?.jsonPrimitive?.contentOrNull
    val toolClassOnClasspath: KClass<out TrailblazeTool>? = if (toolName != null) {
      allToolClasses.entries.firstOrNull { it.key.name == toolName }?.value
    } else {
      allToolClasses.entries.firstOrNull { it.value.qualifiedName == className }?.value
    }
    val toolClassOnClasspathName = toolClassOnClasspath?.qualifiedName

    return if (toolClassOnClasspath != null && toolClassOnClasspathName != null) {
      val newObj = buildJsonObject {
        jsonObject.entries
          .filter { it.key != "raw" && it.key != "toolName" }
          .forEach { (key, value) ->
            put(key, value)
          }
        jsonObject["raw"]?.jsonObject?.entries?.forEach { (key, value) ->
          put(key, value)
        }
        put("class", JsonPrimitive(toolClassOnClasspathName))
      }
      lenientJson.decodeFromString(toolClassOnClasspath.serializer(), newObj.toString())
    } else {
      lenientJson.decodeFromString<OtherTrailblazeTool>(jsonObject.toString())
    }
  }

  override fun serialize(encoder: Encoder, value: TrailblazeTool) {
    val output = encoder as? JsonEncoder
      ?: error("Only JsonEncoder is supported")

    val valueClass = value::class
    val standardSerializer = value::class.serializer() as? KSerializer<TrailblazeTool>
    val toolName = allToolClasses.entries.firstOrNull { valueClass.qualifiedName == it.value.qualifiedName }?.key?.name
    val otherTrailblazeToolData: OtherTrailblazeTool = value as? OtherTrailblazeTool
      ?: if (standardSerializer != null && toolName != null) {
        val objJson = lenientJson.encodeToString(standardSerializer, value)
        OtherTrailblazeTool(
          toolName,
          lenientJson.decodeFromString<JsonObject>(objJson),
        )
      } else {
        error("You are attempting to serialize a TrailblazeTool that has not be configured for the TrailblazeJson instance: $valueClass for content $value.  Please be sure to register this class.")
      }

    val json = lenientJson.encodeToString(otherTrailblazeToolData)
    output.encodeJsonElement(lenientJson.decodeFromString(json))
  }
}
