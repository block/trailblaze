package xyz.block.trailblaze.trailhead

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.DeviceSetupToolsMemory

/**
 * How an on-device run receives the lane's setup tool calls: the `trailblaze.deviceSetupToolsB64`
 * instrumentation arg, the URL-safe, unpadded base64 of the JSON array the host would otherwise
 * seed into memory itself (see [DeviceSetupToolsMemory]). The alphabet keeps `+`, `/` and `=` out of the
 * farm's colon-separated arg list.
 */
object DeviceSetupToolsInstrumentationArg {
  const val KEY = "trailblaze.deviceSetupToolsB64"

  /**
   * The JSON array [value] encodes, or null when the lane sent none.
   *
   * A value that is not base64 of a JSON array throws: dropping it would run the trail without its setup
   * default configuration and report the result as the configured one.
   */
  fun decode(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val json =
      try {
        String(Base64.getUrlDecoder().decode(value.trim()), Charsets.UTF_8)
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Instrumentation arg $KEY is not URL-safe base64", e)
      }
    val parsed =
      try {
        Json.parseToJsonElement(json)
      } catch (e: Exception) {
        throw IllegalArgumentException("Instrumentation arg $KEY does not decode to JSON", e)
      }
    require(parsed is JsonArray) { "Instrumentation arg $KEY must decode to a JSON array" }
    return json
  }

  /** Seeds the decoded configuration into [memory] under [DeviceSetupToolsMemory.DEVICE_SETUP_TOOLS_MEMORY_KEY]. */
  fun seed(memory: AgentMemory, value: String?) {
    decode(value)?.let { memory.remember(DeviceSetupToolsMemory.DEVICE_SETUP_TOOLS_MEMORY_KEY, it) }
  }
}
