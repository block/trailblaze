package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.charleskorn.kaml.YamlInput
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.logs.client.temp.JsonElementSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.Ext.asMaestroCommands

/**
 * A Trailblaze tool that passes raw Maestro commands through for execution.
 * The commands are opaque to Trailblaze — Maestro handles all parsing.
 *
 * Follows the same pattern as all other Trailblaze tools: named properties under the tool name.
 *
 * YAML format:
 * ```yaml
 * - tools:
 *     - maestro:
 *         commands:
 *           - extendedWaitUntil:
 *               notVisible: Gift card added to cart
 *               timeout: 20000
 * ```
 */
@Serializable(with = MaestroTrailblazeToolSerializer::class)
@TrailblazeToolClass(name = "maestro", isForLlm = false, isRecordable = true)
@LLMDescription("Execute raw Maestro YAML commands directly. Prefer using specific Trailblaze tools instead.")
data class MaestroTrailblazeTool(
  val commands: List<JsonObject>,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: error("MaestroTrailblazeTool requires MaestroTrailblazeAgent")
    val maestroCommands = commands.asMaestroCommands()
    if (maestroCommands.size < commands.size) {
      val dropped = commands.size - maestroCommands.size
      val errorMessage = if (dropped == commands.size) {
        "All ${commands.size} maestro command(s) failed to deserialize."
      } else {
        "Failed to deserialize $dropped of ${commands.size} maestro command(s). " +
          "Check logs for 'Failed to deserialize MaestroCommand' details."
      }
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = errorMessage)
    }
    return agent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
  }
}

/**
 * Serializes [MaestroTrailblazeTool] as an object with a `commands` property,
 * consistent with how all other Trailblaze tools are serialized.
 *
 * Supports both YAML (for trail files) and JSON (for log transport).
 */
object MaestroTrailblazeToolSerializer : KSerializer<MaestroTrailblazeTool> {
  private val commandsListSerializer = ListSerializer(
    MapSerializer(String.serializer(), JsonElementSerializer),
  )

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MaestroTrailblazeTool") {
    element("commands", commandsListSerializer.descriptor)
  }

  override fun serialize(encoder: Encoder, value: MaestroTrailblazeTool) {
    encoder.encodeStructure(descriptor) {
      val serializableCommands = value.commands.map { jsonObject ->
        jsonObject.mapValues { (_, elem) -> YamlJsonBridge.jsonElementToSerializable(elem) }
      }
      encodeSerializableElement(descriptor, 0, commandsListSerializer, serializableCommands)
    }
  }

  override fun deserialize(decoder: Decoder): MaestroTrailblazeTool {
    return when (decoder) {
      is YamlInput -> {
        val node = decoder.node
        require(node is YamlMap) {
          "Expected a map with 'commands' under 'maestro:', got ${node::class.simpleName}"
        }
        val commandsNode = node.entries.entries
          .firstOrNull { it.key.content == "commands" }?.value
          ?: error("Expected 'commands' key under 'maestro:'")
        require(commandsNode is YamlList) {
          "Expected 'commands' to be a list, got ${commandsNode::class.simpleName}"
        }
        val commands = commandsNode.items.map { item ->
          YamlJsonBridge.yamlNodeToJsonElement(item) as? JsonObject
            ?: error("Each Maestro command must be a map, got ${item::class.simpleName}")
        }
        MaestroTrailblazeTool(commands)
      }
      is JsonDecoder -> {
        val element = decoder.decodeJsonElement()
        val commandsArray = when (element) {
          is JsonArray -> element
          is JsonObject -> element["commands"] as? JsonArray ?: JsonArray(emptyList())
          else -> JsonArray(emptyList())
        }
        MaestroTrailblazeTool(commandsArray.map { it.jsonObject })
      }
      else -> error("MaestroTrailblazeTool can only be deserialized from YAML or JSON")
    }
  }
}
