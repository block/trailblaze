package xyz.block.trailblaze.tracing

import kotlinx.serialization.json.Json

val TRACING_JSON_INSTANCE = Json {
  isLenient = true
  prettyPrint = true
  encodeDefaults = true
  // Keeps absent span identity OUT of the emitted JSON: with encodeDefaults on, CompleteEvent's
  // nullable sid/psid would otherwise be written as explicit nulls on every root span and every
  // directly-added event. Trace consumers read a missing key and an explicit null the same way.
  explicitNulls = false
}
