package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.modules.SerializersModule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.serializers.MaestroCommandListSerializer
import xyz.block.trailblaze.yaml.serializers.TrailYamlItemSerializer
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer
import kotlin.reflect.KClass

class TrailblazeYaml(
  customTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
) {

  val allTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> =
    TrailblazeToolSet.AllBuiltInTrailblazeToolsForSerialization + customTrailblazeToolClasses

  companion object {
    fun toolToYaml(toolName: String, trailblazeTool: TrailblazeTool): String = defaultYamlInstance.encodeToString(
      TrailblazeToolYamlWrapperSerializer(
        setOf(),
      ),
      TrailblazeToolYamlWrapper(
        name = toolName,
        trailblazeTool = trailblazeTool,
      ),
    )

    private val yamlConfiguration = YamlConfiguration(
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
  }

  val trailblazeToolYamlWrapperSerializer = TrailblazeToolYamlWrapperSerializer(
    allTrailblazeToolClasses,
  )

  val trailYamlItemSerializer = TrailYamlItemSerializer(
    defaultYamlInstance,
    trailblazeToolYamlWrapperSerializer,
  )

  fun getInstance() = Yaml(
    configuration = yamlConfiguration,
    serializersModule = SerializersModule {
      contextual(
        TrailYamlItem::class,
        trailYamlItemSerializer,
      )

      contextual(
        MaestroCommandList::class,
        MaestroCommandListSerializer(),
      )

      contextual(
        TrailblazeToolYamlWrapper::class,
        TrailblazeToolYamlWrapperSerializer(allTrailblazeToolClasses),
      )
    },
  )

  fun encodeToString(items: List<TrailYamlItem>): String = getInstance().encodeToString(
    ListSerializer(
      getInstance().serializersModule.getContextual(TrailYamlItem::class)
        ?: error("Missing contextual serializer for TrailYamlItem"),
    ),
    items,
  )

  @OptIn(ExperimentalSerializationApi::class)
  fun decodeTrail(yaml: String): List<TrailYamlItem> = with(getInstance()) {
    decodeFromString(
      ListSerializer(
        serializersModule.getContextual(TrailYamlItem::class)
          ?: error("Missing contextual serializer for TrailYamlItem"),
      ),
      yaml,
    )
  }

  /**
   * Extracts config from the config trail item if it exists.
   * Returns null if no config item is found.
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun extractTrailConfig(yaml: String): TrailConfig? {
    val trailItems: List<TrailYamlItem> = decodeTrail(yaml)
    return extractTrailConfig(trailItems)
  }

  /**
   * Extracts config from the config trail item if it exists.
   * Returns null if no config item is found.
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun extractTrailConfig(trailItems: List<TrailYamlItem>): TrailConfig? {
    val configItem = trailItems.filterIsInstance<TrailYamlItem.ConfigTrailItem>().firstOrNull()
    return configItem?.config
  }
}
