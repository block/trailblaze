package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
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
     * Shared default instance. Set this via [initDefault] during app startup to configure
     * tool serializers. On JVM, call [initDefault] with a fully-configured instance
     * (e.g., from the `createTrailblazeYaml(customToolClasses)` factory function in trailblaze-common).
     *
     * If not explicitly initialized, defaults to an instance with no tool serializers.
     */
    var Default: TrailblazeYaml = TrailblazeYaml()
      private set

    fun initDefault(instance: TrailblazeYaml) {
      Default = instance
    }

    fun toolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String =
      Default.encodeToolToYaml(toolName, trailblazeTool)
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
