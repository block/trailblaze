package xyz.block.trailblaze.host

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Resolves `{{var}}` / `${var}` tokens in a [TrailblazeTool]'s string fields against [memory]
 * before the tool is YAML-encoded for RPC dispatch. Substitution runs on the typed JSON tree
 * (string scalars only) so the YAML encoder owns escaping of `"`, `\n`, `:`, `#` in resolved
 * values rather than splicing raw text into the wire payload.
 *
 * Two layers of interpolation co-exist by design:
 * - **Boundary (this function)** — runs once on the host before RPC dispatch or host-only
 *   execution. The canonical layer for cross-process resolution because the device's per-request
 *   `AgentMemory` is empty. Also covers host-only tools that don't self-interpolate (e.g.
 *   `RunCommandTrailblazeTool`).
 * - **Tool-internal `memory.interpolateVariables(text)`** — kept for non-RPC paths (host-only
 *   Maestro driver, Compose driver, etc.) where boundary interpolation doesn't run. Idempotent
 *   on the RPC path: by the time a tool re-interpolates against an empty device memory, no
 *   tokens remain.
 */
@OptIn(InternalSerializationApi::class)
internal fun interpolateMemoryInTool(tool: TrailblazeTool, memory: AgentMemory): TrailblazeTool {
  if (memory.variables.isEmpty()) return tool
  val concreteSerializer = @Suppress("UNCHECKED_CAST")
  (tool::class.serializer() as KSerializer<TrailblazeTool>)
  val tree = INTERPOLATION_JSON.encodeToJsonElement(concreteSerializer, tool)
  val interpolated = interpolateStringsInTree(tree, memory)
  return INTERPOLATION_JSON.decodeFromJsonElement(concreteSerializer, interpolated)
}

private fun interpolateStringsInTree(element: JsonElement, memory: AgentMemory): JsonElement = when (element) {
  is JsonPrimitive -> if (element.isString) {
    JsonPrimitive(memory.interpolateVariables(element.content))
  } else {
    element
  }
  is JsonObject -> JsonObject(element.mapValues { interpolateStringsInTree(it.value, memory) })
  is JsonArray -> JsonArray(element.map { interpolateStringsInTree(it, memory) })
}

// Symmetric encode/decode: keep defaults so a tool field that happens to equal its default
// today doesn't silently take on a different default if that default ever changes. We only
// mutate string scalars on the tree, so explicit defaults add no semantic risk.
private val INTERPOLATION_JSON = Json {
  encodeDefaults = true
}
