package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlNamingStrategy
import kotlinx.serialization.modules.SerializersModule
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.yaml.models.MaestroCommandList
import xyz.block.trailblaze.yaml.models.TrailYamlItem
import xyz.block.trailblaze.yaml.models.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.serializers.MaestroCommandListSerializer
import xyz.block.trailblaze.yaml.serializers.TrailYamlItemSerializer
import kotlin.reflect.KClass

class TrailblazeYaml(
  customTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
) {

  val allTrailblazeToolClasses: Set<KClass<out TrailblazeTool>> =
    TrailblazeToolSet.BuiltInTrailblazeTools + customTrailblazeToolClasses

  companion object {
    private val yamlConfiguration = YamlConfiguration(
      encodeDefaults = false,
      yamlNamingStrategy = YamlNamingStrategy.CamelCase,
      multiLineStringStyle = MultiLineStringStyle.Literal,
      singleLineStringStyle = SingleLineStringStyle.PlainExceptAmbiguous,
      strictMode = false,
    )

    val defaultYamlInstance = Yaml(
      configuration = yamlConfiguration,
    )
  }

  val trailblazeToolYamlWrapperSerializer = TrailblazeToolYamlWrapper.TrailblazeToolYamlWrapperSerializer(
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
        TrailblazeToolYamlWrapper.TrailblazeToolYamlWrapperSerializer(allTrailblazeToolClasses),
      )
    },
  )
}
