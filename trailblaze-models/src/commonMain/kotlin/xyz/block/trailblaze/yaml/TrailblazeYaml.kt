package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.yaml.serializers.TrailYamlItemSerializer
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer

class TrailblazeYaml(
  toolSerializersByName: Map<String, KSerializer<out TrailblazeTool>> = emptyMap(),
) {

  companion object {
    val yamlConfiguration = YamlConfiguration(
      encodeDefaults = false,
      breakScalarsAt = 500,
      yamlNamingStrategy = YamlNamingStrategy.CamelCase,
      multiLineStringStyle = MultiLineStringStyle.Literal,
      singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
      strictMode = false,
    )

    val defaultYamlInstance = Yaml(
      configuration = yamlConfiguration,
    )

    /**
     * Shared default instance, lazily built on first access.
     *
     * On JVM/Android, first read invokes [buildTrailblazeYamlDefault], which calls
     * `TrailblazeSerializationInitializer.buildAllTools()` to collect classpath-discovered
     * and imperatively-registered tool classes. That call seals the tool set — any later
     * imperative registration of a new class throws. All registrations must complete
     * before any serialization read.
     *
     * On wasmJs the actual returns an empty [TrailblazeYaml] (wasmJs consumers decode
     * unknown tools via the `OtherTrailblazeTool` fallback and do not need typed
     * serializers).
     *
     * To register tool classes that are not classpath-discoverable (e.g. Android on-device
     * runners loading YAML from AssetManager), call
     * `TrailblazeSerializationInitializer.registerImperativeToolClasses(...)` during
     * class-load init blocks.
     */
    val Default: TrailblazeYaml by lazy { buildTrailblazeYamlDefault() }

    fun toolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
      Default.encodeToolToYaml(toolName, trailblazeTool)

    /**
     * Encode a [JsonElement] as YAML.
     *
     * [JsonElement.serializer] only works with the JSON format (it verifies the encoder is a
     * [kotlinx.serialization.json.JsonEncoder]), so we convert manually instead of going
     * through kaml.
     */
    fun jsonToYaml(jsonElement: JsonElement): String =
      buildString { appendJsonElementAsYaml(jsonElement, indent = 0) }.trimEnd()

    private fun StringBuilder.appendJsonElementAsYaml(
      element: JsonElement,
      indent: Int,
      inlineFirst: Boolean = false,
    ) {
      when (element) {
        is JsonNull -> append("null")
        is JsonPrimitive -> appendYamlScalar(element)
        is JsonObject -> {
          if (element.isEmpty()) {
            append("{}")
            return
          }
          val pad = "  ".repeat(indent)
          element.entries.forEachIndexed { i, (key, value) ->
            if (i > 0 || !inlineFirst) append(pad)
            append(key)
            append(":")
            if ((value is JsonObject || value is JsonArray) && !isEmptyContainer(value)) {
              append("\n")
              appendJsonElementAsYaml(value, indent + 1)
            } else {
              append(" ")
              appendJsonElementAsYaml(value, indent + 1)
            }
            if (i < element.entries.size - 1) append("\n")
          }
        }
        is JsonArray -> {
          if (element.isEmpty()) {
            append("[]")
            return
          }
          val pad = "  ".repeat(indent)
          element.forEachIndexed { i, item ->
            append(pad)
            append("- ")
            appendJsonElementAsYaml(item, indent + 1, inlineFirst = true)
            if (i < element.size - 1) append("\n")
          }
        }
      }
    }

    private fun isEmptyContainer(element: JsonElement): Boolean = when (element) {
      is JsonObject -> element.isEmpty()
      is JsonArray -> element.isEmpty()
      else -> false
    }

    private fun StringBuilder.appendYamlScalar(primitive: JsonPrimitive) {
      if (!primitive.isString) {
        append(primitive.content)
        return
      }
      val s = primitive.content
      if (needsYamlQuoting(s)) {
        append('"')
        append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
        append('"')
      } else {
        append(s)
      }
    }

    private fun needsYamlQuoting(s: String): Boolean =
      s.isEmpty() ||
        s.contains(':') || s.contains('#') || s.contains('\n') ||
        s.startsWith('{') || s.startsWith('[') || s.startsWith('"') ||
        s.startsWith('\'') || s.startsWith('*') || s.startsWith('&') ||
        s.startsWith('!') || s.startsWith('|') || s.startsWith('>') ||
        s.startsWith('%') || s.startsWith('@') ||
        s == "true" || s == "false" || s == "null" || s == "~" ||
        s == "yes" || s == "no" || s == "on" || s == "off" ||
        s.toLongOrNull() != null || s.toDoubleOrNull() != null
  }

  private val trailblazeToolYamlWrapperSerializer: TrailblazeToolYamlWrapperSerializer

  val trailYamlItemSerializer: TrailYamlItemSerializer

  private val yamlInstance: Yaml

  init {
    // Use a lazy provider so the serializer can reference the yaml instance
    // that contains its own serializers module (needed for contextual lookups).
    var lazyYaml: Yaml? = null
    trailblazeToolYamlWrapperSerializer = TrailblazeToolYamlWrapperSerializer(
      toolSerializersByName = toolSerializersByName,
      yamlInstanceProvider = { lazyYaml ?: error("Yaml instance not yet initialized") },
    )
    trailYamlItemSerializer = TrailYamlItemSerializer(
      defaultYamlInstance,
      trailblazeToolYamlWrapperSerializer,
    )
    yamlInstance = Yaml(
      configuration = yamlConfiguration,
      serializersModule = SerializersModule {
        contextual(TrailYamlItem::class, trailYamlItemSerializer)
        contextual(TrailblazeToolYamlWrapper::class, trailblazeToolYamlWrapperSerializer)
      },
    )
    lazyYaml = yamlInstance
  }

  fun getInstance() = yamlInstance

  /** Serialize a single tool to YAML using this instance's tool serializers. */
  fun encodeToolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
    yamlInstance.encodeToString(
      trailblazeToolYamlWrapperSerializer,
      TrailblazeToolYamlWrapper(
        name = toolName,
        trailblazeTool = trailblazeTool,
      ),
    )

  @OptIn(ExperimentalSerializationApi::class)
  fun encodeToString(items: List<TrailYamlItem>): String {
    val encoded = yamlInstance.encodeToString(
      ListSerializer(
        yamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      items,
    )
    return if (encoded.endsWith("\n")) encoded else "$encoded\n"
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun decodeTrail(yaml: String): List<TrailYamlItem> {
    val trailItemList = yamlInstance.decodeFromString(
      ListSerializer(
        yamlInstance.serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      yaml,
    )
    val configItems = trailItemList.filterIsInstance<TrailYamlItem.ConfigTrailItem>()
    require(configItems.isEmpty() || (configItems.size == 1 && configItems[0] == trailItemList[0])) {
      "Only one config item is allowed, and it must be the first item in the trail."
    }
    return trailItemList
  }

  /**
   * Decodes a YAML list of tool wrappers directly (no TrailYamlItem wrapping).
   * Input format: `- tapOnPoint:\n    x: 200\n    y: 400`
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun decodeTools(yaml: String): List<TrailblazeToolYamlWrapper> {
    val contextual = yamlInstance.serializersModule
      .getContextual(TrailblazeToolYamlWrapper::class)
      ?: error("Missing contextual serializer for TrailblazeToolYamlWrapper")
    return yamlInstance.decodeFromString(ListSerializer(contextual), yaml)
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun extractTrailConfig(yaml: String): TrailConfig? {
    val trailItems: List<TrailYamlItem> = decodeTrail(yaml)
    return extractTrailConfig(trailItems)
  }

  @OptIn(ExperimentalSerializationApi::class)
  fun extractTrailConfig(trailItems: List<TrailYamlItem>): TrailConfig? {
    val configItem = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>().firstOrNull()
    return configItem?.config
  }

  fun hasActionableSteps(trailItems: List<TrailYamlItem>): Boolean =
    trailItems.any { item ->
      when (item) {
        is TrailYamlItem.PromptsTrailItem -> item.promptSteps.isNotEmpty()
        is TrailYamlItem.ToolTrailItem -> item.tools.isNotEmpty()
        is TrailYamlItem.ConfigTrailItem -> false
      }
    }

  fun hasRecordedSteps(trailItems: List<TrailYamlItem>): Boolean = trailItems.any { item ->
    when (item) {
      is TrailYamlItem.PromptsTrailItem -> {
        item.promptSteps.any { promptStep ->
          promptStep.recording?.tools?.isNotEmpty() ?: false
        }
      }
      is TrailYamlItem.ConfigTrailItem,
      is TrailYamlItem.ToolTrailItem -> false
    }
  }
}
