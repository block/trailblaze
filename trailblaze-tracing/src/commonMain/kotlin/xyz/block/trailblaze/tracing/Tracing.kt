package xyz.block.trailblaze.tracing

import kotlinx.serialization.json.Json

val TRACING_JSON_INSTANCE = Json {
  isLenient = true
  prettyPrint = true
  encodeDefaults = true
}
