package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.charleskorn.kaml.Yaml
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
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.logs.client.temp.JsonElementSerializer
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
import xyz.block.trailblaze.maestro.MaestroYamlParser
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * A Trailblaze tool that forwards a raw Maestro YAML commands list to Maestro's own parser
 * ([MaestroYamlParser.parseYaml]) for execution. The [yaml] field holds a Maestro commands-list
 * YAML compatible with [xyz.block.trailblaze.maestro.MaestroYamlSerializer.toYaml]; authored YAML
 * may be rewritten during deserialization (e.g. into JSON flow style), so this preserves the
 * semantics of Maestro's YAML reference
 * (https://docs.maestro.dev/reference/commands-available) rather than guaranteeing byte-for-byte
 * preservation of the original text.
 *
 * Authored trail YAML:
 * ```yaml
 * - tools:
 *     - maestro:
 *         commands:
 *           - extendedWaitUntil:
 *               notVisible: Gift card added to cart
 *               timeout: 20000
 *           - back
 *           - eraseText:
 *               charactersToErase: 3
 * ```
 */
@Serializable(with = MaestroTrailblazeToolSerializer::class)
@TrailblazeToolClass(name = "maestro", isForLlm = false, isRecordable = true)
@LLMDescription("Execute raw Maestro YAML commands directly. Prefer using specific Trailblaze tools instead.")
data class MaestroTrailblazeTool(
  /** Full Maestro commands-list YAML (list items prefixed with `- `). */
  val yaml: String,
) : ExecutableTrailblazeTool {
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val agent = toolExecutionContext.maestroTrailblazeAgent
      ?: error("MaestroTrailblazeTool requires MaestroTrailblazeAgent")
    if (yaml.isBlank()) return TrailblazeToolResult.Success()
    val parsed = try {
      MaestroYamlParser.parseYaml(yaml)
    } catch (e: Throwable) {
      Console.error("Failed to parse Maestro YAML: ${e.message}\nYAML:\n$yaml")
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Maestro YAML failed to deserialize: ${e.message}",
      )
    }
    return agent.runMaestroCommands(
      maestroCommands = parsed,
      traceId = toolExecutionContext.traceId,
    )
  }
}

/**
 * Serializes [MaestroTrailblazeTool] as `{commands: [<map>, …]}` in both YAML and JSON so
 * authored trail files and log viewers show structured nested content, while internally the
 * tool holds a single Maestro YAML text. The serializer converts between the two shapes via
 * [YamlJsonBridge] (YAML ↔ JSON trees) and the kaml parser.
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
      // A blank yaml payload is a valid no-op tool (see execute()) and an empty `commands: []`
      // list round-trips through the deserializer as blank too — emit accordingly instead of
      // asking kaml to parse an empty document.
      val serializableCommands = if (value.yaml.isBlank()) {
        emptyList()
      } else {
        val rootNode = Yaml.default.parseToYamlNode(value.yaml)
        require(rootNode is YamlList) {
          "MaestroTrailblazeTool.yaml must be a YAML list of commands, got " +
            "${rootNode::class.simpleName}. YAML:\n${value.yaml}"
        }
        rootNode.items.map { item ->
          // A command can appear as either a YAML map (e.g. `eraseText: {charactersToErase: 3}`)
          // or a bare YAML scalar (e.g. `back`). Maestro's YAML deserializer accepts both;
          // normalise to the map shape with an empty body for downstream consumers.
          val jsonObject = when (val element = YamlJsonBridge.yamlNodeToJsonElement(item)) {
            is JsonObject -> element
            else -> {
              val commandName = element.toString().trim('"')
              buildJsonObject { put(commandName, buildJsonObject { }) }
            }
          }
          jsonObject.mapValues { (_, elem) -> YamlJsonBridge.jsonElementToSerializable(elem) }
        }
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
        // Render each YAML command back to JSON text — JSON is valid YAML flow style, so the
        // combined list parses identically through Maestro's YAML reader, and we avoid having
        // to re-emit block-style YAML text with correct indentation ourselves.
        val yamlText = commandsNode.items.joinToString("\n") { item ->
          val jsonObject = when (val element = YamlJsonBridge.yamlNodeToJsonElement(item)) {
            is JsonObject -> element
            else -> {
              val commandName = element.toString().trim('"')
              buildJsonObject { put(commandName, buildJsonObject { }) }
            }
          }
          "- $jsonObject"
        }
        MaestroTrailblazeTool(yamlText)
      }
      is JsonDecoder -> {
        val element = decoder.decodeJsonElement()
        val commandsArray = when (element) {
          is JsonArray -> element
          is JsonObject -> element["commands"] as? JsonArray ?: JsonArray(emptyList())
          else -> JsonArray(emptyList())
        }
        val yamlText = commandsArray.joinToString("\n") { item -> "- $item" }
        MaestroTrailblazeTool(yamlText)
      }
      else -> error("MaestroTrailblazeTool can only be deserialized from YAML or JSON")
    }
  }
}
