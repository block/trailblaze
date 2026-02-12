package xyz.block.trailblaze.toolcalls

import com.google.gson.GsonBuilder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.jsonObject
import kotlin.reflect.KClass

// Generic KSerializer using JsonObjectConvertible logic
class GenericGsonJsonSerializer<T : Any>(
  private val kClass: KClass<T>,
) : KSerializer<T> {

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor(kClass.qualifiedName!!)

  override fun serialize(encoder: Encoder, value: T) {
    require(encoder is JsonEncoder) // Ensure it's JSON encoding
    val jsonObject = Json.parseToJsonElement(gson.toJson(value)).jsonObject
    encoder.encodeJsonElement(jsonObject)
  }

  override fun deserialize(decoder: Decoder): T {
    require(decoder is JsonDecoder) // Ensure it's JSON decoding
    val jsonObject = decoder.decodeJsonElement().jsonObject
    return gson.fromJson(jsonObject.toString(), kClass.java)
  }

  companion object {
    private val gson = GsonBuilder().setPrettyPrinting().create()
  }
}
