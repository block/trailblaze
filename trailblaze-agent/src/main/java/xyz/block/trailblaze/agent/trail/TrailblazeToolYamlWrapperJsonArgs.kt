package xyz.block.trailblaze.agent.trail

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper

/**
 * Converts a [TrailblazeToolYamlWrapper] to a flat-shape [JsonObject] of executor arguments.
 *
 * Goes through the tool's concrete `@Serializable` class serializer so the result is the flat
 * shape the downstream executor expects (`{"text": "Jane Doe"}`) — not the persisted log
 * shape (`{"toolName": …, "raw": {…}}`) that abstract-typed [TrailblazeTool] encoding would
 * produce. Shared by [TrailGoalPlanner] and [DeterministicTrailExecutor]; both encode tool
 * calls into the same RPC arg shape so they must produce identical JSON.
 *
 * Tools that don't have a concrete kotlinx serializer ([OtherTrailblazeTool],
 * [RawArgumentTrailblazeTool], or non-`@Serializable` test stubs) still produce a usable args
 * object: [OtherTrailblazeTool]/[RawArgumentTrailblazeTool] surface their already-flat raw
 * args, and any other reflection failure logs a diagnostic and returns `{}` so the executor
 * can decide how to handle the missing args rather than the agent crashing during RPC arg prep.
 */
@OptIn(InternalSerializationApi::class)
internal fun TrailblazeToolYamlWrapper.toJsonArgs(): JsonObject {
  // OtherTrailblazeTool / RawArgumentTrailblazeTool already carry the flat args verbatim.
  // Use them directly — no class.serializer() reflection needed. Local-val capture so
  // smart-cast survives — `trailblazeTool` is a cross-module public property.
  val tool = trailblazeTool
  if (tool is OtherTrailblazeTool) return tool.raw
  if (tool is RawArgumentTrailblazeTool) return tool.rawToolArguments
  return try {
    @Suppress("UNCHECKED_CAST")
    val concreteSerializer = tool::class.serializer() as KSerializer<TrailblazeTool>
    val toolJson = TrailblazeJsonInstance.encodeToString(concreteSerializer, tool)
    TrailblazeJsonInstance.decodeFromString<JsonObject>(toolJson)
  } catch (e: kotlin.coroutines.cancellation.CancellationException) {
    // Re-throw cancellation so the catch-broad below doesn't swallow it.
    throw e
  } catch (e: Exception) {
    // Non-`@Serializable` tool, missing serializer, or reflection failure. Log once per
    // wrapper name per process so a recurring failure (test stub, dynamic tool without
    // a registered serializer) doesn't flood stderr during long evals.
    if (toJsonArgsFailureNamesLogged.add(name)) {
      Console.error(
        "[toJsonArgs] Failed to encode '$name' (${tool::class.simpleName}) as executor " +
          "args (${e::class.simpleName}: ${e.message}). Using empty args object. " +
          "Subsequent failures for this name will be suppressed.",
      )
    }
    JsonObject(emptyMap())
  }
}

/**
 * Tracks wrapper names whose encode has already produced a failure log so each offending
 * name logs at most once per process — same throttling pattern as
 * `encodeFailureKeysLogged` in `TrailblazeToolPayload.kt`.
 */
private val toJsonArgsFailureNamesLogged: MutableSet<String> = mutableSetOf()
